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

    // ---------- Узлы шага ----------

    /** Добавить или заменить узел шага. */
    fun putNode(json: String, stepId: String, nodeId: String, node: JSONObject): String = editStep(json, stepId) { step ->
        val id = nodeId.trim()
        if (!idRe.matches(id)) throw ConfigException("id узла должен быть из латиницы, цифр и _")
        val nodes = step.optJSONObject("nodes") ?: JSONObject().also { step.put("nodes", it) }
        nodes.put(id, node)
    }

    fun deleteNode(json: String, stepId: String, nodeId: String): String = editStep(json, stepId) { step ->
        step.optJSONObject("nodes")?.remove(nodeId)
    }

    /** Куда ведут ответы на проверочный вопрос. onYes == null: шаг сразу закрыт. */
    fun setRoots(json: String, stepId: String, onYes: String?, onNo: String): String = editStep(json, stepId) { step ->
        if (onYes.isNullOrBlank()) step.remove("on_yes") else step.put("on_yes", onYes)
        if (onNo.isBlank()) throw ConfigException("Ответ «нет» должен вести в узел")
        step.put("on_no", onNo)
    }

    /** Свободный id узла вида nN. */
    fun newNodeId(json: String, stepId: String): String {
        val ids = HashSet<String>()
        try {
            val arr = JSONObject(json).optJSONArray("steps") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val st = arr.getJSONObject(i)
                if (st.optString("id") == stepId) st.optJSONObject("nodes")?.keys()?.forEach { ids += it }
            }
        } catch (_: JSONException) { }
        var n = 1
        while ("n$n" in ids) n++
        return "n$n"
    }

    /** Собрать JSON узла по полям диалога с проверкой обязательных полей (SPEC 4.5). */
    fun node(type: String, text: String, yes: String?, no: String?, next: String?, options: List<Pair<String, String?>>): JSONObject {
        val o = JSONObject().put("type", type)
        val t = text.trim()
        fun needText() { if (t.isEmpty()) throw ConfigException("Текст узла не может быть пустым"); o.put("text", t) }
        fun target(v: String?, what: String): String {
            if (v.isNullOrBlank()) throw ConfigException("$what: не выбран узел для перехода")
            return v
        }
        when (type) {
            "yesno" -> { needText(); o.put("yes", target(yes, "Ответ «да»")); o.put("no", target(no, "Ответ «нет»")) }
            "instruction", "text" -> { needText(); o.put("next", target(next, "Далее")) }
            "choice" -> {
                needText()
                if (options.isEmpty()) throw ConfigException("У выбора должен быть хотя бы один вариант")
                val arr = JSONArray()
                for ((label, nx) in options) {
                    val l = label.trim()
                    if (l.isEmpty()) throw ConfigException("Надпись варианта не может быть пустой")
                    arr.put(JSONObject().put("label", l).put("next", target(nx, "Вариант «$l»")))
                }
                o.put("options", arr)
            }
            "done" -> { }
            "fail" -> needText()
            else -> throw ConfigException("Неизвестный тип узла \"$type\"")
        }
        return o
    }

    private fun editStep(json: String, stepId: String, change: (JSONObject) -> Unit): String = edit(json) { steps ->
        val step = steps.firstOrNull { it.optString("id") == stepId } ?: throw ConfigException("Шаг \"$stepId\" не найден")
        change(step)
    }

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
