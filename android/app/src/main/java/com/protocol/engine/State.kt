package com.protocol.engine

import org.json.JSONArray
import org.json.JSONObject

enum class Mode { IDLE, RUNNING, STOPPED }

enum class RunState { ACTIVE, WAITING, REASON }

enum class Cause { TIMEOUT, FAIL }

/** Ход одного шага. nodeId == null означает проверочный вопрос. */
data class StepRun(
    val stepId: String,
    val t0: Long,
    var nodeId: String? = null,
    val path: MutableList<String> = ArrayList(),
    var repeats: Int = 0,
    var escalated: Boolean = false,
    var lastAlert: Long = t0,
    var lastInteraction: Long = t0,
    var state: RunState = RunState.ACTIVE,
    var cause: Cause? = null,
)

/** Ход одного процесса. */
data class ProcRun(
    val procId: String,
    val startedAt: Long,
    var nodeId: String,
    var shownAt: Long,
    val path: MutableList<String> = ArrayList(),
    var repeats: Int = 0,
    var escalated: Boolean = false,
    var lastAlert: Long = shownAt,
    var lastInteraction: Long = shownAt,
    var state: RunState = RunState.ACTIVE,
    var cause: Cause? = null,
    var pausedAt: Long? = null,
    /** Узел timer: когда задать вопрос. Только в состоянии WAITING. */
    var timerDueAt: Long? = null,
)

/** Засчитанное монитором сообщение. Текст не хранится. ARCHITECTURE 4.3. */
data class MonMsg(val chat: String, val sender: String, val category: String, val at: Long, val key: String)

/** Всё состояние движка. Сохраняется в JSON между перезапусками процесса. */
data class EngineState(
    var mode: Mode = Mode.IDLE,
    var cycleStart: Long = 0L,
    var nextStepIndex: Int = 0,
    var activeStep: StepRun? = null,
    val procs: MutableList<ProcRun> = ArrayList(),
    /** Сообщения мониторов за текущий цикл, ключ = id монитора. */
    val monitors: MutableMap<String, MutableList<MonMsg>> = LinkedHashMap(),
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("mode", mode.name)
        o.put("cycleStart", cycleStart)
        o.put("nextStepIndex", nextStepIndex)
        o.put("monitors", JSONObject().apply {
            for ((id, msgs) in monitors) put(id, JSONArray().apply {
                for (m in msgs) put(JSONObject().apply {
                    put("chat", m.chat); put("sender", m.sender); put("category", m.category); put("at", m.at); put("key", m.key)
                })
            })
        })
        activeStep?.let { s ->
            o.put("activeStep", JSONObject().apply {
                put("stepId", s.stepId); put("t0", s.t0); put("nodeId", s.nodeId ?: JSONObject.NULL)
                put("path", JSONArray(s.path)); put("repeats", s.repeats); put("escalated", s.escalated)
                put("lastAlert", s.lastAlert); put("lastInteraction", s.lastInteraction)
                put("state", s.state.name); put("cause", s.cause?.name ?: JSONObject.NULL)
            })
        }
        o.put("procs", JSONArray().apply {
            for (p in procs) put(JSONObject().apply {
                put("procId", p.procId); put("startedAt", p.startedAt); put("nodeId", p.nodeId)
                put("shownAt", p.shownAt); put("path", JSONArray(p.path)); put("repeats", p.repeats)
                put("escalated", p.escalated); put("lastAlert", p.lastAlert); put("lastInteraction", p.lastInteraction)
                put("state", p.state.name); put("cause", p.cause?.name ?: JSONObject.NULL)
                put("pausedAt", p.pausedAt ?: JSONObject.NULL)
                put("timerDueAt", p.timerDueAt ?: JSONObject.NULL)
            })
        })
        return o.toString()
    }

    companion object {
        fun fromJson(json: String?): EngineState {
            if (json.isNullOrBlank()) return EngineState()
            return try {
                val o = JSONObject(json)
                val st = EngineState(
                    mode = Mode.valueOf(o.optString("mode", "IDLE")),
                    cycleStart = o.optLong("cycleStart", 0L),
                    nextStepIndex = o.optInt("nextStepIndex", 0),
                )
                o.optJSONObject("activeStep")?.let { s ->
                    st.activeStep = StepRun(
                        stepId = s.getString("stepId"), t0 = s.getLong("t0"),
                        nodeId = if (s.isNull("nodeId")) null else s.getString("nodeId"),
                        path = strings(s.optJSONArray("path")), repeats = s.optInt("repeats"),
                        escalated = s.optBoolean("escalated"), lastAlert = s.optLong("lastAlert"),
                        lastInteraction = s.optLong("lastInteraction"),
                        state = RunState.valueOf(s.optString("state", "ACTIVE")),
                        cause = if (s.isNull("cause")) null else Cause.valueOf(s.getString("cause")),
                    )
                }
                o.optJSONObject("monitors")?.let { mo ->
                    for (id in mo.keys()) {
                        val list = ArrayList<MonMsg>()
                        val a = mo.optJSONArray(id) ?: continue
                        for (i in 0 until a.length()) {
                            val m = a.getJSONObject(i)
                            list += MonMsg(m.optString("chat"), m.optString("sender"), m.optString("category"), m.optLong("at"), m.optString("key"))
                        }
                        st.monitors[id] = list
                    }
                }
                val arr = o.optJSONArray("procs")
                if (arr != null) for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    st.procs += ProcRun(
                        procId = p.getString("procId"), startedAt = p.getLong("startedAt"),
                        nodeId = p.getString("nodeId"), shownAt = p.getLong("shownAt"),
                        path = strings(p.optJSONArray("path")), repeats = p.optInt("repeats"),
                        escalated = p.optBoolean("escalated"), lastAlert = p.optLong("lastAlert"),
                        lastInteraction = p.optLong("lastInteraction"),
                        state = RunState.valueOf(p.optString("state", "ACTIVE")),
                        cause = if (p.isNull("cause")) null else Cause.valueOf(p.getString("cause")),
                        pausedAt = if (p.isNull("pausedAt")) null else p.getLong("pausedAt"),
                        timerDueAt = if (p.isNull("timerDueAt")) null else p.getLong("timerDueAt"),
                    )
                }
                st
            } catch (e: Exception) {
                EngineState()
            }
        }

        private fun strings(a: JSONArray?): MutableList<String> {
            val r = ArrayList<String>()
            if (a != null) for (i in 0 until a.length()) r += a.getString(i)
            return r
        }
    }
}
