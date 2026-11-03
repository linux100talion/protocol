package com.protocol.monitor

import android.content.Context
import com.protocol.engine.Signal
import org.json.JSONObject
import java.io.File

/**
 * Очередь сигналов на диске между службой чтения уведомлений и сервисом.
 * Служба может сработать, когда сервис не живёт и поднять его из фона нельзя.
 * Тогда сигнал ждёт здесь, а сервис забирает очередь при каждом старте:
 * к будильнику шага она всегда разобрана. Оба конца на главном потоке.
 */
class SignalInbox(context: Context) {
    private val file = File(context.filesDir, "signal-inbox.jsonl")

    fun append(s: Signal) {
        try {
            val o = JSONObject().put("source", s.source).put("chat", s.chat).put("sender", s.sender).put("text", s.text).put("at", s.at)
            file.appendText(o.toString() + "\n", Charsets.UTF_8)
        } catch (_: Exception) { }
    }

    /** Забрать всё и очистить. */
    fun drain(): List<Signal> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<Signal>()
        try {
            for (line in file.readLines(Charsets.UTF_8)) {
                if (line.isBlank()) continue
                try {
                    val o = JSONObject(line)
                    out += Signal(o.optString("source"), o.optString("chat"), o.optString("sender"), o.optString("text"), o.optLong("at"))
                } catch (_: Exception) { }
            }
            file.delete()
        } catch (_: Exception) { }
        return out
    }
}
