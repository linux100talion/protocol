package com.protocol.engine

import com.protocol.config.ConfigParser
import com.protocol.config.ConfigValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class EngineTest {

    private val json = """
    {
      "version": 1,
      "day_start": "07:00",
      "steps": [
        { "id": "a", "time": "09:00", "check": "A?", "forgot": "Забыл A", "on_no": "n1",
          "nodes": { "n1": { "type": "yesno", "text": "Ещё раз A?", "yes": "done", "no": "fail" },
                     "done": { "type": "done" }, "fail": { "type": "fail", "text": "A провален" } } },
        { "id": "b", "time": "09:30", "check": "B?", "forgot": "Забыл B", "on_no": "n1",
          "nodes": { "n1": { "type": "instruction", "text": "Сделай B", "next": "done" }, "done": { "type": "done" } } }
      ],
      "processes": [
        { "id": "p", "title": "P", "start": "q1",
          "nodes": { "q1": { "type": "yesno", "text": "Q1?", "yes": "w", "no": "q1" },
                     "w": { "type": "wait", "text": "Ждём", "button": "Дальше", "next": "q2" },
                     "q2": { "type": "yesno", "text": "Q2?", "yes": "done", "no": "q2" },
                     "done": { "type": "done" } } },
        { "id": "tm", "title": "Свет", "start": "t",
          "nodes": { "t": { "type": "timer", "text": "Свет дали?", "period_min": 30, "answer_min": 5, "next": "done" },
                     "done": { "type": "done" } } }
      ]
    }
    """.trimIndent()

    private class Rec : EngineListener {
        val events = ArrayList<String>()
        val blockers = ArrayList<String>()
        var alerts = 0
        override fun log(event: String, vararg fields: String) { events += (listOf(event) + fields).joinToString("|") }
        override fun blocker(isStep: Boolean, id: String, cause: Cause, path: List<String>, reason: String) {
            blockers += "${if (isStep) "STEP" else "PROC"}|$id|$cause|${path.joinToString(">")}|$reason"
        }
        override fun alert() { alerts++ }
        override fun changed() {}
        fun has(prefix: String) = events.any { it.startsWith(prefix) }
    }

    private val zone = ZoneId.of("Europe/Kyiv")
    private fun at(h: Int, m: Int, day: Int = 30): Long =
        ZonedDateTime.of(LocalDate.of(2026, 10, day), LocalTime.of(h, m), zone).toInstant().toEpochMilli()

    private fun make(): Triple<Engine, Rec, EngineState> {
        val cfg = ConfigParser.parse(json)
        val v = ConfigValidator.validate(cfg)
        assertTrue(v.errors.joinToString(), v.ok)
        val rec = Rec()
        val st = EngineState()
        return Triple(Engine(cfg, st, rec, zone), rec, st)
    }

    @Test
    fun `parses and validates example`() {
        val (e, _, _) = make()
        assertEquals(2, e.config.steps.size)
        assertEquals(2, e.config.processes.size)
    }

    @Test
    fun `start skips passed steps and waits for next`() {
        val (e, rec, st) = make()
        e.start(at(9, 10))
        assertTrue(rec.has("SKIPPED_RESTART|a"))
        assertNull(st.activeStep)
        assertEquals(1, st.nextStepIndex)
        assertEquals("b", e.nextStepInfo()?.first)
    }

    @Test
    fun `step is asked on time and closes on yes`() {
        val (e, rec, st) = make()
        e.start(at(8, 0))
        e.tick(at(9, 0))
        assertEquals("a", st.activeStep?.stepId)
        assertTrue(e.stepScreen() is Screen.YesNo)
        e.answerStep(Answer.Yes, at(9, 1))
        assertNull(st.activeStep)
        assertTrue(rec.has("STEP_DONE|a"))
    }

    @Test
    fun `repeat escalate and timeout`() {
        val (e, rec, st) = make()
        e.start(at(8, 0))
        e.tick(at(9, 0))
        val alertsAfterAsk = rec.alerts
        e.tick(at(9, 1)); assertTrue(rec.alerts > alertsAfterAsk); assertTrue(rec.has("REPEAT|a|1"))
        e.tick(at(9, 5)); assertTrue(rec.has("ESCALATED|a"))
        assertEquals("Забыл A", (e.stepScreen() as Screen.YesNo).text)
        e.tick(at(9, 10))
        assertEquals(RunState.REASON, st.activeStep?.state)
        assertTrue(e.stepScreen() is Screen.Reason)
        e.submitReason(true, "a", "не смог", at(9, 11))
        assertEquals(Mode.STOPPED, st.mode)
        assertEquals(1, rec.blockers.size)
        assertTrue(rec.blockers[0].startsWith("STEP|a|TIMEOUT|"))
    }

    @Test
    fun `fail node stops after ack`() {
        val (e, rec, st) = make()
        e.start(at(8, 0))
        e.tick(at(9, 0))
        e.answerStep(Answer.No, at(9, 1))
        e.answerStep(Answer.No, at(9, 2))
        assertTrue(e.stepScreen() is Screen.Ack)
        e.answerStep(Answer.Ack, at(9, 2))
        assertEquals(RunState.REASON, st.activeStep?.state)
        e.submitReason(true, "a", "нигде нет", at(9, 3))
        assertTrue(rec.blockers[0].startsWith("STEP|a|FAIL|check:no>n1:no"))
        assertEquals(Mode.STOPPED, st.mode)
    }

    @Test
    fun `overdue step is asked right after previous closes`() {
        val (e, _, st) = make()
        e.start(at(8, 0))
        e.tick(at(9, 35)) // a и b просрочены, задан только a
        assertEquals("a", st.activeStep?.stepId)
        e.answerStep(Answer.Yes, at(9, 36))
        assertEquals("b", st.activeStep?.stepId)
    }

    @Test
    fun `cycle rolls over at day_start`() {
        val (e, rec, st) = make()
        e.start(at(10, 0))
        assertEquals(2, st.nextStepIndex)
        e.tick(at(7, 0, day = 31))
        assertEquals(0, st.nextStepIndex)
        assertEquals(2, rec.events.count { it.startsWith("CYCLE_START") })
    }

    @Test
    fun `process pauses while step active and resumes from wait`() {
        val (e, rec, st) = make()
        e.start(at(8, 0))
        e.startProcess("p", at(8, 50))
        e.answerProc("p", Answer.Yes, at(8, 51))
        assertEquals(RunState.WAITING, st.procs[0].state)
        e.tick(at(9, 0)) // шаг a задан: процесс на паузе
        assertNotNull(st.procs[0].pausedAt)
        e.answerProc("p", Answer.Resume, at(9, 1)) // игнорируется, шаг в приоритете
        assertEquals(RunState.WAITING, st.procs[0].state)
        e.answerStep(Answer.Yes, at(9, 2))
        e.tick(at(9, 2))
        assertNull(st.procs[0].pausedAt)
        e.answerProc("p", Answer.Resume, at(9, 3))
        assertEquals("q2", st.procs[0].nodeId)
        e.answerProc("p", Answer.Yes, at(9, 4))
        assertTrue(st.procs.isEmpty())
        assertTrue(rec.has("PROC_DONE|p"))
    }

    @Test
    fun `process timeout does not stop program`() {
        val (e, rec, st) = make()
        e.start(at(8, 0))
        e.startProcess("p", at(8, 0))
        e.tick(at(8, 10))
        assertEquals(RunState.REASON, st.procs[0].state)
        e.submitReason(false, "p", "занят", at(8, 11))
        assertTrue(st.procs.isEmpty())
        assertEquals(Mode.RUNNING, st.mode)
        assertTrue(rec.blockers[0].startsWith("PROC|p|TIMEOUT|"))
    }

    @Test
    fun `timer process waits, asks, re-arms on no and on silence, closes on yes`() {
        val (e, rec, st) = make()
        e.startProcess("tm", at(10, 0))
        val run = st.procs[0]
        assertEquals(RunState.WAITING, run.state)
        assertEquals(at(10, 30), run.timerDueAt)
        assertTrue(rec.has("PROC_TIMER_SET|tm|t|due=10:30"))
        assertEquals(at(10, 30), e.nextWakeAt(at(10, 1)))
        assertTrue(e.procScreen("tm") is Screen.Wait)

        e.tick(at(10, 29))
        assertEquals(RunState.WAITING, run.state)
        val alertsBefore = rec.alerts
        e.tick(at(10, 30))
        assertEquals(RunState.ACTIVE, run.state)
        assertNull(run.timerDueAt)
        assertTrue(rec.alerts > alertsBefore)
        assertTrue(e.procScreen("tm") is Screen.YesNo)
        assertTrue(e.nextWakeAt(at(10, 30))!! <= at(10, 35))

        // «Нет»: новый цикл ожидания.
        e.answerProc("tm", Answer.No, at(10, 31))
        assertEquals(RunState.WAITING, run.state)
        assertEquals(at(11, 1), run.timerDueAt)
        assertTrue(run.path.contains("t:no"))

        // Молчание дольше answer_min: автоматически «нет», процесс не падает в причину.
        e.tick(at(11, 1))
        assertEquals(RunState.ACTIVE, run.state)
        e.tick(at(11, 6))
        assertEquals(RunState.WAITING, run.state)
        assertEquals(at(11, 36), run.timerDueAt)
        assertTrue(rec.has("PROC_TIMER_AUTO_NO|tm|t"))
        assertTrue(rec.blockers.isEmpty())

        // «Спросить сейчас», потом «Да».
        e.answerProc("tm", Answer.Resume, at(11, 10))
        assertEquals(RunState.ACTIVE, run.state)
        e.answerProc("tm", Answer.Yes, at(11, 11))
        assertTrue(st.procs.isEmpty())
        assertTrue(rec.has("PROC_DONE|tm"))
    }

    @Test
    fun `timer due survives json round trip`() {
        val (e, _, st) = make()
        e.startProcess("tm", at(10, 0))
        val restored = EngineState.fromJson(st.toJson())
        assertEquals(at(10, 30), restored.procs[0].timerDueAt)
        assertEquals(RunState.WAITING, restored.procs[0].state)
    }

    @Test
    fun `state survives json round trip`() {
        val (e, _, st) = make()
        e.start(at(8, 0))
        e.startProcess("p", at(8, 0))
        e.tick(at(9, 0))
        e.answerStep(Answer.No, at(9, 1))
        val copy = EngineState.fromJson(st.toJson())
        assertEquals(st.mode, copy.mode)
        assertEquals(st.activeStep?.nodeId, copy.activeStep?.nodeId)
        assertEquals(st.activeStep?.path, copy.activeStep?.path)
        assertEquals(st.procs.size, copy.procs.size)
        assertEquals(st.procs[0].pausedAt, copy.procs[0].pausedAt)
    }
}
