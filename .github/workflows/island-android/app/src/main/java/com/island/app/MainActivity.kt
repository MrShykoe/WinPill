package com.island.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

// Экран настроек: выдача разрешений и переключатели возможностей.
class MainActivity : Activity() {
    private val checks = ArrayList<Pair<TextView, () -> Boolean>>()
    private val themeChips = ArrayList<TextView>()

    private fun dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).roundToInt()

    private fun listenerOn(): Boolean =
        (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)

    private fun btOk(): Boolean =
        Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Color.parseColor("#0B0B0D"))

        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(36), dp(20), dp(40))
        sv.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        col.addView(label("Island", 28f, Color.WHITE, true))
        col.addView(label("Динамический остров для Android. Сделай три шага ниже, и он появится сверху экрана.", 14f, Color.parseColor("#9A9AA0"), false))

        col.addView(header("1. Разрешения"))
        col.addView(statusRow(
            "Доступ к уведомлениям",
            "Нужен для плеера и уведомлений в острове. На Android 13+ сначала разреши ограниченные настройки (кнопка ниже).",
            "Открыть", { listenerOn() }
        ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        col.addView(statusRow(
            "Поверх других окон",
            "Чтобы остров рисовался над приложениями.",
            "Открыть", { Settings.canDrawOverlays(this) }
        ) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(statusRow(
            "Ограниченные настройки (Android 13+)",
            "Если «Доступ к уведомлениям» серый: открой «О приложении», нажми ⋮ вверху справа и выбери «Разрешить ограниченные настройки».",
            "О приложении", null
        ) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        col.addView(statusRow(
            "Bluetooth (по желанию)",
            "Чтобы показывать, какие наушники подключились.",
            "Разрешить", { btOk() }
        ) {
            if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        })
        col.addView(statusRow(
            "Работа в фоне (по желанию)",
            "Убери ограничения батареи для Island, иначе некоторые прошивки могут выгружать остров.",
            "Открыть", null
        ) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })

        col.addView(header("2. Проверка"))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val b1 = button("Тест уведомления") { IslandController.current?.test() }
        val b2 = button("Скрыть / показать") { IslandController.current?.toggleHidden() }
        row.addView(b1, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(b2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)
        col.addView(label("Нажатие на остров раскрывает плеер, долгое нажатие открывает эти настройки. Плитку «Остров» можно добавить в шторку быстрых настроек.", 12f, Color.parseColor("#9A9AA0"), false))

        col.addView(header("3. Вид"))
        val trow = LinearLayout(this)
        trow.orientation = LinearLayout.HORIZONTAL
        val names = listOf("Чёрный", "Стекло", "Контур")
        for (i in names.indices) {
            val c = TextView(this)
            c.text = names[i]
            c.gravity = Gravity.CENTER
            c.textSize = 14f
            c.setPadding(0, dp(10), 0, dp(10))
            c.setOnClickListener {
                Prefs.setI(this, K.THEME, i)
                styleThemes()
                IslandController.current?.onSettingsChanged()
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(3), dp(6), dp(3), dp(6))
            trow.addView(c, lp)
            themeChips.add(c)
        }
        col.addView(trow)
        styleThemes()

        col.addView(label("Отступ сверху (под камеру-вырез)", 14f, Color.WHITE, false))
        val sb = SeekBar(this)
        sb.max = 80
        sb.progress = Prefs.i(this, K.OFFSET, 6)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    Prefs.setI(this@MainActivity, K.OFFSET, p)
                    IslandController.current?.onSettingsChanged()
                }
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        col.addView(sb)

        col.addView(header("4. Возможности"))
        col.addView(sw("Уведомления в острове", K.NOTIF))
        col.addView(sw("Громкость", K.VOLUME))
        col.addView(sw("Яркость", K.BRIGHT))
        col.addView(sw("Пауза / продолжение музыки", K.PLAYBACK))
        col.addView(sw("Эквалайзер", K.EQ))
        col.addView(sw("Цвет острова по обложке", K.TINT))
        col.addView(sw("Слова песни", K.LYRICS))
        col.addView(sw("Подсветка слов (караоке)", K.KARAOKE))
        col.addView(sw("Часы, когда музыка не играет", K.CLOCK))
        col.addView(sw("Зарядка и батарея", K.BATTERY))
        col.addView(sw("Wi-Fi и сеть", K.NETWORK))
        col.addView(sw("Bluetooth-устройства", K.BT))

        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        for ((tv, f) in checks) {
            val ok = f()
            tv.text = if (ok) "✓ включено" else "✗ не выдано"
            tv.setTextColor(if (ok) Color.parseColor("#5CD67B") else Color.parseColor("#FF6B6B"))
        }
        IslandController.current?.ensureShown()
    }

    private fun styleThemes() {
        val cur = Prefs.i(this, K.THEME, 0)
        for (i in themeChips.indices) {
            val g = GradientDrawable()
            g.cornerRadius = dp(12).toFloat()
            g.setColor(if (i == cur) Color.parseColor("#FFFFFF") else Color.parseColor("#1F1F24"))
            themeChips[i].background = g
            themeChips[i].setTextColor(if (i == cur) Color.BLACK else Color.WHITE)
        }
    }

    private fun label(t: String, size: Float, color: Int, bold: Boolean): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        v.setPadding(0, dp(4), 0, dp(4))
        if (bold) v.setTypeface(v.typeface, android.graphics.Typeface.BOLD)
        return v
    }

    private fun header(t: String): TextView {
        val v = label(t, 17f, Color.WHITE, true)
        v.setPadding(0, dp(22), 0, dp(6))
        return v
    }

    private fun button(t: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun sw(t: String, key: String): Switch {
        val s = Switch(this)
        s.text = t
        s.textSize = 15f
        s.setTextColor(Color.WHITE)
        s.setPadding(0, dp(10), 0, dp(10))
        s.isChecked = Prefs.b(this, key)
        s.setOnCheckedChangeListener { _, v ->
            Prefs.setB(this, key, v)
            IslandController.current?.onSettingsChanged()
        }
        return s
    }

    private fun statusRow(
        title: String,
        desc: String,
        btn: String,
        check: (() -> Boolean)?,
        onClick: () -> Unit
    ): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(8), 0, dp(8))
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val tt = label(title, 15f, Color.WHITE, true)
        top.addView(tt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (check != null) {
            val st = TextView(this)
            st.textSize = 13f
            top.addView(st)
            checks.add(Pair(st, check))
        }
        box.addView(top)
        box.addView(label(desc, 12f, Color.parseColor("#9A9AA0"), false))
        box.addView(button(btn, onClick))
        return box
    }
}
