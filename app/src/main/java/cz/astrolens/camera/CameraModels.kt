package cz.astrolens.camera

import android.hardware.camera2.CameraMetadata
import android.util.Size

/** Description of one back camera for the lens picker. */
class LensInfo(
    val id: String,
    val label: String,
    val focalMm: Float,
    val equivMm: Float,
    val fovLongDeg: Double
)

/** Everything the UI needs to know about the opened camera. */
class CameraCaps(
    val id: String,
    val hasManual: Boolean,
    val hasRaw: Boolean,
    val isoMin: Int,
    val isoMax: Int,
    val expMinNs: Long,
    val expMaxNs: Long,
    val maxFrameDurationNs: Long,
    val minFocusDiopter: Float,
    val evMin: Int,
    val evMax: Int,
    val evStepStops: Float,
    val zoomMin: Float,
    val zoomMax: Float,
    val awbModes: IntArray,
    val afModes: IntArray,
    val oisSupported: Boolean,
    val oisOffSupported: Boolean,
    val noiseReductionModes: IntArray,
    val sensorOrientation: Int,
    val previewSize: Size,
    val jpegSize: Size,
    val rawSize: Size?,
    val focalMm: Float,
    val equivMm: Float,
    val aperture: Float,
    val pixelPitchUm: Float,
    val fovLongDeg: Double,
    val minFrameDurPreviewNs: Long,
    val minFrameDurStillNs: Long
) {
    /** Exposure times offered on the shutter slider (nanoseconds, ascending). */
    val exposureSteps: LongArray by lazy { buildExposureSteps() }

    /** ISO values offered on the ISO slider (ascending). */
    val isoSteps: IntArray by lazy { buildIsoSteps() }

    /** Aspect ratio (long side / short side) of the preview and still output. */
    val aspect: Double get() = previewSize.width.toDouble() / previewSize.height.toDouble()

    fun clampExposure(ns: Long): Long {
        val hi = if (maxFrameDurationNs > 0) minOf(expMaxNs, maxFrameDurationNs) else expMaxNs
        return ns.coerceIn(expMinNs, hi)
    }

    fun clampIso(iso: Int): Int = iso.coerceIn(isoMin, isoMax)

    fun clampZoom(z: Float): Float = z.coerceIn(zoomMin, zoomMax)

    fun nearestExposureIndex(ns: Long): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in exposureSteps.indices) {
            val d = Math.abs(Math.log(exposureSteps[i].toDouble() / ns.toDouble()))
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun nearestIsoIndex(iso: Int): Int {
        var best = 0
        var bestD = Int.MAX_VALUE
        for (i in isoSteps.indices) {
            val d = Math.abs(isoSteps[i] - iso)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun buildExposureSteps(): LongArray {
        val secs = doubleArrayOf(
            1.0 / 8000, 1.0 / 6400, 1.0 / 5000, 1.0 / 4000, 1.0 / 3200, 1.0 / 2500, 1.0 / 2000, 1.0 / 1600,
            1.0 / 1250, 1.0 / 1000, 1.0 / 800, 1.0 / 640, 1.0 / 500, 1.0 / 400, 1.0 / 320, 1.0 / 250,
            1.0 / 200, 1.0 / 160, 1.0 / 125, 1.0 / 100, 1.0 / 80, 1.0 / 60, 1.0 / 50, 1.0 / 40, 1.0 / 30,
            1.0 / 25, 1.0 / 20, 1.0 / 15, 1.0 / 13, 1.0 / 10, 1.0 / 8, 1.0 / 6, 1.0 / 5, 1.0 / 4, 0.3, 0.4,
            0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0, 2.5, 3.2, 4.0, 5.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0,
            30.0, 45.0, 60.0, 90.0, 120.0
        )
        val hi = if (maxFrameDurationNs > 0) minOf(expMaxNs, maxFrameDurationNs) else expMaxNs
        val out = ArrayList<Long>()
        for (s in secs) {
            val ns = (s * 1e9).toLong()
            if (ns in expMinNs..hi) out.add(ns)
        }
        if (out.isEmpty() || out.first() > expMinNs * 1.2) out.add(0, expMinNs)
        if (out.last() < hi * 0.9) out.add(hi)
        return out.toLongArray()
    }

    private fun buildIsoSteps(): IntArray {
        val std = intArrayOf(
            50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500,
            3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 32000, 51200
        )
        val out = ArrayList<Int>()
        for (v in std) if (v in isoMin..isoMax) out.add(v)
        if (out.isEmpty() || out.first() > isoMin * 1.15) out.add(0, isoMin)
        if (out.last() < isoMax * 0.9) out.add(isoMax)
        return out.toIntArray()
    }
}

/** Mutable capture settings edited by the UI. */
class CamSettings {
    var iso: Int = 1600
    var exposureNs: Long = 10_000_000_000L
    var autoExposure: Boolean = false
    var evSteps: Int = 0

    var focusManual: Boolean = true

    /** Focus position as a diopter value (0 = infinity). */
    var focusDiopter: Float = 0f
    var awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO
    var zoom: Float = 1f

    var saveJpeg: Boolean = true
    var saveRaw: Boolean = false
    var jpegQuality: Int = 95
    var annotated: Boolean = false

    /** 0 = off, 1 = fast, 2 = high quality (CaptureRequest.NOISE_REDUCTION_MODE values). */
    var noiseReduction: Int = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
    var ois: Boolean = false

    /** Preview brightness emulation: 0 = 1/30 s, 1 = 1/8 s, 2 = 1/2 s. */
    var previewMode: Int = 1

    var timerSec: Int = 0
    var seriesCount: Int = 1
    var seriesGapMs: Long = 1000L

    companion object {
        val PREVIEW_MAX_NS = longArrayOf(33_333_333L, 125_000_000L, 500_000_000L)
        val AWB_NAMES = arrayOf("Auto", "Denní", "Žárovka", "Zářivka", "Zataženo", "Soumrak", "Stín")
        val AWB_MODES = intArrayOf(
            CameraMetadata.CONTROL_AWB_MODE_AUTO,
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            CameraMetadata.CONTROL_AWB_MODE_TWILIGHT,
            CameraMetadata.CONTROL_AWB_MODE_SHADE
        )
    }
}

/** Data about one exposure, kept until its pictures are written. */
class FrameMeta(
    val index: Int,
    val total: Int,
    val iso: Int,
    val exposureNs: Long,
    val timeMs: Long,
    val sensorOrientation: Int,
    val zoom: Float,
    /** Opaque sky snapshot (orientation + time) taken when the shutter opened. */
    val snapshot: Any?
)

interface CameraListener {
    fun onCameraReady(caps: CameraCaps)
    fun onCameraError(message: String)
    fun onWarning(message: String)
    fun onExposureStarted(index: Int, total: Int, exposureNs: Long)
    fun onExposureFinished(index: Int, total: Int)
    fun onPhotoSaved(where: String, index: Int, total: Int)
    fun onSeriesFinished(count: Int, cancelled: Boolean)

    /** Called on the camera thread when the shutter opens; returns an opaque sky snapshot. */
    fun takeSnapshot(): Any?
}
