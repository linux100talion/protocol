package com.protocol.config

import org.json.JSONException
import org.json.JSONObject

class ConfigException(message: String) : Exception(message)

/**
 * Разбор JSON в модель. Только структура и типы; смысловые проверки
 * (ссылки, порядок времён, циклы) делает ConfigValidator.
 */
object ConfigParser {

    fun parse(json: String): Config {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw ConfigException("JSON не разбирается: ${e.message}")
        }
        val version = root.optInt("version", -1)
        if (version != 1) throw ConfigException("Поддерживается только \"version\": 1, найдено $version")

        val dayStart = parseTime(root.optString("day_start", ""), "day_start")

        val timeouts = root.optJSONObject("timeouts")?.let {
            Timeouts(
                repeatSec = it.optInt("repeat_sec", 60),
                escalateMin = it.optInt("escalate_min", 5),
                closeMin = it.optInt("close_min", 10),
            )
        } ?: Timeouts()

        val stepsArr = root.optJSONArray("steps") ?: throw ConfigException("Нет массива steps")
        val steps = (0 until stepsArr.length()).map { i ->
            parseStep(stepsArr.getJSONObject(i), "steps[$i]")
        }

        val procArr = root.optJSONArray("processes")
        val processes = if (procArr == null) emptyList() else (0 until procArr.length()).map { i ->
            parseProcess(procArr.getJSONObject(i), "processes[$i]")
        }

        return Config(version, dayStart, timeouts, steps, processes)
    }

    private fun parseStep(o: JSONObject, where: String): Step {
        val id = req(o, "id", where)
        val w = "шаг \"$id\""
        val nodesObj = o.optJSONObject("nodes")
        val nodes = if (nodesObj == null) emptyMap() else parseNodes(nodesObj, w)
        return Step(
            id = id,
            timeMin = parseTime(req(o, "time", w), "$w: time"),
            check = req(o, "check", w),
            forgot = req(o, "forgot", w),
            onYes = o.optString("on_yes", "").ifEmpty { null },
            onNo = req(o, "on_no", w),
            nodes = nodes,
            title = o.optString("title", "").trim().ifEmpty { null },
            untilMin = o.optString("until", "").trim().ifEmpty { null }?.let { parseTime(it, "$w: until") },
        )
    }

    private fun parseProcess(o: JSONObject, where: String): Process {
        val id = req(o, "id", where)
        val w = "процесс \"$id\""
        val nodesObj = o.optJSONObject("nodes") ?: throw ConfigException("$w: нет nodes")
        return Process(
            id = id,
            title = req(o, "title", w),
            start = req(o, "start", w),
            nodes = parseNodes(nodesObj, w),
        )
    }

    private fun parseNodes(o: JSONObject, where: String): Map<String, Node> {
        val result = LinkedHashMap<String, Node>()
        for (key in o.keys()) {
            val n = o.getJSONObject(key)
            val w = "$where, узел \"$key\""
            val type = req(n, "type", w)
            result[key] = when (type) {
                "yesno" -> Node.YesNo(req(n, "text", w), req(n, "yes", w), req(n, "no", w))
                "instruction" -> Node.Instruction(req(n, "text", w), req(n, "next", w))
                "text" -> Node.Text(req(n, "text", w), req(n, "next", w))
                "choice" -> {
                    val arr = n.optJSONArray("options") ?: throw ConfigException("$w: нет options")
                    if (arr.length() == 0) throw ConfigException("$w: options пуст")
                    val opts = (0 until arr.length()).map { i ->
                        val op = arr.getJSONObject(i)
                        Option(req(op, "label", "$w, options[$i]"), req(op, "next", "$w, options[$i]"))
                    }
                    Node.Choice(req(n, "text", w), opts)
                }
                "wait" -> Node.Wait(req(n, "text", w), req(n, "button", w), req(n, "next", w))
                "done" -> Node.Done
                "fail" -> Node.Fail(req(n, "text", w))
                else -> throw ConfigException("$w: неизвестный type \"$type\"")
            }
        }
        return result
    }

    private fun req(o: JSONObject, key: String, where: String): String {
        val v = o.optString(key, "")
        if (v.isBlank()) throw ConfigException("$where: нет поля \"$key\" или оно пустое")
        return v
    }

    private val timeRe = Regex("""^(\d{1,2}):(\d{2})$""")

    fun parseTime(s: String, where: String): Int {
        val m = timeRe.matchEntire(s.trim()) ?: throw ConfigException("$where: время \"$s\" не в формате HH:MM")
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h !in 0..23 || min !in 0..59) throw ConfigException("$where: время \"$s\" вне диапазона")
        return h * 60 + min
    }
}
