package com.protocol.config

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Правка шагов прямо в тексте конфига. Работает с сырым JSON, чтобы не терять
 * ничего, что модель не знает: вложенные узлы, комментарии в виде полей,
 * порядок ключей. Результат нужно прогнать через ConfigParser и
 * ConfigValidator перед записью в файл.
 */
object ConfigEditor {

    /** Поля записи, которые правятся из таблицы. Пустые title и until убирают поле. */
    data class StepFields(
        val id: String,
        val title: String,
        val time: String,
        val until: String,
        val check: String,
        val forgot: String,
    )

    /** Вставить новый шаг после шага с индексом index (-1: в начало). */
    fun insertAfter(json: String, index: Int, f: StepFields): String = edit(json) { steps ->
        val o = JSONObject()
        fill(o, f, isNew = true)
        o.put("on_no", "n1")
        o.put("nodes", defaultNodes())
        steps.add((index + 1).coerceIn(0, steps.size), o)
    }

    /** Поменять поля существующего шага. id не меняется: он идёт в логи. */
    fun update(json: String, index: Int, f: StepFields): String = edit(json) { steps ->
        fill(steps[index], f, isNew = false)
    }

    fun delete(json: String, index: Int): String = edit(json) { steps -> steps.removeAt(index) }

    /** Свободный id вида step_N. */
    fun newId(json: String): String {
        val ids = HashSet<String>()
        try {
            val arr = JSONObject(json).optJSONArray("steps") ?: JSONArray()
            for (i in 0 until arr.length()) ids += arr.getJSONObject(i).optString("id", "")
        } catch (_: JSONException) { }
        var n = 1
        while ("step_$n" in ids) n++
        return "step_$n"
    }

    private val idRe = Regex("""^[A-Za-z0-9_]+$""")

    private fun edit(json: String, change: (MutableList<JSONObject>) -> Unit): String {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw ConfigException("JSON не разбирается: ${e.message}")
        }
        val arr = root.optJSONArray("steps") ?: throw ConfigException("Нет массива steps")
        val steps = (0 until arr.length()).mapTo(ArrayList()) { arr.getJSONObject(it) }
        change(steps)

        // Порядок по времени внутри цикла. Сортировка устойчивая: записи с одним
        // временем остаются в прежнем порядке, сдвигается только изменённая.
        val dayStart = ConfigParser.parseTime(root.optString("day_start", ""), "day_start")
        val sorted = steps.sortedBy { s ->
            val t = ConfigParser.parseTime(s.optString("time", ""), "шаг \"${s.optString("id")}\": time")
            ((t - dayStart) % 1440 + 1440) % 1440
        }
        val out = JSONArray()
        for (s in sorted) out.put(s)
        root.put("steps", out)
        return root.toString(2)
    }

    private fun fill(o: JSONObject, f: StepFields, isNew: Boolean) {
        if (isNew) {
            val id = f.id.trim()
            if (!idRe.matches(id)) throw ConfigException("id должен быть из латиницы, цифр и _")
            o.put("id", id)
        }
        val time = f.time.trim()
        ConfigParser.parseTime(time, "Время")
        o.put("time", time)

        val until = f.until.trim()
        if (until.isEmpty()) o.remove("until") else { ConfigParser.parseTime(until, "Конец интервала"); o.put("until", until) }

        val title = f.title.trim()
        if (title.isEmpty()) o.remove("title") else o.put("title", title)

        val check = f.check.trim()
        if (check.isEmpty()) throw ConfigException("Проверочный вопрос не может быть пустым")
        o.put("check", check)

        val forgot = f.forgot.trim()
        if (forgot.isEmpty()) throw ConfigException("Текст «забыл» не может быть пустым")
        o.put("forgot", forgot)
    }

    /** Минимальное дерево для новой записи. Вложенные вопросы правятся отдельно. */
    private fun defaultNodes(): JSONObject = JSONObject().apply {
        put("n1", JSONObject().put("type", "instruction").put("text", "Сделай это сейчас.").put("next", "n2"))
        put("n2", JSONObject().put("type", "yesno").put("text", "Сделал?").put("yes", "done").put("no", "fail"))
        put("done", JSONObject().put("type", "done"))
        put("fail", JSONObject().put("type", "fail").put("text", "Шаг не выполнен. Программа останавливается."))
    }
}
