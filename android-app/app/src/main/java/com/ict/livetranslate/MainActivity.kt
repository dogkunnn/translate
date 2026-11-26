package com.ict.livetranslate

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), DebugStore.Listener {

    private lateinit var etApiKey: EditText
    private lateinit var etPrompt: EditText
    private lateinit var btnToggle: Button
    private lateinit var btnOverlayPerm: Button
    private lateinit var btnBatteryPerm: Button
    private lateinit var btnToggleSettings: Button
    private lateinit var settingsBody: LinearLayout
    private lateinit var btnCopyLog: Button
    private lateinit var btnToggleLog: Button
    private lateinit var logScroll: ScrollView
    private lateinit var tvStatus: TextView
    private lateinit var tvTranscript: TextView
    private lateinit var tvLog: TextView

    private val prefs by lazy { getSharedPreferences("ict_live_prefs", Context.MODE_PRIVATE) }

    private val defaultPrompt =
        "Translate into natural spoken Thai like a friendly trading mentor explaining to a friend, " +
            "NOT formal essay Thai. Keep all ICT and SMC trading terms in English without translating them " +
            "(such as: gap, FVG, Fair Value Gap, Order Block, OB, Breaker, BOS, CHoCH, CISD, Liquidity, " +
            "Sweep, SMT, Killzone, Silver Bullet, PO3, HTF, LTF, Bias, Draw on Liquidity, Premium, Discount)."

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val audioOk = grants[Manifest.permission.RECORD_AUDIO] ?: (
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                )
            if (audioOk) {
                requestScreenCapture()
            } else {
                DebugStore.log("RECORD_AUDIO permission denied")
                Toast.makeText(this, "กรุณาอนุญาตสิทธิ์ไมค์/เสียงเพื่อดักฟังเสียงแอปอื่น", Toast.LENGTH_LONG).show()
            }
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            DebugStore.log("MediaProjection resultCode=${result.resultCode} hasData=${result.data != null}")
            if (result.resultCode == RESULT_OK && result.data != null) {
                val svcIntent = Intent(this, LiveTranslateService::class.java).apply {
                    action = LiveTranslateService.ACTION_START
                    putExtra(LiveTranslateService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(LiveTranslateService.EXTRA_RESULT_DATA, result.data)
                    putExtra(LiveTranslateService.EXTRA_API_KEY, etApiKey.text.toString().trim())
                    putExtra(LiveTranslateService.EXTRA_PROMPT, etPrompt.text.toString().trim())
                }
                ContextCompat.startForegroundService(this, svcIntent)
            } else {
                DebugStore.setStatus("ยกเลิกการแชร์เสียงหน้าจอ", false)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etApiKey = findViewById(R.id.etApiKey)
        etPrompt = findViewById(R.id.etPrompt)
        btnToggle = findViewById(R.id.btnToggle)
        btnOverlayPerm = findViewById(R.id.btnOverlayPerm)
        btnBatteryPerm = findViewById(R.id.btnBatteryPerm)
        btnToggleSettings = findViewById(R.id.btnToggleSettings)
        settingsBody = findViewById(R.id.settingsBody)
        btnCopyLog = findViewById(R.id.btnCopyLog)
        btnToggleLog = findViewById(R.id.btnToggleLog)
        logScroll = findViewById(R.id.logScroll)
        tvStatus = findViewById(R.id.tvStatus)
        tvTranscript = findViewById(R.id.tvTranscript)
        tvLog = findViewById(R.id.tvLog)

        etApiKey.setText(prefs.getString("api_key", ""))
        etPrompt.setText(prefs.getString("prompt", defaultPrompt))

        btnToggle.setOnClickListener {
            savePrefs()
            if (DebugStore.isRunning) {
                stopServiceSession()
            } else {
                startFlow()
            }
        }

        btnOverlayPerm.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "เปิดสิทธิ์ปุ่มลอยเรียบร้อยแล้ว", Toast.LENGTH_SHORT).show()
            }
        }

        btnBatteryPerm.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        btnToggleSettings.setOnClickListener {
            settingsBody.visibility =
                if (settingsBody.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        btnToggleLog.setOnClickListener {
            logScroll.visibility =
                if (logScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        btnCopyLog.setOnClickListener {
            val text = DebugStore.getLogsText()
            if (text.isEmpty()) {
                Toast.makeText(this, "ยังไม่มี Log ให้คัดลอก", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ICT Live Dub Log", text))
            Toast.makeText(this, "คัดลอก Log แล้ว!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        DebugStore.addListener(this)
        refreshPermissionButtons()
    }

    override fun onStop() {
        super.onStop()
        DebugStore.removeListener(this)
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "ตั้งค่าไม่จำกัดแบตเตอรี่ไว้แล้ว", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun refreshPermissionButtons() {
        val overlayOk = Settings.canDrawOverlays(this)
        btnOverlayPerm.text = if (overlayOk) "ปุ่มลอย: เปิดแล้ว" else "เปิดปุ่มลอยทับแอป"

        val pm = getSystemService(PowerManager::class.java)
        val batOk = pm.isIgnoringBatteryOptimizations(packageName)
        btnBatteryPerm.text = if (batOk) "แบต: ไม่จำกัดแล้ว" else "ตั้งค่าไม่จำกัดแบต"
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
        DebugStore.log("Launching MediaProjection permission dialog...")
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun stopServiceSession() {
        val stopIntent = Intent(this, LiveTranslateService::class.java).apply {
            action = LiveTranslateService.ACTION_STOP
        }
        startService(stopIntent)
    }

    override fun onStateChanged() {
        tvStatus.text = "สถานะ: ${DebugStore.status}"
        tvTranscript.text = DebugStore.getTranscript()
        val logs = DebugStore.getLogsText()
        tvLog.text = if (logs.isEmpty()) "ยังไม่มี Log..." else logs
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }

        if (DebugStore.isRunning) {
            btnToggle.text = "หยุดแปลสด"
            btnToggle.setBackgroundColor(0xFFEF4444.toInt())
        } else {
            btnToggle.text = "เริ่มแชร์เสียง & พากย์ไทยสด"
            btnToggle.setBackgroundColor(0xFF3B82F6.toInt())
        }
    }
}
