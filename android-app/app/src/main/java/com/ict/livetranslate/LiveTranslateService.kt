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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
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

class LiveTranslateService : Service() {

    companion object {
        const val ACTION_START = "com.ict.livetranslate.START"
        const val ACTION_STOP = "com.ict.livetranslate.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_PROMPT = "prompt"

        const val BROADCAST_UPDATE = "com.ict.livetranslate.UPDATE"
        const val EXTRA_STATUS = "status"
        const val EXTRA_TRANSCRIPT = "transcript"
        const val EXTRA_RUNNING = "running"

        private const val CHANNEL_ID = "ict_live_dub_channel"
        private const val NOTIF_ID = 1001
        private const val INPUT_RATE = 16000
        private const val OUTPUT_RATE = 24000
        private const val MODEL = "models/gemini-3.5-live-translate-preview"
    }

    private val running = AtomicBoolean(false)
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var webSocket: WebSocket? = null
    private var captureThread: Thread? = null

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
            val apiKey = intent.getStringExtra(EXTRA_API_KEY).orEmpty().trim()
            val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty().trim()

            if (resultCode != Activity.RESULT_OK || resultData == null || apiKey.isEmpty()) {
                broadcastState("ข้อมูลไม่ครบ (ตรวจสอบสิทธิ์แชร์จอหรือ API Key)", "", false)
                stopSelf()
                return START_NOT_STICKY
            }

            startInForeground()
            startLiveSession(resultCode, resultData, apiKey, prompt, tryPrompt = prompt.isNotEmpty())
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

        val openIntent = Intent(this, MainActivity::class.java)
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

    @SuppressLint("MissingPermission")
    private fun startLiveSession(
        resultCode: Int,
        resultData: Intent,
        apiKey: String,
        prompt: String,
        tryPrompt: Boolean
    ) {
        if (mediaProjection == null) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            mediaProjection = mpm.getMediaProjection(resultCode, resultData)
        }
        val projection = mediaProjection ?: run {
            broadcastState("ไม่สามารถเริ่ม MediaProjection ได้", "", false)
            stopSelf()
            return
        }

        running.set(true)
        broadcastState("กำลังเชื่อมต่อ Gemini Live Translate...", "", true)

        // Output AudioTrack uses USAGE_ASSISTANCE_NAVIGATION_GUIDANCE so AudioPlaybackCapture
        // (which only captures USAGE_MEDIA / USAGE_GAME / USAGE_UNKNOWN) won't echo our own Thai voice.
        val outBufSize = AudioTrack.getMinBufferSize(
            OUTPUT_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)

        if (audioTrack == null) {
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
        }

        val wsUrl =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(wsUrl).build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            private var setupDone = false

            override fun onOpen(ws: WebSocket, response: Response) {
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
                    if (json.has("setupComplete") && !setupDone) {
                        setupDone = true
                        broadcastState("กำลังพากย์ไทยสด! เปิด YouTube / IG ฟังได้เลย", "", true)
                        startAudioCaptureLoop(ws)
                        return
                    }

                    val serverContent = json.optJSONObject("serverContent") ?: return
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
                            }
                        }
                        val txt = part.optString("text")
                        if (txt.isNotEmpty()) {
                            broadcastState("กำลังพากย์ไทยสด...", txt, true)
                        }
                    }
                } catch (_: Exception) {
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                if (running.get() && tryPrompt && code == 1007) {
                    // Retry automatically without systemInstruction if model rejects it
                    ws.cancel()
                    startLiveSession(resultCode, resultData, apiKey, prompt, tryPrompt = false)
                } else if (running.get()) {
                    broadcastState("ตัดการเชื่อมต่อ ($code: $reason)", "", false)
                    stopEverything()
                    stopSelf()
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (running.get()) {
                    broadcastState("เชื่อมต่อไม่สำเร็จ: ${t.message}", "", false)
                    stopEverything()
                    stopSelf()
                }
            }
        })
    }

    private fun startAudioCaptureLoop(ws: WebSocket) {
        if (captureThread != null) return
        val rec = audioRecord ?: return
        rec.startRecording()

        captureThread = Thread {
            val buffer = ByteArray(2048) // 64ms at 16kHz 16-bit mono
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
                }
            }
        }.apply { start() }
    }

    private fun stopEverything() {
        running.set(false)
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
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null

        broadcastState("หยุดการทำงานแล้ว", "", false)
    }

    private fun broadcastState(status: String, transcriptChunk: String, isRunning: Boolean) {
        val intent = Intent(BROADCAST_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_TRANSCRIPT, transcriptChunk)
            putExtra(EXTRA_RUNNING, isRunning)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }
}
