package cz.astrolens.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.location.Location
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.Surface
import cz.astrolens.util.MediaSaver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Camera2 wrapper with full manual control: ISO, exposure, focus, white balance, zoom,
 * RAW (DNG) capture, self timer and interval series.
 */
class CameraController(
    private val context: Context,
    private val listener: CameraListener
) {
    val settings = CamSettings()

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    var caps: CameraCaps? = null
        private set
    private var chars: CameraCharacteristics? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var texture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var wantRawStream = false

    @Volatile
    var gpsLocation: Location? = null

    /** Optional hook that draws sky labels onto a JPEG; runs on a background thread. */
    @Volatile
    var annotator: ((ByteArray, FrameMeta) -> ByteArray?)? = null

    // series state (camera thread only)
    private var seriesActive = false
    private var seriesTotal = 0
    private var seriesStarted = 0
    private var seriesDone = 0
    private var seriesGapMs = 1000L
    private var capturing = false
    private var longExposure = false
    private var cancelled = false

    private val lock = Any()
    private val metaByTs = ConcurrentHashMap<Long, FrameMeta>()
    private val pendingResults = HashMap<Long, TotalCaptureResult>()
    private val pendingRaw = HashMap<Long, Image>()

    private val nextRunnable = Runnable { fireNext() }

    val isBusy: Boolean get() = seriesActive || capturing

    // ------------------------------------------------------------------ thread

    fun startThread() {
        if (thread != null) return
        val t = HandlerThread("AstroCam")
        t.start()
        thread = t
        handler = Handler(t.looper)
    }

    /** Closes the camera and stops the camera thread. */
    fun release() {
        val h = handler
        if (h != null) h.post { closeInternal() }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun post(block: () -> Unit) {
        val h = handler ?: return
        h.post {
            try {
                block()
            } catch (e: Exception) {
                reportError("Chyba fotoaparátu: " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun reportError(msg: String) {
        main.post { listener.onCameraError(msg) }
    }

    private fun warn(msg: String) {
        main.post { listener.onWarning(msg) }
    }

    // ------------------------------------------------------------------ lenses

    private class Geo(
        val focal: Float,
        val equiv: Float,
        val fovLongDeg: Double,
        val pitchUm: Float,
        val aperture: Float
    )

    private fun geometry(c: CameraCharacteristics): Geo? {
        val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: return null
        if (focals.isEmpty()) return null
        val focal = focals[0]
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val pix = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val activeLong = max(active.width(), active.height()).toFloat()
        val pixLong = if (pix != null) max(pix.width, pix.height).toFloat() else activeLong
        val physLong = max(phys.width, phys.height)
        val effLong = physLong * (activeLong / pixLong)
        val diag = sqrt(phys.width * phys.width + phys.height * phys.height)
        val equiv = focal * 43.27f / diag
        val fov = Math.toDegrees(2.0 * atan((effLong / (2.0 * focal))))
        val pitch = physLong / pixLong * 1000f
        val aps = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        val ap = if (aps != null && aps.isNotEmpty()) aps[0] else 1.8f
        return Geo(focal, equiv, fov, pitch, ap)
    }

    private fun lensLabel(equiv: Float): String {
        val name = when {
            equiv < 18f -> "Ultraširoký"
            equiv < 40f -> "Hlavní"
            equiv < 85f -> "Tele"
            else -> "Dlouhé tele"
        }
        return name + " · " + equiv.roundToInt() + " mm"
    }

    fun listLenses(): List<LensInfo> {
        val out = ArrayList<LensInfo>()
        try {
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val g = geometry(c) ?: continue
                out.add(LensInfo(id, lensLabel(g.equiv), g.focal, g.equiv, g.fovLongDeg))
            }
        } catch (e: Exception) {
            // no cameras available
        }
        out.sortBy { it.equivMm }
        return out
    }

    /** The lens closest to a classic 26 mm main camera. */
    fun defaultLensId(): String? {
        val lenses = listLenses()
        if (lenses.isEmpty()) return null
        var best = lenses[0]
        for (l in lenses) {
            if (Math.abs(l.equivMm - 26f) < Math.abs(best.equivMm - 26f)) best = l
        }
        return best.id
    }

    // ----------------------------------------------------------------- caps

    private fun buildCaps(id: String, c: CameraCharacteristics): CameraCaps {
        val capList = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val manual = capList.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val raw = capList.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 800)
        val expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(1_000_000L, 100_000_000L)
        val maxFrame = c.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: 0L
        val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        val evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val zoomRange = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        val awb = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)
        val af = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
        val nr = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: IntArray(0)
        val orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val geo = geometry(c) ?: Geo(5.5f, 26f, 70.0, 1.0f, 1.8f)

        val targetAspect = active.width().toDouble() / active.height().toDouble()
        val jpegSize = pickLargest(map.getOutputSizes(ImageFormat.JPEG), targetAspect)
        val previewSize = pickPreview(map.getOutputSizes(SurfaceTexture::class.java), targetAspect)
        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
        val rawSize = if (raw && rawSizes != null && rawSizes.isNotEmpty()) pickLargest(rawSizes, targetAspect) else null

        val minPrev = try { map.getOutputMinFrameDuration(SurfaceTexture::class.java, previewSize) } catch (e: Exception) { 33_333_333L }
        var minStill = try { map.getOutputMinFrameDuration(ImageFormat.JPEG, jpegSize) } catch (e: Exception) { 0L }
        if (rawSize != null) {
            val r = try { map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, rawSize) } catch (e: Exception) { 0L }
            if (r > minStill) minStill = r
        }

        return CameraCaps(
            id = id,
            hasManual = manual,
            hasRaw = raw && rawSize != null,
            isoMin = isoRange.lower,
            isoMax = isoRange.upper,
            expMinNs = expRange.lower,
            expMaxNs = expRange.upper,
            maxFrameDurationNs = maxFrame,
            minFocusDiopter = minFocus,
            evMin = evRange.lower,
            evMax = evRange.upper,
            evStepStops = if (evStep != null) evStep.toFloat() else 0.33f,
            zoomMin = if (zoomRange != null) zoomRange.lower else 1f,
            zoomMax = if (zoomRange != null) zoomRange.upper else 1f,
            awbModes = awb,
            afModes = af,
            oisSupported = ois.isNotEmpty() && ois.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON),
            oisOffSupported = ois.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF),
            noiseReductionModes = nr,
            sensorOrientation = orientation,
            previewSize = previewSize,
            jpegSize = jpegSize,
            rawSize = rawSize,
            focalMm = geo.focal,
            equivMm = geo.equiv,
            aperture = geo.aperture,
            pixelPitchUm = geo.pitchUm,
            fovLongDeg = geo.fovLongDeg,
            minFrameDurPreviewNs = minPrev,
            minFrameDurStillNs = minStill
        )
    }

    private fun aspectOf(s: Size): Double = s.width.toDouble() / s.height.toDouble()

    private fun pickLargest(sizes: Array<Size>?, targetAspect: Double): Size {
        val all = sizes ?: arrayOf(Size(1920, 1080))
        var best: Size? = null
        for (s in all) {
            if (Math.abs(aspectOf(s) - targetAspect) > 0.02) continue
            if (best == null || s.width.toLong() * s.height > best.width.toLong() * best.height) best = s
        }
        if (best == null) {
            for (s in all) {
                if (best == null || s.width.toLong() * s.height > best.width.toLong() * best.height) best = s
            }
        }
        return best!!
    }

    private fun pickPreview(sizes: Array<Size>?, targetAspect: Double): Size {
        val all = sizes ?: arrayOf(Size(1280, 720))
        var best: Size? = null
        for (s in all) {
            if (Math.abs(aspectOf(s) - targetAspect) > 0.02) continue
            if (s.width > 2048) continue
            if (best == null || s.width.toLong() * s.height > best.width.toLong() * best.height) best = s
        }
        if (best == null) {
            for (s in all) {
                if (s.width > 2048) continue
                if (best == null || s.width.toLong() * s.height > best.width.toLong() * best.height) best = s
            }
        }
        return best ?: all[0]
    }

    // ----------------------------------------------------------------- opening

    @SuppressLint("MissingPermission")
    fun open(lensId: String?, st: SurfaceTexture) {
        post {
            closeInternal()
            texture = st
            val id = lensId ?: defaultLensId()
            if (id == null) {
                reportError("Nebyl nalezen žádný zadní fotoaparát")
                return@post
            }
            val c = manager.getCameraCharacteristics(id)
            chars = c
            caps = buildCaps(id, c)
            wantRawStream = settings.saveRaw && (caps?.hasRaw == true)
            manager.openCamera(id, stateCallback, handler)
        }
    }

    /** Rebuilds the capture session, for example after switching RAW on or off. */
    fun rebuildSession() {
        post {
            if (device != null) {
                wantRawStream = settings.saveRaw && (caps?.hasRaw == true)
                createSession()
            }
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            post { createSession() }
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            device = null
            session = null
            reportError("Fotoaparát byl odpojen")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            device = null
            session = null
            reportError("Chyba fotoaparátu (kód $error)")
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession() {
        val d = device ?: return
        val c = caps ?: return
        val st = texture ?: return
        closeSessionOnly()
        st.setDefaultBufferSize(c.previewSize.width, c.previewSize.height)
        val ps = Surface(st)
        previewSurface = ps
        val jr = ImageReader.newInstance(c.jpegSize.width, c.jpegSize.height, ImageFormat.JPEG, 3)
        jr.setOnImageAvailableListener(jpegListener, handler)
        jpegReader = jr
        val surfaces = ArrayList<Surface>()
        surfaces.add(ps)
        surfaces.add(jr.surface)
        val rs = c.rawSize
        if (wantRawStream && rs != null) {
            val rr = ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 3)
            rr.setOnImageAvailableListener(rawListener, handler)
            rawReader = rr
            surfaces.add(rr.surface)
        }
        d.createCaptureSession(surfaces, sessionCallback, handler)
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(s: CameraCaptureSession) {
            session = s
            post { startPreviewInternal() }
            val c = caps
            if (c != null) main.post { listener.onCameraReady(c) }
        }

        override fun onConfigureFailed(s: CameraCaptureSession) {
            if (rawReader != null) {
                wantRawStream = false
                settings.saveRaw = false
                warn("Tento fotoaparát nezvládne RAW spolu s náhledem, DNG je vypnuto.")
                post { createSession() }
            } else {
                reportError("Nepodařilo se spustit náhled fotoaparátu")
            }
        }
    }

    // ----------------------------------------------------------------- settings -> request

    private fun previewParams(c: CameraCaps): Pair<Long, Int> {
        val st = settings
        val exp = c.clampExposure(st.exposureNs)
        val iso = c.clampIso(st.iso)
        val maxPrev = max(c.expMinNs, CamSettings.PREVIEW_MAX_NS[st.previewMode.coerceIn(0, 2)])
        if (exp <= maxPrev) return Pair(exp, iso)
        val product = exp.toDouble() * iso.toDouble()
        val pi = (product / maxPrev.toDouble()).toInt().coerceIn(c.isoMin, c.isoMax)
        return Pair(maxPrev, pi)
    }

    private fun frameDuration(c: CameraCaps, exp: Long, minDur: Long): Long {
        var fd = max(exp, minDur)
        if (c.maxFrameDurationNs > 0 && fd > c.maxFrameDurationNs) fd = c.maxFrameDurationNs
        return fd
    }

    private fun applySettings(b: CaptureRequest.Builder, preview: Boolean) {
        val c = caps ?: return
        val st = settings
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        if (st.autoExposure || !c.hasManual) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, st.evSteps.coerceIn(c.evMin, c.evMax))
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            val exp: Long
            val iso: Int
            if (preview) {
                val p = previewParams(c)
                exp = p.first
                iso = p.second
            } else {
                exp = c.clampExposure(st.exposureNs)
                iso = c.clampIso(st.iso)
            }
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(
                CaptureRequest.SENSOR_FRAME_DURATION,
                frameDuration(c, exp, if (preview) c.minFrameDurPreviewNs else c.minFrameDurStillNs)
            )
        }

        if (st.focusManual && c.minFocusDiopter > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, st.focusDiopter.coerceIn(0f, c.minFocusDiopter))
        } else if (c.afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        val awb = if (c.awbModes.contains(st.awbMode)) st.awbMode else CameraMetadata.CONTROL_AWB_MODE_AUTO
        b.set(CaptureRequest.CONTROL_AWB_MODE, awb)
        b.set(CaptureRequest.CONTROL_ZOOM_RATIO, c.clampZoom(st.zoom))

        if (c.oisOffSupported && c.oisSupported) {
            b.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (st.ois) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }

        if (!preview) {
            if (c.noiseReductionModes.contains(st.noiseReduction)) {
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, st.noiseReduction)
            }
            b.set(CaptureRequest.JPEG_QUALITY, st.jpegQuality.coerceIn(50, 100).toByte())
            b.set(CaptureRequest.JPEG_ORIENTATION, c.sensorOrientation)
            val loc = gpsLocation
            if (loc != null) b.set(CaptureRequest.JPEG_GPS_LOCATION, loc)
        }
    }

    private fun startPreviewInternal() {
        val d = device ?: return
        val s = session ?: return
        val ps = previewSurface ?: return
        if (caps == null) return
        val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(ps)
        applySettings(b, true)
        s.setRepeatingRequest(b.build(), null, handler)
    }

    /** Re-applies the current settings to the running preview. */
    fun updatePreview() {
        post {
            if (!(capturing && longExposure)) startPreviewInternal()
        }
    }

    // ----------------------------------------------------------------- capturing

    /** Starts a series of [count] exposures with [gapMs] between them (a single shot is a series of one). */
    fun startSeries(count: Int, gapMs: Long) {
        post {
            if (capturing || seriesActive) return@post
            seriesActive = true
            cancelled = false
            seriesTotal = max(1, count)
            seriesStarted = 0
            seriesDone = 0
            seriesGapMs = max(0L, gapMs)
            fireNext()
        }
    }

    fun cancelSeries() {
        post {
            if (!seriesActive && !capturing) return@post
            cancelled = true
            handler?.removeCallbacks(nextRunnable)
            if (capturing) {
                try {
                    session?.abortCaptures()
                } catch (e: Exception) {
                    // session already closed
                }
            } else {
                finishSeries()
            }
        }
    }

    private fun fireNext() {
        if (!seriesActive) return
        val d = device
        val s = session
        val c = caps
        if (d == null || s == null || c == null) {
            finishSeries()
            return
        }
        try {
            seriesStarted++
            val st = settings
            val exp = if (st.autoExposure || !c.hasManual) 0L else c.clampExposure(st.exposureNs)
            val iso = if (st.autoExposure || !c.hasManual) 0 else c.clampIso(st.iso)
            val meta = FrameMeta(
                seriesStarted, seriesTotal, iso, exp, System.currentTimeMillis(),
                c.sensorOrientation, st.zoom, listener.takeSnapshot()
            )
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            val jr = jpegReader
            val rr = rawReader
            val wantJpeg = st.saveJpeg || rr == null
            if (wantJpeg && jr != null) b.addTarget(jr.surface)
            if (rr != null && st.saveRaw) b.addTarget(rr.surface)
            applySettings(b, false)
            b.setTag(meta)
            longExposure = exp >= 600_000_000L
            capturing = true
            if (longExposure) {
                try {
                    s.stopRepeating()
                } catch (e: Exception) {
                    // ignore
                }
            }
            s.capture(b.build(), captureCallback, handler)
        } catch (e: Exception) {
            capturing = false
            reportError("Snímek se nepodařilo spustit: " + (e.message ?: ""))
            finishSeries()
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(s: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            val m = request.tag as? FrameMeta ?: return
            metaByTs[timestamp] = m
            if (metaByTs.size > 12) {
                val oldest = metaByTs.keys.minOrNull()
                if (oldest != null) metaByTs.remove(oldest)
            }
            main.post { listener.onExposureStarted(m.index, m.total, m.exposureNs) }
        }

        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val m = request.tag as? FrameMeta
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
            if (ts != null) offerResult(ts, result)
            capturing = false
            seriesDone++
            if (m != null) main.post { listener.onExposureFinished(m.index, m.total) }
            afterCapture()
        }

        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            capturing = false
            if (!cancelled) warn("Snímek se nepodařilo pořídit.")
            afterCapture()
        }
    }

    private fun afterCapture() {
        if (longExposure) {
            longExposure = false
            try {
                startPreviewInternal()
            } catch (e: Exception) {
                // preview will be restarted on the next settings change
            }
        }
        if (seriesActive && !cancelled && seriesStarted < seriesTotal) {
            handler?.postDelayed(nextRunnable, seriesGapMs)
        } else {
            finishSeries()
        }
    }

    private fun finishSeries() {
        val wasCancelled = cancelled
        seriesActive = false
        capturing = false
        longExposure = false
        val n = seriesDone
        main.post { listener.onSeriesFinished(n, wasCancelled) }
        try {
            startPreviewInternal()
        } catch (e: Exception) {
            // ignore
        }
    }

    // ----------------------------------------------------------------- images

    private val jpegListener = ImageReader.OnImageAvailableListener { reader ->
        val img = try { reader.acquireNextImage() } catch (e: Exception) { null }
        if (img != null) {
            try {
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                val meta = metaByTs[img.timestamp]
                io.execute { processJpeg(bytes, meta) }
            } finally {
                img.close()
            }
        }
    }

    private val rawListener = ImageReader.OnImageAvailableListener { reader ->
        val img = try { reader.acquireNextImage() } catch (e: Exception) { null }
        if (img != null) {
            val ts = img.timestamp
            synchronized(lock) {
                pendingRaw[ts] = img
                if (pendingRaw.size > 6) {
                    val oldest = pendingRaw.keys.minOrNull()
                    if (oldest != null && oldest != ts) {
                        try { pendingRaw.remove(oldest)?.close() } catch (e: Exception) { }
                        pendingResults.remove(oldest)
                    }
                }
            }
            tryWriteDng(ts)
        }
    }

    private fun offerResult(ts: Long, r: TotalCaptureResult) {
        if (!(wantRawStream && settings.saveRaw)) return
        synchronized(lock) {
            pendingResults[ts] = r
            if (pendingResults.size > 8) {
                val oldest = pendingResults.keys.minOrNull()
                if (oldest != null && oldest != ts) pendingResults.remove(oldest)
            }
        }
        tryWriteDng(ts)
    }

    private fun tryWriteDng(ts: Long) {
        var img: Image? = null
        var res: TotalCaptureResult? = null
        synchronized(lock) {
            val i = pendingRaw[ts]
            val r = pendingResults[ts]
            if (i != null && r != null) {
                img = i
                res = r
                pendingRaw.remove(ts)
                pendingResults.remove(ts)
            }
        }
        val im = img
        val rs = res
        if (im != null && rs != null) {
            val meta = metaByTs[ts]
            io.execute { writeDng(im, rs, meta) }
        }
    }

    private fun exifOrientation(sensorOrientation: Int): Int = when (sensorOrientation) {
        90 -> 6
        180 -> 3
        270 -> 8
        else -> 1
    }

    private fun writeDng(img: Image, res: TotalCaptureResult, meta: FrameMeta?) {
        var where: String? = null
        try {
            val c = chars
            if (c != null) {
                val dng = DngCreator(c, res)
                dng.setOrientation(exifOrientation(caps?.sensorOrientation ?: 90))
                val loc = gpsLocation
                if (loc != null) dng.setLocation(loc)
                where = MediaSaver.save(context, baseName(meta) + ".dng", "image/x-adobe-dng") { os ->
                    dng.writeImage(os, img)
                }
                dng.close()
            }
        } catch (e: Exception) {
            warn("DNG se nepodařilo uložit: " + (e.message ?: ""))
        } finally {
            try { img.close() } catch (e: Exception) { }
        }
        val w = where
        main.post { listener.onPhotoSaved(w ?: "", meta?.index ?: 0, meta?.total ?: 0) }
    }

    private fun processJpeg(bytes: ByteArray, meta: FrameMeta?) {
        val base = baseName(meta)
        val where = MediaSaver.save(context, "$base.jpg", "image/jpeg") { it.write(bytes) }
        if (settings.annotated && meta != null) {
            val fn = annotator
            if (fn != null) {
                try {
                    val out = fn(bytes, meta)
                    if (out != null) MediaSaver.save(context, base + "_popisky.jpg", "image/jpeg") { it.write(out) }
                } catch (t: Throwable) {
                    warn("Popisky se nepodařilo vykreslit.")
                }
            }
        }
        main.post { listener.onPhotoSaved(where ?: "", meta?.index ?: 0, meta?.total ?: 0) }
    }

    private fun expTag(ns: Long): String {
        if (ns <= 0L) return "auto"
        val s = ns / 1e9
        return if (s >= 1.0) {
            (if (s >= 10.0 || Math.abs(s - Math.rint(s)) < 0.05) Math.round(s).toString() else String.format(Locale.US, "%.1f", s)) + "s"
        } else {
            "1-" + Math.round(1.0 / s) + "s"
        }
    }

    private fun baseName(meta: FrameMeta?): String {
        val t = meta?.timeMs ?: System.currentTimeMillis()
        val df = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val sb = StringBuilder("AL_").append(df.format(Date(t)))
        if (meta != null) {
            if (meta.total > 1) sb.append('_').append(String.format(Locale.US, "%03d", meta.index))
            if (meta.iso > 0) sb.append("_ISO").append(meta.iso)
            sb.append('_').append(expTag(meta.exposureNs))
        }
        return sb.toString()
    }

    // ----------------------------------------------------------------- closing

    private fun closeSessionOnly() {
        try { session?.close() } catch (e: Exception) { }
        session = null
        try { previewSurface?.release() } catch (e: Exception) { }
        previewSurface = null
        try { jpegReader?.close() } catch (e: Exception) { }
        jpegReader = null
        try { rawReader?.close() } catch (e: Exception) { }
        rawReader = null
        synchronized(lock) {
            for (i in pendingRaw.values) {
                try { i.close() } catch (e: Exception) { }
            }
            pendingRaw.clear()
            pendingResults.clear()
        }
    }

    private fun closeInternal() {
        handler?.removeCallbacks(nextRunnable)
        seriesActive = false
        capturing = false
        longExposure = false
        closeSessionOnly()
        try { device?.close() } catch (e: Exception) { }
        device = null
    }
}
