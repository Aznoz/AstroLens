package cz.astrolens.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import cz.astrolens.astro.SkyEngine
import cz.astrolens.camera.FrameMeta
import cz.astrolens.util.Fmt
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Draws the sky overlay onto a saved JPEG using the orientation captured at shutter time. */
object Annotator {
    fun annotate(bytes: ByteArray, meta: FrameMeta, engine: SkyEngine, options: OverlayOptions, quality: Int): ByteArray? {
        val snap = meta.snapshot as? SkySnapshot ?: return null
        val cat = engine.catalog ?: return null

        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opt = BitmapFactory.Options()
        opt.inSampleSize = if (bounds.outWidth.toLong() * bounds.outHeight > 13_000_000L) 2 else 1
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt) ?: return null

        val m = Matrix()
        m.postRotate(meta.sensorOrientation.toFloat())
        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (upright !== raw) raw.recycle()
        val bmp = if (upright.isMutable) upright else upright.copy(Bitmap.Config.ARGB_8888, true)
        if (bmp !== upright) upright.recycle()

        val frame = engine.frameAt(snap.timeMs, snap.latDeg, snap.lonDeg)
        val f = SimpleDateFormat("d. M. yyyy HH:mm:ss", Locale("cs", "CZ"))
        f.timeZone = TimeZone.getDefault()
        val hud = snap.place + "\n" + f.format(Date(snap.timeMs)) + "\n" + Fmt.exposure(meta.exposureNs) + " · ISO " + meta.iso +
            " · zoom " + Fmt.f1(meta.zoom.toDouble()) + "×"

        val canvas = Canvas(bmp)
        val paint = Paint()
        paint.isAntiAlias = true
        // Text sizes are tuned for ~400 px wide screens, so scale to the photo.
        val density = bmp.width / 400f
        val input = RenderInput(cat, frame, snap.worldToDevice, snap.cameraAxis, snap.fovLongDeg, snap.zoom, options, null, null, hud)
        SkyRenderer().render(canvas, bmp.width.toFloat(), bmp.height.toFloat(), density, input)

        val out = ByteArrayOutputStream(bytes.size)
        bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(60, 100), out)
        bmp.recycle()
        return out.toByteArray()
    }
}
