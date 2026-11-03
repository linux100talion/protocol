package com.protocol.engine

import com.protocol.config.Config
import com.protocol.config.Monitor
import com.protocol.config.Node
import com.protocol.config.Process
import com.protocol.config.Step
import com.protocol.config.formatHHMM
import java.time.ZoneId

/** Ответ человека на текущий экран. */
sealed class Answer {
    object Yes : Answer()
    object No : Answer()
    object Ack : Answer()
    data class Text(val value: String) : Answer()
    data class Choice(val index: Int) : Answer()
    /** Кнопка узла wait. */
    object Resume : Answer()
}

/**
 * Сообщение из внешнего источника, как его отдаёт адаптер. ARCHITECTURE 4.3, 4.4.
 * Адаптер только переводит чужой формат в этот, решает движок.
 */
data class Signal(val source: String, val chat: String, val sender: String, val text: String, val at: Long) {
    /** Ключ дедупликации: источник перевыставляет уведомление с теми же сообщениями. */
    val key: String get() = "$source|${chat.trim()}|${sender.trim()}|$at|${text.hashCode()}"
}

/** Сводка монитора за цикл для экрана и лога. */
data class MonitorSummary(val monitorId: String, val total: Int, val byCategory: List<Pair<String, Int>>) {
    /** «12 сообщений, вопросов 3». */
    fun text(monitor: Monitor): String {
        if (total == 0) return "сообщений нет"
        val parts = byCategory.filter { it.second > 0 }.map { (id, n) -> "${monitor.categories.firstOrNull { c -> c.id == id }?.label ?: id}: $n" }
        return if (monitor.categories.size <= 1) "сообщений $total" else "сообщений $total, ${parts.joinToString(", ")}"
    }
}

/** Что показывать на экране. Движок не знает про Android. */
sealed class Screen {
    abstract val title: String
    abstract val text: String

    data class YesNo(override val title: String, override val text: String, val escalated: Boolean) : Screen()
    data class Ack(override val title: String, override val text: String, val escalated: Boolean) : Screen()
    data class Text(override val title: String, override val text: String, val escalated: Boolean) : Screen()
    data class Choice(override val title: String, override val text: String, val options: List<String>, val escalated: Boolean) : Screen()
    data class Wait(override val title: String, override val text: String, val button: String) : Screen()
    /** Запрос причины блокера. */
    data class Reason(override val title: String, override val text: String, val isStep: Boolean, val id: String) : Screen()
}

/** Что движок просит сделать снаружи. */
interface EngineListener {
    fun log(event: String, vararg fields: String)
    fun blocker(isStep: Boolean, id: String, cause: Cause, path: List<String>, reason: String)
    /** Момент сигнала: первый показ шага и каждый повтор. Сейчас без звука и вибрации. */
    fun alert()
    /** Состояние изменилось: перерисовать экран, уведомления, сохранить. */
    fun changed()
}

/**
 * Состояние и правила. SPEC.md, раздел 5. Чистый Kotlin, тестируется без Android.
 * Все методы вызываются из одного потока.
 */
