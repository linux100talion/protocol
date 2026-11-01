package com.protocol.config

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

/**
 * Хранит ссылку на файл конфига, его последнюю прочитанную копию и состояние движка.
 * Конфиг перечитывается из файла при каждом запуске (SPEC 4.1).
 */
class ConfigStore(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("protocol", Context.MODE_PRIVATE)

    var configUri: Uri?
        get() = prefs.getString("config_uri", null)?.let { Uri.parse(it) }
        set(value) = prefs.edit { putString("config_uri", value?.toString()) }

    var configText: String?
        get() = prefs.getString("config_text", null)
        private set(value) = prefs.edit { putString("config_text", value) }

    var engineStateJson: String?
        get() = prefs.getString("engine_state", null)
        set(value) = prefs.edit { putString("engine_state", value) }

    sealed class LoadResult {
        data class Ok(val config: Config, val warnings: List<String>) : LoadResult()
        data class Error(val message: String) : LoadResult()
        object NotConfigured : LoadResult()
    }

    /** Перечитать файл, разобрать, проверить. При успехе копия сохраняется. */
    fun reload(): LoadResult {
        val uri = configUri ?: return LoadResult.NotConfigured
        val text = try {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: return LoadResult.Error("Файл конфига не открывается")
        } catch (e: Exception) {
            return LoadResult.Error("Файл конфига не читается: ${e.message}")
        }
        return parseAndValidate(text).also { if (it is LoadResult.Ok) configText = text }
    }

    /**
     * Записать новый текст в файл конфига и обновить копию.
     * Возвращает текст ошибки или null. Текст должен быть уже проверен.
     */
    fun save(text: String): String? {
        val uri = configUri ?: return "Конфиг не выбран"
        return try {
            val out = ctx.contentResolver.openOutputStream(uri, "wt")
                ?: return "Файл конфига не открывается на запись"
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            configText = text
            null
        } catch (e: SecurityException) {
            "Нет доступа на запись. Выберите файл заново через «Выбрать конфиг»."
        } catch (e: Exception) {
            "Файл не записан: ${e.message}"
        }
    }

    /** Загрузить последнюю копию без обращения к файлу (после перезагрузки). */
    fun loadCached(): LoadResult {
        val text = configText ?: return reload()
        return parseAndValidate(text)
    }

    private fun parseAndValidate(text: String): LoadResult {
        val config = try {
            ConfigParser.parse(text)
        } catch (e: ConfigException) {
            return LoadResult.Error(e.message ?: "ошибка конфига")
        }
        val v = ConfigValidator.validate(config)
        if (!v.ok) return LoadResult.Error(v.errors.joinToString("\n"))
        return LoadResult.Ok(config, v.warnings)
    }
}
