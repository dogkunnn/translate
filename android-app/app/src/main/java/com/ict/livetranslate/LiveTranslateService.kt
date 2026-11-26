package com.ict.livetranslate

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class LiveTranslateService : Service(), DebugStore.Listener {

    companion object {
        const val ACTION_START = "com.ict.livetranslate.START"
        const val ACTION_STOP = "com.ict.livetranslate.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_PROMPT = "prompt"

        private const val CHANNEL_ID = "ict_live_dub_channel"
        private const val NOTIF_ID = 1001
        private const val INPUT_RATE = 16000
        private const val OUTPUT_RATE = 24000
        private const val MODEL = "models/gemini-3.5-live-translate-preview"
    }

    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            DebugStore.log("MediaProjection.Callback.onStop() fired by system")
            if (running.get()) {
                DebugStore.setStatus("ระบบหยุดการแชร์เสียงหน้าจอแล้ว", false)
                stopEverything()
                stopSelf()
            }
        }
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var webSocket: WebSocket? = null
    private var captureThread: Thread? = null

    // Floating bubble overlay views
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var tvFloatStatus: TextView? = null
    private var tvFloatTranscript: TextView? = null

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DebugStore.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            DebugStore.log("Received ACTION_STOP")
            stopEverything()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START) {
            DebugStore.clearTranscriptAndLogs()
            DebugStore.log("Service ACTION_START received")
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
            val apiKey = intent.getStringExtra(EXTRA_API_KEY).orEmpty().trim()
            val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty().trim()

            if (resultCode != Activity.RESULT_OK || resultData == null || apiKey.isEmpty()) {
                DebugStore.setStatus("ข้อมูลไม่ครบ (resultCode=$resultCode, hasKey=${apiKey.isNotEmpty()})", false)
                stopSelf()
                return START_NOT_STICKY
            }

            try {
                startInForeground()
                DebugStore.log("Foreground service started")
                showFloatingBubbleIfAllowed()
                startLiveSession(resultCode, resultData, apiKey, prompt, tryPrompt = prompt.isNotEmpty())
            } catch (e: Exception) {
                DebugStore.log("FATAL start error: ${e.javaClass.simpleName}: ${e.message}")
                DebugStore.setStatus("เริ่มทำงานไม่สำเร็จ: ${e.message}", false)
                stopEverything()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "ICT Live Dubbing",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }

        val stopIntent = Intent(this, LiveTranslateService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 1, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ICT / SMC Live Dub กำลังทำงาน")
            .setContentText("กำลังฟังเสียงจากแอปอื่นและแปลเป็นภาษาไทยสดๆ")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "หยุด", stopPending)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingBubbleIfAllowed() {
        if (!Settings.canDrawOverlays(this)) {
            DebugStore.log("Overlay permission not granted; skipping floating bubble")
            return
        }
        if (floatingView != null) return

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val inflater = LayoutInflater.from(this)
            val view = inflater.inflate(R.layout.floating_bubble, null)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 40
                y = 260
            }

            val bubbleHead = view.findViewById<View>(R.id.bubbleHead)
            val bubblePanel = view.findViewById<View>(R.id.bubblePanel)
            tvFloatStatus = view.findViewById(R.id.tvFloatStatus)
            tvFloatTranscript = view.findViewById(R.id.tvFloatTranscript)

            view.findViewById<Button>(R.id.btnFloatOpen).setOnClickListener {
                val i = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(i)
            }
            view.findViewById<Button>(R.id.btnFloatStop).setOnClickListener {
                stopEverything()
                stopSelf()
            }

            bubbleHead.setOnTouchListener(object : View.OnTouchListener {
                private var initX = 0
                private var initY = 0
                private var touchX = 0f
                private var touchY = 0f
                private var moved = false

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initX = params.x
                            initY = params.y
                            touchX = event.rawX
                            touchY = event.rawY
                            moved = false
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - touchX).toInt()
                            val dy = (event.rawY - touchY).toInt()
                            if (abs(dx) > 8 || abs(dy) > 8) moved = true
                            params.x = initX + dx
                            params.y = initY + dy
                            windowManager?.updateViewLayout(view, params)
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!moved) {
                                bubblePanel.visibility =
                                    if (bubblePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                            }
                            return true
                        }
                    }
                    return false
                }
            })

            windowManager?.addView(view, params)
            floatingView = view
            bubbleParams = params
            DebugStore.log("Floating bubble shown")
        } catch (e: Exception) {
            DebugStore.log("Floating bubble error: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLiveSession(
        resultCode: Int,
        resultData: Intent,
        apiKey: String,
        prompt: String,
        tryPrompt: Boolean
    ) {
        try {
            if (mediaProjection == null) {
                DebugStore.log("Getting MediaProjection...")
                val mpm = getSystemService(MediaProjectionManager::class.java)
                mediaProjection = mpm.getMediaProjection(resultCode, resultData)?.also {
                    it.registerCallback(projectionCallback, mainHandler)
                }
                DebugStore.log("MediaProjection obtained & callback registered")
            }
            val projection = mediaProjection ?: run {
                DebugStore.setStatus("ไม่สามารถเริ่ม MediaProjection ได้", false)
                stopSelf()
                return
            }

            running.set(true)
            DebugStore.setStatus("กำลังเชื่อมต่อ Gemini Live Translate (tryPrompt=$tryPrompt)...", true)

            if (audioTrack == null) {
                val outBufSize = AudioTrack.getMinBufferSize(
                    OUTPUT_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(8192)

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(OUTPUT_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(outBufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                audioTrack?.play()
                DebugStore.log("AudioTrack ready (24kHz, buf=$outBufSize)")
            }

            if (audioRecord == null) {
                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val inBufSize = AudioRecord.getMinBufferSize(
                    INPUT_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                audioRecord = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(INPUT_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(inBufSize)
                    .build()
                DebugStore.log("AudioRecord ready (state=${audioRecord?.state}, buf=$inBufSize)")
            }

            val wsUrl =
                "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
            DebugStore.log("Opening WebSocket to Gemini v1beta...")
            val request = Request.Builder().url(wsUrl).build()

            webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                private var setupDone = false
                private var rxAudioCount = 0

                override fun onOpen(ws: WebSocket, response: Response) {
                    DebugStore.log("WebSocket onOpen (HTTP ${response.code}), sending setup...")
                    val setupObj = JSONObject().apply {
                        put("model", MODEL)
                        put(
                            "generationConfig",
                            JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                        )
                        put(
                            "translationConfig",
                            JSONObject().put("targetLanguageCode", "th")
                        )
                        if (tryPrompt && prompt.isNotEmpty()) {
                            put(
                                "systemInstruction",
                                JSONObject().put(
                                    "parts",
                                    JSONArray().put(JSONObject().put("text", prompt))
                                )
                            )
                        }
                    }
                    ws.send(JSONObject().put("setup", setupObj).toString())
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    handleServerJson(text, ws)
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    handleServerJson(bytes.utf8(), ws)
                }

                private fun handleServerJson(raw: String, ws: WebSocket) {
                    try {
                        val json = JSONObject(raw)
                        if (json.has("error")) {
                            val errObj = json.optJSONObject("error")
                            val msg = errObj?.optString("message") ?: raw.take(200)
                            DebugStore.log("Server JSON error: $msg")
                            DebugStore.setStatus("Gemini Error: $msg", false)
                            stopEverything()
                            stopSelf()
                            return
                        }
                        if (json.has("setupComplete") && !setupDone) {
                            setupDone = true
                            DebugStore.log("setupComplete received! Starting audio capture...")
                            DebugStore.setStatus("กำลังพากย์ไทยสด! เปิด YouTube / IG ฟังได้เลย", true)
                            startAudioCaptureLoop(ws)
                            return
                        }

                        val serverContent = json.optJSONObject("serverContent")
                        if (serverContent == null) {
                            DebugStore.log("RX other: ${raw.take(180)}")
                            return
                        }
                        val modelTurn = serverContent.optJSONObject("modelTurn") ?: return
                        val parts = modelTurn.optJSONArray("parts") ?: return

                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            val inlineData = part.optJSONObject("inlineData")
                            if (inlineData != null) {
                                val b64 = inlineData.optString("data")
                                if (b64.isNotEmpty()) {
                                    val pcm = Base64.decode(b64, Base64.DEFAULT)
                                    audioTrack?.write(pcm, 0, pcm.size)
                                    rxAudioCount++
                                    if (rxAudioCount == 1 || rxAudioCount % 50 == 0) {
                                        DebugStore.log("RX Thai audio chunks: $rxAudioCount (${pcm.size}B)")
                                    }
                                }
                            }
                            val txt = part.optString("text")
                            if (txt.isNotEmpty()) {
                                DebugStore.appendTranscript(txt)
                            }
                        }
                    } catch (e: Exception) {
                        DebugStore.log("JSON parse err: ${e.message} | raw=${raw.take(120)}")
                    }
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    DebugStore.log("WebSocket onClosing: code=$code reason=$reason")
                    if (running.get() && tryPrompt && code == 1007) {
                        DebugStore.log("Retrying without systemInstruction due to 1007...")
                        ws.cancel()
                        mainHandler.post {
                            startLiveSession(resultCode, resultData, apiKey, prompt, tryPrompt = false)
                        }
                    } else if (running.get()) {
                        DebugStore.setStatus("ตัดการเชื่อมต่อ ($code: $reason)", false)
                        stopEverything()
                        stopSelf()
                    }
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    DebugStore.log("WebSocket onClosed: code=$code reason=$reason")
                    if (running.get()) {
                        DebugStore.setStatus("การเชื่อมต่อถูกปิด ($code: $reason)", false)
                        stopEverything()
                        stopSelf()
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    val codeInfo = response?.let { "HTTP ${it.code} ${it.message}" } ?: "no-http"
                    DebugStore.log("WebSocket onFailure ($codeInfo): ${t.javaClass.simpleName}: ${t.message}")
                    if (running.get()) {
                        DebugStore.setStatus("เชื่อมต่อไม่สำเร็จ: ${t.message}", false)
                        stopEverything()
                        stopSelf()
                    }
                }
            })
        } catch (e: Exception) {
            DebugStore.log("startLiveSession exception: ${e.javaClass.simpleName}: ${e.message}")
            DebugStore.setStatus("เกิดข้อผิดพลาด: ${e.message}", false)
            stopEverything()
            stopSelf()
        }
    }

    private fun startAudioCaptureLoop(ws: WebSocket) {
        if (captureThread != null) return
        val rec = audioRecord ?: return
        rec.startRecording()
        DebugStore.log("AudioRecord.startRecording() called (recordingState=${rec.recordingState})")

        captureThread = Thread {
            val buffer = ByteArray(2048)
            var txCount = 0
            while (running.get()) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val b64 = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                    val chunk = JSONObject()
                        .put("mimeType", "audio/pcm;rate=16000")
                        .put("data", b64)
                    val payload = JSONObject().put(
                        "realtimeInput",
                        JSONObject().put("mediaChunks", JSONArray().put(chunk))
                    )
                    ws.send(payload.toString())
                    txCount++
                    if (txCount == 1 || txCount % 100 == 0) {
                        DebugStore.log("TX audio chunks: $txCount (last=${read}B)")
                    }
                } else if (read < 0) {
                    DebugStore.log("AudioRecord.read error: $read")
                    break
                }
            }
            DebugStore.log("Audio capture loop ended")
        }.apply { start() }
    }

    private fun removeFloatingBubble() {
        mainHandler.post {
            floatingView?.let {
                try {
                    windowManager?.removeView(it)
                } catch (_: Exception) {
                }
            }
            floatingView = null
        }
    }

    private fun stopEverything() {
        running.set(false)
        removeFloatingBubble()
        try {
            captureThread?.interrupt()
        } catch (_: Exception) {
        }
        captureThread = null

        try {
            webSocket?.close(1000, "User stopped")
        } catch (_: Exception) {
        }
        webSocket = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null

        DebugStore.setStatus("หยุดการทำงานแล้ว", false)
    }

    override fun onStateChanged() {
        tvFloatStatus?.text = DebugStore.status
        val t = DebugStore.getTranscript()
        if (t.isNotEmpty()) {
            tvFloatTranscript?.text = t.takeLast(220)
        }
    }

    override fun onDestroy() {
        DebugStore.removeListener(this)
        stopEverything()
        super.onDestroy()
    }
}
