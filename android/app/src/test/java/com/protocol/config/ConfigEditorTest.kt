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
        valid(out)
        assertEquals(listOf("a", "c"), ids(out))
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

    // ---------- Узлы ----------

    @Test
    fun `put node adds and replaces`() {
        val n = ConfigEditor.node("instruction", "Позвони", null, null, "done", emptyList())
        var out = ConfigEditor.putNode(json, "b", "n2", n)
        var b = valid(out).step("b")!!
        assertEquals(3, b.nodes.size)
        assertEquals("done", (b.nodes["n2"] as Node.Instruction).next)

        val y = ConfigEditor.node("yesno", "Готово?", "done", "n2", null, emptyList())
        out = ConfigEditor.putNode(out, "b", "n1", y)
        b = valid(out).step("b")!!
        assertEquals("n2", (b.nodes["n1"] as Node.YesNo).no)
    }

    @Test
    fun `choice node keeps options order`() {
        val c = ConfigEditor.node("choice", "Что?", null, null, null, listOf("Раз" to "done", "Два" to "fail"))
        val out = ConfigEditor.putNode(json, "a", "n1", c)
        val a = valid(out).step("a")!!
        val node = a.nodes["n1"] as Node.Choice
        assertEquals(listOf("Раз", "Два"), node.options.map { it.label })
        assertEquals("fail", node.options[1].next)
    }

    @Test
    fun `delete node makes dangling reference visible to validator`() {
        val out = ConfigEditor.deleteNode(json, "a", "fail")
        val v = ConfigValidator.validate(ConfigParser.parse(out))
        assertTrue(v.errors.any { it.contains("fail") })
    }

    @Test
    fun `set roots`() {
        var out = ConfigEditor.setRoots(json, "a", "done", "fail")
        var a = valid(out).step("a")!!
        assertEquals("done", a.onYes); assertEquals("fail", a.onNo)
        out = ConfigEditor.setRoots(out, "a", null, "n1")
        a = valid(out).step("a")!!
        assertNull(a.onYes); assertEquals("n1", a.onNo)
    }

    @Test
    fun `new node id is free within the step`() {
        assertEquals("n2", ConfigEditor.newNodeId(json, "a"))
        assertEquals("n1", ConfigEditor.newNodeId(json, "missing"))
    }

    @Test
    fun `node builder rejects incomplete input`() {
        try { ConfigEditor.node("yesno", "Q?", "done", null, null, emptyList()); fail() } catch (_: ConfigException) { }
        try { ConfigEditor.node("instruction", " ", null, null, "done", emptyList()); fail() } catch (_: ConfigException) { }
        try { ConfigEditor.node("choice", "Q?", null, null, null, emptyList()); fail() } catch (_: ConfigException) { }
        try { ConfigEditor.putNode(json, "a", "плохой", ConfigEditor.node("done", "", null, null, null, emptyList())); fail() } catch (_: ConfigException) { }
        try { ConfigEditor.putNode(json, "nope", "n9", ConfigEditor.node("done", "", null, null, null, emptyList())); fail() } catch (_: ConfigException) { }
    }
}
