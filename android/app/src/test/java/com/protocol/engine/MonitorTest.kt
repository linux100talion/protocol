package com.protocol.engine

import com.protocol.config.ConfigParser
import com.protocol.config.ConfigValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Монитор: конфиг, счёт, дедупликация, сводка в шаге, смена цикла. ARCHITECTURE 4.3. */
class MonitorTest {

    private val json = """
    {
      "version": 1,
      "day_start": "07:00",
      "monitors": [
        { "id": "family", "source": "whatsapp", "chats": ["Семья", "Дача"],
          "categories": [
            { "id": "question", "label": "Вопросы", "contains": ["?"] },
            { "id": "other", "label": "Прочее" }
          ] },
        { "id": "work", "source": "viber", "chats": ["Работа"] }
      ],
      "steps": [
        { "id": "review", "time": "21:00", "check": "Разобрал чаты?", "forgot": "Забыл чаты", "on_no": "n1",
          "monitors": ["family", "work"],
          "nodes": { "n1": { "type": "instruction", "text": "Разбери", "next": "done" }, "done": { "type": "done" } } }
      ]
    }
    """.trimIndent()

    private class Rec : EngineListener {
        val events = ArrayList<String>()
        var changes = 0
        override fun log(event: String, vararg fields: String) { events += (listOf(event) + fields).joinToString("|") }
        override fun blocker(isStep: Boolean, id: String, cause: Cause, path: List<String>, reason: String) {}
        override fun alert() {}
        override fun changed() { changes++ }
    }

    private val zone = ZoneId.of("Europe/Kyiv")
    private fun at(h: Int, m: Int, day: Int = 30): Long =
        ZonedDateTime.of(LocalDate.of(2026, 10, day), LocalTime.of(h, m), zone).toInstant().toEpochMilli()

    private fun make(): Pair<Engine, Rec> {
        val cfg = ConfigParser.parse(json)
        val v = ConfigValidator.validate(cfg)
        assertTrue(v.errors.joinToString(), v.ok)
        val rec = Rec()
        return Engine(cfg, EngineState(), rec, zone) to rec
    }

    private fun sig(chat: String, text: String, at: Long, sender: String = "Мама", source: String = "whatsapp") =
        Signal(source, chat, sender, text, at)

    @Test
    fun `parses monitors and default category`() {
        val cfg = ConfigParser.parse(json)
        assertEquals(2, cfg.monitors.size)
        assertEquals(listOf("question", "other"), cfg.monitor("family")!!.categories.map { it.id })
        assertEquals(listOf("all"), cfg.monitor("work")!!.categories.map { it.id })
        assertEquals(listOf("family", "work"), cfg.step("review")!!.monitors)
    }

    @Test
    fun `validator rejects bad monitors`() {
        fun errorsOf(j: String) = ConfigValidator.validate(ConfigParser.parse(j)).errors.joinToString("\n")
        val base = json
        assertTrue(errorsOf(base.replace("\"source\": \"viber\"", "\"source\": \"telegram\"")).contains("неизвестный source"))
        assertTrue(errorsOf(base.replace("\"chats\": [\"Работа\"]", "\"chats\": []")).contains("chats пуст"))
        assertTrue(errorsOf(base.replace("\"monitors\": [\"family\", \"work\"]", "\"monitors\": [\"nope\"]")).contains("несуществующий монитор"))
        assertTrue(errorsOf(base.replace("\"id\": \"work\"", "\"id\": \"family\"")).contains("id повторяется"))
    }

    @Test
    fun `counts classifies and dedupes`() {
        val (e, rec) = make()
        assertTrue(e.receive(sig("Семья", "Когда приедешь?", at(10, 0)), at(10, 0)))
        assertTrue(e.receive(sig("Дача", "Купил хлеб", at(10, 1)), at(10, 1)))
        assertFalse("дубль", e.receive(sig("Семья", "Когда приедешь?", at(10, 0)), at(10, 2)))
        assertFalse("чужая беседа", e.receive(sig("Соседи", "Привет?", at(10, 3)), at(10, 3)))
        assertFalse("чужой источник", e.receive(sig("Семья", "Привет", at(10, 4), source = "viber"), at(10, 4)))
        assertTrue(e.receive(sig("Работа", "Отчёт", at(10, 5), source = "viber"), at(10, 5)))

        val fam = e.monitorSummary("family")
        assertEquals(2, fam.total)
        assertEquals(listOf("question" to 1, "other" to 1), fam.byCategory)
        assertEquals(1, e.monitorSummary("work").total)
        assertEquals(3, rec.events.count { it.startsWith("MON_MSG|") })
        assertEquals("MON_MSG|family|Семья|question", rec.events.first { it.startsWith("MON_MSG|") })
        assertEquals(3, rec.changes)
    }

    @Test
    fun `summary appended to check question and logged`() {
        val (e, rec) = make()
        e.receive(sig("Семья", "Когда приедешь?", at(10, 0)), at(10, 0))
        e.receive(sig("Семья", "Ок", at(10, 1)), at(10, 1))
        e.start(at(20, 0))
        e.tick(at(21, 0))
        val scr = e.stepScreen() as Screen.YesNo
        assertTrue(scr.text, scr.text.startsWith("Разобрал чаты?\n\n"))
        assertTrue(scr.text, scr.text.contains("Семья, Дача: сообщений 2, Вопросы: 1, Прочее: 1"))
        assertTrue(scr.text, scr.text.contains("Работа: сообщений нет"))
        assertTrue(rec.events.joinToString("\n"), rec.events.any { it.startsWith("STEP_ASKED|review|plan=21:00|family=2|work=0") })
    }

    @Test
    fun `start drops messages from before the cycle`() {
        val (e, _) = make()
        e.receive(sig("Семья", "Вчера", at(6, 0)), at(6, 0))
        e.receive(sig("Семья", "Сегодня", at(8, 0)), at(8, 0))
        e.start(at(9, 0))
        assertEquals(1, e.monitorSummary("family").total)
    }

    @Test
    fun `cycle roll logs summary and clears`() {
        val (e, rec) = make()
        e.start(at(22, 0))
        e.answerStep(Answer.Yes, at(22, 0))
        e.receive(sig("Семья", "Ночь?", at(23, 0)), at(23, 0))
        e.tick(at(7, 0, day = 31))
        assertTrue(rec.events.joinToString("\n"), rec.events.contains("MON_CYCLE|family|сообщений 1, Вопросы: 1"))
        assertEquals(0, e.monitorSummary("family").total)
    }

    @Test
    fun `state survives json round trip`() {
        val (e, _) = make()
        e.receive(sig("Семья", "Привет?", at(10, 0)), at(10, 0))
        val restored = EngineState.fromJson(e.state.toJson())
        assertEquals(1, restored.monitors["family"]!!.size)
        assertEquals("question", restored.monitors["family"]!![0].category)
        val e2 = Engine(e.config, restored, Rec(), zone)
        assertFalse("дубль после восстановления", e2.receive(sig("Семья", "Привет?", at(10, 0)), at(10, 1)))
    }
}
