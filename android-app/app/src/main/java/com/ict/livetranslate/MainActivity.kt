package com.ict.livetranslate

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsetsController
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity(), DebugStore.Listener {

    private data class ModelChoice(val title: String, val id: String)
    private data class ToneChoice(val title: String, val instruction: String)

    private val modelChoices = listOf(
        ModelChoice("Gemini 3.5 Live Translate (แนะนำ • พากย์สด)", "models/gemini-3.5-live-translate-preview"),
        ModelChoice("Gemini 2.5 Flash Native Audio", "models/gemini-2.5-flash-native-audio-preview-09-2025"),
        ModelChoice("Gemini Live 2.5 Flash", "models/gemini-live-2.5-flash-preview"),
        ModelChoice("OpenAI GPT-4o Realtime (ใช้ OpenAI Key)", "openai:gpt-4o-realtime-preview"),
        ModelChoice("OpenAI GPT Realtime Mini (ไวกว่า • OpenAI Key)", "openai:gpt-4o-mini-realtime-preview")
    )

    private val toneChoices = listOf(
        ToneChoice(
            "โค้ชสอนเทรด (ภาษาพูดเป็นกันเอง)",
            "Translate into natural spoken Thai like a friendly trading mentor explaining to a friend, NOT formal essay Thai."
        ),
        ToneChoice(
            "กระชับ ตรงประเด็น เข้าใจไว",
            "Translate into concise, clear spoken Thai focused on fast comprehension during live chart analysis."
        ),
        ToneChoice(
            "แปลตรงตัวแบบสุภาพ",
            "Translate accurately into polite, natural Thai while preserving the speaker's exact meaning."
        )
    )

    private val termGroups = mapOf(
        "ict" to "FVG, IFVG, BPR, Fair Value Gap, Order Block, OB, Breaker, Mitigation, BOS, CHoCH, MSS, CISD, Liquidity, Sweep, Raid, SMT, Killzone, Silver Bullet, PO3, AMD, HTF, LTF, Bias, DOL, Draw on Liquidity, Premium, Discount, OTE, EQH, EQL, NWOG, NDOG, Inducement",
        "ttfm" to "TTFM, Timeframe Alignment, Top-Down, C2, C3, C4, Fractal, Swing High, Swing Low, Expansion, Retracement",
        "gxt" to "GXT, GxTradez, Gap Filter, Relevant Swing, Anomaly, Liquidity Sweep",
        "vtrades" to "VTrades, VTRADESE, V-Shape, Entry Model, Confirmation, Continuation"
    )

    private lateinit var rootContainer: LinearLayout
    private lateinit var tvBrandTitle: TextView
    private lateinit var tvBrandSub: TextView
    private lateinit var tvLatencyBadge: TextView

    // Pages
    private lateinit var pageLive: LinearLayout
    private lateinit var pageGlossary: ScrollView
    private lateinit var pageSystem: LinearLayout
    private lateinit var bottomNav: BottomNavigationView

    // Page 1
    private lateinit var cardHero: LinearLayout
    private lateinit var liveDot: View
    private lateinit var tvStatus: TextView
    private lateinit var tvActiveModelPill: TextView
    private lateinit var btnToggle: MaterialButton
    private lateinit var btnMuteMain: MaterialButton
    private lateinit var tvHint: TextView
    private lateinit var cardTranscript: LinearLayout
    private lateinit var tvTranscriptTitle: TextView
    private lateinit var btnCopyTranscript: MaterialButton
    private lateinit var btnClearTranscript: MaterialButton
    private lateinit var transcriptScroll: ScrollView
    private lateinit var tvTranscript: TextView

    // Page 2
    private lateinit var cardModel: LinearLayout
    private lateinit var tvModelSectionTitle: TextView
    private lateinit var tilModel: TextInputLayout
    private lateinit var dropModel: AutoCompleteTextView
    private lateinit var tilTone: TextInputLayout
    private lateinit var dropTone: AutoCompleteTextView
    private lateinit var tilApiKey: TextInputLayout
    private lateinit var etApiKey: TextInputEditText
    private lateinit var cardGlossary: LinearLayout
    private lateinit var tvGlossaryTitle: TextView
    private lateinit var tvGlossarySub: TextView
    private lateinit var btnResetTerms: MaterialButton
    private lateinit var chipIct: Chip
    private lateinit var chipTtfm: Chip
    private lateinit var chipGxt: Chip
    private lateinit var chipVtrades: Chip
    private lateinit var etTerms: EditText

    // Page 3
    private lateinit var cardTheme: LinearLayout
    private lateinit var tvThemeTitle: TextView
    private lateinit var tilTheme: TextInputLayout
    private lateinit var dropTheme: AutoCompleteTextView
    private lateinit var cardPerms: LinearLayout
    private lateinit var tvPermsTitle: TextView
    private lateinit var btnOverlayPerm: MaterialButton
    private lateinit var btnBatteryPerm: MaterialButton
    private lateinit var cardLog: LinearLayout
    private lateinit var tvLogTitle: TextView
    private lateinit var btnCopyLog: MaterialButton
    private lateinit var btnClearLog: MaterialButton
    private lateinit var logScroll: ScrollView
    private lateinit var tvLog: TextView

    private val prefs by lazy { getSharedPreferences("nexsus_prefs_v2", Context.MODE_PRIVATE) }
    private var selectedModelIdx = 0
    private var selectedToneIdx = 0
    private var currentTheme = ThemeManager.themes.first()
    private var pulseAnim: ObjectAnimator? = null

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
                Toast.makeText(this, "กรุณาอนุญาตสิทธิ์เสียงเพื่อดักฟังเสียงแอปอื่น", Toast.LENGTH_LONG).show()
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
                    putExtra(LiveTranslateService.EXTRA_API_KEY, etApiKey.text?.toString().orEmpty().trim())
                    putExtra(LiveTranslateService.EXTRA_PROMPT, buildFullPrompt())
                    putExtra(LiveTranslateService.EXTRA_MODEL, modelChoices[selectedModelIdx].id)
                }
                ContextCompat.startForegroundService(this, svcIntent)
            } else {
                DebugStore.setStatus("ยกเลิกการแชร์เสียงหน้าจอ", false)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupDropdowns()
        setupChipsAndButtons()
        setupBottomNav()

        currentTheme = ThemeManager.byId(prefs.getString("theme_id", "pastel_violet"))
        applyTheme(currentTheme)
    }

    private fun bindViews() {
        rootContainer = findViewById(R.id.rootContainer)
        tvBrandTitle = findViewById(R.id.tvBrandTitle)
        tvBrandSub = findViewById(R.id.tvBrandSub)
        tvLatencyBadge = findViewById(R.id.tvLatencyBadge)

        pageLive = findViewById(R.id.pageLive)
        pageGlossary = findViewById(R.id.pageGlossary)
        pageSystem = findViewById(R.id.pageSystem)
        bottomNav = findViewById(R.id.bottomNav)

        cardHero = findViewById(R.id.cardHero)
        liveDot = findViewById(R.id.liveDot)
        tvStatus = findViewById(R.id.tvStatus)
        tvActiveModelPill = findViewById(R.id.tvActiveModelPill)
        btnToggle = findViewById(R.id.btnToggle)
        btnMuteMain = findViewById(R.id.btnMuteMain)
        tvHint = findViewById(R.id.tvHint)
        cardTranscript = findViewById(R.id.cardTranscript)
        tvTranscriptTitle = findViewById(R.id.tvTranscriptTitle)
        btnCopyTranscript = findViewById(R.id.btnCopyTranscript)
        btnClearTranscript = findViewById(R.id.btnClearTranscript)
        transcriptScroll = findViewById(R.id.transcriptScroll)
        tvTranscript = findViewById(R.id.tvTranscript)

        cardModel = findViewById(R.id.cardModel)
        tvModelSectionTitle = findViewById(R.id.tvModelSectionTitle)
        tilModel = findViewById(R.id.tilModel)
        dropModel = findViewById(R.id.dropModel)
        tilTone = findViewById(R.id.tilTone)
        dropTone = findViewById(R.id.dropTone)
        tilApiKey = findViewById(R.id.tilApiKey)
        etApiKey = findViewById(R.id.etApiKey)
        cardGlossary = findViewById(R.id.cardGlossary)
        tvGlossaryTitle = findViewById(R.id.tvGlossaryTitle)
        tvGlossarySub = findViewById(R.id.tvGlossarySub)
        btnResetTerms = findViewById(R.id.btnResetTerms)
        chipIct = findViewById(R.id.chipIct)
        chipTtfm = findViewById(R.id.chipTtfm)
        chipGxt = findViewById(R.id.chipGxt)
        chipVtrades = findViewById(R.id.chipVtrades)
        etTerms = findViewById(R.id.etTerms)

        cardTheme = findViewById(R.id.cardTheme)
        tvThemeTitle = findViewById(R.id.tvThemeTitle)
        tilTheme = findViewById(R.id.tilTheme)
        dropTheme = findViewById(R.id.dropTheme)
        cardPerms = findViewById(R.id.cardPerms)
        tvPermsTitle = findViewById(R.id.tvPermsTitle)
        btnOverlayPerm = findViewById(R.id.btnOverlayPerm)
        btnBatteryPerm = findViewById(R.id.btnBatteryPerm)
        cardLog = findViewById(R.id.cardLog)
        tvLogTitle = findViewById(R.id.tvLogTitle)
        btnCopyLog = findViewById(R.id.btnCopyLog)
        btnClearLog = findViewById(R.id.btnClearLog)
        logScroll = findViewById(R.id.logScroll)
        tvLog = findViewById(R.id.tvLog)
    }

    private fun setupDropdowns() {
        selectedModelIdx = prefs.getInt("model_idx", 0).coerceIn(0, modelChoices.lastIndex)
        val modelTitles = modelChoices.map { it.title }
        dropModel.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, modelTitles))
        dropModel.setText(modelTitles[selectedModelIdx], false)
        dropModel.setOnItemClickListener { _, _, pos, _ ->
            selectedModelIdx = pos
            savePrefs()
            tvActiveModelPill.text = "Model: ${modelChoices[pos].title}"
            val isOpenAi = modelChoices[pos].id.startsWith("openai:")
            tilApiKey.hint = if (isOpenAi) "OpenAI API Key (sk-...)" else "Gemini API Key (AIza...)"
        }
        tvActiveModelPill.text = "Model: ${modelChoices[selectedModelIdx].title}"
        tilApiKey.hint = if (modelChoices[selectedModelIdx].id.startsWith("openai:")) "OpenAI API Key (sk-...)" else "Gemini API Key (AIza...)"

        selectedToneIdx = prefs.getInt("tone_idx", 0).coerceIn(0, toneChoices.lastIndex)
        val toneTitles = toneChoices.map { it.title }
        dropTone.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, toneTitles))
        dropTone.setText(toneTitles[selectedToneIdx], false)
        dropTone.setOnItemClickListener { _, _, pos, _ ->
            selectedToneIdx = pos
            savePrefs()
        }

        val themeLabels = ThemeManager.themes.map { it.label }
        val savedThemeIdx = ThemeManager.themes.indexOfFirst { it.id == prefs.getString("theme_id", "pastel_violet") }
            .coerceAtLeast(0)
        dropTheme.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, themeLabels))
        dropTheme.setText(themeLabels[savedThemeIdx], false)
        dropTheme.setOnItemClickListener { _, _, pos, _ ->
            currentTheme = ThemeManager.themes[pos]
            prefs.edit().putString("theme_id", currentTheme.id).apply()
            applyTheme(currentTheme)
        }
    }

    private fun setupChipsAndButtons() {
        etApiKey.setText(prefs.getString("api_key", ""))
        val savedTerms = prefs.getString("terms", null)
        if (savedTerms != null) {
            etTerms.setText(savedTerms)
        } else {
            rebuildTermsFromChips()
        }

        val chipListener = View.OnClickListener { rebuildTermsFromChips() }
        chipIct.setOnClickListener(chipListener)
        chipTtfm.setOnClickListener(chipListener)
        chipGxt.setOnClickListener(chipListener)
        chipVtrades.setOnClickListener(chipListener)

        btnResetTerms.setOnClickListener {
            chipIct.isChecked = true
            chipTtfm.isChecked = true
            chipGxt.isChecked = true
            chipVtrades.isChecked = true
            rebuildTermsFromChips()
            Toast.makeText(this, "รีเซ็ตคำศัพท์เริ่มต้นแล้ว", Toast.LENGTH_SHORT).show()
        }

        btnToggle.setOnClickListener {
            savePrefs()
            if (DebugStore.isRunning) {
                stopServiceSession()
            } else {
                startFlow()
            }
        }

        btnMuteMain.setOnClickListener { DebugStore.toggleMute() }

        btnCopyTranscript.setOnClickListener {
            val txt = DebugStore.getTranscript()
            if (txt.isEmpty()) {
                Toast.makeText(this, "ยังไม่มีคำแปลให้คัดลอก", Toast.LENGTH_SHORT).show()
            } else {
                copyText("Nexsus Transcript", txt, "คัดลอกคำแปลแล้ว!")
            }
        }
        btnClearTranscript.setOnClickListener {
            DebugStore.clearTranscriptAndLogs()
            Toast.makeText(this, "ล้างข้อความแล้ว", Toast.LENGTH_SHORT).show()
        }

        btnOverlayPerm.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "เปิดสิทธิ์ปุ่มลอยเรียบร้อยแล้ว", Toast.LENGTH_SHORT).show()
            }
        }

        btnBatteryPerm.setOnClickListener { requestIgnoreBatteryOptimization() }

        btnCopyLog.setOnClickListener {
            val txt = DebugStore.getLogsText()
            if (txt.isEmpty()) {
                Toast.makeText(this, "ยังไม่มี Log ให้คัดลอก", Toast.LENGTH_SHORT).show()
            } else {
                copyText("Nexsus Debug Log", txt, "คัดลอก Log แล้ว!")
            }
        }
        btnClearLog.setOnClickListener {
            DebugStore.clearTranscriptAndLogs()
            Toast.makeText(this, "ล้าง Log แล้ว", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            val targetPage: View = when (item.itemId) {
                R.id.nav_live -> pageLive
                R.id.nav_glossary -> pageGlossary
                R.id.nav_system -> pageSystem
                else -> pageLive
            }
            switchPage(targetPage)
            true
        }
    }

    private fun switchPage(target: View) {
        val pages = listOf(pageLive, pageGlossary, pageSystem)
        for (p in pages) {
            if (p == target) {
                if (p.visibility != View.VISIBLE) {
                    p.alpha = 0f
                    p.translationY = 18f
                    p.visibility = View.VISIBLE
                    p.animate().alpha(1f).translationY(0f).setDuration(180).start()
                }
            } else {
                p.visibility = View.GONE
            }
        }
    }

    private fun rebuildTermsFromChips() {
        val parts = mutableListOf<String>()
        if (chipIct.isChecked) parts.add(termGroups["ict"]!!)
        if (chipTtfm.isChecked) parts.add(termGroups["ttfm"]!!)
        if (chipGxt.isChecked) parts.add(termGroups["gxt"]!!)
        if (chipVtrades.isChecked) parts.add(termGroups["vtrades"]!!)
        etTerms.setText(parts.joinToString(", "))
        savePrefs()
    }

    private fun buildFullPrompt(): String {
        val tone = toneChoices[selectedToneIdx].instruction
        val terms = etTerms.text.toString().trim()
        return if (terms.isEmpty()) tone
        else "$tone Keep all these trading terms in English without translating them: $terms."
    }

    private fun applyTheme(t: NexsusTheme) {
        val d = resources.displayMetrics.density
        rootContainer.setBackgroundColor(t.bg)

        // Status bar color & icon contrast
        window.statusBarColor = t.bg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                if (t.isDark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        }

        tvBrandTitle.setTextColor(t.accent)
        tvBrandSub.setTextColor(t.textSecondary)
        tvLatencyBadge.background = ThemeManager.roundedBox(t.accentSoft, t.cardBorder, 999f, d)
        tvLatencyBadge.setTextColor(t.statusGood)

        val cards = listOf(cardHero, cardTranscript, cardModel, cardGlossary, cardTheme, cardPerms, cardLog)
        for (c in cards) {
            c.background = ThemeManager.roundedBox(t.card, t.cardBorder, 18f, d)
        }

        val inputs = listOf(transcriptScroll, etTerms, logScroll)
        for (inp in inputs) {
            inp.background = ThemeManager.roundedBox(t.inputBg, t.cardBorder, 12f, d)
        }

        tvActiveModelPill.setTextColor(t.textSecondary)
        tvHint.setTextColor(t.textSecondary)
        tvTranscriptTitle.setTextColor(t.textPrimary)
        tvTranscript.setTextColor(t.textPrimary)
        tvModelSectionTitle.setTextColor(t.textPrimary)
        tvGlossaryTitle.setTextColor(t.textPrimary)
        tvGlossarySub.setTextColor(t.textSecondary)
        etTerms.setTextColor(t.textPrimary)
        etTerms.setHintTextColor(t.textSecondary)
        tvThemeTitle.setTextColor(t.textPrimary)
        tvPermsTitle.setTextColor(t.textPrimary)
        tvLogTitle.setTextColor(t.textPrimary)
        tvLog.setTextColor(t.textSecondary)

        etApiKey.setTextColor(t.textPrimary)
        dropModel.setTextColor(t.textPrimary)
        dropTone.setTextColor(t.textPrimary)
        dropTheme.setTextColor(t.textPrimary)

        val textInputs = listOf(tilModel, tilTone, tilApiKey, tilTheme)
        for (til in textInputs) {
            til.boxStrokeColor = t.accent
            til.defaultHintTextColor = ColorStateList.valueOf(t.textSecondary)
        }

        btnMuteMain.backgroundTintList = ColorStateList.valueOf(t.accentSoft)
        btnMuteMain.setTextColor(t.textPrimary)
        btnOverlayPerm.backgroundTintList = ColorStateList.valueOf(t.accentSoft)
        btnOverlayPerm.setTextColor(t.textPrimary)
        btnBatteryPerm.backgroundTintList = ColorStateList.valueOf(t.accentSoft)
        btnBatteryPerm.setTextColor(t.textPrimary)

        btnCopyTranscript.setTextColor(t.accent)
        btnClearTranscript.setTextColor(t.textSecondary)
        btnResetTerms.setTextColor(t.accent)
        btnCopyLog.setTextColor(t.statusGood)
        btnClearLog.setTextColor(t.textSecondary)

        bottomNav.setBackgroundColor(t.navBg)
        val navColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(t.accent, t.textSecondary)
        )
        bottomNav.itemIconTintList = navColors
        bottomNav.itemTextColor = navColors

        onStateChanged()
    }

    private fun updateLiveDot(running: Boolean) {
        val color = if (running) currentTheme.statusGood else currentTheme.textSecondary
        liveDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        if (running) {
            if (pulseAnim == null) {
                pulseAnim = ObjectAnimator.ofFloat(liveDot, "alpha", 1f, 0.25f, 1f).apply {
                    duration = 1100
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
        } else {
            pulseAnim?.cancel()
            pulseAnim = null
            liveDot.alpha = 1f
        }
    }

    override fun onStart() {
        super.onStart()
        DebugStore.addListener(this)
        refreshPermissionButtons()
    }

    override fun onStop() {
        super.onStop()
        savePrefs()
        DebugStore.removeListener(this)
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "ตั้งค่าไม่จำกัดแบตเตอรี่ไว้แล้ว", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun refreshPermissionButtons() {
        val overlayOk = Settings.canDrawOverlays(this)
        btnOverlayPerm.text = if (overlayOk) "ปุ่มลอย: เปิดแล้ว ✓" else "เปิดปุ่มลอยทับแอป"
        val pm = getSystemService(PowerManager::class.java)
        val batOk = pm.isIgnoringBatteryOptimizations(packageName)
        btnBatteryPerm.text = if (batOk) "แบต: ไม่จำกัดแล้ว ✓" else "ตั้งค่าไม่จำกัดแบต"
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("api_key", etApiKey.text?.toString().orEmpty().trim())
            .putString("terms", etTerms.text.toString())
            .putInt("model_idx", selectedModelIdx)
            .putInt("tone_idx", selectedToneIdx)
            .apply()
    }

    private fun startFlow() {
        if (etApiKey.text?.toString().orEmpty().trim().isEmpty()) {
            Toast.makeText(this, "กรุณาใส่ Gemini API Key ในแท็บ 'ศัพท์ & โมเดล' ก่อน", Toast.LENGTH_LONG).show()
            bottomNav.selectedItemId = R.id.nav_glossary
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
        startService(Intent(this, LiveTranslateService::class.java).apply {
            action = LiveTranslateService.ACTION_STOP
        })
    }

    private fun copyText(label: String, text: String, toastMsg: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
    }

    override fun onStateChanged() {
        tvStatus.text = DebugStore.status
        tvStatus.setTextColor(if (DebugStore.isRunning) currentTheme.statusGood else currentTheme.textPrimary)
        updateLiveDot(DebugStore.isRunning)

        val ms = DebugStore.latencyMs
        tvLatencyBadge.text = if (ms >= 0) "Delay: $ms ms" else "Delay: -- ms"

        val t = DebugStore.getTranscript()
        tvTranscript.text = if (t.isEmpty()) "รอเสียงพูด..." else t
        transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }

        val logs = DebugStore.getLogsText()
        tvLog.text = if (logs.isEmpty()) "ยังไม่มี Log..." else logs
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }

        if (DebugStore.isRunning) {
            btnToggle.text = "หยุดแปลสด"
            btnToggle.backgroundTintList = ColorStateList.valueOf(currentTheme.danger)
            btnMuteMain.visibility = View.VISIBLE
            btnMuteMain.text = if (DebugStore.isMuted) "เปิดเสียง" else "ปิดเสียง"
        } else {
            btnToggle.text = "เริ่มแชร์เสียง & พากย์ไทยสด"
            btnToggle.backgroundTintList = ColorStateList.valueOf(currentTheme.accent)
            btnMuteMain.visibility = View.GONE
        }
    }
}
