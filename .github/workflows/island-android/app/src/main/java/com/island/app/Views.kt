package com.island.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

// Сам остров: размер задаётся кодом (tw, th) и плавно анимируется.
class PillView(c: Context) : FrameLayout(c) {
    var tw = 0
    var th = 0
    var onOutside: (() -> Unit)? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cw = MeasureSpec.makeMeasureSpec(tw, MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(th, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility != GONE) v.measure(cw, ch)
        }
        setMeasuredDimension(tw, th)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            onOutside?.invoke()
            return true
        }
        return super.onTouchEvent(event)
    }
}

// Кнопки, нарисованные кодом: не нужны картинки.
class IconView(c: Context, var kind: Int) : View(c) {
    companion object {
        const val PLAY = 0
        const val PAUSE = 1
        const val NEXT = 2
        const val PREV = 3
        const val CLOSE = 4
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()

    init {
        isClickable = true
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = min(w, h) * 0.26f
        val cx = w / 2
        val cy = h / 2
        p.alpha = if (isPressed) 120 else 255
        p.style = Paint.Style.FILL
        when (kind) {
            PLAY -> {
                path.reset()
                path.moveTo(cx - s * 0.8f, cy - s)
                path.lineTo(cx - s * 0.8f, cy + s)
                path.lineTo(cx + s, cy)
                path.close()
                canvas.drawPath(path, p)
            }
            PAUSE -> {
                canvas.drawRoundRect(cx - s * 0.85f, cy - s, cx - s * 0.2f, cy + s, 3f, 3f, p)
                canvas.drawRoundRect(cx + s * 0.2f, cy - s, cx + s * 0.85f, cy + s, 3f, 3f, p)
            }
            NEXT -> {
                path.reset()
                path.moveTo(cx - s, cy - s)
                path.lineTo(cx - s, cy + s)
                path.lineTo(cx + s * 0.6f, cy)
                path.close()
                canvas.drawPath(path, p)
                canvas.drawRoundRect(cx + s * 0.7f, cy - s, cx + s, cy + s, 2f, 2f, p)
            }
            PREV -> {
                path.reset()
                path.moveTo(cx + s, cy - s)
                path.lineTo(cx + s, cy + s)
                path.lineTo(cx - s * 0.6f, cy)
                path.close()
                canvas.drawPath(path, p)
                canvas.drawRoundRect(cx - s, cy - s, cx - s * 0.7f, cy + s, 2f, 2f, p)
            }
            CLOSE -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = resources.displayMetrics.density * 2f
                val k = s * 0.8f
                canvas.drawLine(cx - k, cy - k, cx + k, cy + k, p)
                canvas.drawLine(cx + k, cy - k, cx - k, cy + k, p)
            }
        }
    }
}

// Полоса прогресса: ползунок трека и полоска громкости/яркости.
class SeekView(c: Context) : View(c) {
    var progress = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var touchable = true
    var thumb = true
    var dragging = false
    var onSeek: ((Float) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x33, 255, 255, 255) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cy = height / 2f
        val r = (if (thumb) 2f else 4f) * d
        val pad = if (thumb) 6f * d else 0f
        val l = pad
        val rr = w - pad
        canvas.drawRoundRect(l, cy - r, rr, cy + r, r, r, track)
        val x = l + (rr - l) * progress
        if (x > l) canvas.drawRoundRect(l, cy - r, x, cy + r, r, r, fill)
        if (thumb) canvas.drawCircle(x, cy, 6f * d, fill)
    }

    private fun moveTo(e: MotionEvent) {
        val pad = 6f * d
        val span = width - 2 * pad
        if (span > 0) progress = (e.x - pad) / span
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!touchable) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                moveTo(e)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) moveTo(e)
            MotionEvent.ACTION_UP -> if (dragging) {
                moveTo(e)
                dragging = false
                onSeek?.invoke(progress)
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}

// Эквалайзер: четыре столбика, пока играет музыка.
class EqView(c: Context) : View(c) {
    var active = false
        set(v) {
            field = v
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val sp = floatArrayOf(5.1f, 7.3f, 6.2f, 8.4f)
    private val ph = floatArrayOf(0f, 1.7f, 3.1f, 4.6f)

    override fun onDraw(canvas: Canvas) {
        val t = System.nanoTime() / 1e9f
        val bw = 3f * d
        val gap = 3f * d
        val hgt = height.toFloat()
        for (i in 0 until 4) {
            val f = if (active) 0.25f + 0.75f * abs(sin(t * sp[i] + ph[i])) else 0.2f
            val hh = hgt * f
            val x = i * (bw + gap)
            canvas.drawRoundRect(x, (hgt - hh) / 2, x + bw, (hgt + hh) / 2, bw / 2, bw / 2, p)
        }
        if (active && isShown) postInvalidateOnAnimation()
    }
}
