package com.ict.livetranslate

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etApiKey: EditText
    private lateinit var etPrompt: EditText
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvTranscript: TextView

    private var isRunning = false
    private val prefs by lazy { getSharedPreferences("ict_live_prefs", Context.MODE_PRIVATE) }

    private val defaultPrompt =
        "Translate into natural spoken Thai like a friendly trading mentor explaining to a friend, " +
            "NOT formal essay Thai. Keep all ICT and SMC trading terms in English without translating them " +
            "(such as: gap, FVG, Fair Value Gap, Order Block, OB, Breaker, BOS, CHoCH, CISD, Liquidity, " +
            "Sweep, SMT, Killzone, Silver Bullet, PO3, HTF, LTF, Bias, Draw on Liquidity, Premium, Discount)."

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val status = intent.getStringExtra(LiveTranslateService.EXTRA_STATUS).orEmpty()
            val chunk = intent.getStringExtra(LiveTranslateService.EXTRA_TRANSCRIPT).orEmpty()
            isRunning = intent.getBooleanExtra(LiveTranslateService.EXTRA_RUNNING, false)

            if (status.isNotEmpty()) tvStatus.text = "สถานะ: $status"
            if (chunk.isNotEmpty()) {
                tvTranscript.append(chunk)
            }
            updateButtonUi()
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val audioOk = grants[Manifest.permission.RECORD_AUDIO] ?: (
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                )
            if (audioOk) {
                requestScreenCapture()
            } else {
                Toast.makeText(this, "กรุณาอนุญาตสิทธิ์เสียงเพื่อดักฟังเสียงแอปอื่น", Toast.LENGTH_LONG).show()
            }
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val svcIntent = Intent(this, LiveTranslateService::class.java).apply {
                    action = LiveTranslateService.ACTION_START
                    putExtra(LiveTranslateService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(LiveTranslateService.EXTRA_RESULT_DATA, result.data)
                    putExtra(LiveTranslateService.EXTRA_API_KEY, etApiKey.text.toString().trim())
                    putExtra(LiveTranslateService.EXTRA_PROMPT, etPrompt.text.toString().trim())
                }
                ContextCompat.startForegroundService(this, svcIntent)
                isRunning = true
                updateButtonUi()
            } else {
                Toast.makeText(this, "ยกเลิกการแชร์เสียงหน้าจอ", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etApiKey = findViewById(R.id.etApiKey)
        etPrompt = findViewById(R.id.etPrompt)
        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)
        tvTranscript = findViewById(R.id.tvTranscript)

        etApiKey.setText(prefs.getString("api_key", ""))
        etPrompt.setText(prefs.getString("prompt", defaultPrompt))

        btnToggle.setOnClickListener {
            savePrefs()
            if (isRunning) {
                stopServiceSession()
            } else {
                startFlow()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(LiveTranslateService.BROADCAST_UPDATE)
        ContextCompat.registerReceiver(this, updateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(updateReceiver)
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("api_key", etApiKey.text.toString().trim())
            .putString("prompt", etPrompt.text.toString())
            .apply()
    }

    private fun startFlow() {
        if (etApiKey.text.toString().trim().isEmpty()) {
            Toast.makeText(this, "กรุณาใส่ Gemini API Key ก่อน", Toast.LENGTH_SHORT).show()
            return
        }
        tvTranscript.text = ""

        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun stopServiceSession() {
        val stopIntent = Intent(this, LiveTranslateService::class.java).apply {
            action = LiveTranslateService.ACTION_STOP
        }
        startService(stopIntent)
        isRunning = false
        updateButtonUi()
    }

    private fun updateButtonUi() {
        if (isRunning) {
            btnToggle.text = "หยุดแปลสด"
            btnToggle.setBackgroundColor(0xFFEF4444.toInt())
        } else {
            btnToggle.text = "เริ่มแชร์เสียง & พากย์ไทยสด"
            btnToggle.setBackgroundColor(0xFF3B82F6.toInt())
        }
    }
}
