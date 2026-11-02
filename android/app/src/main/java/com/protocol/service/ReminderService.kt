package com.protocol.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.protocol.AppVisibility
import com.protocol.R
import com.protocol.config.ConfigStore
import com.protocol.config.Node
import com.protocol.engine.Answer
import com.protocol.engine.Cause
import com.protocol.engine.Engine
import com.protocol.engine.EngineListener
import com.protocol.engine.EngineState
import com.protocol.engine.Mode
import com.protocol.engine.RunState
import com.protocol.engine.Schedule
import com.protocol.engine.Screen
import com.protocol.log.EventLog
import com.protocol.notif.Notifications
import com.protocol.ui.StepActivity

/**
 * Постоянный foreground-сервис. Владеет движком, таймерами, уведомлениями. SPEC 8.
 * Все команды приходят интентами и выполняются в главном потоке.
 */
class ReminderService : Service() {

    companion object {
        const val ACTION_INIT = "init"            // поднять движок без запуска программы
        const val ACTION_RELOAD = "reload"        // конфиг изменён с главного экрана
        const val ACTION_START = "start"          // «Запустить»
        const val ACTION_MANUAL_STOP = "manual_stop"
        const val ACTION_TICK = "tick"
        const val ACTION_ANSWER = "answer"
        const val ACTION_START_PROC = "start_proc"
        const val ACTION_REASON = "reason"
        const val ACTION_TIME_CHANGED = "time_changed"

        const val EXTRA_PROC_ID = "proc_id"       // null => шаг
        const val EXTRA_ANSWER = "answer"         // yes|no|ack|text|choice|resume
        const val EXTRA_VALUE = "value"           // текст или индекс
        const val EXTRA_IS_STEP = "is_step"
        const val EXTRA_ID = "id"

        fun intent(ctx: Context, action: String): Intent = Intent(ctx, ReminderService::class.java).setAction(action)

        fun send(ctx: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
        }

        fun answer(ctx: Context, procId: String?, kind: String, value: String? = null) {
            send(ctx, intent(ctx, ACTION_ANSWER).putExtra(EXTRA_PROC_ID, procId).putExtra(EXTRA_ANSWER, kind).putExtra(EXTRA_VALUE, value))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: ConfigStore
    private lateinit var log: EventLog
    private var engine: Engine? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Такт по таймеру: после него уведомления перерисовываются сразу, не дожидаясь будильника.
    private val tickRunnable = Runnable { engine?.tick(now()); render(); planNextWake() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = ConfigStore(this)
        log = EventLog(this)
        goForeground(getString(R.string.notif_service_text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (engine == null && !loadEngine(fromFile = intent?.action == ACTION_START)) {
            // Конфиг негоден: сервис не нужен.
            stopSelf()
            return START_NOT_STICKY
        }
        val e = engine!!
        val now = now()
        val i = intent ?: Intent()
        when (i.action) {
            ACTION_START -> {
                // На «Запустить» конфиг перечитывается из файла (SPEC 5.1).
                if (!loadEngine(fromFile = true)) { stopSelf(); return START_NOT_STICKY }
                engine!!.start(now)
            }
            ACTION_MANUAL_STOP -> e.manualStop(now)
            ACTION_TICK, null -> e.tick(now)
            ACTION_TIME_CHANGED -> { e.listenerLog("TIME_CHANGED"); e.tick(now) }
            ACTION_ANSWER -> {
                val procId = i.getStringExtra(EXTRA_PROC_ID)
                val value = i.getStringExtra(EXTRA_VALUE)
                val answer = when (i.getStringExtra(EXTRA_ANSWER)) {
                    "yes" -> Answer.Yes
                    "no" -> Answer.No
                    "ack" -> Answer.Ack
                    "text" -> Answer.Text(value ?: "")
                    "choice" -> Answer.Choice(value?.toIntOrNull() ?: -1)
                    "resume" -> Answer.Resume
                    else -> null
                }
                if (answer != null) {
                    if (procId == null) e.answerStep(answer, now) else e.answerProc(procId, answer, now)
                }
            }
            ACTION_START_PROC -> i.getStringExtra(EXTRA_PROC_ID)?.let { e.startProcess(it, now) }
            ACTION_RELOAD -> if (e.state.mode != Mode.RUNNING) {
                // Не в работе: пересобрать движок на новом конфиге, состояние (открытые
                // процессы) сохраняется. В работе конфиг перечитается на «Запустить» (SPEC 5.1).
                engine = null
                if (!loadEngine(fromFile = false)) { stopSelf(); return START_NOT_STICKY }
            }
            ACTION_REASON -> e.submitReason(
                i.getBooleanExtra(EXTRA_IS_STEP, true),
                i.getStringExtra(EXTRA_ID) ?: "",
                i.getStringExtra(EXTRA_VALUE) ?: "",
                now,
            )
        }
        render()
        planNextWake()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        releaseWake()
        super.onDestroy()
    }

    // ---------- Движок ----------

    private fun Engine.listenerLog(event: String) = log.write(cycleDate(), event)

    private fun cycleDate() = Schedule.cycleDate(engine?.state?.cycleStart?.takeIf { it > 0 } ?: System.currentTimeMillis())

    private fun loadEngine(fromFile: Boolean): Boolean {
        val result = if (fromFile) store.reload() else store.loadCached()
        val config = when (result) {
            is ConfigStore.LoadResult.Ok -> result.config
            is ConfigStore.LoadResult.Error -> {
                EngineHolder.lastError = result.message
                log.write(cycleDate(), "CONFIG_ERROR", result.message.replace('\n', ' '))
                EngineHolder.engine = null; EngineHolder.bump()
                return false
            }
            ConfigStore.LoadResult.NotConfigured -> {
                EngineHolder.lastError = null
                EngineHolder.engine = null; EngineHolder.bump()
                return false
            }
        }
        EngineHolder.lastError = null
        val state = EngineState.fromJson(store.engineStateJson)
        val listener = object : EngineListener {
            override fun log(event: String, vararg fields: String) = this@ReminderService.log.write(cycleDate(), event, *fields)
            override fun blocker(isStep: Boolean, id: String, cause: Cause, path: List<String>, reason: String) =
                this@ReminderService.log.blocker(cycleDate(), isStep, id, cause, path, reason)
            // Приложение полностью беззвучное: ни звука, ни вибрации. Сигнал только
            // уведомлением и полноэкранным вопросом, экран включается.
            override fun alert() {}
            override fun changed() {
                store.engineStateJson = engine?.state?.toJson()
                EngineHolder.bump()
            }
        }
        engine = Engine(config, state, listener)
        EngineHolder.engine = engine
        if (fromFile) log.write(cycleDate(), "CONFIG_OK", "steps=${config.steps.size}", "processes=${config.processes.size}")
        EngineHolder.bump()
        return true
    }

    // ---------- Таймеры ----------

    private fun now() = System.currentTimeMillis()

    private fun planNextWake() {
        handler.removeCallbacks(tickRunnable)
        val e = engine ?: return
        val at = e.nextWakeAt(now())
        if (at == null) { AlarmScheduler.cancel(this); releaseWake(); return }
        val delay = (at - now()).coerceAtLeast(0L)
        // Страховка будильником всегда; близкие такты ещё и таймером под wakelock.
        AlarmScheduler.schedule(this, at)
        if (delay <= 2 * 60_000L) {
            acquireWake()
            handler.postDelayed(tickRunnable, delay)
        } else {
            releaseWake()
        }
    }

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "protocol:step").also { it.acquire(15 * 60_000L) }
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ---------- Уведомления ----------

    private fun goForeground(text: String) {
        val n = Notifications.service(this, text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifications.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifications.ID_SERVICE, n)
        }
    }

    private fun render() {
        val e = engine ?: return
        val nm = getSystemService(NotificationManager::class.java)
        val st = e.state

        // Текст служебного уведомления.
        val serviceText = when {
            st.mode == Mode.RUNNING && st.activeStep != null -> getString(R.string.label_current_step, st.activeStep!!.stepId)
            st.mode == Mode.RUNNING -> e.nextStepInfo()?.let { getString(R.string.label_next_step, it.first, it.second) } ?: getString(R.string.notif_service_text)
            st.mode == Mode.STOPPED -> getString(R.string.notif_stopped_text)
            else -> getString(R.string.status_idle)
        }
        nm.notify(Notifications.ID_SERVICE, Notifications.service(this, serviceText))

        // Шаг по графику: полноэкранное уведомление и экран поверх блокировки.
        // Процессы отдельного экрана не получают, они идут на главном экране;
        // активному процессу достаётся тихое уведомление, ведущее на главный экран.
        val stepScreen = e.stepScreen()
        val activeProc = if (stepScreen == null) st.procs.firstOrNull { it.state != RunState.WAITING } else null
        val procScreen = activeProc?.let { e.procScreen(it.procId) }
        val screen: Screen? = stepScreen ?: procScreen
        // Уведомление нужно, только когда оно действительно уведомляет.
        // Пока окно приложения на экране, сразу после нажатия кнопки его нет:
        // следующий узел и так виден. Оно появляется, когда человек молчит
        // дольше repeat_sec, и дальше повторяется, пока узел не закрыт.
        // Если окна нет (ответ дан кнопкой в шторке), уведомление остаётся
        // и показывает следующий узел: по нему можно войти в экран шага.
        val now = now()
        val repeatMs = e.config.timeouts.repeatMs
        val stepRun = st.activeStep
        // Вопрос таймера приходит сам, без нажатия: уведомляем всегда, даже при открытом окне.
        val timerQuestion = activeProc != null &&
            e.config.process(activeProc.procId)?.nodes?.get(activeProc.nodeId) is Node.Timer
        val quiet = AppVisibility.visible && !timerQuestion && when {
            stepScreen != null -> stepRun != null && stepRun.state == RunState.ACTIVE && now - stepRun.lastInteraction < repeatMs
            activeProc != null -> now - activeProc.lastInteraction < repeatMs
            else -> false
        }
        if (screen != null && !quiet) {
            nm.notify(Notifications.ID_ALERT, Notifications.alert(this, screen, activeProc?.procId))
        } else {
            nm.cancel(Notifications.ID_ALERT)
        }
        if (stepScreen != null) {
            // Если система запретит запуск из фона, сработает fullScreenIntent уведомления.
            try {
                startActivity(Intent(this, StepActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            } catch (_: Exception) { }
        }

        // Ожидающие процессы: тихие уведомления с кнопкой.
        for (p in st.procs) {
            val s = e.procScreen(p.procId)
            if (p.state == RunState.WAITING && s is Screen.Wait) nm.notify(Notifications.waitId(p.procId), Notifications.wait(this, p.procId, s))
            else nm.cancel(Notifications.waitId(p.procId))
        }

        // Остановлена и процессов нет: сервис больше не нужен.
        if (st.mode != Mode.RUNNING && st.procs.isEmpty()) {
            if (st.mode == Mode.STOPPED) nm.notify(Notifications.ID_STOPPED, Notifications.stopped(this))
            AlarmScheduler.cancel(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
}
