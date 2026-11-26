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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class LiveTranslateService : Service(), DebugStore.Listener {

    companion object {
        const val ACTION_START = "com.ict.livetranslate.START"
        const val ACTION_STOP = "com.ict.livetranslate.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_MODEL = "model"

        private const val CHANNEL_ID = "nexsus_live_dub_channel"
        private const val NOTIF_ID = 1001
        private const val INPUT_RATE = 16000
        private const val OUTPUT_RATE = 24000
        private const val DEFAULT_MODEL = "models/gemini-3.5-live-translate-preview"
    }

    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastTxMillis = AtomicLong(0L)

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

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var tvBubbleLatency: TextView? = null
    private var tvFloatLatency: TextView? = null
    private var tvFloatStatus: TextView? = null
    private var tvFloatTranscript: TextView? = null
    private var btnFloatMute: Button? = null

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
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
            DebugStore.setMuted(false)
            DebugStore.log("Nexsus ACTION_START received")
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
            val apiKey = intent.getStringExtra(EXTRA_API_KEY).orEmpty().trim()
            val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty().trim()
            val model = intent.getStringExtra(EXTRA_MODEL).orEmpty().trim().ifEmpty { DEFAULT_MODEL }

            if (resultCode != Activity.RESULT_OK || resultData == null || apiKey.isEmpty()) {
                DebugStore.setStatus("ข้อมูลไม่ครบ (ตรวจสอบสิทธิ์แชร์จอหรือ API Key)", false)
                stopSelf()
                return START_NOT_STICKY
            }

            try {
                startInForeground()
                showFloatingBubbleIfAllowed()
                startLiveSession(
                    resultCode = resultCode,
                    resultData = resultData,
                    apiKey = apiKey,
                    model = model,
                    prompt = prompt,
                    tryPrompt = prompt.isNotEmpty(),
                    tryTranscription = true
                )
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
                "Nexsus Live Dubbing",
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
            .setContentTitle("Nexsus กำลังพากย์ไทยสด")
            .setContentText("กำลังฟังเสียงจากแอปอื่นและแปลแบบเรียลไทม์")
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
        if (!Settings.canDrawOverlays(this) || floatingView != null) return

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val inflater = LayoutInflater.from(this)
            val view = inflater.inflate(R.layout.floating_bubble, null)
            val screenWidth = resources.displayMetrics.widthPixels

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 28
                y = 240
            }

            val bubbleHead = view.findViewById<View>(R.id.bubbleHead)
            val bubblePanel = view.findViewById<View>(R.id.bubblePanel)
            tvBubbleLatency = view.findViewById(R.id.tvBubbleLatency)
            tvFloatLatency = view.findViewById(R.id.tvFloatLatency)
            tvFloatStatus = view.findViewById(R.id.tvFloatStatus)
            tvFloatTranscript = view.findViewById(R.id.tvFloatTranscript)
            btnFloatMute = view.findViewById(R.id.btnFloatMute)

            btnFloatMute?.setOnClickListener {
                DebugStore.toggleMute()
            }
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
                            } else if (bubblePanel.visibility != View.VISIBLE) {
                                // Snap bubble to nearest horizontal screen edge like Messenger
                                val mid = screenWidth / 2
                                params.x = if (params.x + 80 < mid) 24 else (screenWidth - 190).coerceAtLeast(24)
                                windowManager?.updateViewLayout(view, params)
                            }
                            return true
                        }
                    }
                    return false
                }
            })

            windowManager?.addView(view, params)
            floatingView = view
        } catch (e: Exception) {
            DebugStore.log("Floating bubble error: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLiveSession(
        resultCode: Int,
        resultData: Intent,
        apiKey: String,
        model: String,
        prompt: String,
        tryPrompt: Boolean,
        tryTranscription: Boolean
    ) {
        try {
            if (mediaProjection == null) {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                mediaProjection = mpm.getMediaProjection(resultCode, resultData)?.also {
                    it.registerCallback(projectionCallback, mainHandler)
                }
                DebugStore.log("MediaProjection ready")
            }
            val projection = mediaProjection ?: run {
                DebugStore.setStatus("ไม่สามารถเริ่ม MediaProjection ได้", false)
                stopSelf()
                return
            }

            running.set(true)
            DebugStore.setStatus("กำลังเชื่อมต่อ $model ...", true)

            if (audioTrack == null) {
                val outBufSize = AudioTrack.getMinBufferSize(
                    OUTPUT_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                val trackBuilder = AudioTrack.Builder()
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

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
                audioTrack = trackBuilder.build()
                audioTrack?.play()
                DebugStore.log("AudioTrack low-latency ready (24kHz, buf=$outBufSize)")
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
            val connectStartMs = System.currentTimeMillis()
            val request = Request.Builder().url(wsUrl).build()

            webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                private var setupDone = false
                private var rxAudioCount = 0

                override fun onOpen(ws: WebSocket, response: Response) {
                    val handshakeMs = System.currentTimeMillis() - connectStartMs
                    DebugStore.setLatency(handshakeMs)
                    DebugStore.log("WS connected (${handshakeMs}ms), sending setup (prompt=$tryPrompt, tx=$tryTranscription)")

                    val genConfig = JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put(
                            "translationConfig",
                            JSONObject().put("targetLanguageCode", "th")
                        )

                    val setupObj = JSONObject().apply {
                        put("model", model)
                        put("generationConfig", genConfig)
                        if (tryTranscription) {
                            put("outputAudioTranscription", JSONObject())
                            put("inputAudioTranscription", JSONObject())
                        }
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
                    lastTxMillis.set(System.currentTimeMillis())
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
                            val rtt = System.currentTimeMillis() - lastTxMillis.get()
                            DebugStore.setLatency(rtt)
                            DebugStore.log("setupComplete (${rtt}ms)! Capturing audio...")
                            DebugStore.setStatus("กำลังพากย์ไทยสด! เปิด YouTube / IG ฟังได้เลย", true)
                            startAudioCaptureLoop(ws)
                            return
                        }

                        val serverContent = json.optJSONObject("serverContent") ?: return

                        // Measure latency from last audio chunk sent to server response
                        val txTs = lastTxMillis.get()
                        if (txTs > 0) {
                            val delay = (System.currentTimeMillis() - txTs).coerceIn(1L, 9999L)
                            DebugStore.setLatency(delay)
                        }

                        // 1. Live output transcription (translated Thai text)
                        val outTx = serverContent.optJSONObject("outputTranscription")
                        if (outTx != null) {
                            val t = outTx.optString("text")
                            if (t.isNotEmpty()) {
                                DebugStore.appendTranscript(t)
                            }
                        }

                        // 2. Audio & any inline text parts
                        val modelTurn = serverContent.optJSONObject("modelTurn")
                        val parts = modelTurn?.optJSONArray("parts")
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val part = parts.getJSONObject(i)
                                val inlineData = part.optJSONObject("inlineData")
                                if (inlineData != null) {
                                    val b64 = inlineData.optString("data")
                                    if (b64.isNotEmpty()) {
                                        val pcm = Base64.decode(b64, Base64.DEFAULT)
                                        if (!DebugStore.isMuted) {
                                            audioTrack?.write(pcm, 0, pcm.size)
                                        }
                                        rxAudioCount++
                                        if (rxAudioCount == 1 || rxAudioCount % 80 == 0) {
                                            DebugStore.log("RX Thai audio chunks: $rxAudioCount")
                                        }
                                    }
                                }
                                val txt = part.optString("text")
                                if (txt.isNotEmpty()) {
                                    DebugStore.appendTranscript(txt)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        DebugStore.log("JSON parse err: ${e.message}")
                    }
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    DebugStore.log("WebSocket onClosing: code=$code reason=$reason")
                    if (running.get() && code == 1007) {
                        if (tryTranscription && reason.contains("Transcription", ignoreCase = true)) {
                            DebugStore.log("Retrying without audioTranscription due to 1007...")
                            ws.cancel()
                            mainHandler.post {
                                startLiveSession(
                                    resultCode, resultData, apiKey, model, prompt,
                                    tryPrompt = tryPrompt, tryTranscription = false
                                )
                            }
                            return
                        }
                        if (tryPrompt) {
                            DebugStore.log("Retrying without systemInstruction due to 1007...")
                            ws.cancel()
                            mainHandler.post {
                                startLiveSession(
                                    resultCode, resultData, apiKey, model, prompt,
                                    tryPrompt = false, tryTranscription = tryTranscription
                                )
                            }
                            return
                        }
                    }
                    if (running.get()) {
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
                    DebugStore.log("WebSocket onFailure ($codeInfo): ${t.message}")
                    if (running.get()) {
                        DebugStore.setStatus("เชื่อมต่อไม่สำเร็จ: ${t.message}", false)
                        stopEverything()
                        stopSelf()
                    }
                }
            })
        } catch (e: Exception) {
            DebugStore.log("startLiveSession exception: ${e.message}")
            DebugStore.setStatus("เกิดข้อผิดพลาด: ${e.message}", false)
            stopEverything()
            stopSelf()
        }
    }

    private fun startAudioCaptureLoop(ws: WebSocket) {
        if (captureThread != null) return
        val rec = audioRecord ?: return
        rec.startRecording()

        captureThread = Thread {
            val buffer = ByteArray(2048) // 64ms at 16kHz mono 16-bit
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
                    lastTxMillis.set(System.currentTimeMillis())
                    ws.send(payload.toString())
                } else if (read < 0) {
                    DebugStore.log("AudioRecord.read error: $read")
                    break
                }
            }
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
        val ms = DebugStore.latencyMs
        val msText = if (ms >= 0) "$ms ms" else "-- ms"
        tvBubbleLatency?.text = msText
        tvFloatLatency?.text = "Delay: $msText"

        btnFloatMute?.text = if (DebugStore.isMuted) "เปิดเสียง" else "ปิดเสียง"

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
