package com.protocol.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.protocol.R
import com.protocol.config.ConfigStore
import com.protocol.databinding.ActivityMainBinding
import com.protocol.databinding.ItemProcessBinding
import com.protocol.engine.Mode
import com.protocol.monitor.MonitorListenerService
import com.protocol.service.EngineHolder
import com.protocol.service.ReminderService

class MainActivity : AppCompatActivity() {

    private companion object { const val KEY_CONTROLS_OPEN = "controls_open" }

    private lateinit var b: ActivityMainBinding
    private lateinit var store: ConfigStore
    /** Недописанные ответы по процессам: карточки перерисовываются при каждом такте движка. */
    private val drafts = HashMap<String, String>()
    private lateinit var schedule: ScheduleSection

    private val pickConfig = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        // Запись нужна таблице протокола: правки пишутся в этот же файл.
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        store.configUri = uri
        checkConfig()
        ReminderService.send(this, ReminderService.intent(this, ReminderService.ACTION_INIT))
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Копия текущего конфига в файл по выбору пользователя (SPEC 6.1). */
    private val exportConfig = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val text = store.configText ?: run { checkConfig(); return@registerForActivityResult }
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("файл не открывается")
            Toast.makeText(this, R.string.export_done, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.export_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        store = ConfigStore(this)

        // Панель кнопок свёрнута по умолчанию, состояние запоминается.
        b.controlsHeader.setOnClickListener { setControlsOpen(!prefs.getBoolean(KEY_CONTROLS_OPEN, false)) }
        setControlsOpen(prefs.getBoolean(KEY_CONTROLS_OPEN, false))

        b.pickConfigButton.setOnClickListener { pickConfig.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
        b.checkConfigButton.setOnClickListener { checkConfig() }
        b.exportConfigButton.setOnClickListener {
            // Текст берётся из последней прочитанной копии; перед этим перечитать файл.
            if (store.configUri != null) store.reload()
            exportConfig.launch("protocol-config-${java.time.LocalDate.now()}.json")
        }
        b.logsButton.setOnClickListener { startActivity(Intent(this, LogsActivity::class.java)) }
        b.startStopButton.setOnClickListener {
            val running = EngineHolder.engine?.state?.mode == Mode.RUNNING
            if (running) ReminderService.send(this, ReminderService.intent(this, ReminderService.ACTION_MANUAL_STOP))
            else if (ensurePermissions()) ReminderService.send(this, ReminderService.intent(this, ReminderService.ACTION_START))
        }

        schedule = ScheduleSection(this, store, b.scheduleTable, b.editScheduleButton, b.processButtons, b.addProcessButton) { ensureNotifications() }

        EngineHolder.version.observe(this) { render() }
        // Поднять движок, чтобы главный экран знал состояние.
        if (store.configUri != null) ReminderService.send(this, ReminderService.intent(this, ReminderService.ACTION_INIT))
    }

    override fun onResume() {
        super.onResume()
        render()
        // Если идёт шаг по графику, открыть сразу его экран, а не главный:
        // например, после ответа «Нет» кнопкой в шторке уведомления.
        if (EngineHolder.engine?.stepScreen() != null) {
            startActivity(Intent(this, StepActivity::class.java))
        }
    }

    private fun render() {
        val engine = EngineHolder.engine
        val err = EngineHolder.lastError
        b.openProcesses.removeAllViews()

        when {
            store.configUri == null -> { b.statusText.text = getString(R.string.status_not_configured); b.detailText.text = "" }
            err != null -> { b.statusText.text = getString(R.string.status_config_error); b.detailText.text = err }
            engine == null -> { b.statusText.text = getString(R.string.status_idle); b.detailText.text = "" }
            else -> {
                val st = engine.state
                b.statusText.text = when (st.mode) {
                    Mode.RUNNING -> getString(R.string.status_running)
                    Mode.STOPPED -> getString(R.string.status_stopped)
                    Mode.IDLE -> getString(R.string.status_idle)
                }
                b.detailText.text = when {
                    st.activeStep != null -> getString(R.string.label_current_step, st.activeStep!!.stepId)
                    else -> engine.nextStepInfo()?.let { getString(R.string.label_next_step, it.first, it.second) } ?: ""
                }
                // Открытые процессы идут прямо здесь, карточкой с вопросом и кнопками. SPEC 5.8.
                drafts.keys.retainAll(st.procs.map { it.procId }.toSet())
                for (run in st.procs) {
                    val scr = engine.procScreen(run.procId) ?: continue
                    val card = ItemProcessBinding.inflate(layoutInflater, b.openProcesses, false)
                    card.titleText.text = scr.title
                    card.questionText.text = scr.text
                    ScreenBinder.bind(this, scr, run.procId, card.answerInput, card.buttons, large = false)
                    card.answerInput.setText(drafts[run.procId] ?: "")
                    card.answerInput.doAfterTextChanged { drafts[run.procId] = it?.toString() ?: "" }
                    b.openProcesses.addView(card.root)
                }
            }
        }
        renderMonitors(engine)
        schedule.render()
        b.startStopButton.isEnabled = store.configUri != null && err == null
        b.startStopButton.text = getString(if (engine?.state?.mode == Mode.RUNNING) R.string.btn_stop else R.string.btn_start)
    }

    /** Блок «Мониторы»: доступ к уведомлениям и счёт за цикл по каждому монитору. ARCHITECTURE 4.3. */
    private fun renderMonitors(engine: com.protocol.engine.Engine?) {
        val monitors = engine?.config?.monitors.orEmpty()
        b.monitorsBlock.visibility = if (monitors.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        if (monitors.isEmpty() || engine == null) return
        val enabled = MonitorListenerService.enabled(this)
        b.monitorAccessText.text = getString(if (enabled) R.string.mon_access_ok else R.string.mon_access_missing)
        b.monitorAccessButton.visibility = if (enabled) android.view.View.GONE else android.view.View.VISIBLE
        b.monitorAccessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        b.monitorRows.removeAllViews()
        for (m in monitors) {
            val tv = android.widget.TextView(this)
            tv.setPadding(0, 8, 0, 8)
            tv.text = getString(R.string.mon_line, m.id, m.chats.joinToString(", "), engine.monitorSummary(m.id).text(m))
            b.monitorRows.addView(tv)
        }
    }

    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    private fun setControlsOpen(open: Boolean) {
        prefs.edit().putBoolean(KEY_CONTROLS_OPEN, open).apply()
        b.controlsPanel.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
        b.controlsArrow.setImageResource(if (open) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float)
    }

    /** Разрешение на уведомления нужно и процессам, не только программе: спрашиваем при первом из действий. */
    private fun ensureNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return false
        }
        return true
    }

    private fun checkConfig() {
        val msg = when (val r = store.reload()) {
            is ConfigStore.LoadResult.Ok -> buildString {
                append("Конфиг в порядке.\nШагов: ${r.config.steps.size}, процессов: ${r.config.processes.size}.")
                if (r.warnings.isNotEmpty()) append("\n\nПредупреждения:\n").append(r.warnings.joinToString("\n"))
            }
            is ConfigStore.LoadResult.Error -> "Ошибка:\n${r.message}"
            ConfigStore.LoadResult.NotConfigured -> getString(R.string.status_not_configured)
        }
        AlertDialog.Builder(this).setTitle(R.string.btn_check_config).setMessage(msg).setPositiveButton(android.R.string.ok, null).show()
    }

    /**
     * Разрешения по SPEC 9. Возвращает true, если можно стартовать прямо сейчас.
     * Недостающие запрашиваются по одному; после выдачи человек жмёт «Запустить» снова.
     */
    private fun ensurePermissions(): Boolean {
        if (!ensureNotifications()) return false
        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(AlarmManager::class.java)
            if (!am.canScheduleExactAlarms()) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                return false
            }
        }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            return false
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                return false
            }
        }
        return true
    }
}
