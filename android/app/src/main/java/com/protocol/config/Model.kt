package com.protocol.config

/**
 * Модель конфига. Соответствует SPEC.md, раздел 4.
 * Время хранится в минутах от полуночи.
 */
data class Config(
    val version: Int,
    val dayStartMin: Int,
    val timeouts: Timeouts,
    val steps: List<Step>,
    val processes: List<Process>,
) {
    fun step(id: String): Step? = steps.firstOrNull { it.id == id }
    fun process(id: String): Process? = processes.firstOrNull { it.id == id }
}

data class Timeouts(
    val repeatSec: Int = 60,
    val escalateMin: Int = 5,
    val closeMin: Int = 10,
) {
    val repeatMs: Long get() = repeatSec * 1000L
    val escalateMs: Long get() = escalateMin * 60_000L
    val closeMs: Long get() = closeMin * 60_000L
}

data class Step(
    val id: String,
    val timeMin: Int,
    val check: String,
    val forgot: String,
    val onYes: String?,
    val onNo: String,
    val nodes: Map<String, Node>,
    /** Название события для таблицы на главном экране. Без него показывается check. */
    val title: String? = null,
    /** Конец интервала «time - until». Только для отображения, движок не использует. */
    val untilMin: Int? = null,
) {
    val displayTitle: String get() = title ?: check

    /** Смещение от начала цикла в минутах, 0..1439. */
    fun offsetMin(dayStartMin: Int): Int = ((timeMin - dayStartMin) % 1440 + 1440) % 1440
}

data class Process(
    val id: String,
    val title: String,
    val start: String,
    val nodes: Map<String, Node>,
)

sealed class Node {
    abstract val text: String

    data class YesNo(override val text: String, val yes: String, val no: String) : Node()
    data class Instruction(override val text: String, val next: String) : Node()
    data class Text(override val text: String, val next: String) : Node()
    data class Choice(override val text: String, val options: List<Option>) : Node()
    data class Wait(override val text: String, val button: String, val next: String) : Node()
    object Done : Node() { override val text: String get() = "" }
    data class Fail(override val text: String) : Node()

    /** Все id узлов, на которые ссылается этот узел. */
    fun targets(): List<String> = when (this) {
        is YesNo -> listOf(yes, no)
        is Instruction -> listOf(next)
        is Text -> listOf(next)
        is Choice -> options.map { it.next }
        is Wait -> listOf(next)
        Done -> emptyList()
        is Fail -> emptyList()
    }
}

data class Option(val label: String, val next: String)

fun formatHHMM(min: Int): String = "%02d:%02d".format(min / 60, min % 60)
