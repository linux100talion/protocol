package com.protocol.ui

import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.protocol.R
import com.protocol.config.Config
import com.protocol.config.ConfigEditor
import com.protocol.config.ConfigException
import com.protocol.config.ConfigParser
import com.protocol.config.ConfigStore
import com.protocol.config.Process
import com.protocol.config.Step
import com.protocol.config.formatHHMM
import com.protocol.databinding.DialogProcessBinding
import com.protocol.databinding.DialogStepBinding
import com.protocol.databinding.ItemProcessRowBinding
import com.protocol.databinding.ItemStepBinding
import com.protocol.service.EngineHolder
import com.protocol.service.ReminderService

/**
 * Таблица протокола на главном экране: шаги основного конфига строками
 * «время, название». Нажатие на название показывает подсказку. Карандаш в
 * заголовке включает режим правки: у каждой строки появляются «+»
 * (вставить ниже), карандаш (править) и корзина (удалить). SPEC 6.1.
 *
 * Правки пишутся прямо в файл конфига через ConfigEditor. Вложенные узлы
 * (вопросы после «нет») здесь не редактируются.
 */
class ScheduleSection(
    private val activity: AppCompatActivity,
    private val store: ConfigStore,
    private val table: LinearLayout,
    private val editToggle: ImageButton,
    private val processList: LinearLayout,
    private val addProcessButton: Button,
    /** Проверка разрешений перед запуском процесса; false: запрос показан, запуск позже. */
    private val canStartProcess: () -> Boolean,
) {
    private var editMode = false
    private var cachedText: String? = null
    private var cachedConfig: Config? = null

    init {
        editToggle.setOnClickListener { editMode = !editMode; render() }
        addProcessButton.setOnClickListener { currentText()?.let { showProcessEditor(it, null) } }
    }

    fun render() {
        table.removeAllViews()
        processList.removeAllViews()
        val text = currentText()
        val config = text?.let { parse(it) }
        editToggle.visibility = if (config == null) View.GONE else View.VISIBLE
        editToggle.alpha = if (editMode) 1f else 0.45f
        addProcessButton.visibility = if (config != null && editMode) View.VISIBLE else View.GONE
        if (text == null || config == null) return

        renderProcesses(text, config)

        config.steps.forEachIndexed { i, s ->
            val row = ItemStepBinding.inflate(activity.layoutInflater, table, false)
            row.timeText.text = timeLabel(s)
            row.titleText.text = s.displayTitle
            row.titleText.setOnClickListener { showHint(config, s) }
            row.actions.visibility = if (editMode) View.VISIBLE else View.GONE
            row.addButton.setOnClickListener { showEditor(text, config, i, insert = true) }
            row.editButton.setOnClickListener { showEditor(text, config, i, insert = false) }
            row.deleteButton.setOnClickListener { confirmDelete(text, s, i) }
            table.addView(row.root)
        }
    }

    // ---------- Процессы ----------

    /**
     * Кнопка запуска на каждый процесс из конфига. Запуск идёт через движок,
     * поэтому кнопка активна, только если движок знает процесс (после правки
     * в работе он узнает его при перезапуске) и процесс сейчас не открыт.
     * В режиме правки у строки карандаш и корзина, долгое нажатие открывает подсказки.
     */
    private fun renderProcesses(text: String, config: Config) {
        val engine = EngineHolder.engine
        for (p in config.processes) {
            val row = ItemProcessRowBinding.inflate(activity.layoutInflater, processList, false)
            row.startButton.text = p.title
            val known = engine?.config?.process(p.id) != null
            val open = engine?.state?.procs?.any { it.procId == p.id } == true
            row.startButton.isEnabled = !open
            row.startButton.setOnClickListener {
                when {
                    !known -> Toast.makeText(activity, R.string.proc_not_loaded, Toast.LENGTH_SHORT).show()
                    canStartProcess() -> ReminderService.send(activity, ReminderService.intent(activity, ReminderService.ACTION_START_PROC).putExtra(ReminderService.EXTRA_PROC_ID, p.id))
                }
            }
            row.startButton.setOnLongClickListener { TreeActivity.open(activity, ConfigEditor.PROCESSES, p.id, edit = false); true }
            row.actions.visibility = if (editMode) View.VISIBLE else View.GONE
            row.editButton.setOnClickListener { showProcessEditor(text, p) }
            row.deleteButton.setOnClickListener { confirmDeleteProcess(text, p) }
            processList.addView(row.root)
        }
    }

    private fun showProcessEditor(text: String, proc: Process?) {
        val d = DialogProcessBinding.inflate(activity.layoutInflater)
        d.titleInput.setText(proc?.title ?: "")
        d.idInput.setText(proc?.id ?: ConfigEditor.newProcessId(text))
        d.idInput.isEnabled = proc == null
        val builder = AlertDialog.Builder(activity)
            .setTitle(if (proc == null) R.string.proc_new else R.string.proc_edit)
            .setView(d.root)
            .setPositiveButton(R.string.btn_save, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (proc != null) builder.setNeutralButton(R.string.btn_tree) { _, _ -> TreeActivity.open(activity, ConfigEditor.PROCESSES, proc.id, edit = true) }
        val dlg = builder.create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newText = try {
                    if (proc == null) ConfigEditor.insertProcess(text, ConfigEditor.ProcessFields(d.idInput.text.toString(), d.titleInput.text.toString()))
                    else ConfigEditor.updateProcess(text, proc.id, d.titleInput.text.toString())
                } catch (e: ConfigException) { showError(e.message ?: "ошибка"); return@setOnClickListener }
                if (apply(newText)) dlg.dismiss()
            }
        }
        dlg.show()
    }

    private fun confirmDeleteProcess(text: String, proc: Process) {
        AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.proc_delete_confirm, proc.title))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                try { apply(ConfigEditor.deleteProcess(text, proc.id)) } catch (e: ConfigException) { showError(e.message ?: "ошибка") }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- Данные ----------

    /** Текст конфига из копии в ConfigStore: она обновляется при чтении файла и при сохранении. */
    private fun currentText(): String? {
        if (store.configUri == null) return null
        return store.configText ?: run { store.reload(); store.configText }
    }

    private fun parse(text: String): Config? {
        if (text == cachedText) return cachedConfig
        cachedText = text
        cachedConfig = try { ConfigParser.parse(text) } catch (_: ConfigException) { null }
        return cachedConfig
    }

    private fun timeLabel(s: Step): String =
        if (s.untilMin != null) "${formatHHMM(s.timeMin)} - ${formatHHMM(s.untilMin)}" else formatHHMM(s.timeMin)

    // ---------- Подсказка ----------

    private fun showHint(config: Config, s: Step) {
        val t = config.timeouts
        val msg = buildString {
            append("Время: ").append(timeLabel(s)).append('\n')
            append("Вопрос: ").append(s.check).append('\n')
            append("Если забыл: ").append(s.forgot).append("\n\n")
            append("Ответ «да»: ").append(if (s.onYes == null) "шаг закрыт" else "продолжение в узле «${s.onYes}»").append('\n')
            append("Ответ «нет»: подсказки с узла «${s.onNo}», всего узлов ${s.nodes.size}").append("\n\n")
            append("Повтор сигнала каждые ${t.repeatSec} с, через ${t.escalateMin} мин текст меняется на «забыл», ")
            append("через ${t.closeMin} мин без ответа программа останавливается.\n")
            append("id в логе: ").append(s.id)
        }
        AlertDialog.Builder(activity).setTitle(s.displayTitle).setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.btn_tree) { _, _ -> TreeActivity.open(activity, ConfigEditor.STEPS, s.id, edit = false) }
            .show()
    }

    // ---------- Правка ----------

    private fun showEditor(text: String, config: Config, index: Int, insert: Boolean) {
        val d = DialogStepBinding.inflate(activity.layoutInflater)
        val step = if (insert) null else config.steps[index]
        val anchor = config.steps[index]
        d.idInput.setText(step?.id ?: ConfigEditor.newId(text))
        d.idInput.isEnabled = insert
        d.titleInput.setText(step?.title ?: "")
        d.timeInput.setText(formatHHMM(step?.timeMin ?: anchor.timeMin))
        d.untilInput.setText(step?.untilMin?.let { formatHHMM(it) } ?: "")
        d.checkInput.setText(step?.check ?: "")
        d.forgotInput.setText(step?.forgot ?: "")

        val builder = AlertDialog.Builder(activity)
            .setTitle(if (insert) R.string.sched_new_step else R.string.sched_edit_step)
            .setView(d.root)
            .setPositiveButton(R.string.btn_save, null)
            .setNegativeButton(android.R.string.cancel, null)
        // Вложенные вопросы правятся на отдельном экране; у новой записи их пока нет.
        if (step != null) builder.setNeutralButton(R.string.btn_tree) { _, _ -> TreeActivity.open(activity, ConfigEditor.STEPS, step.id, edit = true) }
        val dlg = builder.create()
        dlg.setOnShowListener {
            // Кнопка не закрывает диалог сама: при ошибке введённое остаётся.
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val f = ConfigEditor.StepFields(
                    id = d.idInput.text.toString(),
                    title = d.titleInput.text.toString(),
                    time = d.timeInput.text.toString(),
                    until = d.untilInput.text.toString(),
                    check = d.checkInput.text.toString(),
                    forgot = d.forgotInput.text.toString(),
                )
                val newText = try {
                    if (insert) ConfigEditor.insertAfter(text, index, f) else ConfigEditor.update(text, index, f)
                } catch (e: ConfigException) {
                    showError(e.message ?: "ошибка"); return@setOnClickListener
                }
                if (apply(newText)) dlg.dismiss()
            }
        }
        dlg.show()
    }

    private fun confirmDelete(text: String, s: Step, index: Int) {
        AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.sched_delete_confirm, s.displayTitle))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                try { apply(ConfigEditor.delete(text, index)) } catch (e: ConfigException) { showError(e.message ?: "ошибка") }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Проверить новый текст целиком, записать в файл, перерисовать. */
    private fun apply(newText: String): Boolean {
        if (!ConfigSaver.apply(activity, store, newText)) return false
        render()
        return true
    }

    private fun showError(msg: String) = ConfigSaver.showError(activity, msg)
}
