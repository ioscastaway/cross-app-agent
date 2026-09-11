package com.ioscastaway.crossappagent.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
 * The bubble's face: one cut-out character per agent state, drawn straight onto the screen.
 *
 * No plate and no ring. The character already says what is happening — a microphone while
 * listening, an hourglass while working, a question mark when it needs an answer — so a coloured
 * disc behind it would only box in artwork that was drawn to stand on its own. All that is added is
 * a soft contact shadow, which is what keeps it readable over a light wallpaper.
 *
 * To use different art, drop `idle.png`, `listening.png`, `working.png`, `asking.png`, `done.png`
 * and `failed.png` into the directory [customDirHint] reports. Those stay on the device.
 */
class BubbleFaceView(context: Context) : View(context) {

    enum class Face(@param:DrawableRes val art: Int) {
        IDLE(R.drawable.face_idle),
        LISTENING(R.drawable.face_listening),
        WORKING(R.drawable.face_working),
        ASKING(R.drawable.face_asking),
        DONE(R.drawable.face_done),
        FAILED(R.drawable.face_failed),
    }

    var face: Face = Face.IDLE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var phase = 0f

    private val art = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }

    private val dst = Rect()
    private val ground = RectF()

    private val builtIn = HashMap<Face, Bitmap?>()
    private val custom = HashMap<Face, Bitmap?>()

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200
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
        context.getExternalFilesDir(CUSTOM_DIR)?.let { dir ->
            sequenceOf("png", "webp", "jpg", "jpeg")
                .map { File(dir, f.name.lowercase() + "." + it) }
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

        // A slow float, and a shadow that tightens as the character rises, so it reads as hovering
        // rather than as a sticker pasted on the screen.
        val lift = sin(phase * TWO_PI)
        val bob = lift * s * 0.022f

        val groundW = s * (0.30f - lift * 0.02f)
        val groundH = s * (0.045f - lift * 0.006f)
        val groundY = cy + s * 0.41f
        ground.set(cx - groundW, groundY - groundH, cx + groundW, groundY + groundH)
        shadow.alpha = (44 - lift * 10).roundToInt().coerceIn(0, 255)
        canvas.drawOval(ground, shadow)

        bitmapFor(face)?.let { bmp ->
            val half = s * 0.47f
            dst.set(
                (cx - half).roundToInt(),
                (cy + bob - half).roundToInt(),
                (cx + half).roundToInt(),
                (cy + bob + half).roundToInt(),
            )
            canvas.drawBitmap(bmp, null, dst, art)
        }
    }

    companion object {
        const val CUSTOM_DIR = "bubble"
        private const val TWO_PI = (Math.PI * 2).toFloat()

        /** Shown in the app so you know where to put your own art. */
        fun customDirHint(context: Context): String =
            context.getExternalFilesDir(CUSTOM_DIR)?.absolutePath ?: "(external storage unavailable)"
    }
}
