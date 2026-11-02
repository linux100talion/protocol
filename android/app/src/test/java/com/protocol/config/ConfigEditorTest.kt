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
        var out = ConfigEditor.putNode(json, "steps", "b", "n2", n)
        var b = valid(out).step("b")!!
        assertEquals(3, b.nodes.size)
        assertEquals("done", (b.nodes["n2"] as Node.Instruction).next)

        val y = ConfigEditor.node("yesno", "Готово?", "done", "n2", null, emptyList())
        out = ConfigEditor.putNode(out, "steps", "b", "n1", y)
        b = valid(out).step("b")!!
        assertEquals("n2", (b.nodes["n1"] as Node.YesNo).no)
    }

    @Test
    fun `choice node keeps options order`() {
        val c = ConfigEditor.node("choice", "Что?", null, null, null, listOf("Раз" to "done", "Два" to "fail"))
        val out = ConfigEditor.putNode(json, "steps", "a", "n1", c)
        val a = valid(out).step("a")!!
        val node = a.nodes["n1"] as Node.Choice
        assertEquals(listOf("Раз", "Два"), node.options.map { it.label })
        assertEquals("fail", node.options[1].next)
    }

    @Test
    fun `delete node makes dangling reference visible to validator`() {
        val out = ConfigEditor.deleteNode(json, "steps", "a", "fail")
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
        assertEquals("n2", ConfigEditor.newNodeId(json, "steps", "a"))
        assertEquals("n1", ConfigEditor.newNodeId(json, "steps", "missing"))
    }

    @Test
    fun `node builder rejects incomplete input`() {
        try { ConfigEditor.node("yesno", "Q?", "done", null, null, emptyList()); fail() } catch (_: ConfigException) { }
        try { ConfigEditor.node("instruction", " ", null, null, "done", emptyList()); fail() } catch (_: ConfigException) { }
        try { ConfigEditor.node("choice", "Q?", null, null, null, emptyList()); fail() } catch (_: ConfigException) { }
        try { ConfigEditor.putNode(json, "steps", "a", "плохой", ConfigEditor.node("done", "", null, null, null, emptyList())); fail() } catch (_: ConfigException) { }
        try { ConfigEditor.putNode(json, "steps", "nope", "n9", ConfigEditor.node("done", "", null, null, null, emptyList())); fail() } catch (_: ConfigException) { }
    }

    // ---------- Процессы ----------

    @Test
    fun `process lifecycle`() {
        assertEquals("proc_1", ConfigEditor.newProcessId(json))
        var out = ConfigEditor.insertProcess(json, ConfigEditor.ProcessFields("proc_1", "Свет"))
        var c = valid(out)
        assertEquals(1, c.processes.size)
        assertEquals("Свет", c.processes[0].title)
        assertEquals("n1", c.processes[0].start)
        assertEquals("proc_2", ConfigEditor.newProcessId(out))

        out = ConfigEditor.updateProcess(out, "proc_1", "Выключили свет")
        assertEquals("Выключили свет", valid(out).processes[0].title)

        val w = ConfigEditor.node("wait", "Ждём электрика", null, null, "done", emptyList(), button = "Пришёл")
        out = ConfigEditor.putNode(out, "processes", "proc_1", "n2", w)
        out = ConfigEditor.putNode(out, "processes", "proc_1", "n1", ConfigEditor.node("instruction", "Позвони", null, null, "n2", emptyList()))
        c = valid(out)
        assertTrue(c.processes[0].nodes["n2"] is Node.Wait)
        assertEquals("n3", ConfigEditor.newNodeId(out, "processes", "proc_1"))

        out = ConfigEditor.setStart(out, "proc_1", "n2")
        assertEquals("n2", valid(out).processes[0].start)

        out = ConfigEditor.deleteProcess(out, "proc_1")
        assertTrue(valid(out).processes.isEmpty())
        // Шаги при этом не тронуты.
        assertEquals(listOf("a", "b", "c"), ids(out))
    }

    @Test
    fun `timer node builds, parses and is process-only`() {
        val tm = ConfigEditor.node("timer", "Свет дали?", null, null, "done", emptyList(), periodMin = "30", answerMin = "5")
        var out = ConfigEditor.insertProcess(json, ConfigEditor.ProcessFields("p", "Свет"))
        out = ConfigEditor.putNode(out, "processes", "p", "n1", tm)
        val node = valid(out).processes[0].nodes["n1"] as Node.Timer
        assertEquals(30, node.periodMin); assertEquals(5, node.answerMin); assertEquals("done", node.next)

        val inStep = ConfigEditor.putNode(json, "steps", "b", "n1", tm)
        assertTrue(ConfigValidator.validate(ConfigParser.parse(inStep)).errors.any { it.contains("timer") })
        try { ConfigEditor.node("timer", "Q", null, null, "done", emptyList(), periodMin = "0", answerMin = "5"); fail() } catch (_: ConfigException) { }
        try { ConfigEditor.node("timer", "Q", null, null, "done", emptyList(), periodMin = "10", answerMin = "x"); fail() } catch (_: ConfigException) { }
    }

    @Test
    fun `wait in a step is rejected by validator, wait without button by builder`() {
        val w = ConfigEditor.node("wait", "Жди", null, null, "done", emptyList(), button = "Дальше")
        val out = ConfigEditor.putNode(json, "steps", "b", "n1", w)
        assertTrue(ConfigValidator.validate(ConfigParser.parse(out)).errors.any { it.contains("wait") })
        try { ConfigEditor.node("wait", "Жди", null, null, "done", emptyList(), button = " "); fail() } catch (_: ConfigException) { }
        try { ConfigEditor.insertProcess(json, ConfigEditor.ProcessFields("ok", " ")); fail() } catch (_: ConfigException) { }
        try { ConfigEditor.deleteProcess(json, "nope"); fail() } catch (_: ConfigException) { }
    }
}
