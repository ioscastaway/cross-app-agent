package com.ioscastaway.crossappagent.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.annotation.DrawableRes
import com.ioscastaway.crossappagent.R
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The bubble's face: a white plate with one Fluent Emoji on it, a ring in the state colour, and a
 * little motion so the thing never looks frozen.
 *
 * Art is Microsoft's Fluent Emoji (MIT) rather than anything drawn here, and the licence travels
 * with the repository in `third_party/`. To use your own character instead, drop images into the
 * app's external files directory — see [customDirHint]. Those stay on the device.
 */
class BubbleFaceView(context: Context) : View(context) {

    enum class Face(@param:DrawableRes val art: Int, val tint: Int) {
        IDLE(R.drawable.face_idle, 0xFF7C6BD6.toInt()),
        LISTENING(R.drawable.face_listening, 0xFFE05252.toInt()),
        WORKING(R.drawable.face_working, 0xFF2E7CD6.toInt()),
        ASKING(R.drawable.face_asking, 0xFFE0A32E.toInt()),
        DONE(R.drawable.face_done, 0xFF3AA35C.toInt()),
        FAILED(R.drawable.face_failed, 0xFFB54B4B.toInt()),
    }

    var face: Face = Face.IDLE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var phase = 0f

    private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLATE }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val art = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val arc = RectF()
    private val dst = Rect()

    private val builtIn = HashMap<Face, Bitmap?>()
    private val custom = HashMap<Face, Bitmap?>()

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ticker.start()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        super.onDetachedFromWindow()
    }

    /** Call after adding or replacing files in the custom directory. */
    fun reloadCustomArt() {
        custom.clear()
        invalidate()
    }

    private fun bitmapFor(f: Face): Bitmap? = custom.getOrPut(f) {
        val dir = context.getExternalFilesDir(CUSTOM_DIR)
        dir?.let { d ->
            sequenceOf("png", "webp", "jpg", "jpeg")
                .map { File(d, f.name.lowercase() + "." + it) }
                .firstOrNull(File::isFile)
                ?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
        }
    } ?: builtIn.getOrPut(f) {
        runCatching { BitmapFactory.decodeResource(resources, f.art) }.getOrNull()
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val r = s * 0.33f

        // A slow bob keeps the bubble alive without pulling the eye the way a spin would.
        val bob = sin(phase * TWO_PI) * s * 0.012f

        canvas.drawCircle(cx, cy + bob + s * 0.018f, r, shadow)
        canvas.drawCircle(cx, cy + bob, r, plate)

        ring.color = face.tint
        ring.strokeWidth = s * 0.028f
        canvas.drawCircle(cx, cy + bob, r - ring.strokeWidth / 2f, ring)

        bitmapFor(face)?.let { bmp ->
            val half = (r * 0.66f).roundToInt()
            dst.set(
                (cx - half).roundToInt(),
                (cy + bob - half).roundToInt(),
                (cx + half).roundToInt(),
                (cy + bob + half).roundToInt(),
            )
            canvas.drawBitmap(bmp, null, dst, art)
        }

        drawStateMotion(canvas, cx, cy + bob, r, s)
    }

    /** Motion outside the plate: a sweeping arc while working, expanding rings while listening. */
    private fun drawStateMotion(canvas: Canvas, cx: Float, cy: Float, r: Float, s: Float) {
        when (face) {
            Face.WORKING -> {
                ring.color = face.tint
                ring.strokeWidth = s * 0.042f
                val rr = r + s * 0.06f
                arc.set(cx - rr, cy - rr, cx + rr, cy + rr)
                canvas.drawArc(arc, phase * 360f, 100f, false, ring)
            }
            Face.LISTENING -> {
                ring.strokeWidth = s * 0.03f
                for (i in 0..1) {
                    val t = (phase + i * 0.5f) % 1f
                    ring.color = Color.argb(
                        ((1f - t) * 170).toInt(),
                        Color.red(face.tint), Color.green(face.tint), Color.blue(face.tint),
                    )
                    canvas.drawCircle(cx, cy, r + t * s * 0.16f, ring)
                }
            }
            else -> Unit
        }
    }

    companion object {
        const val CUSTOM_DIR = "bubble"
        private const val PLATE = 0xF2FFFFFF.toInt()
        private const val TWO_PI = (Math.PI * 2).toFloat()

        /** Shown in the app so you know where to put your own art. */
        fun customDirHint(context: Context): String =
            context.getExternalFilesDir(CUSTOM_DIR)?.absolutePath ?: "(external storage unavailable)"
    }
}
