package com.island.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import kotlin.math.roundToInt

// Маленькое окно для новой заметки (в оверлее клавиатура не работает, поэтому отдельное окно).
class NoteActivity : Activity() {
    private fun dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)
        title = "Новая заметка"

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(8), dp(16), dp(12))

        val et = EditText(this)
        et.hint = "Заметка"
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        et.minLines = 2
        et.maxLines = 6
        et.setTextColor(Color.WHITE)
        col.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val save = Button(this)
        save.text = "Сохранить"
        save.setOnClickListener {
            val t = et.text.toString().trim()
            if (t.isNotEmpty()) {
                val list = Prefs.notes(this)
                list.add(0, t)
                Prefs.saveNotes(this, list)
                IslandController.current?.refreshNotes()
            }
            finish()
        }
        col.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(col)
    }
}
