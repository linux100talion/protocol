package com.protocol.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConfigEditorTest {

    private val json = """
    {
      "version": 1,
      "day_start": "07:00",
      "note": "поле, которого модель не знает",
      "steps": [
        { "id": "a", "time": "09:00", "check": "A?", "forgot": "Забыл A", "on_no": "n1",
          "nodes": { "n1": { "type": "yesno", "text": "Ещё раз A?", "yes": "done", "no": "fail" },
                     "done": { "type": "done" }, "fail": { "type": "fail", "text": "A провален" } } },
        { "id": "b", "time": "12:00", "until": "15:00", "title": "Б", "check": "B?", "forgot": "Забыл B", "on_no": "n1",
          "nodes": { "n1": { "type": "instruction", "text": "Сделай B", "next": "done" }, "done": { "type": "done" } } },
        { "id": "c", "time": "02:00", "check": "C?", "forgot": "Забыл C", "on_no": "n1",
          "nodes": { "n1": { "type": "instruction", "text": "Сделай C", "next": "done" }, "done": { "type": "done" } } }
      ]
    }
    """.trimIndent()

    private fun fields(id: String = "x", time: String, title: String = "", until: String = "", check: String = "X?", forgot: String = "Забыл X") =
        ConfigEditor.StepFields(id, title, time, until, check, forgot)

    private fun ids(text: String) = ConfigParser.parse(text).steps.map { it.id }

    private fun valid(text: String): Config {
        val c = ConfigParser.parse(text)
        val v = ConfigValidator.validate(c)
        assertTrue(v.errors.joinToString(), v.ok)
        return c
    }

    @Test
    fun `parser reads title and until`() {
        val c = ConfigParser.parse(json)
        assertEquals("Б", c.steps[1].title)
        assertEquals(15 * 60, c.steps[1].untilMin)
        assertNull(c.steps[0].title)
        assertEquals("A?", c.steps[0].displayTitle)
        assertEquals("Б", c.steps[1].displayTitle)
    }

    @Test
    fun `insert after keeps position for equal time and stays valid`() {
        val out = ConfigEditor.insertAfter(json, 0, fields(time = "09:00", title = "Икс"))
        val c = valid(out)
        assertEquals(listOf("a", "x", "b", "c"), c.steps.map { it.id })
        assertEquals("Икс", c.steps[1].title)
        assertEquals("n1", c.steps[1].onNo)
    }

    @Test
    fun `insert is moved to its place by time within cycle`() {
        val out = ConfigEditor.insertAfter(json, 2, fields(time = "10:00"))
        assertEquals(listOf("a", "x", "b", "c"), ids(out))
    }

    @Test
    fun `insert at start`() {
        val out = ConfigEditor.insertAfter(json, -1, fields(time = "07:30"))
        assertEquals(listOf("x", "a", "b", "c"), ids(out))
    }

    @Test
    fun `update changes fields and clears optional ones`() {
        val out = ConfigEditor.update(json, 1, fields(id = "ignored", time = "12:30", title = "", until = "", check = "B2?", forgot = "Забыл B2"))
        val c = valid(out)
        val b = c.step("b")!!
        assertEquals(12 * 60 + 30, b.timeMin)
        assertNull(b.title)
        assertNull(b.untilMin)
        assertEquals("B2?", b.check)
        assertEquals(2, b.nodes.size)
    }

    @Test
    fun `update that moves time reorders`() {
        val out = ConfigEditor.update(json, 0, fields(time = "13:00"))
        assertEquals(listOf("b", "a", "c"), ids(out))
    }

    @Test
    fun `delete removes step and keeps others`() {
        val out = ConfigEditor.delete(json, 1)
        assertEquals(listOf("a", "c"), ids(valid(out)))
    }

    @Test
    fun `unknown fields survive`() {
        val out = ConfigEditor.update(json, 0, fields(time = "09:00"))
        assertEquals("поле, которого модель не знает", JSONObject(out).getString("note"))
    }

    @Test
    fun `new id is free`() {
        assertEquals("step_1", ConfigEditor.newId(json))
        val out = ConfigEditor.insertAfter(json, 0, fields(id = "step_1", time = "09:00"))
        assertEquals("step_2", ConfigEditor.newId(out))
    }

    @Test
    fun `bad input is rejected`() {
        try { ConfigEditor.insertAfter(json, 0, fields(time = "9:5x")); fail() } catch (_: ConfigException) { }
        try { ConfigEditor.insertAfter(json, 0, fields(id = "плохой", time = "09:00")); fail() } catch (_: ConfigException) { }
        try { ConfigEditor.update(json, 0, fields(time = "09:00", check = " ")); fail() } catch (_: ConfigException) { }
    }
}
