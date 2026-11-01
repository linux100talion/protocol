package com.protocol.ui

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.protocol.R
import com.protocol.config.ConfigException
import com.protocol.config.ConfigParser
import com.protocol.config.ConfigStore
import com.protocol.config.ConfigValidator
import com.protocol.engine.Mode
import com.protocol.service.EngineHolder
import com.protocol.service.ReminderService

/**
 * Общий путь записи правленого конфига: проверить целиком (SPEC 4.6),
 * записать в файл, сообщить сервису. При ошибке файл не трогается.
 */
object ConfigSaver {

    fun apply(activity: Activity, store: ConfigStore, newText: String): Boolean {
        val config = try {
            ConfigParser.parse(newText)
        } catch (e: ConfigException) {
            showError(activity, e.message ?: "ошибка"); return false
        }
        val v = ConfigValidator.validate(config)
        if (!v.ok) { showError(activity, v.errors.joinToString("\n")); return false }
        store.save(newText)?.let { showError(activity, it); return false }

        // Движок не в работе перечитает файл сразу, работающий: после перезапуска (SPEC 5.1).
        val running = EngineHolder.engine?.state?.mode == Mode.RUNNING
        ReminderService.send(activity, ReminderService.intent(activity, ReminderService.ACTION_RELOAD))
        Toast.makeText(activity, if (running) R.string.sched_saved_running else R.string.sched_saved, Toast.LENGTH_LONG).show()
        return true
    }

    fun showError(activity: Activity, msg: String) {
        AlertDialog.Builder(activity).setTitle(R.string.sched_error).setMessage(msg)
            .setPositiveButton(android.R.string.ok, null).show()
    }
}
