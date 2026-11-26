package com.ict.livetranslate

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object DebugStore {
    interface Listener {
        fun onStateChanged()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val logs = ArrayList<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    var status: String = "พร้อมใช้งาน • กดเริ่มแล้วเลือก 'ทั้งหน้าจอ'"
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var isMuted: Boolean = false
        private set

    @Volatile
    var latencyMs: Long = -1L
        private set

    private val transcriptBuilder = StringBuilder()

    fun addListener(l: Listener) {
        listeners.addIfAbsent(l)
        l.onStateChanged()
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun log(msg: String) {
        val line = "[${timeFmt.format(Date())}] $msg"
        synchronized(logs) {
            logs.add(line)
            if (logs.size > 300) {
                logs.removeAt(0)
            }
        }
        notifyListeners()
    }

    fun setStatus(newStatus: String, running: Boolean? = null) {
        status = newStatus
        if (running != null) {
            isRunning = running
        }
        log("STATUS: $newStatus")
    }

    fun setLatency(ms: Long) {
        latencyMs = ms
        notifyListeners()
    }

    fun toggleMute(): Boolean {
        isMuted = !isMuted
        log(if (isMuted) "Audio output muted" else "Audio output unmuted")
        notifyListeners()
        return isMuted
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        notifyListeners()
    }

    fun appendTranscript(chunk: String) {
        if (chunk.isEmpty()) return
        synchronized(transcriptBuilder) {
            transcriptBuilder.append(chunk)
            if (transcriptBuilder.length > 6000) {
                transcriptBuilder.delete(0, transcriptBuilder.length - 5000)
            }
        }
        notifyListeners()
    }

    fun clearTranscriptAndLogs() {
        synchronized(transcriptBuilder) {
            transcriptBuilder.clear()
        }
        synchronized(logs) {
            logs.clear()
        }
        latencyMs = -1L
        notifyListeners()
    }

    fun getTranscript(): String = synchronized(transcriptBuilder) { transcriptBuilder.toString() }

    fun getLogsText(): String = synchronized(logs) { logs.joinToString("\n") }

    private fun notifyListeners() {
        mainHandler.post {
            for (l in listeners) {
                l.onStateChanged()
            }
        }
    }
}
