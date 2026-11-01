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
import android.widget.Button
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
import com.protocol.service.EngineHolder
import com.protocol.service.ReminderService

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var store: ConfigStore
    /** Недописанные ответы по процессам: карточки перерисовываются при каждом такте движка. */
    private val drafts = HashMap<String, String>()

    private val pickConfig = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        store.configUri = uri
        checkConfig()
        ReminderService.send(this, ReminderService.intent(this, ReminderService.ACTION_INIT))
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        store = ConfigStore(this)

        b.pickConfigButton.setOnClickListener { pickConfig.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
        b.checkConfigButton.setOnClickListener { checkConfig() }
        b.logsButton.setOnClickListener { startActivity(Intent(this, LogsActivity::class.java)) }
        b.startStopButton.setOnClickListener {
            val running = EngineHolder.engine?.state?.mode == Mode.RUNNING
            if (running) ReminderService.send(this, ReminderService.intent(this, ReminderService.ACTION_MANUAL_STOP))
            else if (ensurePermissions()) ReminderService.send(this, ReminderService.intent(this, ReminderService.ACTION_START))
        }

        EngineHolder.version.observe(this) { render() }
        // Поднять движок, чтобы главный экран знал состояние.
        if (store.configUri != null) ReminderService.send(this, ReminderService.intent(this, ReminderService.ACTION_INIT))
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val engine = EngineHolder.engine
        val err = EngineHolder.lastError
        b.processButtons.removeAllViews()
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
                for (p in engine.config.processes) {
                    b.processButtons.addView(Button(this).apply {
                        text = p.title
                        isEnabled = st.procs.none { it.procId == p.id }
                        setOnClickListener {
                            ReminderService.send(this@MainActivity, ReminderService.intent(this@MainActivity, ReminderService.ACTION_START_PROC).putExtra(ReminderService.EXTRA_PROC_ID, p.id))
                        }
                    })
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
        b.startStopButton.isEnabled = store.configUri != null && err == null
        b.startStopButton.text = getString(if (engine?.state?.mode == Mode.RUNNING) R.string.btn_stop else R.string.btn_start)
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
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return false
        }
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
