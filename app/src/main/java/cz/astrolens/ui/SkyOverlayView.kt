package cz.astrolens.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import cz.astrolens.astro.AstroMath
import cz.astrolens.astro.SkyEngine
import cz.astrolens.sensor.OrientationProvider
import cz.astrolens.util.Fmt
import kotlin.math.hypot

/** Transparent view over the camera preview that draws the sky for the current orientation. */
class SkyOverlayView(
    context: Context,
    private val engine: SkyEngine,
    private val orientation: OrientationProvider
) : View(context) {
    private val renderer = SkyRenderer()
    private val density = context.resources.displayMetrics.density
    private val msg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density
        setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
    }

    var options = OverlayOptions()
    var fovLongDeg = 70.0
    var zoom = 1f
    var selected: SkyTarget? = null
    var goto: SkyTarget? = null
    var running = true

    /** Called when the user taps an object (or empty sky, with null). */
    var onPick: ((SkyTarget?) -> Unit)? = null

    /** Called with the relative scale factor of a pinch gesture. */
    var onPinch: ((Float) -> Unit)? = null

    /** Extra HUD text shown under the standard lines. */
    var hudExtra: String = ""

    /** Latest values for the status line (read by the activity). */
    @Volatile
    var centerAltDeg = 0.0

    @Volatile
    var centerAzDeg = 0.0

    private var pinching = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            pinching = true
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            onPinch?.invoke(d.scaleFactor)
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            postDelayed({ pinching = false }, 150)
        }
    })

    override fun onDraw(canvas: Canvas) {
        if (running) postInvalidateDelayed(33)
        if (!options.overlayOn) return
        val cat = engine.catalog
        if (cat == null) {
            canvas.drawText("Načítám katalog hvězd…", 16f * density, 60f * density, msg)
            return
        }
        val frame = engine.frame()
        val w2d = orientation.worldToDevice()
        val axis = orientation.cameraAxis()
        centerAltDeg = AstroMath.altitudeDeg(axis)
        centerAzDeg = AstroMath.azimuthDeg(axis)
        val effFov = Math.toDegrees(2.0 * Math.atan(Math.tan(Math.toRadians(fovLongDeg) / 2.0) * options.fovScale / zoom))
        val hud = StringBuilder()
        hud.append("Azimut ").append(Fmt.f0(centerAzDeg)).append("° ").append(Fmt.compass(centerAzDeg))
        hud.append(" · výška ").append(Fmt.signedDeg(centerAltDeg))
        hud.append("\nZáběr ").append(Fmt.f1(effFov)).append("° · zoom ").append(Fmt.f1(zoom.toDouble())).append("×")
        if (hudExtra.isNotEmpty()) hud.append('\n').append(hudExtra)
        val input = RenderInput(cat, frame, w2d, axis, fovLongDeg, zoom, options, goto, selected, hud.toString())
        renderer.render(canvas, width.toFloat(), height.toFloat(), density, input)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (e.pointerCount > 1 || pinching) return true
        if (e.actionMasked == MotionEvent.ACTION_DOWN) return true
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val dt = e.eventTime - e.downTime
            if (dt < 400) pick(e.x, e.y)
            return true
        }
        return true
    }

    private fun pick(x: Float, y: Float) {
        var best = -1
        var bestScore = Float.MAX_VALUE
        val radius = 40f * density
        for (i in 0 until renderer.pickN) {
            val d = hypot(renderer.pickX[i] - x, renderer.pickY[i] - y)
            if (d > radius) continue
            val score = d - renderer.pickPrio[i] * 6f * density
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        if (best < 0) {
            onPick?.invoke(null)
        } else {
            onPick?.invoke(SkyTarget(renderer.pickKind[best], renderer.pickIndex[best]))
        }
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        invalidate()
    }
}
