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

    const val STEPS = "steps"
    const val PROCESSES = "processes"

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

    // ---------- Узлы шага или процесса ----------

    /** Добавить или заменить узел. kind: STEPS или PROCESSES, ownerId: id шага или процесса. */
    fun putNode(json: String, kind: String, ownerId: String, nodeId: String, node: JSONObject): String = editOwner(json, kind, ownerId) { owner ->
        val id = nodeId.trim()
        if (!idRe.matches(id)) throw ConfigException("id узла должен быть из латиницы, цифр и _")
        val nodes = owner.optJSONObject("nodes") ?: JSONObject().also { owner.put("nodes", it) }
        nodes.put(id, node)
    }

    fun deleteNode(json: String, kind: String, ownerId: String, nodeId: String): String = editOwner(json, kind, ownerId) { owner ->
        owner.optJSONObject("nodes")?.remove(nodeId)
    }

    /** Куда ведут ответы на проверочный вопрос шага. onYes == null: шаг сразу закрыт. */
    fun setRoots(json: String, stepId: String, onYes: String?, onNo: String): String = editOwner(json, STEPS, stepId) { step ->
        if (onYes.isNullOrBlank()) step.remove("on_yes") else step.put("on_yes", onYes)
        if (onNo.isBlank()) throw ConfigException("Ответ «нет» должен вести в узел")
        step.put("on_no", onNo)
    }

    /** Стартовый узел процесса. */
    fun setStart(json: String, procId: String, start: String): String = editOwner(json, PROCESSES, procId) { proc ->
        if (start.isBlank()) throw ConfigException("Начало процесса должно вести в узел")
        proc.put("start", start)
    }

    /** Свободный id узла вида nN внутри шага или процесса. */
    fun newNodeId(json: String, kind: String, ownerId: String): String {
        val ids = HashSet<String>()
        try {
            for (o in list(JSONObject(json), kind, required = false)) {
                if (o.optString("id") == ownerId) o.optJSONObject("nodes")?.keys()?.forEach { ids += it }
            }
        } catch (_: JSONException) { }
        var n = 1
        while ("n$n" in ids) n++
        return "n$n"
    }

    /** Собрать JSON узла по полям диалога с проверкой обязательных полей (SPEC 4.5). */
    fun node(
        type: String,
        text: String,
        yes: String?,
        no: String?,
        next: String?,
        options: List<Pair<String, String?>>,
        button: String? = null,
        periodMin: String? = null,
        answerMin: String? = null,
    ): JSONObject {
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
            "wait" -> {
                needText()
                val btn = button?.trim().orEmpty()
                if (btn.isEmpty()) throw ConfigException("У ожидания должна быть надпись кнопки")
                o.put("button", btn)
                o.put("next", target(next, "Далее"))
            }
            "timer" -> {
                needText()
                fun minutes(v: String?, what: String): Int {
                    val n = v?.trim()?.toIntOrNull() ?: throw ConfigException("$what: нужно целое число минут")
                    if (n <= 0) throw ConfigException("$what: должно быть больше 0")
                    return n
                }
                o.put("period_min", minutes(periodMin, "Перерыв между вопросами"))
                o.put("answer_min", minutes(answerMin, "Время на ответ"))
                o.put("next", target(next, "Далее"))
            }
            "done" -> { }
            "fail" -> needText()
            else -> throw ConfigException("Неизвестный тип узла \"$type\"")
        }
        return o
    }

    // ---------- Процессы ----------

    data class ProcessFields(val id: String, val title: String)

    /** Новый процесс в конец списка с минимальным деревом: инструкция → done. */
    fun insertProcess(json: String, f: ProcessFields): String = editProcesses(json) { procs ->
        val id = f.id.trim()
        if (!idRe.matches(id)) throw ConfigException("id должен быть из латиницы, цифр и _")
        val title = f.title.trim()
        if (title.isEmpty()) throw ConfigException("Название процесса не может быть пустым")
        procs += JSONObject().put("id", id).put("title", title).put("start", "n1").put(
            "nodes",
            JSONObject()
                .put("n1", JSONObject().put("type", "instruction").put("text", "Сделай это.").put("next", "done"))
                .put("done", JSONObject().put("type", "done")),
        )
    }

    fun updateProcess(json: String, procId: String, title: String): String = editOwner(json, PROCESSES, procId) { proc ->
        val t = title.trim()
        if (t.isEmpty()) throw ConfigException("Название процесса не может быть пустым")
        proc.put("title", t)
    }

    fun deleteProcess(json: String, procId: String): String = editProcesses(json) { procs ->
        if (!procs.removeAll { it.optString("id") == procId }) throw ConfigException("Процесс \"$procId\" не найден")
    }

    /** Свободный id процесса вида proc_N. */
    fun newProcessId(json: String): String {
        val ids = HashSet<String>()
        try { for (o in list(JSONObject(json), PROCESSES, required = false)) ids += o.optString("id", "") } catch (_: JSONException) { }
        var n = 1
        while ("proc_$n" in ids) n++
        return "proc_$n"
    }


    private fun editRoot(json: String, change: (JSONObject) -> Unit): String {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw ConfigException("JSON не разбирается: ${e.message}")
        }
        change(root)
        sortSteps(root)
        return root.toString(2)
    }

    private fun list(root: JSONObject, key: String, required: Boolean): MutableList<JSONObject> {
        val arr = root.optJSONArray(key) ?: if (required) throw ConfigException("Нет массива $key") else JSONArray()
        return (0 until arr.length()).mapTo(ArrayList()) { arr.getJSONObject(it) }
    }

    private fun store(root: JSONObject, key: String, items: List<JSONObject>) {
        val out = JSONArray()
        for (o in items) out.put(o)
        root.put(key, out)
    }

    /** Правка списка шагов. */
    private fun edit(json: String, change: (MutableList<JSONObject>) -> Unit): String = editRoot(json) { root ->
        val steps = list(root, STEPS, required = true)
        change(steps)
        store(root, STEPS, steps)
    }

    /** Правка списка процессов. */
    private fun editProcesses(json: String, change: (MutableList<JSONObject>) -> Unit): String = editRoot(json) { root ->
        val procs = list(root, PROCESSES, required = false)
        change(procs)
        store(root, PROCESSES, procs)
    }

    /** Правка одного шага или процесса по id. */
    private fun editOwner(json: String, kind: String, id: String, change: (JSONObject) -> Unit): String = editRoot(json) { root ->
        val o = list(root, kind, required = true).firstOrNull { it.optString("id") == id }
            ?: throw ConfigException("${if (kind == STEPS) "Шаг" else "Процесс"} \"$id\" не найден")
        change(o)
    }

    /**
     * Порядок шагов по времени внутри цикла. Сортировка устойчивая: записи с
     * одним временем остаются в прежнем порядке, сдвигается только изменённая.
     */
    private fun sortSteps(root: JSONObject) {
        val arr = root.optJSONArray(STEPS) ?: return
        val dayStart = ConfigParser.parseTime(root.optString("day_start", ""), "day_start")
        val steps = (0 until arr.length()).map { arr.getJSONObject(it) }
        val sorted = steps.sortedBy { s ->
            val t = ConfigParser.parseTime(s.optString("time", ""), "шаг \"${s.optString("id")}\": time")
            ((t - dayStart) % 1440 + 1440) % 1440
        }
        store(root, STEPS, sorted)
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
