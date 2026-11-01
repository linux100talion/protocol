package com.protocol.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.protocol.R
import com.protocol.config.ConfigEditor
import com.protocol.config.ConfigException
import com.protocol.config.ConfigParser
import com.protocol.config.ConfigStore
import com.protocol.config.Node
import com.protocol.config.Step
import com.protocol.databinding.ActivityTreeBinding
import com.protocol.databinding.DialogNodeBinding
import com.protocol.databinding.DialogRootsBinding
import com.protocol.databinding.ItemNodeBinding
import com.protocol.databinding.ItemOptionBinding

/**
 * Подсказки шага: переходы с проверочного вопроса и узлы дерева (SPEC 4.5).
 * Узлы показаны в порядке обхода от «нет» и «да», недостижимые в конце.
 * Карандаш включает режим правки: правка и удаление узлов, добавление,
 * смена переходов. Запись через ConfigSaver, проверка конфига целиком.
 */
class TreeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STEP_ID = "step_id"
        const val EXTRA_EDIT = "edit"

        fun open(ctx: Context, stepId: String, edit: Boolean) {
            ctx.startActivity(Intent(ctx, TreeActivity::class.java).putExtra(EXTRA_STEP_ID, stepId).putExtra(EXTRA_EDIT, edit))
        }

        private val TYPES = listOf("yesno", "instruction", "text", "choice", "done", "fail")
    }

    private lateinit var b: ActivityTreeBinding
    private lateinit var store: ConfigStore
    private lateinit var stepId: String
    private var editMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTreeBinding.inflate(layoutInflater)
        setContentView(b.root)
        store = ConfigStore(this)
        stepId = intent.getStringExtra(EXTRA_STEP_ID) ?: run { finish(); return }
        editMode = intent.getBooleanExtra(EXTRA_EDIT, false)
        b.editToggle.setOnClickListener { editMode = !editMode; render() }
        b.rootsEditButton.setOnClickListener { showRootsEditor() }
        b.addNodeButton.setOnClickListener { showNodeEditor(null) }
        render()
    }

    // ---------- Данные ----------

    private fun current(): Pair<String, Step>? {
        val text = store.configText ?: return null
        val config = try { ConfigParser.parse(text) } catch (_: ConfigException) { return null }
        val step = config.step(stepId) ?: return null
        return text to step
    }

    private fun orderedIds(step: Step): List<String> {
        val order = ArrayList<String>()
        val queue = ArrayDeque(listOfNotNull(step.onNo, step.onYes))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id in order || id !in step.nodes) continue
            order += id
            step.nodes[id]?.targets()?.forEach { queue.addLast(it) }
        }
        for (id in step.nodes.keys) if (id !in order) order += id
        return order
    }

    private fun typeKey(n: Node): String = when (n) {
        is Node.YesNo -> "yesno"
        is Node.Instruction -> "instruction"
        is Node.Text -> "text"
        is Node.Choice -> "choice"
        is Node.Wait -> "wait"
        Node.Done -> "done"
        is Node.Fail -> "fail"
    }

    private fun typeLabel(key: String): String = when (key) {
        "yesno" -> getString(R.string.type_yesno)
        "instruction" -> getString(R.string.type_instruction)
        "text" -> getString(R.string.type_text)
        "choice" -> getString(R.string.type_choice)
        "done" -> getString(R.string.type_done)
        "fail" -> getString(R.string.type_fail)
        else -> key
    }

    private fun links(n: Node): String = when (n) {
        is Node.YesNo -> "Да → ${n.yes}\nНет → ${n.no}"
        is Node.Instruction -> "Далее → ${n.next}"
        is Node.Text -> "Далее → ${n.next}"
        is Node.Choice -> n.options.joinToString("\n") { "«${it.label}» → ${it.next}" }
        is Node.Wait -> "«${n.button}» → ${n.next}"
        Node.Done -> ""
        is Node.Fail -> ""
    }

    // ---------- Экран ----------

    private fun render() {
        val (_, step) = current() ?: run { finish(); return }
        b.stepTitle.text = step.displayTitle
        b.checkText.text = step.check
        b.editToggle.alpha = if (editMode) 1f else 0.45f
        val vis = if (editMode) View.VISIBLE else View.GONE
        b.rootsEditButton.visibility = vis
        b.addNodeButton.visibility = vis
        b.rootsText.text = buildString {
            append(getString(R.string.tree_yes_root)).append(": ")
            append(step.onYes?.let { "узел $it" } ?: getString(R.string.tree_step_closed)).append('\n')
            append(getString(R.string.tree_no_root)).append(": узел ").append(step.onNo)
        }

        b.nodes.removeAllViews()
        for (id in orderedIds(step)) {
            val n = step.nodes[id] ?: continue
            val card = ItemNodeBinding.inflate(layoutInflater, b.nodes, false)
            card.headText.text = "$id · ${typeLabel(typeKey(n))}"
            card.bodyText.text = n.text
            card.bodyText.visibility = if (n.text.isEmpty()) View.GONE else View.VISIBLE
            val l = links(n)
            card.linksText.text = l
            card.linksText.visibility = if (l.isEmpty()) View.GONE else View.VISIBLE
            card.actions.visibility = vis
            card.editButton.setOnClickListener { showNodeEditor(id) }
            card.deleteButton.setOnClickListener { confirmDelete(id) }
            b.nodes.addView(card.root)
        }
    }

    // ---------- Правка ----------

    private fun targetAdapter(ids: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ids)

    private fun select(spinner: Spinner, ids: List<String>, value: String?) {
        val i = ids.indexOf(value)
        if (i >= 0) spinner.setSelection(i)
    }

    private fun selected(spinner: Spinner): String? = spinner.selectedItem as? String

    private fun showRootsEditor() {
        val (text, step) = current() ?: return
        val ids = step.nodes.keys.toList()
        val d = DialogRootsBinding.inflate(layoutInflater)
        val yesIds = listOf(getString(R.string.tree_step_closed)) + ids
        d.yesSpinner.adapter = targetAdapter(yesIds)
        d.noSpinner.adapter = targetAdapter(ids)
        select(d.yesSpinner, yesIds, step.onYes ?: yesIds[0])
        select(d.noSpinner, ids, step.onNo)
        val dlg = AlertDialog.Builder(this).setTitle(R.string.tree_roots).setView(d.root)
            .setPositiveButton(R.string.btn_save, null).setNegativeButton(android.R.string.cancel, null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val onYes = if (d.yesSpinner.selectedItemPosition == 0) null else selected(d.yesSpinner)
                val newText = try {
                    ConfigEditor.setRoots(text, stepId, onYes, selected(d.noSpinner) ?: "")
                } catch (e: ConfigException) { ConfigSaver.showError(this, e.message ?: "ошибка"); return@setOnClickListener }
                if (ConfigSaver.apply(this, store, newText)) { dlg.dismiss(); render() }
            }
        }
        dlg.show()
    }

    private fun showNodeEditor(nodeId: String?) {
        val (text, step) = current() ?: return
        val existing = nodeId?.let { step.nodes[it] }
        val ids = step.nodes.keys.toList()
        val d = DialogNodeBinding.inflate(layoutInflater)

        d.typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, TYPES.map { typeLabel(it) })
        d.yesSpinner.adapter = targetAdapter(ids)
        d.noSpinner.adapter = targetAdapter(ids)
        d.nextSpinner.adapter = targetAdapter(ids)
        d.idInput.setText(nodeId ?: ConfigEditor.newNodeId(text, stepId))
        d.idInput.isEnabled = nodeId == null

        val optionRows = ArrayList<ItemOptionBinding>()
        fun addOptionRow(label: String, next: String?) {
            val row = ItemOptionBinding.inflate(layoutInflater, d.options, false)
            row.labelInput.setText(label)
            row.nextSpinner.adapter = targetAdapter(ids)
            select(row.nextSpinner, ids, next)
            row.removeButton.setOnClickListener { d.options.removeView(row.root); optionRows.remove(row) }
            optionRows += row
            d.options.addView(row.root)
        }
        d.addOptionButton.setOnClickListener { addOptionRow("", null) }

        fun showFieldsFor(type: String) {
            d.textInput.visibility = if (type == "done") View.GONE else View.VISIBLE
            d.yesRow.visibility = if (type == "yesno") View.VISIBLE else View.GONE
            d.noRow.visibility = if (type == "yesno") View.VISIBLE else View.GONE
            d.nextRow.visibility = if (type == "instruction" || type == "text") View.VISIBLE else View.GONE
            d.optionsBlock.visibility = if (type == "choice") View.VISIBLE else View.GONE
        }
        d.typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = showFieldsFor(TYPES[position])
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Заполнение из существующего узла.
        if (existing != null) {
            val key = typeKey(existing)
            d.typeSpinner.setSelection(TYPES.indexOf(key).coerceAtLeast(0))
            d.textInput.setText(existing.text)
            when (existing) {
                is Node.YesNo -> { select(d.yesSpinner, ids, existing.yes); select(d.noSpinner, ids, existing.no) }
                is Node.Instruction -> select(d.nextSpinner, ids, existing.next)
                is Node.Text -> select(d.nextSpinner, ids, existing.next)
                is Node.Choice -> existing.options.forEach { addOptionRow(it.label, it.next) }
                else -> {}
            }
            showFieldsFor(key)
        } else {
            showFieldsFor(TYPES[0])
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle(if (nodeId == null) R.string.tree_node_new else R.string.tree_node_edit)
            .setView(d.root)
            .setPositiveButton(R.string.btn_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val type = TYPES[d.typeSpinner.selectedItemPosition]
                val newText = try {
                    val node = ConfigEditor.node(
                        type = type,
                        text = d.textInput.text.toString(),
                        yes = selected(d.yesSpinner),
                        no = selected(d.noSpinner),
                        next = selected(d.nextSpinner),
                        options = optionRows.map { it.labelInput.text.toString() to selected(it.nextSpinner) },
                    )
                    ConfigEditor.putNode(text, stepId, d.idInput.text.toString(), node)
                } catch (e: ConfigException) { ConfigSaver.showError(this, e.message ?: "ошибка"); return@setOnClickListener }
                if (ConfigSaver.apply(this, store, newText)) { dlg.dismiss(); render() }
            }
        }
        dlg.show()
    }

    private fun confirmDelete(nodeId: String) {
        val (text, _) = current() ?: return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.tree_delete_node_confirm, nodeId))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                try {
                    if (ConfigSaver.apply(this, store, ConfigEditor.deleteNode(text, stepId, nodeId))) render()
                } catch (e: ConfigException) { ConfigSaver.showError(this, e.message ?: "ошибка") }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