class Engine(
    val config: Config,
    val state: EngineState,
    private val listener: EngineListener,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val t = config.timeouts

    companion object {
        /** Потолок сообщений одного монитора в состоянии: JSON не должен разрастаться. */
        const val MAX_MON_MSGS = 2000
    }

    // ---------- Запуск и остановка ----------

    /** SPEC 5.1. */
    fun start(now: Long) {
        state.mode = Mode.RUNNING
        state.cycleStart = Schedule.cycleStartFor(now, config.dayStartMin, zone)
        state.activeStep = null
        pruneMonitors()
        listener.log("START")
        listener.log("CYCLE_START", Schedule.cycleDate(state.cycleStart, zone).toString())
        var idx = 0
        while (idx < config.steps.size && dueAt(idx) <= now) {
            listener.log("SKIPPED_RESTART", config.steps[idx].id)
            idx++
        }
        state.nextStepIndex = idx
        listener.changed()
        tick(now)
    }

    /** Ручная остановка с главного экрана. Процессы не трогает. */
    fun manualStop(now: Long) {
        if (state.mode != Mode.RUNNING) return
        state.mode = Mode.IDLE
        state.activeStep = null
        listener.log("MANUAL_STOP")
        listener.changed()
        tick(now)
    }

    // ---------- Такт ----------

    /** Вызывается сервисом по таймеру и по будильнику. Идемпотентен. */
    fun tick(now: Long) {
        var changed = false
        if (state.mode == Mode.RUNNING) {
            changed = rollCycle(now) || changed
            changed = askDueStep(now) || changed
            changed = tickStep(now) || changed
        }
        changed = tickProcs(now) || changed
        if (changed) listener.changed()
    }

    private fun rollCycle(now: Long): Boolean {
        var changed = false
        while (state.activeStep == null) {
            val next = Schedule.nextCycleStart(state.cycleStart, config.dayStartMin, zone)
            if (now < next) break
            // Итог мониторов в лог уходящего цикла, до смены даты файла.
            for (m in config.monitors) listener.log("MON_CYCLE", m.id, monitorSummary(m.id).text(m))
            state.cycleStart = next
            state.nextStepIndex = 0
            pruneMonitors()
            listener.log("CYCLE_START", Schedule.cycleDate(state.cycleStart, zone).toString())
            changed = true
        }
        return changed
    }

    private fun askDueStep(now: Long): Boolean {
        if (state.activeStep != null) return false
        if (state.nextStepIndex >= config.steps.size) return false
        if (dueAt(state.nextStepIndex) > now) return false
        val step = config.steps[state.nextStepIndex]
        state.activeStep = StepRun(stepId = step.id, t0 = now)
        val mons = step.monitors.mapNotNull { config.monitor(it) }.map { "${it.id}=${monitorSummary(it.id).total}" }
        listener.log("STEP_ASKED", step.id, "plan=${formatHHMM(step.timeMin)}", *mons.toTypedArray())
        listener.alert()
        return true
    }

    private fun tickStep(now: Long): Boolean {
        val run = state.activeStep ?: return false
        if (run.state != RunState.ACTIVE) return false
        var changed = false
        val elapsed = now - run.t0
        if (elapsed >= t.closeMs) {
            run.state = RunState.REASON
            run.cause = Cause.TIMEOUT
            listener.log("TIMEOUT", run.stepId, "path=${run.path.joinToString(">")}")
            listener.alert()
            return true
        }
        if (!run.escalated && elapsed >= t.escalateMs) {
            run.escalated = true
            listener.log("ESCALATED", run.stepId)
            changed = true
        }
        if (now - run.lastAlert >= t.repeatMs) {
            run.lastAlert = now
            run.repeats++
            listener.log("REPEAT", run.stepId, run.repeats.toString())
            // Не шумим, пока человек реально жмёт кнопки (SPEC 5.3).
            if (now - run.lastInteraction >= t.repeatMs) listener.alert()
            changed = true
        }
        return changed
    }

    private fun tickProcs(now: Long): Boolean {
        var changed = false
        val stepBusy = state.activeStep != null
        for (p in state.procs) {
            if (stepBusy) {
                // Приоритет у графика: процессы на паузе (SPEC 5.8).
                if (p.pausedAt == null) { p.pausedAt = now; changed = true }
                continue
            }
            p.pausedAt?.let { paused ->
                val delta = now - paused
                p.shownAt += delta; p.lastAlert += delta; p.lastInteraction += delta
                p.pausedAt = null
                changed = true
            }
            val proc = config.process(p.procId)
            val node = proc?.nodes?.get(p.nodeId)
            if (p.state == RunState.WAITING && node is Node.Timer && proc != null) {
                val due = p.timerDueAt
                if (due != null && now >= due) { askTimer(p, proc, now); changed = true }
                continue
            }
            if (p.state != RunState.ACTIVE) continue
            val elapsed = now - p.shownAt
            if (node is Node.Timer && proc != null) {
                // Таймер: без ответа answer_min считается «нет», новый цикл ожидания.
                if (elapsed >= node.answerMs) {
                    p.path += "${p.nodeId}:timeout"
                    listener.log("PROC_TIMER_AUTO_NO", p.procId, p.nodeId)
                    armTimer(p, proc, node, p.nodeId, now)
                    changed = true
                    continue
                }
            } else if (elapsed >= t.closeMs) {
                p.state = RunState.REASON
                p.cause = Cause.TIMEOUT
                listener.log("PROC_TIMEOUT", p.procId, "node=${p.nodeId}", "path=${p.path.joinToString(">")}")
                listener.alert()
                changed = true
                continue
            }
            if (!p.escalated && elapsed >= t.escalateMs) {
                p.escalated = true
                listener.log("PROC_ESCALATED", p.procId, p.nodeId)
                changed = true
            }
            if (now - p.lastAlert >= t.repeatMs) {
                p.lastAlert = now
                p.repeats++
                listener.log("PROC_REPEAT", p.procId, p.nodeId, p.repeats.toString())
                if (now - p.lastInteraction >= t.repeatMs) listener.alert()
                changed = true
            }
        }
        return changed
    }

    /**
     * Когда движку нужен следующий такт. null: ждать нечего.
     * Сервис ставит на это время будильник или таймер.
     */
    fun nextWakeAt(now: Long): Long? {
        val times = ArrayList<Long>()
        if (state.mode == Mode.RUNNING) {
            val run = state.activeStep
            if (run == null) {
                if (state.nextStepIndex < config.steps.size) times += dueAt(state.nextStepIndex)
                else times += Schedule.nextCycleStart(state.cycleStart, config.dayStartMin, zone)
            } else if (run.state == RunState.ACTIVE) {
                times += run.lastAlert + t.repeatMs
                // Такт ровно через repeat_sec после нажатия: сервис тогда покажет уведомление.
                times += run.lastInteraction + t.repeatMs
                if (!run.escalated) times += run.t0 + t.escalateMs
                times += run.t0 + t.closeMs
            }
        }
        if (state.activeStep == null) for (p in state.procs) {
            val node = config.process(p.procId)?.nodes?.get(p.nodeId)
            if (p.state == RunState.WAITING) { p.timerDueAt?.let { times += it }; continue }
            if (p.state != RunState.ACTIVE) continue
            times += p.lastAlert + t.repeatMs
            if (!p.escalated) times += p.shownAt + t.escalateMs
            times += p.shownAt + (if (node is Node.Timer) node.answerMs else t.closeMs)
        }
        return times.filter { it > now }.minOrNull() ?: times.minOrNull()?.let { now }
    }

    // ---------- Ответы по шагу ----------

    fun answerStep(answer: Answer, now: Long) {
        val run = state.activeStep ?: return
        val step = config.step(run.stepId) ?: return
        if (run.state != RunState.ACTIVE) return
        run.lastInteraction = now
        val nodeId = run.nodeId
        if (nodeId == null) {
            when (answer) {
                Answer.Yes -> {
                    run.path += "check:yes"
                    listener.log("ANSWER", step.id, "check", "yes")
                    if (step.onYes != null) gotoStepNode(run, step, step.onYes, now) else closeStep(run, step, now)
                }
                Answer.No -> {
                    run.path += "check:no"
                    listener.log("ANSWER", step.id, "check", "no")
                    gotoStepNode(run, step, step.onNo, now)
                }
                else -> return
            }
        } else {
            val node = step.nodes[nodeId] ?: return
            val target = applyAnswer(node, nodeId, answer, run.path) { a -> listener.log("ANSWER", step.id, nodeId, a) }
            when {
                node is Node.Fail && answer == Answer.Ack -> {
                    run.state = RunState.REASON
                    run.cause = Cause.FAIL
                    listener.log("FAIL", step.id, "path=${run.path.joinToString(">")}")
                }
                target != null -> gotoStepNode(run, step, target, now)
                else -> return
            }
        }
        listener.changed()
        tick(now)
    }

    private fun gotoStepNode(run: StepRun, step: Step, target: String, now: Long) {
        when (step.nodes[target]) {
            Node.Done -> closeStep(run, step, now)
            null -> return
            else -> run.nodeId = target
        }
    }

    private fun closeStep(run: StepRun, step: Step, now: Long) {
        val dur = (now - run.t0) / 1000
        listener.log("STEP_DONE", step.id, "%dm%02ds".format(dur / 60, dur % 60), "repeats=${run.repeats}")
        state.activeStep = null
        state.nextStepIndex++
    }

    // ---------- Процессы ----------

    fun startProcess(procId: String, now: Long) {
        val proc = config.process(procId) ?: return
        val run = ProcRun(procId = proc.id, startedAt = now, nodeId = proc.start, shownAt = now)
        if (state.activeStep != null) run.pausedAt = now
        state.procs += run
        listener.log("PROC_START", proc.id)
        enterProcNode(run, proc, proc.start, now)
        listener.changed()
        tick(now)
    }

    fun answerProc(procId: String, answer: Answer, now: Long) {
        val run = state.procs.firstOrNull { it.procId == procId } ?: return
        val proc = config.process(procId) ?: return
        if (state.activeStep != null) return // шаг в приоритете, процесс на паузе
        val node = proc.nodes[run.nodeId] ?: return
        run.lastInteraction = now
        when {
            run.state == RunState.WAITING && node is Node.Timer -> {
                // «Спросить сейчас»: не ждать конца периода.
                if (answer != Answer.Resume) return
                askTimer(run, proc, now)
            }
            run.state == RunState.WAITING -> {
                if (answer != Answer.Resume || node !is Node.Wait) return
                run.path += "${run.nodeId}:resume"
                listener.log("PROC_RESUME", proc.id, run.nodeId)
                run.state = RunState.ACTIVE
                enterProcNode(run, proc, node.next, now)
            }
            run.state != RunState.ACTIVE -> return
            node is Node.Timer && answer == Answer.No -> {
                run.path += "${run.nodeId}:no"
                listener.log("PROC_ANSWER", proc.id, run.nodeId, "no")
                armTimer(run, proc, node, run.nodeId, now)
            }
            node is Node.Fail && answer == Answer.Ack -> {
                run.state = RunState.REASON
                run.cause = Cause.FAIL
                listener.log("PROC_FAIL", proc.id, "path=${run.path.joinToString(">")}")
            }
            else -> {
                val target = applyAnswer(node, run.nodeId, answer, run.path) { a -> listener.log("PROC_ANSWER", proc.id, run.nodeId, a) }
                    ?: return
                enterProcNode(run, proc, target, now)
            }
        }
        listener.changed()
        tick(now)
    }

    private fun enterProcNode(run: ProcRun, proc: Process, target: String, now: Long) {
        when (val node = proc.nodes[target]) {
            null -> return
            Node.Done -> {
                val dur = (now - run.startedAt) / 1000
                listener.log("PROC_DONE", proc.id, "%dm%02ds".format(dur / 60, dur % 60))
                state.procs.remove(run)
            }
            is Node.Wait -> {
                run.nodeId = target
                run.state = RunState.WAITING
                run.shownAt = now; run.lastAlert = now; run.escalated = false
                listener.log("PROC_WAIT", proc.id, target)
            }
            is Node.Timer -> armTimer(run, proc, node, target, now)
            else -> {
                run.nodeId = target
                run.shownAt = now; run.lastAlert = now; run.escalated = false
            }
        }
    }

    /** Узел timer: уснуть на period, вопрос потом. */
    private fun armTimer(run: ProcRun, proc: Process, node: Node.Timer, target: String, now: Long) {
        run.nodeId = target
        run.state = RunState.WAITING
        run.timerDueAt = now + node.periodMs
        run.shownAt = now; run.lastAlert = now; run.escalated = false
        listener.log("PROC_TIMER_SET", proc.id, target, "due=${hhmm(now + node.periodMs)}")
    }

    /** Узел timer: период вышел или «Спросить сейчас», задать вопрос. */
    private fun askTimer(run: ProcRun, proc: Process, now: Long) {
        run.state = RunState.ACTIVE
        run.timerDueAt = null
        run.shownAt = now; run.lastAlert = now; run.lastInteraction = now; run.escalated = false
        listener.log("PROC_TIMER_DUE", proc.id, run.nodeId)
        listener.alert()
    }

    private fun hhmm(ms: Long): String {
        val lt = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
        return "%02d:%02d".format(lt.hour, lt.minute)
    }

    // ---------- Мониторы ----------

    /**
     * Принять сообщение из внешнего источника. ARCHITECTURE 4.3.
     * Находит монитор по источнику и беседе, классифицирует по тексту и
     * отправителю, отсекает дубль, засчитывает. true: засчитано хотя бы одним
     * монитором. Ничего наружу не делает: это счёт, не действие.
     */
    fun receive(signal: Signal, now: Long): Boolean {
        var counted = false
        for (m in config.monitors) {
            if (m.source != signal.source || !m.allowsChat(signal.chat)) continue
            val category = m.classify(signal.text, signal.sender) ?: continue
            val list = state.monitors.getOrPut(m.id) { ArrayList() }
            if (list.any { it.key == signal.key }) continue
            list += MonMsg(signal.chat.trim(), signal.sender.trim(), category, signal.at, signal.key)
            if (list.size > MAX_MON_MSGS) list.subList(0, list.size - MAX_MON_MSGS).clear()
            listener.log("MON_MSG", m.id, signal.chat.trim(), category)
            counted = true
        }
        if (counted) listener.changed()
        return counted
    }

    fun monitorSummary(monitorId: String): MonitorSummary {
        val m = config.monitor(monitorId)
        val msgs = state.monitors[monitorId] ?: emptyList<MonMsg>()
        val cats = m?.categories?.map { c -> c.id to msgs.count { it.category == c.id } } ?: emptyList()
        return MonitorSummary(monitorId, msgs.size, cats)
    }

    /** Текст сводки для шага: по строке на монитор, или null, если мониторов у шага нет. */
    fun stepMonitorText(step: Step): String? {
        val mons = step.monitors.mapNotNull { config.monitor(it) }
        if (mons.isEmpty()) return null
        return mons.joinToString("\n") { m -> "${m.chats.joinToString(", ")}: ${monitorSummary(m.id).text(m)}" }
    }

    /** Оставить только сообщения текущего цикла и только известных мониторов. */
    private fun pruneMonitors() {
        val known = config.monitors.map { it.id }.toSet()
        state.monitors.keys.retainAll(known)
        for (list in state.monitors.values) list.removeAll { it.at < state.cycleStart }
    }

    // ---------- Причина блокера ----------

    /** SPEC 5.5 и 5.8. Для шага останавливает программу, для процесса закрывает процесс. */
    fun submitReason(isStep: Boolean, id: String, reason: String, now: Long) {
        if (reason.isBlank()) return
        if (isStep) {
            val run = state.activeStep ?: return
            if (run.state != RunState.REASON) return
            listener.log("BLOCKER_REASON", run.stepId, reason)
            listener.blocker(true, run.stepId, run.cause ?: Cause.TIMEOUT, run.path, reason)
            state.activeStep = null
            state.mode = Mode.STOPPED
            listener.log("STOPPED")
        } else {
            val run = state.procs.firstOrNull { it.procId == id } ?: return
            if (run.state != RunState.REASON) return
            listener.log("PROC_FAILED", run.procId, reason)
            listener.blocker(false, run.procId, run.cause ?: Cause.TIMEOUT, run.path, reason)
            state.procs.remove(run)
        }
        listener.changed()
        tick(now)
    }

    // ---------- Экраны ----------

    /** Экран шага, если шаг активен. Приоритетнее процессов. */
    fun stepScreen(): Screen? {
        val run = state.activeStep ?: return null
        val step = config.step(run.stepId) ?: return null
        if (run.state == RunState.REASON) return Screen.Reason("Шаг ${step.id}", "Шаг ${step.id} не решён. Почему?", true, step.id)
        val title = "Шаг ${step.id}"
        val nodeId = run.nodeId
        if (nodeId == null) {
            val check = if (run.escalated) step.forgot else step.check
            val mon = stepMonitorText(step)
            return Screen.YesNo(title, if (mon == null) check else "$check\n\n$mon", run.escalated)
        }
        return nodeScreen(title, step.nodes[nodeId] ?: return null, run.escalated, escalatePrefix = step.forgot)
    }

    fun procScreen(procId: String): Screen? {
        val run = state.procs.firstOrNull { it.procId == procId } ?: return null
        val proc = config.process(procId) ?: return null
        val title = proc.title
        if (run.state == RunState.REASON) return Screen.Reason(title, "Процесс ${proc.id} не завершён. Почему?", false, proc.id)
        val node = proc.nodes[run.nodeId] ?: return null
        if (node is Node.Timer && run.state == RunState.WAITING) {
            val due = run.timerDueAt?.let { hhmm(it) } ?: "?"
            return Screen.Wait(title, "Вопрос «${node.text}» в $due", "Спросить сейчас")
        }
        return nodeScreen(title, node, run.escalated, escalatePrefix = null)
    }

    private fun nodeScreen(title: String, node: Node, escalated: Boolean, escalatePrefix: String?): Screen {
        val text = if (escalated) (escalatePrefix?.let { "$it\n\n${node.text}" } ?: "Ты не ответил: ${node.text}") else node.text
        return when (node) {
            is Node.YesNo -> Screen.YesNo(title, text, escalated)
            is Node.Instruction -> Screen.Ack(title, text, escalated)
            is Node.Fail -> Screen.Ack(title, node.text, false)
            is Node.Text -> Screen.Text(title, text, escalated)
            is Node.Choice -> Screen.Choice(title, text, node.options.map { it.label }, escalated)
            is Node.Wait -> Screen.Wait(title, node.text, node.button)
            is Node.Timer -> Screen.YesNo(title, text, escalated)
            Node.Done -> Screen.Ack(title, "", false)
        }
    }

    /** Описание ожидания для главного экрана и уведомления службы. */
    fun nextStepInfo(): Pair<String, String>? {
        if (state.mode != Mode.RUNNING || state.activeStep != null) return null
        if (state.nextStepIndex >= config.steps.size) return null
        val s = config.steps[state.nextStepIndex]
        return s.id to formatHHMM(s.timeMin)
    }

    // ---------- Вспомогательное ----------

    private fun dueAt(index: Int): Long =
        Schedule.dueAt(state.cycleStart, config.steps[index].offsetMin(config.dayStartMin))

    /**
     * Применяет ответ к узлу: пишет путь и лог, возвращает id следующего узла.
     * null: ответ не подходит узлу или узел конечный.
     */
    private fun applyAnswer(node: Node, nodeId: String, answer: Answer, path: MutableList<String>, log: (String) -> Unit): String? {
        return when {
            node is Node.YesNo && answer == Answer.Yes -> { path += "$nodeId:yes"; log("yes"); node.yes }
            node is Node.YesNo && answer == Answer.No -> { path += "$nodeId:no"; log("no"); node.no }
            node is Node.Timer && answer == Answer.Yes -> { path += "$nodeId:yes"; log("yes"); node.next }
            node is Node.Instruction && answer == Answer.Ack -> { path += "$nodeId:ok"; log("ok"); node.next }
            node is Node.Text && answer is Answer.Text && answer.value.isNotBlank() -> {
                path += "$nodeId:text"; log(answer.value.replace('\n', ' ')); node.next
            }
            node is Node.Choice && answer is Answer.Choice && answer.index in node.options.indices -> {
                val opt = node.options[answer.index]
                path += "$nodeId:${answer.index}"; log(opt.label); opt.next
            }
            else -> null
        }
    }
}
