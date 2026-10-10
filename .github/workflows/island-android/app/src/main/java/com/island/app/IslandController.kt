package com.island.app

import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Весь остров: окно поверх экрана, плеер, уведомления, всплывашки, слова песен, карман, быстрые действия.
class IslandController(private val svc: NotificationListenerService) {
    companion object {
        @Volatile
        var current: IslandController? = null
    }

    private enum class Mode { MINI, FULL, OSD, NOTIF, POCKET, ACTIONS }

    private val ctx: Context = svc
    private val h = Handler(Looper.getMainLooper())
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerCn = ComponentName(ctx, IslandListener::class.java)
    private val density = ctx.resources.displayMetrics.density

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val GRAY = Color.parseColor("#333333")
    private val DIM = Color.argb(0x40, 255, 255, 255)
    private val CHIP = Color.argb(0x1F, 255, 255, 255)

    private fun dp(v: Number): Int = (v.toFloat() * density).roundToInt()

    private fun screenW(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.width()
        else ctx.resources.displayMetrics.widthPixels

    // ---------------- состояние ----------------
    private var mode = Mode.MINI
    private var temp: Mode? = null
    private var expanded = false
    private var pocketOpen = false
    private var actionsOpen = false
    private var attached = false
    private var ready = false
    var hidden = false
        private set
    private var osdW = 0
    private var notifClick: (() -> Unit)? = null

    // ---------------- вьюхи ----------------
    private lateinit var pill: PillView
    private lateinit var bg: GradientDrawable
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var views: Map<Mode, View>

    private lateinit var mini: LinearLayout
    private lateinit var coverMini: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniEq: EqView

    private lateinit var full: LinearLayout
    private lateinit var coverBig: ImageView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var fullEq: EqView
    private lateinit var timeL: TextView
    private lateinit var timeR: TextView
    private lateinit var seek: SeekView
    private lateinit var playBtn: IconView

    private lateinit var osd: LinearLayout
    private lateinit var osdIcon: TextView
    private lateinit var osdBar: SeekView
    private lateinit var osdText: TextView
    private lateinit var osdValue: TextView

    private lateinit var notif: LinearLayout
    private lateinit var notifIcon: ImageView
    private lateinit var notifTitle: TextView
    private lateinit var notifBody: TextView

    private lateinit var pocket: LinearLayout
    private lateinit var pocketTitle: TextView
    private lateinit var noteList: LinearLayout
    private lateinit var noteEmpty: TextView

    private lateinit var actions: LinearLayout

    // ---------------- помощники для вёрстки ----------------
    private fun text(size: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.textSize = size
        t.setTextColor(color)
        t.isSingleLine = true
        t.ellipsize = TextUtils.TruncateAt.END
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun rounded(color: Int, r: Float): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = r
        return g
    }

    private fun roundClip(v: View, r: Int) {
        v.clipToOutline = true
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, r.toFloat())
            }
        }
    }

    private fun lpw(w: Int, hh: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, hh, weight)

    private fun coverView(size: Int, radius: Int): ImageView {
        val iv = ImageView(ctx)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.background = rounded(GRAY, radius.toFloat())
        roundClip(iv, radius)
        return iv
    }

    private fun chip(label: String, textSize: Float = 12f, onClick: () -> Unit): TextView {
        val t = TextView(ctx)
        t.text = label
        t.textSize = textSize
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.isSingleLine = true
        t.ellipsize = TextUtils.TruncateAt.END
        t.background = rounded(CHIP, dp(15).toFloat())
        t.setPadding(dp(8), 0, dp(8), 0)
        t.setOnClickListener { v ->
            v.scaleX = 0.9f
            v.scaleY = 0.9f
            v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2f)).start()
            onClick()
        }
        return t
    }

    // ---------------- сборка интерфейса ----------------
    private fun buildUi() {
        pill = PillView(ctx)
        bg = GradientDrawable()
        bg.setColor(Color.BLACK)
        bg.cornerRadius = dp(28).toFloat() // при малой высоте само сжимается до «таблетки»
        pill.background = bg
        pill.clipToOutline = true
        pill.outlineProvider = ViewOutlineProvider.BACKGROUND
        pill.onOutside = {
            if (expanded || pocketOpen || actionsOpen) {
                expanded = false
                pocketOpen = false
                actionsOpen = false
                go()
            }
        }

        // --- свёрнутый вид ---
        mini = LinearLayout(ctx)
        mini.orientation = LinearLayout.HORIZONTAL
        mini.gravity = Gravity.CENTER_VERTICAL
        mini.setPadding(dp(8), 0, dp(14), 0)
        coverMini = coverView(22, dp(11))
        mini.addView(coverMini, lpw(dp(22), dp(22)))
        miniTitle = text(12f)
        miniTitle.setPadding(dp(10), 0, dp(10), 0)
        mini.addView(miniTitle, lpw(0, WRAP, 1f))
        miniEq = EqView(ctx)
        mini.addView(miniEq, lpw(dp(21), dp(18)))
        mini.setOnClickListener {
            expanded = true
            pocketOpen = false
            actionsOpen = false
            go()
        }
        mini.setOnLongClickListener {
            openApp()
            true
        }

        // --- развёрнутый вид ---
        full = LinearLayout(ctx)
        full.orientation = LinearLayout.VERTICAL
        full.setPadding(dp(16), dp(14), dp(16), dp(8))
        val r1 = LinearLayout(ctx)
        r1.orientation = LinearLayout.HORIZONTAL
        r1.gravity = Gravity.CENTER_VERTICAL
        coverBig = coverView(54, dp(14))
        r1.addView(coverBig, lpw(dp(54), dp(54)))
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, dp(8), 0)
        titleText = text(16f, Color.WHITE, true)
        artistText = text(13f, Color.parseColor("#999999"))
        col.addView(titleText, lpw(MATCH, WRAP))
        col.addView(artistText, lpw(MATCH, WRAP))
        r1.addView(col, lpw(0, WRAP, 1f))
        fullEq = EqView(ctx)
        r1.addView(fullEq, lpw(dp(21), dp(18)))
        val closeBtn = IconView(ctx, IconView.CLOSE)
        closeBtn.setOnClickListener {
            expanded = false
            go()
        }
        r1.addView(closeBtn, lpw(dp(34), dp(34)))
        full.addView(r1, lpw(MATCH, dp(54)))

        val r2 = LinearLayout(ctx)
        r2.orientation = LinearLayout.HORIZONTAL
        r2.gravity = Gravity.CENTER_VERTICAL
        timeL = text(11f, Color.parseColor("#888888"))
        timeL.text = "0:00"
        timeL.minWidth = dp(34)
        seek = SeekView(ctx)
        seek.onSeek = { f ->
            val d = durationMs
            if (d > 0) mc?.transportControls?.seekTo((f * d).toLong())
        }
        timeR = text(11f, Color.parseColor("#888888"))
        timeR.text = "0:00"
        timeR.minWidth = dp(34)
        timeR.gravity = Gravity.END
        r2.addView(timeL, lpw(WRAP, WRAP))
        r2.addView(seek, lpw(0, dp(22), 1f))
        r2.addView(timeR, lpw(WRAP, WRAP))
        val r2p = lpw(MATCH, dp(26))
        r2p.topMargin = dp(8)
        full.addView(r2, r2p)

        val r3 = FrameLayout(ctx)
        val ctl = LinearLayout(ctx)
        ctl.orientation = LinearLayout.HORIZONTAL
        val prev = IconView(ctx, IconView.PREV)
        prev.setOnClickListener { mc?.transportControls?.skipToPrevious() }
        playBtn = IconView(ctx, IconView.PLAY)
        playBtn.setOnClickListener {
            val c = mc
            if (c != null) {
                if (playing) c.transportControls.pause() else c.transportControls.play()
            }
        }
        val next = IconView(ctx, IconView.NEXT)
        next.setOnClickListener { mc?.transportControls?.skipToNext() }
        ctl.addView(prev, lpw(dp(44), dp(44)))
        ctl.addView(playBtn, lpw(dp(44), dp(44)))
        ctl.addView(next, lpw(dp(44), dp(44)))
        r3.addView(ctl, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        r3.addView(
            chip("Действия") {
                actionsOpen = true
                pocketOpen = false
                go()
            },
            FrameLayout.LayoutParams(dp(78), dp(30), Gravity.START or Gravity.CENTER_VERTICAL)
        )
        r3.addView(
            chip("Карман") {
                pocketOpen = true
                actionsOpen = false
                renderNotes()
                go()
            },
            FrameLayout.LayoutParams(dp(68), dp(30), Gravity.END or Gravity.CENTER_VERTICAL)
        )
        full.addView(r3, lpw(MATCH, dp(46)))

        // --- всплывашка: громкость, яркость, пауза, сеть ---
        osd = LinearLayout(ctx)
        osd.orientation = LinearLayout.HORIZONTAL
        osd.gravity = Gravity.CENTER_VERTICAL
        osd.setPadding(dp(16), 0, dp(16), 0)
        osdIcon = TextView(ctx)
        osdIcon.textSize = 18f
        osdIcon.setTextColor(Color.WHITE)
        osdIcon.gravity = Gravity.CENTER
        osd.addView(osdIcon, lpw(dp(30), WRAP))
        osdBar = SeekView(ctx)
        osdBar.touchable = false
        osdBar.thumb = false
        val bp = lpw(0, dp(14), 1f)
        bp.leftMargin = dp(10)
        bp.rightMargin = dp(10)
        osd.addView(osdBar, bp)
        osdText = text(13f)
        osdText.visibility = View.GONE
        val tp = lpw(0, WRAP, 1f)
        tp.leftMargin = dp(10)
        osd.addView(osdText, tp)
        osdValue = text(14f, Color.WHITE, true)
        osdValue.minWidth = dp(28)
        osdValue.gravity = Gravity.END
        osd.addView(osdValue, lpw(WRAP, WRAP))

        // --- уведомление ---
        notif = LinearLayout(ctx)
        notif.orientation = LinearLayout.HORIZONTAL
        notif.gravity = Gravity.CENTER_VERTICAL
        notif.setPadding(dp(14), 0, dp(14), 0)
        notifIcon = coverView(40, dp(12))
        notif.addView(notifIcon, lpw(dp(40), dp(40)))
        val nc = LinearLayout(ctx)
        nc.orientation = LinearLayout.VERTICAL
        nc.setPadding(dp(12), 0, 0, 0)
        notifTitle = text(14f, Color.WHITE, true)
        notifBody = text(13f, Color.parseColor("#A0A0A0"))
        nc.addView(notifTitle, lpw(MATCH, WRAP))
        nc.addView(notifBody, lpw(MATCH, WRAP))
        notif.addView(nc, lpw(0, WRAP, 1f))
        notif.setOnClickListener {
            try {
                notifClick?.invoke()
            } catch (e: Exception) {
            }
            h.removeCallbacks(endTemp)
            endTemp.run()
        }

        // --- карман (заметки) ---
        pocket = LinearLayout(ctx)
        pocket.orientation = LinearLayout.VERTICAL
        pocket.setPadding(dp(16), dp(10), dp(16), dp(12))
        val ph = LinearLayout(ctx)
        ph.orientation = LinearLayout.HORIZONTAL
        ph.gravity = Gravity.CENTER_VERTICAL
        pocketTitle = text(14f, Color.WHITE, true)
        pocketTitle.setPadding(dp(4), 0, 0, 0)
        ph.addView(pocketTitle, lpw(0, WRAP, 1f))
        val addP = lpw(dp(96), dp(30))
        addP.rightMargin = dp(6)
        ph.addView(chip("＋ Заметка") { startActivitySafe(Intent(ctx, NoteActivity::class.java)) }, addP)
        val pClose = IconView(ctx, IconView.CLOSE)
        pClose.setOnClickListener {
            pocketOpen = false
            go()
        }
        ph.addView(pClose, lpw(dp(34), dp(34)))
        pocket.addView(ph, lpw(MATCH, dp(34)))
        val holder = FrameLayout(ctx)
        val hs = HorizontalScrollView(ctx)
        hs.isHorizontalScrollBarEnabled = false
        noteList = LinearLayout(ctx)
        noteList.orientation = LinearLayout.HORIZONTAL
        hs.addView(noteList, FrameLayout.LayoutParams(WRAP, MATCH))
        holder.addView(hs, FrameLayout.LayoutParams(MATCH, MATCH))
        noteEmpty = TextView(ctx)
        noteEmpty.textSize = 13f
        noteEmpty.setTextColor(Color.parseColor("#888888"))
        noteEmpty.text = "Заметок пока нет. Нажми «＋ Заметка»"
        holder.addView(noteEmpty, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        val hp = lpw(MATCH, 0, 1f)
        hp.topMargin = dp(8)
        pocket.addView(holder, hp)

        // --- быстрые действия ---
        actions = LinearLayout(ctx)
        actions.orientation = LinearLayout.VERTICAL
        actions.setPadding(dp(16), dp(10), dp(16), dp(12))
        val ah = LinearLayout(ctx)
        ah.orientation = LinearLayout.HORIZONTAL
        ah.gravity = Gravity.CENTER_VERTICAL
        val at = text(14f, Color.WHITE, true)
        at.text = "Быстрые действия"
        at.setPadding(dp(4), 0, 0, 0)
        ah.addView(at, lpw(0, WRAP, 1f))
        val aClose = IconView(ctx, IconView.CLOSE)
        aClose.setOnClickListener {
            actionsOpen = false
            go()
        }
        ah.addView(aClose, lpw(dp(34), dp(34)))
        actions.addView(ah, lpw(MATCH, dp(34)))
        buildActions()

        views = mapOf(
            Mode.MINI to mini, Mode.FULL to full, Mode.OSD to osd,
            Mode.NOTIF to notif, Mode.POCKET to pocket, Mode.ACTIONS to actions
        )
        for ((_, v) in views) {
            pill.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
            v.visibility = View.GONE
            v.alpha = 0f
        }
        mini.visibility = View.VISIBLE
        mini.alpha = 1f
    }

    private fun buildActions() {
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        fun add(label: String, act: () -> Unit) {
            labels.add(label)
            acts.add(act)
        }
        add("Фонарик") {
            closeActions()
            toggleTorch()
        }
        add("Звук вкл/выкл") {
            closeActions()
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
        }
        add("Калькулятор") {
            closeActions()
            startActivitySafe(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR))
        }
        add("Камера") {
            closeActions()
            startActivitySafe(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        }
        add("Wi-Fi") {
            closeActions()
            startActivitySafe(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        add("Настройки") {
            closeActions()
            openApp()
        }
        var idx = 0
        for (row in 0 until 2) {
            val rowL = LinearLayout(ctx)
            rowL.orientation = LinearLayout.HORIZONTAL
            for (col in 0 until 3) {
                val act = acts[idx]
                val c = chip(labels[idx], 12f) { act() }
                idx++
                val p = lpw(0, MATCH, 1f)
                p.setMargins(dp(3), dp(3), dp(3), dp(3))
                rowL.addView(c, p)
            }
            actions.addView(rowL, lpw(MATCH, 0, 1f))
        }
    }

    private fun closeActions() {
        actionsOpen = false
        go()
    }

    private fun startActivitySafe(i: Intent) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
        }
    }

    private fun openApp() {
        startActivitySafe(Intent(ctx, MainActivity::class.java))
    }

    private var torchOn = false
    private fun toggleTorch() {
        try {
            val cmgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var id: String? = null
            for (cid in cmgr.cameraIdList) {
                if (cmgr.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                    id = cid
                    break
                }
            }
            if (id == null) return
            torchOn = !torchOn
            cmgr.setTorchMode(id, torchOn)
            showToast("🔦", if (torchOn) "Фонарик включён" else "Фонарик выключен", null)
        } catch (e: Exception) {
        }
    }

    // ---------------- режимы и размеры ----------------
    private fun rest(): Mode = when {
        pocketOpen -> Mode.POCKET
        actionsOpen -> Mode.ACTIONS
        expanded -> Mode.FULL
        else -> Mode.MINI
    }

    private val endTemp = Runnable {
        temp = null
        setMode(rest())
    }

    // Действие пользователя: сбрасывает всплывашку и показывает обычный режим.
    private fun go() {
        h.removeCallbacks(endTemp)
        temp = null
        setMode(rest())
    }

    private fun showTemp(m: Mode, ms: Long) {
        temp = m
        h.removeCallbacks(endTemp)
        setMode(m)
        h.postDelayed(endTemp, ms)
    }

    private fun sizeFor(m: Mode): Pair<Int, Int> {
        val maxW = screenW() - dp(24)
        return when (m) {
            Mode.MINI -> Pair(miniW(), dp(36))
            Mode.FULL -> Pair(min(dp(340), maxW), dp(166))
            Mode.OSD -> Pair(min(if (osdW > 0) osdW else dp(250), maxW), dp(44))
            Mode.NOTIF -> Pair(min(dp(340), maxW), dp(66))
            Mode.POCKET -> Pair(min(dp(340), maxW), dp(150))
            Mode.ACTIONS -> Pair(min(dp(340), maxW), dp(150))
        }
    }

    private fun miniW(): Int {
        val tw = miniTitle.paint.measureText(miniTitle.text.toString())
        var extra = dp(8 + 14 + 20)
        if (coverMini.visibility == View.VISIBLE) extra += dp(22)
        if (miniEq.visibility == View.VISIBLE) extra += dp(21)
        val maxW = min(dp(300), screenW() - dp(40))
        return (tw + extra).toInt().coerceIn(dp(120), max(dp(120), maxW))
    }

    private fun setMode(m: Mode) {
        mode = m
        val (w, hh) = sizeFor(m)
        animateSize(w, hh)
        for ((k, v) in views) {
            if (k == m) {
                if (v.visibility != View.VISIBLE) {
                    v.alpha = 0f
                    v.visibility = View.VISIBLE
                }
                v.animate().alpha(1f).setStartDelay(80).setDuration(260).start()
            } else if (v.visibility == View.VISIBLE) {
                v.animate().alpha(0f).setStartDelay(0).setDuration(120).withEndAction {
                    if (mode != k) v.visibility = View.GONE
                }.start()
            }
        }
    }

    private var sizeAnim: ValueAnimator? = null

    private fun setPillSize(w: Int, hh: Int) {
        pill.tw = w
        pill.th = hh
        pill.requestLayout()
    }

    private fun animateSize(w: Int, hh: Int) {
        sizeAnim?.cancel()
        val w0 = if (pill.tw > 0) pill.tw else w
        val h0 = if (pill.th > 0) pill.th else hh
        if (w0 == w && h0 == hh) {
            setPillSize(w, hh)
            return
        }
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 460
        a.interpolator = OvershootInterpolator(0.8f)
        a.addUpdateListener { va ->
            val f = va.animatedValue as Float
            setPillSize((w0 + (w - w0) * f).toInt(), (h0 + (hh - h0) * f).toInt())
        }
        sizeAnim = a
        a.start()
    }

    private fun resizeMini() {
        if (mode == Mode.MINI && temp == null) {
            val (w, hh) = sizeFor(Mode.MINI)
            animateSize(w, hh)
        }
    }

    // ---------------- внешний вид ----------------
    private var curBg = Color.BLACK
    private var bgAnim: ValueAnimator? = null

    private fun applyLook() {
        when (Prefs.i(ctx, K.THEME, 0)) {
            1 -> { // Стекло
                bg.colors = intArrayOf(Color.argb(0xD8, 0x34, 0x34, 0x3A), Color.argb(0xD8, 0x0A, 0x0A, 0x0C))
                bg.orientation = GradientDrawable.Orientation.TOP_BOTTOM
                bg.setStroke(dp(1), Color.argb(0x60, 255, 255, 255))
            }
            2 -> { // Контур
                bg.setColor(Color.BLACK)
                bg.setStroke(dp(1.5f), Color.argb(0xE6, 255, 255, 255))
            }
            else -> { // Чёрный (+ оттенок от обложки)
                bg.setColor(curBg)
                bg.setStroke(0, Color.TRANSPARENT)
            }
        }
    }

    private fun setBgColor(target: Int) {
        if (Prefs.i(ctx, K.THEME, 0) != 0) return
        bgAnim?.cancel()
        val a = ValueAnimator.ofArgb(curBg, target)
        a.duration = 500
        a.addUpdateListener { va ->
            curBg = va.animatedValue as Int
            bg.setColor(curBg)
        }
        bgAnim = a
        a.start()
    }

    private fun tintOf(b: Bitmap?): Int {
        if (b == null) return Color.BLACK
        return try {
            val px = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)
            val hsv = FloatArray(3)
            Color.colorToHSV(px, hsv)
            hsv[1] = min(hsv[1], 0.8f)
            hsv[2] = min(hsv[2], 0.30f)
            Color.HSVToColor(hsv)
        } catch (e: Exception) {
            Color.BLACK
        }
    }

    fun onSettingsChanged() {
        h.post {
            if (!this::pill.isInitialized) return@post
            applyLook()
            lp.y = dp(Prefs.i(ctx, K.OFFSET, 6))
            if (attached) {
                try {
                    wm.updateViewLayout(pill, lp)
                } catch (e: Exception) {
                }
            } else attach()
            if (!Prefs.b(ctx, K.LYRICS) && lyricMode) setLyricMode(false)
            else if (Prefs.b(ctx, K.LYRICS) && !lyricMode && curTitle.isNotEmpty() && lyr == null) loadLyrics()
            setBgColor(if (Prefs.b(ctx, K.TINT) && playing) tintOf(cover) else Color.BLACK)
            updateMini()
            resizeMini()
        }
    }

    fun refreshNotes() {
        h.post { renderNotes() }
    }

    fun ensureShown() {
        h.post { attach() }
    }

    fun toggleHidden() {
        hidden = !hidden
        h.post { pill.visibility = if (hidden) View.GONE else View.VISIBLE }
    }

    fun test() {
        h.post {
            ready = true
            val icon = try {
                ctx.packageManager.getApplicationIcon(ctx.packageName)
            } catch (e: Exception) {
                null
            }
            showNotif("Island", "Тест уведомления", "Так выглядят уведомления в острове", icon, null)
        }
    }

    // ---------------- запуск / остановка ----------------
    fun start() {
        buildUi()
        lp = WindowManager.LayoutParams(
            WRAP, WRAP,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = dp(Prefs.i(ctx, K.OFFSET, 6))
        lp.title = "Island"
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        applyLook()
        notesCache.clear()
        notesCache.addAll(Prefs.notes(ctx))
        renderNotes()
        updateMini()
        val (w, hh) = sizeFor(Mode.MINI)
        setPillSize(w, hh)
        attach()
        initMedia()
        registerAll()
        h.post(loop)
        h.postDelayed({ ready = true }, 2500)
    }

    fun stop() {
        h.removeCallbacksAndMessages(null)
        unregisterAll()
        for (c in ctrls) {
            try {
                c.unregisterCallback(mcb)
            } catch (e: Exception) {
            }
        }
        ctrls = emptyList()
        try {
            msm.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) {
        }
        sizeAnim?.cancel()
        bgAnim?.cancel()
        if (attached) {
            try {
                wm.removeView(pill)
            } catch (e: Exception) {
            }
            attached = false
        }
        if (current === this) current = null
    }

    private fun attach() {
        if (attached) return
        if (!Settings.canDrawOverlays(ctx)) return
        try {
            wm.addView(pill, lp)
            attached = true
        } catch (e: Exception) {
        }
    }

    // ---------------- плеер ----------------
    private var mc: MediaController? = null
    private var ctrls: List<MediaController> = emptyList()
    private var curTitle = ""
    private var curArtist = ""
    private var curKey = ""
    private var playing = false
    private var durationMs = 0L
    private var cover: Bitmap? = null
    private var quiet = true // не показывать «пауза/играет» при смене источника

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        onSessions(list)
    }

    private val mcb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            pick()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            pick()
        }

        override fun onSessionDestroyed() {
            try {
                onSessions(msm.getActiveSessions(listenerCn))
            } catch (e: Exception) {
            }
        }
    }

    private fun initMedia() {
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerCn)
            onSessions(msm.getActiveSessions(listenerCn))
        } catch (e: SecurityException) {
            goIdle()
        } catch (e: Exception) {
            goIdle()
        }
    }

    private fun onSessions(list: List<MediaController>?) {
        for (c in ctrls) {
            try {
                c.unregisterCallback(mcb)
            } catch (e: Exception) {
            }
        }
        ctrls = list ?: emptyList()
        for (c in ctrls) {
            try {
                c.registerCallback(mcb, h)
            } catch (e: Exception) {
            }
        }
        pick()
    }

    private fun pick() {
        var best: MediaController? = null
        for (c in ctrls) {
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
                best = c
                break
            }
        }
        if (best == null) {
            val curTok = mc?.sessionToken
            for (c in ctrls) {
                if (curTok != null && c.sessionToken == curTok) {
                    best = c
                    break
                }
            }
        }
        if (best == null) best = ctrls.firstOrNull()
        if (best?.sessionToken != mc?.sessionToken) quiet = true
        mc = best
        refreshMedia()
    }

    private fun positionNow(): Long {
        val st = mc?.playbackState ?: return 0L
        var p = st.position
        if (st.state == PlaybackState.STATE_PLAYING) {
            val dt = SystemClock.elapsedRealtime() - st.lastPositionUpdateTime
            p += (dt * st.playbackSpeed).toLong()
        }
        return max(0L, p)
    }

    private fun setCovers() {
        coverMini.setImageBitmap(cover)
        coverBig.setImageBitmap(cover)
    }

    private fun refreshMedia() {
        val c = mc
        if (c == null) {
            goIdle()
            return
        }
        val md = c.metadata
        val st = c.playbackState
        val t = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val a = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        if (t.isEmpty()) {
            goIdle()
            return
        }
        val wasPlaying = playing
        playing = st?.state == PlaybackState.STATE_PLAYING
        durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        val key = "$a|$t"
        val trackChanged = key != curKey
        val nb = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (trackChanged) {
            curKey = key
            curTitle = t
            curArtist = a
            cover = nb
            setCovers()
            lyr = null
            setLyricMode(false)
            loadLyrics()
        } else if (nb != null && nb !== cover) {
            cover = nb
            setCovers()
        }
        setBgColor(if (Prefs.b(ctx, K.TINT) && playing) tintOf(cover) else Color.BLACK)

        if (!quiet && wasPlaying != playing && !trackChanged && mode == Mode.MINI &&
            Prefs.b(ctx, K.PLAYBACK)
        ) {
            showToast(if (playing) "▶" else "⏸", t, null)
        }
        quiet = false

        miniEq.active = playing
        fullEq.active = playing
        playBtn.kind = if (playing) IconView.PAUSE else IconView.PLAY
        playBtn.invalidate()
        titleText.text = t
        artistText.text = a
        updateMini()
        resizeMini()
        updateProgress()
        kick()
    }

    private fun goIdle() {
        playing = false
        curTitle = ""
        curArtist = ""
        curKey = ""
        durationMs = 0
        cover = null
        setCovers()
        lyr = null
        setLyricMode(false)
        miniEq.active = false
        fullEq.active = false
        playBtn.kind = IconView.PLAY
        playBtn.invalidate()
        titleText.text = "Ничего не играет"
        artistText.text = ""
        setBgColor(Color.BLACK)
        updateMini()
        resizeMini()
        updateProgress()
    }

    private fun fmt(sec: Long): String = "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"

    private fun updateProgress() {
        val d = durationMs
        val p = positionNow()
        if (!seek.dragging) seek.progress = if (d > 0) p.toFloat() / d else 0f
        timeL.text = fmt(p / 1000)
        timeR.text = if (d > 0) fmt(d / 1000) else "0:00"
    }

    // ---------------- свёрнутая строка: часы / название / слова ----------------
    private var clockMode = false
    private val clockFmt = SimpleDateFormat("HH:mm  EEE, d MMM", Locale("ru"))

    private fun setMiniText(s: String) {
        if (miniTitle.text.toString() != s) {
            miniTitle.text = s
            resizeMini()
        }
    }

    private fun updateMini() {
        val idle = mc == null || curTitle.isEmpty()
        clockMode = idle && Prefs.b(ctx, K.CLOCK)
        if (clockMode) {
            coverMini.visibility = View.GONE
            miniEq.visibility = View.GONE
            miniTitle.paint.shader = null
            setMiniText(clockFmt.format(Date()))
        } else {
            coverMini.visibility = View.VISIBLE
            miniEq.visibility = if (Prefs.b(ctx, K.EQ)) View.VISIBLE else View.GONE
            if (!lyricMode) {
                miniTitle.paint.shader = null
                setMiniText(if (idle) "Ничего не играет" else curTitle)
            }
        }
    }

    // ---------------- слова песни ----------------
    private var lyricMode = false
    private var lyr: List<LrcLine>? = null
    private var lyrIdx = -1
    private var lineT0 = -1.0
    private var lineT1 = 0.0
    private var waveW = 10f

    private fun loadLyrics() {
        if (!Prefs.b(ctx, K.LYRICS) || curTitle.isBlank()) return
        val title = curTitle
        val artist = curArtist
        val dur = durationMs / 1000.0
        Thread {
            val l = try {
                Lyrics.get(title, artist, dur)
            } catch (e: Exception) {
                null
            }
            h.post {
                if (title == curTitle && mc != null && l != null && Prefs.b(ctx, K.LYRICS)) {
                    lyr = l
                    setLyricMode(true)
                }
            }
        }.start()
    }

    private fun setLyricMode(on: Boolean) {
        if (on) {
            lyricMode = true
            lyrIdx = -2
            kick()
        } else {
            if (!lyricMode) return
            lyricMode = false
            lineT0 = -1.0
            miniTitle.paint.shader = null
            miniTitle.invalidate()
            updateMini()
            resizeMini()
        }
    }

    private fun lyricTick() {
        val l = lyr ?: return
        val t = positionNow() / 1000.0 + 0.15
        var idx = -1
        for (i in l.indices) {
            if (l[i].t <= t) idx = i else break
        }
        if (idx != lyrIdx) {
            lyrIdx = idx
            setLine(idx)
        }
        if (lineT0 >= 0) updateWave(t)
    }

    private fun setLine(idx: Int) {
        val l = lyr ?: return
        val line = if (idx >= 0) l[idx].s else ""
        if (line.isBlank()) {
            miniTitle.paint.shader = null
            lineT0 = -1.0
            setMiniText(curTitle)
        } else {
            setMiniText(line)
            if (Prefs.b(ctx, K.KARAOKE)) {
                lineT0 = l[idx].t
                val nxt = if (idx + 1 < l.size) l[idx + 1].t else lineT0 + 4
                lineT1 = lineT0 + (nxt - lineT0).coerceIn(0.8, 7.0) * 0.88
                waveW = max(10f, miniTitle.paint.measureText(line))
                updateWave(-1.0)
            } else {
                lineT0 = -1.0
                miniTitle.paint.shader = null
            }
        }
        miniTitle.translationY = dp(9).toFloat()
        miniTitle.animate().translationY(0f).setDuration(380).setInterpolator(OvershootInterpolator(1.2f)).start()
    }

    // Волна: текст проявляется слева направо мягкой полосой света вслед за голосом.
    private fun updateWave(t: Double) {
        if (lineT0 < 0) return
        val frac = if (t <= lineT0) 0.0 else ((t - lineT0) / (lineT1 - lineT0)).coerceIn(0.0, 1.0)
        val x = frac.toFloat() * waveW
        val feather = dp(14).toFloat()
        val len = waveW + 8f
        val cx = -feather + x / waveW * (waveW + 2 * feather)
        val a = ((cx - feather) / len).coerceIn(0f, 1f)
        val b = ((cx + feather) / len).coerceIn(0f, 1f)
        miniTitle.paint.shader = LinearGradient(
            0f, 0f, len, 0f,
            intArrayOf(Color.WHITE, Color.WHITE, DIM, DIM),
            floatArrayOf(0f, a, b, 1f),
            Shader.TileMode.CLAMP
        )
        miniTitle.invalidate()
    }

    // ---------------- главный цикл ----------------
    private val loop = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (e: Exception) {
            }
            val d = when {
                lyricMode && playing -> 50L
                mode == Mode.FULL && playing -> 250L
                else -> 1000L
            }
            h.postDelayed(this, d)
        }
    }

    private fun kick() {
        h.removeCallbacks(loop)
        h.post(loop)
    }

    private fun tick() {
        if (clockMode) setMiniText(clockFmt.format(Date()))
        if (lyricMode) lyricTick()
        if (mode == Mode.FULL) updateProgress()
    }

    // ---------------- всплывашки ----------------
    private fun showToast(glyph: String, text: String?, value: Float?) {
        if (!ready) return
        osdIcon.text = glyph
        if (value != null) {
            osdBar.visibility = View.VISIBLE
            osdText.visibility = View.GONE
            osdBar.progress = value
            osdValue.visibility = View.VISIBLE
            osdValue.text = (value * 100).roundToInt().toString()
            osdW = dp(250)
        } else {
            val s = text ?: ""
            osdBar.visibility = View.GONE
            osdText.visibility = View.VISIBLE
            osdText.text = s
            osdValue.visibility = View.GONE
            val need = osdText.paint.measureText(s).toInt() + dp(16 + 30 + 10 + 16 + 6)
            osdW = need.coerceIn(dp(150), max(dp(150), min(dp(330), screenW() - dp(24))))
        }
        showTemp(Mode.OSD, 1800)
    }

    private fun showNotif(app: String, title: String, body: String, icon: Drawable?, pi: PendingIntent?) {
        notifTitle.text = if (title.isNotEmpty()) title else app
        notifBody.text = if (title.isNotEmpty()) (if (body.isNotEmpty()) body else app) else body
        notifIcon.setImageDrawable(icon)
        notifClick = {
            try {
                pi?.send()
            } catch (e: Exception) {
            }
        }
        showTemp(Mode.NOTIF, 4500)
    }

    private var lastSig = ""
    private var lastSigAt = 0L

    fun onNotification(sbn: StatusBarNotification) {
        if (!Prefs.b(ctx, K.NOTIF)) return
        if (sbn.packageName == ctx.packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS) return
        val ex = n.extras ?: return
        if (ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val body = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString() ?: ""
        if (title.isEmpty() && body.isEmpty()) return
        val pm = ctx.packageManager
        val app = try {
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
        var icon: Drawable? = try {
            n.getLargeIcon()?.loadDrawable(ctx)
        } catch (e: Exception) {
            null
        }
        if (icon == null) {
            icon = try {
                pm.getApplicationIcon(sbn.packageName)
            } catch (e: Exception) {
                null
            }
        }
        val pi = n.contentIntent
        val sig = sbn.packageName + "|" + title + "|" + body
        h.post {
            val now = SystemClock.elapsedRealtime()
            if (sig == lastSig && now - lastSigAt < 5000) return@post
            lastSig = sig
            lastSigAt = now
            showNotif(app, title, body, icon, pi)
        }
    }

    // ---------------- карман: заметки ----------------
    private val notesCache = ArrayList<String>()

    private fun renderNotes() {
        notesCache.clear()
        notesCache.addAll(Prefs.notes(ctx))
        pocketTitle.text = "Заметки ${notesCache.size}"
        noteEmpty.visibility = if (notesCache.isEmpty()) View.VISIBLE else View.GONE
        noteList.removeAllViews()
        for (note in notesCache.toList()) {
            val t = TextView(ctx)
            t.text = note
            t.textSize = 12f
            t.setTextColor(Color.WHITE)
            t.maxLines = 3
            t.ellipsize = TextUtils.TruncateAt.END
            t.setPadding(dp(10), dp(8), dp(10), dp(8))
            t.background = rounded(CHIP, dp(14).toFloat())
            t.setOnClickListener {
                try {
                    val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("note", note))
                    showToast("📋", "Скопировано", null)
                } catch (e: Exception) {
                }
            }
            t.setOnLongClickListener {
                notesCache.remove(note)
                Prefs.saveNotes(ctx, notesCache)
                renderNotes()
                true
            }
            val p = lpw(dp(130), MATCH)
            p.rightMargin = dp(8)
            noteList.addView(t, p)
        }
    }

    // ---------------- громкость, яркость, батарея, сеть, Bluetooth ----------------
    private var lastVol = -1
    private var lastBr = -1
    private var plugged: Boolean? = null
    private var lastLow = 101
    private var netKey = ""
    private var regd = false

    private val volRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!Prefs.b(ctx, K.VOLUME)) return
            if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return
            val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val mx = max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            if (v == lastVol) return
            val first = lastVol < 0
            lastVol = v
            if (first && !ready) return
            showToast(if (v == 0) "🔇" else "🔊", null, v.toFloat() / mx)
        }
    }

    private val brObs = object : ContentObserver(h) {
        override fun onChange(selfChange: Boolean) {
            if (!Prefs.b(ctx, K.BRIGHT)) return
            val b = try {
                Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            } catch (e: Exception) {
                return
            }
            if (b == lastBr) return
            lastBr = b
            showToast("🔆", null, b / 255f)
        }
    }

    private val batRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val sc = max(1, i.getIntExtra(BatteryManager.EXTRA_SCALE, 100))
            val pct = if (lvl >= 0) lvl * 100 / sc else -1
            val pl = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val prev = plugged
            plugged = pl
            if (!Prefs.b(ctx, K.BATTERY)) return
            if (prev != null && prev != pl) {
                showToast(
                    if (pl) "⚡" else "🔋",
                    (if (pl) "Зарядка подключена" else "Зарядка отключена") + (if (pct >= 0) ", $pct%" else ""),
                    null
                )
            }
            if (!pl && pct in 0..20) {
                if (pct <= 10 && lastLow > 10) {
                    lastLow = 10
                    showToast("❗", "Заряд $pct%. Подключи зарядку", null)
                } else if (lastLow > 20) {
                    lastLow = 20
                    showToast("🪫", "Заряд $pct%", null)
                }
            }
            if (pct > 25 || pl) lastLow = 101
        }
    }

    private val btRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!Prefs.b(ctx, K.BT)) return
            val connected = i.action == BluetoothDevice.ACTION_ACL_CONNECTED
            @Suppress("DEPRECATION")
            val dev = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val rawName: String? = try {
                dev?.name
            } catch (e: SecurityException) {
                null
            }
            val name = rawName ?: "устройство"
            showToast("🎧", (if (connected) "Подключено: " else "Отключено: ") + name, null)
        }
    }

    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val k = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
                else -> return
            }
            onNet(k)
        }

        override fun onLost(network: Network) {
            onNet("none")
        }
    }

    private fun onNet(k: String) {
        if (k == netKey) return
        val first = netKey.isEmpty()
        netKey = k
        if (first || !Prefs.b(ctx, K.NETWORK)) return
        when (k) {
            "none" -> showToast("🚫", "Нет подключения", null)
            "wifi" -> showToast("📶", "Подключено: Wi-Fi", null)
            "cell" -> showToast("📶", "Мобильная сеть", null)
            else -> showToast("🌐", "Сеть подключена", null)
        }
    }

    private fun reg(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(r, f)
    }

    private fun registerAll() {
        if (regd) return
        regd = true
        try {
            reg(volRx, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        } catch (e: Exception) {
        }
        try {
            reg(batRx, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {
        }
        try {
            val f = IntentFilter()
            f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            reg(btRx, f)
        } catch (e: Exception) {
        }
        try {
            ctx.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, brObs
            )
        } catch (e: Exception) {
        }
        try {
            cm.registerDefaultNetworkCallback(netCb, h)
        } catch (e: Exception) {
        }
    }

    private fun unregisterAll() {
        if (!regd) return
        regd = false
        for (r in listOf(volRx, batRx, btRx)) {
            try {
                ctx.unregisterReceiver(r)
            } catch (e: Exception) {
            }
        }
        try {
            ctx.contentResolver.unregisterContentObserver(brObs)
        } catch (e: Exception) {
        }
        try {
            cm.unregisterNetworkCallback(netCb)
        } catch (e: Exception) {
        }
    }
}
