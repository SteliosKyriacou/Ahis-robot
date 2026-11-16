package ai.rosey.cherry

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Rosey-Cherry's animated face, drawn entirely on a Canvas (no image assets). */
class FaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Activity { IDLE, LISTENING, THINKING, TALKING }

    /** happy, excited, curious, silly, calm, surprised, sad */
    var mood: String = "happy"
    var activity: Activity = Activity.IDLE

    /** How loud Ahilya is right now (0..1); the listening glow grows with it. */
    var voiceLevel: Float = 0f
        set(value) { field = value; if (value > shownLevel) shownLevel = value }
    private var shownLevel = 0f

    private val start = SystemClock.uptimeMillis()
    private var nextBlink = start + 2500L
    private var blinkStart = 0L

    private val bg = Color.parseColor("#FFF3F6")
    private val faceColor = Color.parseColor("#F2546B")
    private val faceShade = Color.parseColor("#D93E57")
    private val ink = Color.parseColor("#3A2230")
    private val cheek = Color.parseColor("#FF9BB0")
    private val stem = Color.parseColor("#5B8C3A")
    private val leaf = Color.parseColor("#7BC24F")
    private val listenGlow = Color.parseColor("#7FD3FF")
    private val tongue = Color.parseColor("#FF7A93")
    private val mouthInside = Color.parseColor("#7A1F33")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val t = (now - start) / 1000f

        canvas.drawColor(bg)

        val cx = width / 2f
        // Head + stem + leaf is about 2.6 radii tall: size it to fit both portrait and landscape.
        val r = min(width * 0.34f, height / 3.1f)
        // Gentle floating bob; bouncier when excited.
        val bobAmp = if (mood == "excited") 0.04f else 0.015f
        val cy = height / 2f + r * 0.28f + sin(t * 2f) * r * bobAmp

        // Listening: soft pulsing rings around the head.
        shownLevel = maxOf(voiceLevel, shownLevel * 0.9f) // smooth fall-off
        if (activity == Activity.LISTENING) {
            // Solid halo that swells with her voice, so she can see Rosey hearing her.
            fill.color = listenGlow
            fill.alpha = (70 + 120 * shownLevel).toInt()
            canvas.drawCircle(cx, cy, r * (1.04f + 0.22f * shownLevel), fill)
            for (i in 0..2) {
                val phase = ((t * 0.8f) + i / 3f) % 1f
                fill.alpha = ((1f - phase) * 90).toInt()
                canvas.drawCircle(cx, cy, r * (1.02f + phase * 0.35f + 0.2f * shownLevel), fill)
            }
            fill.alpha = 255
        } else {
            shownLevel = 0f
        }

        drawStemAndLeaf(canvas, cx, cy, r, t)

        // Head (a cherry!) with a little shine.
        fill.color = faceShade
        canvas.drawCircle(cx, cy + r * 0.03f, r, fill)
        fill.color = faceColor
        canvas.drawCircle(cx, cy, r * 0.97f, fill)
        fill.color = Color.WHITE
        fill.alpha = 70
        canvas.drawOval(cx - r * 0.62f, cy - r * 0.72f, cx - r * 0.22f, cy - r * 0.48f, fill)
        fill.alpha = 255

        // Cheeks
        fill.color = cheek
        val cheekY = cy + r * 0.25f
        val cheekGrow = if (mood == "excited" || mood == "silly") 1.2f else 1f
        canvas.drawOval(cx - r * 0.72f, cheekY - r * 0.08f * cheekGrow, cx - r * 0.42f, cheekY + r * 0.08f * cheekGrow, fill)
        canvas.drawOval(cx + r * 0.42f, cheekY - r * 0.08f * cheekGrow, cx + r * 0.72f, cheekY + r * 0.08f * cheekGrow, fill)

        drawEyes(canvas, cx, cy, r, now, t)
        drawMouth(canvas, cx, cy, r, t)

        if (activity == Activity.THINKING) drawThinkingDots(canvas, cx, cy, r, t)
        if (mood == "excited" || mood == "surprised") drawSparkles(canvas, cx, cy, r, t)

        postInvalidateOnAnimation()
    }

    private fun drawStemAndLeaf(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        val sway = sin(t * 1.5f) * r * 0.03f
        stroke.color = stem
        stroke.strokeWidth = r * 0.07f
        path.reset()
        path.moveTo(cx, cy - r * 0.92f)
        path.quadTo(cx + r * 0.05f, cy - r * 1.3f, cx + r * 0.32f + sway, cy - r * 1.45f)
        canvas.drawPath(path, stroke)

        fill.color = leaf
        val lx = cx + r * 0.22f + sway
        val ly = cy - r * 1.38f
        path.reset()
        path.moveTo(lx, ly)
        path.quadTo(lx + r * 0.3f, ly - r * 0.38f, lx + r * 0.62f, ly - r * 0.12f)
        path.quadTo(lx + r * 0.3f, ly + r * 0.18f, lx, ly)
        canvas.drawPath(path, fill)
    }

    private fun drawEyes(canvas: Canvas, cx: Float, cy: Float, r: Float, now: Long, t: Float) {
        // Blinking
        if (now >= nextBlink && blinkStart == 0L) blinkStart = now
        var open = 1f
        if (blinkStart != 0L) {
            val p = (now - blinkStart) / 180f
            open = abs(1f - 2f * p).coerceIn(0f, 1f)
            if (p >= 1f) {
                blinkStart = 0L
                nextBlink = now + 2000L + Random.nextLong(3500L)
            }
        }

        val ex = r * 0.34f
        val ey = cy - r * 0.12f
        val ew = r * 0.17f
        var eh = r * 0.22f
        if (mood == "surprised") eh *= 1.25f

        // Where the pupils look.
        var lookX = 0f
        var lookY = 0f
        when {
            activity == Activity.THINKING -> { lookX = r * 0.05f; lookY = -r * 0.07f }
            mood == "curious" -> { lookX = sin(t * 0.9f) * r * 0.05f; lookY = -r * 0.03f }
            activity == Activity.LISTENING -> { lookY = r * 0.01f }
            else -> lookX = sin(t * 0.4f) * r * 0.025f
        }

        for (side in listOf(-1f, 1f)) {
            val x = cx + side * ex
            if (mood == "calm" || mood == "silly" && side > 0) {
                // Happy closed eye (an upside-down U), or a silly wink.
                stroke.color = ink
                stroke.strokeWidth = r * 0.06f
                rect.set(x - ew, ey - eh * 0.5f, x + ew, ey + eh * 0.9f)
                canvas.drawArc(rect, 200f, 140f, false, stroke)
                continue
            }
            val h = eh * open.coerceAtLeast(0.08f)
            fill.color = ink
            canvas.drawOval(x - ew + lookX, ey - h + lookY, x + ew + lookX, ey + h + lookY, fill)
            if (open > 0.5f) {
                fill.color = Color.WHITE
                canvas.drawCircle(x - ew * 0.3f + lookX, ey - h * 0.4f + lookY, ew * 0.32f, fill)
                canvas.drawCircle(x + ew * 0.3f + lookX, ey + h * 0.3f + lookY, ew * 0.14f, fill)
            }
        }

        // Eyebrows for a few moods.
        stroke.color = ink
        stroke.strokeWidth = r * 0.045f
        val browY = ey - eh - r * 0.1f
        when (mood) {
            "curious" -> {
                canvas.drawLine(cx - ex - ew, browY, cx - ex + ew, browY, stroke)
                canvas.drawLine(cx + ex - ew, browY - r * 0.06f, cx + ex + ew, browY - r * 0.1f, stroke)
            }
            "sad" -> {
                canvas.drawLine(cx - ex - ew, browY + r * 0.02f, cx - ex + ew, browY - r * 0.05f, stroke)
                canvas.drawLine(cx + ex - ew, browY - r * 0.05f, cx + ex + ew, browY + r * 0.02f, stroke)
            }
            "surprised" -> {
                rect.set(cx - ex - ew, browY - r * 0.12f, cx - ex + ew, browY + r * 0.02f)
                canvas.drawArc(rect, 200f, 140f, false, stroke)
                rect.set(cx + ex - ew, browY - r * 0.12f, cx + ex + ew, browY + r * 0.02f)
                canvas.drawArc(rect, 200f, 140f, false, stroke)
            }
        }
    }

    private fun drawMouth(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        val my = cy + r * 0.33f
        val mw = r * 0.3f
        stroke.color = ink
        stroke.strokeWidth = r * 0.06f

        if (activity == Activity.TALKING) {
            // Wobbly open mouth that looks like syllables.
            val talk = (abs(sin(t * 11f)) * 0.6f + abs(sin(t * 7.3f + 1f)) * 0.4f)
            val h = r * (0.05f + 0.16f * talk)
            fill.color = mouthInside
            canvas.drawOval(cx - mw * 0.75f, my - h * 0.4f, cx + mw * 0.75f, my + h, fill)
            fill.color = tongue
            canvas.drawOval(cx - mw * 0.4f, my + h * 0.35f, cx + mw * 0.4f, my + h * 0.95f, fill)
            stroke.strokeWidth = r * 0.04f
            canvas.drawOval(cx - mw * 0.75f, my - h * 0.4f, cx + mw * 0.75f, my + h, stroke)
            return
        }

        when (mood) {
            "surprised" -> {
                fill.color = mouthInside
                canvas.drawOval(cx - r * 0.1f, my - r * 0.06f, cx + r * 0.1f, my + r * 0.16f, fill)
            }
            "sad" -> {
                path.reset()
                path.moveTo(cx - mw * 0.6f, my + r * 0.08f)
                path.quadTo(cx, my - r * 0.04f, cx + mw * 0.6f, my + r * 0.08f)
                canvas.drawPath(path, stroke)
            }
            "excited" -> {
                // Big open grin.
                path.reset()
                path.moveTo(cx - mw, my - r * 0.03f)
                path.quadTo(cx, my + r * 0.38f, cx + mw, my - r * 0.03f)
                path.close()
                fill.color = mouthInside
                canvas.drawPath(path, fill)
                stroke.strokeWidth = r * 0.04f
                canvas.drawPath(path, stroke)
            }
            "silly" -> {
                path.reset()
                path.moveTo(cx - mw, my)
                path.quadTo(cx, my + r * 0.22f, cx + mw, my)
                canvas.drawPath(path, stroke)
                fill.color = tongue
                canvas.drawOval(cx + mw * 0.05f, my + r * 0.04f, cx + mw * 0.6f, my + r * 0.24f, fill)
            }
            else -> {
                val depth = if (activity == Activity.THINKING) 0.08f else 0.2f
                path.reset()
                path.moveTo(cx - mw, my)
                path.quadTo(cx, my + r * depth, cx + mw, my)
                canvas.drawPath(path, stroke)
            }
        }
    }

    private fun drawThinkingDots(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        fill.color = ink
        for (i in 0..2) {
            val lift = sin(t * 5f - i * 0.8f).coerceAtLeast(0f) * r * 0.08f
            canvas.drawCircle(cx + r * (0.9f + i * 0.2f), cy - r * (1.0f + i * 0.05f) - lift, r * 0.055f, fill)
        }
    }

    private fun drawSparkles(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        fill.color = Color.parseColor("#FFD24D")
        for (i in 0..4) {
            val a = (i / 5f) * 2f * PI.toFloat() + t * 0.6f
            val dist = r * 1.25f
            val s = r * (0.05f + 0.03f * abs(sin(t * 3f + i)))
            drawStar(canvas, cx + cos(a) * dist, cy + sin(a) * dist, s)
        }
    }

    private fun drawStar(canvas: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        for (k in 0 until 8) {
            val rad = if (k % 2 == 0) s * 2f else s * 0.6f
            val a = k * PI.toFloat() / 4f
            val px = x + cos(a) * rad
            val py = y + sin(a) * rad
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        canvas.drawPath(path, fill)
    }
}
