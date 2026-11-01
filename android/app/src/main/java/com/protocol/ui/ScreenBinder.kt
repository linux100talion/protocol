package com.protocol.ui

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.protocol.R
import com.protocol.engine.Screen
import com.protocol.service.ReminderService

/**
 * Превращает Screen движка в поле ввода и кнопки. Общий для экрана шага
 * и карточки процесса на главном экране. target: id процесса или null для шага.
 */
object ScreenBinder {

    fun bind(ctx: Context, scr: Screen, target: String?, input: EditText, buttons: LinearLayout, large: Boolean) {
        buttons.removeAllViews()
        input.visibility = View.GONE
        input.setText("")
        input.hint = ""

        fun add(label: String, onClick: () -> Unit) {
            buttons.addView(Button(ctx).apply {
                text = label
                if (large) { textSize = 22f; minHeight = 160 }
                setOnClickListener { onClick() }
            })
        }

        when (scr) {
            is Screen.YesNo -> {
                add(ctx.getString(R.string.btn_yes)) { ReminderService.answer(ctx, target, "yes") }
                add(ctx.getString(R.string.btn_no)) { ReminderService.answer(ctx, target, "no") }
            }
            is Screen.Ack -> add(ctx.getString(R.string.btn_ack)) { ReminderService.answer(ctx, target, "ack") }
            is Screen.Text -> {
                input.visibility = View.VISIBLE
                add(ctx.getString(R.string.btn_answer)) {
                    val v = input.text.toString().trim()
                    if (v.isEmpty()) Toast.makeText(ctx, R.string.text_empty, Toast.LENGTH_SHORT).show()
                    else ReminderService.answer(ctx, target, "text", v)
                }
            }
            is Screen.Choice -> scr.options.forEachIndexed { i, label ->
                add(label) { ReminderService.answer(ctx, target, "choice", i.toString()) }
            }
            is Screen.Wait -> add(scr.button) { ReminderService.answer(ctx, target, "resume") }
            is Screen.Reason -> {
                input.visibility = View.VISIBLE
                input.hint = ctx.getString(R.string.stop_hint)
                add(ctx.getString(R.string.btn_answer)) {
                    val v = input.text.toString().trim()
                    if (v.isEmpty()) Toast.makeText(ctx, R.string.stop_empty, Toast.LENGTH_SHORT).show()
                    else ReminderService.send(ctx, ReminderService.intent(ctx, ReminderService.ACTION_REASON)
                        .putExtra(ReminderService.EXTRA_IS_STEP, scr.isStep)
                        .putExtra(ReminderService.EXTRA_ID, scr.id)
                        .putExtra(ReminderService.EXTRA_VALUE, v))
                }
            }
        }
    }
}
