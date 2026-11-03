package com.protocol.config

/**
 * Смысловые проверки конфига. SPEC.md, раздел 4.6.
 * errors: конфиг отвергается. warnings: показываются, но не мешают.
 */
class ValidationResult(val errors: List<String>, val warnings: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

object ConfigValidator {

    fun validate(c: Config): ValidationResult {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()

        if (c.steps.isEmpty()) errors += "steps пуст"
        if (c.timeouts.repeatSec <= 0) errors += "timeouts.repeat_sec должен быть больше 0"
        if (c.timeouts.escalateMin <= 0) errors += "timeouts.escalate_min должен быть больше 0"
        if (c.timeouts.closeMin <= c.timeouts.escalateMin)
            errors += "timeouts.close_min должен быть больше escalate_min"

        // Уникальность id шагов и порядок времён внутри цикла.
        val seenSteps = HashSet<String>()
        var prevOffset = -1
        for (s in c.steps) {
            if (!seenSteps.add(s.id)) errors += "шаг \"${s.id}\": id повторяется"
            if (!idRe.matches(s.id)) errors += "шаг \"${s.id}\": id должен быть из латиницы, цифр и _"
            val off = s.offsetMin(c.dayStartMin)
            if (off < prevOffset) errors += "шаг \"${s.id}\": время ${formatHHMM(s.timeMin)} нарушает порядок внутри цикла"
            prevOffset = off

            val roots = listOfNotNull(s.onYes, s.onNo)
            checkTree("шаг \"${s.id}\"", s.nodes, roots, allowWait = false, errors, warnings)
            for (m in s.monitors) if (c.monitor(m) == null) errors += "шаг \"${s.id}\": ссылка на несуществующий монитор \"$m\""
        }

        // Мониторы. ARCHITECTURE 4.3.
        val seenMons = HashSet<String>()
        for (m in c.monitors) {
            val w = "монитор \"${m.id}\""
            if (!seenMons.add(m.id)) errors += "$w: id повторяется"
            if (!idRe.matches(m.id)) errors += "$w: id должен быть из латиницы, цифр и _"
            if (m.source !in Monitor.SOURCES) errors += "$w: неизвестный source \"${m.source}\", допустимы ${Monitor.SOURCES.joinToString()}"
            if (m.chats.isEmpty()) errors += "$w: chats пуст"
            val seenCats = HashSet<String>()
            for ((i, cat) in m.categories.withIndex()) {
                if (!seenCats.add(cat.id)) errors += "$w: категория \"${cat.id}\" повторяется"
                if (!idRe.matches(cat.id)) errors += "$w: id категории \"${cat.id}\" должен быть из латиницы, цифр и _"
                if (cat.contains.isEmpty() && i != m.categories.lastIndex)
                    warnings += "$w: категория \"${cat.id}\" без условий ловит всё, категории после неё недостижимы"
                if (cat.contains.any { it.isBlank() }) errors += "$w: категория \"${cat.id}\": пустая строка в contains"
            }
        }

        val seenProcs = HashSet<String>()
        for (p in c.processes) {
            if (!seenProcs.add(p.id)) errors += "процесс \"${p.id}\": id повторяется"
            if (!idRe.matches(p.id)) errors += "процесс \"${p.id}\": id должен быть из латиницы, цифр и _"
            checkTree("процесс \"${p.id}\"", p.nodes, listOf(p.start), allowWait = true, errors, warnings)
        }

        return ValidationResult(errors, warnings)
    }

    private val idRe = Regex("""^[A-Za-z0-9_]+$""")

    private fun checkTree(
        where: String,
        nodes: Map<String, Node>,
        roots: List<String>,
        allowWait: Boolean,
        errors: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        // Ссылки ведут в существующие узлы.
        for (r in roots) if (r !in nodes) errors += "$where: ссылка на несуществующий узел \"$r\""
        for ((id, n) in nodes) {
            if (!allowWait && n is Node.Wait) errors += "$where, узел \"$id\": wait допустим только в процессах"
            if (!allowWait && n is Node.Timer) errors += "$where, узел \"$id\": timer допустим только в процессах"
            for (t in n.targets()) if (t !in nodes) errors += "$where, узел \"$id\": ссылка на несуществующий узел \"$t\""
        }
        if (errors.isNotEmpty()) return

        // Достижимость от корней.
        val reachable = HashSet<String>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!reachable.add(id)) continue
            nodes[id]?.targets()?.forEach { queue.addLast(it) }
        }
        for (id in nodes.keys) if (id !in reachable) warnings += "$where, узел \"$id\" недостижим"

        // Из каждого достижимого узла должен существовать путь в done или fail.
        val canExit = HashSet<String>()
        var changed = true
        while (changed) {
            changed = false
            for ((id, n) in nodes) {
                if (id in canExit) continue
                val exits = n is Node.Done || n is Node.Fail || n.targets().any { it in canExit }
                if (exits) { canExit.add(id); changed = true }
            }
        }
        for (id in reachable) if (id !in canExit) errors += "$where, узел \"$id\": из него нет выхода в done или fail"
    }
}
