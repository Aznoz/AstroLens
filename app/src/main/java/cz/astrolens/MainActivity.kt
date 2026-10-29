package cz.astrolens

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.GeomagneticField
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import cz.astrolens.astro.AstroMath
import cz.astrolens.astro.BodyKind
import cz.astrolens.astro.Catalog
import cz.astrolens.astro.CatalogLoader
import cz.astrolens.astro.Ephemeris
import cz.astrolens.astro.SearchItem
import cz.astrolens.astro.SkyEngine
import cz.astrolens.astro.TextUtil
import cz.astrolens.camera.CamSettings
import cz.astrolens.camera.CameraCaps
import cz.astrolens.camera.CameraController
import cz.astrolens.camera.CameraListener
import cz.astrolens.camera.FrameMeta
import cz.astrolens.camera.LensInfo
import cz.astrolens.camera.Presets
import cz.astrolens.sensor.LocationHelper
import cz.astrolens.sensor.OrientationProvider
import cz.astrolens.ui.Annotator
import cz.astrolens.ui.Info
import cz.astrolens.ui.InfoText
import cz.astrolens.ui.OverlayOptions
import cz.astrolens.ui.SkyOverlayView
import cz.astrolens.ui.SkySnapshot
import cz.astrolens.ui.SkyTarget
import cz.astrolens.ui.Ui
import cz.astrolens.util.Fmt
import cz.astrolens.util.K
import cz.astrolens.util.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity(), CameraListener {
    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var engine: SkyEngine
    private lateinit var orientation: OrientationProvider
    private lateinit var location: LocationHelper
    private lateinit var cam: CameraController
    private lateinit var opts: OverlayOptions
    private lateinit var overlay: SkyOverlayView
    private lateinit var textureView: TextureView
    private lateinit var root: FrameLayout
    private lateinit var previewBox: FrameLayout
    private lateinit var statusView: TextView
    private lateinit var readout: TextView
    private lateinit var shutter: TextView
    private lateinit var progressBox: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var controls: LinearLayout
    private lateinit var sheet: LinearLayout
    private lateinit var sheetScroll: ScrollView
    private lateinit var sheetTitle: TextView
    private lateinit var sheetBody: LinearLayout
    private lateinit var infoCard: LinearLayout
    private val tabViews = ArrayList<TextView>()

    private val main = Handler(Looper.getMainLooper())
    private var texture: SurfaceTexture? = null
    private var caps: CameraCaps? = null
    private var lenses: List<LensInfo> = emptyList()
    private var resumed = false
    private var insetTop = 0
    private var insetBottom = 0
    private var currentTab = -1
    private var selected: SkyTarget? = null
    private var gotoTarget: SkyTarget? = null
    private var searchItems: List<SearchItem> = emptyList()
    private var exposureStartMs = 0L
    private var exposureLenNs = 0L
    private var seriesIndex = 0
    private var seriesTotal = 0
    private var countdownActive = false
    private var cameraError: String? = null
    private val dateFmt = SimpleDateFormat("d. M. HH:mm:ss", Locale("cs", "CZ"))

    private val tabNames = arrayOf("Expozice", "Režimy", "Obloha", "Hledat", "Víc")

    // ================================================================== lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui = Ui(this)
        prefs = Prefs(this)
        engine = SkyEngine()
        orientation = OrientationProvider(this)
        cam = CameraController(this, this)
        opts = OverlayOptions()
        opts.load(prefs)
        location = LocationHelper(this) { loc -> onLocation(loc) }

        loadSettings()
        loadLocation()
        orientation.smoothing = prefs.float(K.SMOOTHING, 0.15f).toDouble()

        buildUi()
        applyNight()

        cam.annotator = { bytes, meta -> Annotator.annotate(bytes, meta, engine, opts.copyForSnapshot(), cam.settings.jpegQuality) }

        Thread {
            try {
                val cat = CatalogLoader.load { name -> assets.open(name) }
                engine.catalog = cat
                val items = ArrayList(cat.searchItems())
                for ((i, b) in listOf("Slunce", "Měsíc", "Merkur", "Venuše", "Mars", "Jupiter", "Saturn", "Uran", "Neptun").withIndex()) {
                    val sub = if (i < 2) (if (i == 0) "Hvězda" else "Družice Země") else "Planeta"
                    items.add(SearchItem(SearchItem.BODY, i, b, sub, TextUtil.norm(b)))
                }
                searchItems = items
            } catch (e: Exception) {
                main.post { Toast.makeText(this, "Katalog se nepodařilo načíst: " + e.message, Toast.LENGTH_LONG).show() }
            }
        }.start()

        requestNeededPermissions()
        if (!prefs.bool("welcome_shown", false)) {
            prefs.put("welcome_shown", true)
            showWelcome()
        }
    }

    private fun showWelcome() {
        AlertDialog.Builder(this)
            .setTitle("Vítejte v AstroLens")
            .setMessage(
                "Namiřte telefon na oblohu. Aplikace pozná, kam míříte, a přes živý obraz vykreslí hvězdy, souhvězdí, planety, Měsíc a mlhoviny.\n\n" +
                    "• Klepnutím na objekt zobrazíte informace a můžete se k němu nechat navést.\n" +
                    "• Záložka Režimy nastaví fotoaparát pro oblohu, Měsíc nebo planety.\n" +
                    "• Záložka Expozice dává plnou ruční kontrolu (ISO, čas až desítky sekund, ostření, zoom, RAW).\n" +
                    "• Pro dlouhé expozice použijte stativ nebo opřete telefon.\n" +
                    "• Pokud překryv nesedí, zkalibrujte ho v záložce Víc: zaměřte jasný objekt do středu a stiskněte kalibraci.\n\n" +
                    "Nikdy nemiřte na Slunce bez slunečního filtru."
            )
            .setPositiveButton("Rozumím", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        main.removeCallbacks(tick)
        main.post(tick)
        orientation.start(prefs.int(K.SENSOR_SOURCE, OrientationProvider.SOURCE_COMPASS))
        updateDeclination()
        if (prefs.bool(K.LOC_AUTO, true)) location.start()
        cam.startThread()
        main.postDelayed({ if (resumed) tryOpen() }, 250)
    }

    override fun onPause() {
        resumed = false
        saveSettings()
        orientation.stop()
        location.stop()
        main.removeCallbacksAndMessages(null)
        countdownActive = false
        cam.cancelSeries()
        cam.release()
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            currentTab >= 0 -> showTab(-1)
            selected != null -> select(null)
            else -> super.onBackPressed()
        }
    }

    private fun requestNeededPermissions() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA)
        if (!location.hasPermission()) need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 77)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraError = "Bez povolení fotoaparátu aplikace nemůže fungovat. Povolte ho v nastavení systému."
        } else {
            cameraError = null
            tryOpen()
        }
        if (location.hasPermission() && prefs.bool(K.LOC_AUTO, true)) location.start()
    }

    // ================================================================== settings

    private fun loadSettings() {
        val s = cam.settings
        s.iso = prefs.int(K.ISO, 1600)
        s.exposureNs = prefs.long(K.EXPOSURE_NS, 10_000_000_000L)
        s.autoExposure = prefs.bool(K.AUTO_EXPOSURE, false)
        s.evSteps = prefs.int(K.EV, 0)
        s.focusManual = prefs.bool(K.FOCUS_MANUAL, true)
        s.focusDiopter = prefs.float(K.FOCUS_POS, 0f)
        s.awbMode = prefs.int(K.AWB, s.awbMode)
        s.zoom = prefs.float(K.ZOOM, 1f)
        s.saveJpeg = prefs.bool(K.SAVE_JPEG, true)
        s.saveRaw = prefs.bool(K.SAVE_RAW, false)
        s.jpegQuality = prefs.int(K.JPEG_QUALITY, 95)
        s.annotated = prefs.bool(K.ANNOTATED, false)
        s.noiseReduction = prefs.int(K.NOISE_REDUCTION, s.noiseReduction)
        s.ois = prefs.bool(K.OIS, false)
        s.previewMode = prefs.int(K.PREVIEW_MODE, 1)
        s.timerSec = prefs.int(K.TIMER, 0)
        s.seriesCount = prefs.int(K.SERIES_COUNT, 1)
        s.seriesGapMs = prefs.long(K.SERIES_GAP, 1000L)
    }

    private fun saveSettings() {
        val s = cam.settings
        prefs.put(K.ISO, s.iso)
        prefs.put(K.EXPOSURE_NS, s.exposureNs)
        prefs.put(K.AUTO_EXPOSURE, s.autoExposure)
        prefs.put(K.EV, s.evSteps)
        prefs.put(K.FOCUS_MANUAL, s.focusManual)
        prefs.put(K.FOCUS_POS, s.focusDiopter)
        prefs.put(K.AWB, s.awbMode)
        prefs.put(K.ZOOM, s.zoom)
        prefs.put(K.SAVE_JPEG, s.saveJpeg)
        prefs.put(K.SAVE_RAW, s.saveRaw)
        prefs.put(K.JPEG_QUALITY, s.jpegQuality)
        prefs.put(K.ANNOTATED, s.annotated)
        prefs.put(K.NOISE_REDUCTION, s.noiseReduction)
        prefs.put(K.OIS, s.ois)
        prefs.put(K.PREVIEW_MODE, s.previewMode)
        prefs.put(K.TIMER, s.timerSec)
        prefs.put(K.SERIES_COUNT, s.seriesCount)
        prefs.put(K.SERIES_GAP, s.seriesGapMs)
    }

    private fun loadLocation() {
        engine.latDeg = prefs.float(K.LOC_LAT, 50.0755f).toDouble()
        engine.lonDeg = prefs.float(K.LOC_LON, 14.4378f).toDouble()
    }

    private fun onLocation(loc: Location) {
        main.post {
            if (!prefs.bool(K.LOC_AUTO, true)) return@post
            engine.latDeg = loc.latitude
            engine.lonDeg = loc.longitude
            engine.altM = loc.altitude
            cam.gpsLocation = loc
            prefs.put(K.LOC_LAT, loc.latitude.toFloat())
            prefs.put(K.LOC_LON, loc.longitude.toFloat())
            updateDeclination()
        }
    }

    private fun updateDeclination() {
        if (!prefs.bool(K.USE_DECLINATION, true)) {
            orientation.declinationDeg = 0.0
            return
        }
        try {
            val g = GeomagneticField(engine.latDeg.toFloat(), engine.lonDeg.toFloat(), engine.altM.toFloat(), System.currentTimeMillis())
            orientation.declinationDeg = g.declination.toDouble()
        } catch (e: Exception) {
            orientation.declinationDeg = 0.0
        }
    }

    private fun changed() {
        saveSettings()
        cam.updatePreview()
        updateReadout()
    }

    private fun applyNight() {
        if (opts.night) {
            val cm = ColorMatrix(
                floatArrayOf(
                    0.40f, 0.70f, 0.14f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            val p = Paint()
            p.colorFilter = ColorMatrixColorFilter(cm)
            root.setLayerType(View.LAYER_TYPE_HARDWARE, p)
        } else {
            root.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    // ================================================================== camera

    private fun tryOpen() {
        val st = texture ?: return
        if (!resumed) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        cam.startThread()
        lenses = cam.listLenses()
        var lens = prefs.string(K.LENS_ID, "")
        if (lens.isEmpty() || lenses.none { it.id == lens }) lens = cam.defaultLensId() ?: ""
        cam.open(if (lens.isEmpty()) null else lens, st)
    }

    override fun onCameraReady(caps: CameraCaps) {
        this.caps = caps
        cameraError = null
        val s = cam.settings
        s.iso = caps.clampIso(s.iso)
        s.exposureNs = caps.clampExposure(s.exposureNs)
        s.zoom = caps.clampZoom(s.zoom)
        if (!caps.hasManual) s.autoExposure = true
        if (!caps.hasRaw) s.saveRaw = false
        s.focusDiopter = s.focusDiopter.coerceIn(0f, max(0f, caps.minFocusDiopter))
        if (caps.minFocusDiopter <= 0f) s.focusManual = false
        s.evSteps = s.evSteps.coerceIn(caps.evMin, caps.evMax)
        overlay.fovLongDeg = caps.fovLongDeg
        overlay.zoom = s.zoom
        layoutPreview()
        if (!prefs.bool("first_preset_done", false)) {
            prefs.put("first_preset_done", true)
            val f = engine.frame()
            Presets.byId("sky")?.let { Presets.apply(it, s, caps, f.moon.mag, f.moon.illum) }
            overlay.zoom = s.zoom
            cam.updatePreview()
        }
        updateReadout()
        if (currentTab >= 0) showTab(currentTab)
    }

    override fun onCameraError(message: String) {
        cameraError = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onWarning(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onExposureStarted(index: Int, total: Int, exposureNs: Long) {
        seriesIndex = index
        seriesTotal = total
        exposureStartMs = SystemClock.elapsedRealtime()
        exposureLenNs = exposureNs
        progressBox.visibility = View.VISIBLE
        shutter.text = "■"
        shutter.background = ui.rounded(Ui.RED, 40f)
        updateProgress()
    }

    override fun onExposureFinished(index: Int, total: Int) {
        exposureLenNs = 0L
        progressText.text = "Zpracovávám snímek " + (index + 1) + " / " + total + "…"
    }

    override fun onPhotoSaved(where: String, index: Int, total: Int) {
        if (where.isEmpty()) {
            Toast.makeText(this, "Snímek se nepodařilo uložit", Toast.LENGTH_LONG).show()
        } else if (total <= 1) {
            Toast.makeText(this, "Uloženo: $where", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSeriesFinished(count: Int, cancelled: Boolean) {
        progressBox.visibility = View.GONE
        exposureLenNs = 0L
        shutter.text = ""
        shutter.background = ui.rounded(android.graphics.Color.WHITE, 40f)
        val msg = if (cancelled) "Přerušeno, uloženo snímků: $count" else if (count > 1) "Hotovo, uloženo snímků: $count do Obrázky/AstroLens" else null
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun takeSnapshot(): Any? {
        val c = caps ?: return null
        val real = System.currentTimeMillis()
        return SkySnapshot(
            real, orientation.worldToDevice(), orientation.cameraAxis(), c.fovLongDeg, cam.settings.zoom,
            engine.latDeg, engine.lonDeg, placeName()
        )
    }

    private fun placeName(): String =
        Fmt.f2(abs(engine.latDeg)) + (if (engine.latDeg >= 0) "° s. š., " else "° j. š., ") +
            Fmt.f2(abs(engine.lonDeg)) + (if (engine.lonDeg >= 0) "° v. d." else "° z. d.")

    // ================================================================== shutter

    private fun onShutter() {
        if (cameraError != null || caps == null) {
            Toast.makeText(this, cameraError ?: "Fotoaparát se ještě nespustil", Toast.LENGTH_SHORT).show()
            return
        }
        if (countdownActive) {
            countdownActive = false
            progressBox.visibility = View.GONE
            shutter.text = ""
            shutter.background = ui.rounded(android.graphics.Color.WHITE, 40f)
            return
        }
        if (cam.isBusy) {
            cam.cancelSeries()
            Toast.makeText(this, "Ruším po dokončení probíhajícího snímku…", Toast.LENGTH_SHORT).show()
            return
        }
        if (sunInView()) {
            AlertDialog.Builder(this)
                .setTitle("Slunce v záběru")
                .setMessage("Fotografování Slunce bez filtru může poškodit snímač fotoaparátu. Opravdu chcete pokračovat?")
                .setPositiveButton("Fotit") { _, _ -> beginShot() }
                .setNegativeButton("Zrušit", null)
                .show()
            return
        }
        beginShot()
    }

    private fun sunInView(): Boolean {
        val c = caps ?: return false
        if (cam.settings.autoExposure && c.hasManual.not()) return false
        val f = engine.frame()
        val sunEnu = f.bodyEnu(f.sun)
        if (AstroMath.altitudeDeg(sunEnu) < -3.0) return false
        val sep = AstroMath.separationDeg(orientation.cameraAxis(), sunEnu)
        val half = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(c.fovLongDeg) / 2.0) / cam.settings.zoom))
        return sep < half + 3.0
    }

    private fun beginShot() {
        val s = cam.settings
        if (!s.saveJpeg && !s.saveRaw) s.saveJpeg = true
        saveSettings()
        if (s.timerSec > 0) {
            countdownActive = true
            val end = SystemClock.elapsedRealtime() + s.timerSec * 1000L
            progressBox.visibility = View.VISIBLE
            shutter.text = "■"
            shutter.background = ui.rounded(Ui.RED, 40f)
            fun step() {
                if (!countdownActive) return
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0) {
                    countdownActive = false
                    progressBox.visibility = View.GONE
                    cam.startSeries(s.seriesCount, s.seriesGapMs)
                } else {
                    progressText.text = "Spuštění za " + ((left + 999) / 1000) + " s"
                    progressBar.max = s.timerSec * 1000
                    progressBar.progress = (s.timerSec * 1000 - left).toInt()
                    main.postDelayed({ step() }, 100)
                }
            }
            step()
        } else {
            cam.startSeries(s.seriesCount, s.seriesGapMs)
        }
    }

    private fun updateProgress() {
        if (exposureLenNs <= 0L) return
        val elapsed = SystemClock.elapsedRealtime() - exposureStartMs
        val total = exposureLenNs / 1_000_000L
        val left = max(0L, total - elapsed)
        progressText.text = (if (seriesTotal > 1) "Snímek " + (seriesIndex + 1) + " / " + seriesTotal + " · " else "") +
            "Expozice " + Fmt.f1(left / 1000.0) + " s"
        progressBar.max = max(1L, total).toInt()
        progressBar.progress = min(elapsed, total).toInt()
    }

    // ================================================================== periodic UI update

    private val tick: Runnable = object : Runnable {
        override fun run() {
            updateStatus()
            updateProgress()
            overlay.selected = selected
            overlay.goto = gotoTarget
            overlay.hudExtra = if (engine.timeOffsetMs != 0L) "Cestování v čase: " + dateFmt.format(Date(engine.nowMs())) else ""
            main.postDelayed(this, 250)
        }
    }

    private fun updateStatus() {
        val f = engine.frame()
        val sunAlt = f.sunAltDeg()
        val sb = StringBuilder()
        sb.append(dateFmt.format(Date(f.timeMs))).append(" · ").append(Ephemeris.twilightName(sunAlt))
        sb.append("\nMěsíc ").append(Fmt.f0(f.moon.illum * 100.0)).append(" % · ").append(f.moon.extra)
        if (orientation.usesMagnetometer && orientation.accuracy < android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) {
            sb.append("\nKompas nepřesný, pohybujte telefonem do osmičky")
        }
        if (!orientation.hasSensor) sb.append("\nTelefon nemá snímač natočení")
        statusView.text = sb.toString()
    }

    private fun updateReadout() {
        val s = cam.settings
        val c = caps
        val parts = ArrayList<String>()
        if (s.autoExposure) {
            parts.add("AUTO" + if (s.evSteps != 0) " EV" + (if (s.evSteps > 0) "+" else "") + s.evSteps else "")
        } else {
            parts.add("ISO " + s.iso)
            parts.add(Fmt.exposure(s.exposureNs))
        }
        parts.add(if (s.focusManual) (if (s.focusDiopter <= 0.01f) "∞" else Fmt.f1(1.0 / s.focusDiopter) + " m") else "AF")
        parts.add(Fmt.f1(s.zoom.toDouble()) + "×")
        if (c != null) parts.add(Fmt.f0(c.equivMm.toDouble() * s.zoom) + " mm")
        if (s.seriesCount > 1) parts.add("série ${s.seriesCount}")
        if (s.saveRaw) parts.add("RAW")
        readout.text = parts.joinToString("  ·  ")
        overlay.zoom = s.zoom
    }

    // ================================================================== layout

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(android.graphics.Color.BLACK)

        previewBox = FrameLayout(this)
        textureView = TextureView(this)
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                texture = st
                if (resumed) tryOpen()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                texture = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        previewBox.addView(textureView, FrameLayout.LayoutParams(-1, -1))
        overlay = SkyOverlayView(this, engine, orientation)
        overlay.options = opts
        overlay.onPick = { t -> select(t) }
        overlay.onPinch = { factor ->
            val c = caps
            if (c != null) {
                val s = cam.settings
                s.zoom = c.clampZoom(s.zoom * factor)
                overlay.zoom = s.zoom
                cam.updatePreview()
                updateReadout()
            }
        }
        previewBox.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        statusView = ui.text("", 11.5f, android.graphics.Color.WHITE)
        statusView.setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        statusView.gravity = Gravity.END
        val sp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
        sp.setMargins(0, ui.dp(8), ui.dp(10), 0)
        previewBox.addView(statusView, sp)

        root.addView(previewBox, FrameLayout.LayoutParams(-1, -1, Gravity.TOP))

        // exposure progress
        progressBox = ui.vbox()
        progressBox.visibility = View.GONE
        progressBox.background = ui.rounded(Ui.PANEL, 14f)
        ui.pad(progressBox, 16, 12, 16, 12)
        progressText = ui.text("", 15f, Ui.TEXT, true)
        progressText.gravity = Gravity.CENTER
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.layoutParams = ui.lp(ui.dp(240), ui.dp(16))
        progressBox.addView(progressText)
        progressBox.addView(progressBar)
        val pp = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        root.addView(progressBox, pp)

        // info card + sheet
        infoCard = ui.vbox()
        infoCard.visibility = View.GONE
        infoCard.isClickable = true
        infoCard.background = ui.rounded(Ui.PANEL, 16f)
        ui.pad(infoCard, 14, 10, 14, 12)
        root.addView(infoCard, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        sheet = ui.vbox()
        sheet.visibility = View.GONE
        sheet.isClickable = true
        sheet.background = ui.rounded(Ui.PANEL, 18f)
        val head = ui.hbox()
        ui.pad(head, 16, 10, 8, 0)
        sheetTitle = ui.text("", 17f, Ui.TEXT, true)
        sheetTitle.layoutParams = ui.lp(0, -2, 1f)
        val close = ui.text("✕", 20f, Ui.DIM, true)
        ui.pad(close, 14, 6, 14, 6)
        close.setOnClickListener { showTab(-1) }
        head.addView(sheetTitle)
        head.addView(close)
        sheet.addView(head)
        sheetScroll = MaxHeightScroll(this, (resources.displayMetrics.heightPixels * 0.55f).toInt())
        sheetBody = ui.vbox()
        ui.pad(sheetBody, 14, 0, 14, 12)
        sheetScroll.addView(sheetBody)
        sheet.addView(sheetScroll, ui.lp(-1, -2))
        root.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // bottom controls
        controls = ui.vbox()
        controls.isClickable = true
        controls.setBackgroundColor(android.graphics.Color.argb(235, 0, 0, 0))
        readout = ui.text("", 14f, Ui.ACCENT, true)
        readout.gravity = Gravity.CENTER
        readout.setOnClickListener { showTab(0) }
        ui.pad(readout, 8, 8, 8, 4)
        controls.addView(readout, ui.lp(-1, -2))

        val shutterRow = FrameLayout(this)
        shutter = ui.text("", 24f, android.graphics.Color.WHITE, true)
        shutter.gravity = Gravity.CENTER
        shutter.background = ui.rounded(android.graphics.Color.WHITE, 40f, android.graphics.Color.argb(120, 255, 255, 255), 4f)
        shutter.setOnClickListener { onShutter() }
        val shp = FrameLayout.LayoutParams(ui.dp(76), ui.dp(76), Gravity.CENTER)
        shp.setMargins(0, ui.dp(2), 0, ui.dp(6))
        shutterRow.addView(shutter, shp)

        val overlayBtn = ui.button("AR", {})
        overlayBtn.setOnClickListener {
            opts.overlayOn = !opts.overlayOn
            prefs.put(K.OVERLAY_ON, opts.overlayOn)
            overlayBtn.alpha = if (opts.overlayOn) 1f else 0.45f
        }
        overlayBtn.alpha = if (opts.overlayOn) 1f else 0.45f
        shutterRow.addView(overlayBtn, FrameLayout.LayoutParams(ui.dp(64), -2, Gravity.CENTER_VERTICAL or Gravity.START).also { it.leftMargin = ui.dp(28) })

        val lensBtn = ui.button("Objektiv", {})
        lensBtn.setOnClickListener { cycleLens() }
        shutterRow.addView(lensBtn, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END).also { it.rightMargin = ui.dp(20) })
        controls.addView(shutterRow, ui.lp(-1, -2))

        val tabs = ui.hbox()
        for ((i, n) in tabNames.withIndex()) {
            val t = ui.text(n, 13f, Ui.DIM, true)
            t.gravity = Gravity.CENTER
            ui.pad(t, 4, 12, 4, 12)
            t.layoutParams = ui.lp(0, -2, 1f)
            t.setOnClickListener { showTab(if (currentTab == i) -1 else i) }
            tabViews.add(t)
            tabs.addView(t)
        }
        controls.addView(tabs, ui.lp(-1, -2))
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        root.setOnApplyWindowInsetsListener { _, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insetTop = b.top
            insetBottom = b.bottom
            layoutPreview()
            ins
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionOverlays() }
        setContentView(root)
        layoutPreview()
        updateReadout()
    }

    /** Sizes the preview to the camera's aspect ratio and keeps it below the status bar. */
    private fun layoutPreview() {
        val c = caps
        val aspect = if (c != null) c.aspect else 4.0 / 3.0
        val dm = resources.displayMetrics
        val availW = dm.widthPixels
        var w = availW
        var h = (w * aspect).toInt()
        val maxH = dm.heightPixels - insetTop - ui.dp(150) - insetBottom
        if (h > maxH) {
            h = maxH
            w = (h / aspect).toInt()
        }
        val lp = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        lp.topMargin = insetTop
        previewBox.layoutParams = lp
        (controls.layoutParams as FrameLayout.LayoutParams).bottomMargin = insetBottom
        controls.requestLayout()
        positionOverlays()
    }

    private fun positionOverlays() {
        val cb = controls.height + insetBottom
        for (v in arrayOf<View>(sheet, infoCard)) {
            val p = v.layoutParams as FrameLayout.LayoutParams
            if (p.bottomMargin != cb) {
                p.bottomMargin = cb
                v.layoutParams = p
            }
        }
    }

    private fun cycleLens() {
        if (lenses.size < 2) {
            Toast.makeText(this, "Telefon má jen jeden zadní fotoaparát", Toast.LENGTH_SHORT).show()
            return
        }
        val cur = caps?.id
        val i = lenses.indexOfFirst { it.id == cur }
        val next = lenses[(i + 1) % lenses.size]
        prefs.put(K.LENS_ID, next.id)
        cam.settings.zoom = 1f
        Toast.makeText(this, next.label, Toast.LENGTH_SHORT).show()
        tryOpen()
    }

    // ================================================================== selection & info card

    private fun select(t: SkyTarget?) {
        selected = t
        overlay.selected = t
        buildInfoCard()
    }

    private fun buildInfoCard() {
        infoCard.removeAllViews()
        val t = selected
        val cat = engine.catalog
        if (t == null || cat == null || currentTab >= 0) {
            infoCard.visibility = View.GONE
            return
        }
        val info: Info? = InfoText.describe(engine.frame(), cat, t, engine.latDeg, engine.lonDeg)
        if (info == null) {
            infoCard.visibility = View.GONE
            return
        }
        val row = ui.hbox()
        val titles = ui.vbox()
        titles.layoutParams = ui.lp(0, -2, 1f)
        titles.addView(ui.text(info.title, 20f, Ui.TEXT, true))
        titles.addView(ui.text(info.subtitle, 13f, Ui.ACCENT))
        row.addView(titles)
        val x = ui.text("✕", 20f, Ui.DIM, true)
        ui.pad(x, 12, 4, 4, 4)
        x.setOnClickListener { select(null) }
        row.addView(x)
        infoCard.addView(row)
        if (info.warning.isNotEmpty()) {
            val w = ui.text(info.warning, 13f, Ui.WARN, true)
            ui.pad(w, 0, 6, 0, 2)
            infoCard.addView(w)
        }
        val body = ui.vbox()
        ui.pad(body, 0, 6, 0, 4)
        for (ln in info.lines) body.addView(ui.text(ln, 13f, Ui.TEXT))
        if (info.text.isNotEmpty()) {
            val d = ui.text(info.text, 12.5f, Ui.DIM)
            ui.pad(d, 0, 6, 0, 0)
            body.addView(d)
        }
        val sc = MaxHeightScroll(this, (resources.displayMetrics.heightPixels * 0.26f).toInt())
        sc.addView(body)
        infoCard.addView(sc, ui.lp(-1, -2))

        val buttons = ui.hbox()
        buttons.setPadding(0, ui.dp(8), 0, 0)
        val going = gotoTarget == t
        val goBtn = ui.accentButton(if (going) "Zrušit navádění" else "Navést") {
            gotoTarget = if (gotoTarget == t) null else t
            overlay.goto = gotoTarget
            buildInfoCard()
        }
        goBtn.layoutParams = ui.lp(0, -2, 1f).also { it.rightMargin = ui.dp(6) }
        buttons.addView(goBtn)
        if (info.preset.isNotEmpty()) {
            val pr = Presets.byId(info.preset)
            val pb = ui.button("Režim: " + (pr?.name ?: "")) { applyPreset(info.preset) }
            pb.layoutParams = ui.lp(0, -2, 1.3f)
            buttons.addView(pb)
        }
        infoCard.addView(buttons)
        infoCard.visibility = View.VISIBLE
    }

    // ================================================================== sheets

    private fun showTab(i: Int) {
        currentTab = i
        for ((k, v) in tabViews.withIndex()) {
            v.setTextColor(if (k == i) Ui.ACCENT else Ui.DIM)
        }
        if (i < 0) {
            sheet.visibility = View.GONE
            buildInfoCard()
            return
        }
        infoCard.visibility = View.GONE
        sheetBody.removeAllViews()
        sheetTitle.text = tabNames[i]
        when (i) {
            0 -> buildExposurePanel(sheetBody)
            1 -> buildModesPanel(sheetBody)
            2 -> buildLayersPanel(sheetBody)
            3 -> buildSearchPanel(sheetBody)
            else -> buildSettingsPanel(sheetBody)
        }
        sheet.visibility = View.VISIBLE
        sheetScroll.scrollTo(0, 0)
        positionOverlays()
    }

    private fun trailHint(c: CameraCaps): String {
        val s = cam.settings
        val (strict, classic) = Presets.trailLimits(c, s.zoom)
        val sec = s.exposureNs / 1e9
        val ok = if (sec <= strict) "hvězdy zůstanou body" else if (sec <= classic) "hvězdy začnou mírně táhnout" else "hvězdy budou stopy"
        return "Ekvivalent " + Fmt.f0(c.equivMm.toDouble() * s.zoom) + " mm · bez stop do " + Fmt.f1(strict) + " s (přísně), " +
            Fmt.f1(classic) + " s (pravidlo 500). Teď: " + ok + "."
    }

    private fun buildExposurePanel(b: LinearLayout) {
        val c = caps
        if (c == null) {
            b.addView(ui.hint(cameraError ?: "Fotoaparát se spouští…"))
            return
        }
        val s = cam.settings
        if (!c.hasManual) b.addView(ui.hint("Tento fotoaparát nepodporuje plně ruční expozici, funguje jen automatika."))

        b.addView(ui.section("Expozice"))
        val modeChoice = ui.choice(listOf("Ruční", "Automatická"), if (s.autoExposure) 1 else 0) { p ->
            s.autoExposure = p == 1 || !c.hasManual
            changed()
            showTab(0)
        }
        b.addView(modeChoice.view)

        if (!s.autoExposure) {
            var isoS: Ui.Slider? = null
            isoS = ui.slider("ISO", c.isoSteps.size - 1, c.nearestIsoIndex(s.iso), s.iso.toString()) { p, _ ->
                s.iso = c.isoSteps[p]
                isoS?.setValueText?.invoke(s.iso.toString())
                changed()
            }
            b.addView(isoS.view)

            val hintView = ui.hint(trailHint(c))
            var expS: Ui.Slider? = null
            expS = ui.slider("Čas závěrky", c.exposureSteps.size - 1, c.nearestExposureIndex(s.exposureNs), Fmt.exposure(s.exposureNs)) { p, _ ->
                s.exposureNs = c.exposureSteps[p]
                expS?.setValueText?.invoke(Fmt.exposure(s.exposureNs))
                hintView.text = trailHint(c)
                changed()
            }
            b.addView(expS.view)
            b.addView(hintView)
            b.addView(ui.hint("Maximální čas závisí na telefonu: tento fotoaparát nabízí až " + Fmt.exposure(c.exposureSteps.last()) + "."))
        } else {
            if (c.evMax > c.evMin) {
                var evS: Ui.Slider? = null
                evS = ui.slider("Korekce expozice (EV)", c.evMax - c.evMin, s.evSteps - c.evMin, Fmt.f1(s.evSteps * c.evStepStops.toDouble())) { p, _ ->
                    s.evSteps = p + c.evMin
                    evS?.setValueText?.invoke(Fmt.f1(s.evSteps * c.evStepStops.toDouble()))
                    changed()
                }
                b.addView(evS.view)
            }
        }

        b.addView(ui.section("Ostření"))
        if (c.minFocusDiopter > 0f) {
            val fc = ui.choice(listOf("Ruční", "Automatické"), if (s.focusManual) 0 else 1) { p ->
                s.focusManual = p == 0
                changed()
                showTab(0)
            }
            b.addView(fc.view)
            if (s.focusManual) {
                val steps = 100
                fun txt(d: Float) = if (d <= 0.01f) "nekonečno ∞" else Fmt.f2(1.0 / d) + " m"
                var fS: Ui.Slider? = null
                fS = ui.slider("Vzdálenost ostření", steps, (s.focusDiopter / c.minFocusDiopter * steps).toInt(), txt(s.focusDiopter)) { p, _ ->
                    s.focusDiopter = p.toFloat() / steps * c.minFocusDiopter
                    fS?.setValueText?.invoke(txt(s.focusDiopter))
                    changed()
                }
                b.addView(fS.view)
                b.addView(ui.button("Nastavit na nekonečno ∞") {
                    s.focusDiopter = 0f
                    changed()
                    showTab(0)
                }.also { it.layoutParams = ui.lp(-1, -2) })
                b.addView(ui.hint("Hvězdy ostří na nekonečnu. Mnoho telefonů má skutečné nekonečno mírně za koncem posuvníku, ověřte na jasné hvězdě."))
            }
        } else {
            b.addView(ui.hint("Tento fotoaparát má pevné ostření."))
        }

        b.addView(ui.section("Zoom"))
        val zr = c.zoomMax - c.zoomMin
        if (zr > 0.05f) {
            var zS: Ui.Slider? = null
            zS = ui.slider("Přiblížení", (zr * 20).toInt(), ((s.zoom - c.zoomMin) * 20).toInt(), Fmt.f1(s.zoom.toDouble()) + "×") { p, _ ->
                s.zoom = c.clampZoom(c.zoomMin + p / 20f)
                zS?.setValueText?.invoke(Fmt.f1(s.zoom.toDouble()) + "×")
                overlay.zoom = s.zoom
                changed()
            }
            b.addView(zS.view)
            b.addView(ui.hint("Zoom můžete měnit i dvěma prsty přímo na obrazu."))
        } else {
            b.addView(ui.hint("Tento objektiv nemá zoom."))
        }

        b.addView(ui.section("Vyvážení bílé"))
        val awbIdx = max(0, CamSettings.AWB_MODES.indexOf(s.awbMode))
        b.addView(ui.choice(CamSettings.AWB_NAMES.toList(), awbIdx) { p ->
            s.awbMode = CamSettings.AWB_MODES[p]
            changed()
        }.view)

        b.addView(ui.section("Ukládání"))
        b.addView(ui.toggle("JPEG", s.saveJpeg) { v ->
            s.saveJpeg = v
            if (!v && !s.saveRaw) s.saveJpeg = true
            changed()
        }.view)
        if (c.hasRaw) {
            b.addView(ui.toggle("RAW (DNG)", s.saveRaw) { v ->
                s.saveRaw = v
                changed()
                cam.rebuildSession()
                updateReadout()
            }.view)
        } else {
            b.addView(ui.hint("RAW (DNG) tento fotoaparát nenabízí."))
        }
        b.addView(ui.toggle("Uložit i kopii s popisky objektů", s.annotated) { v ->
            s.annotated = v
            changed()
        }.view)
        var qS: Ui.Slider? = null
        qS = ui.slider("Kvalita JPEG", 40, s.jpegQuality - 60, s.jpegQuality.toString()) { p, _ ->
            s.jpegQuality = p + 60
            qS?.setValueText?.invoke(s.jpegQuality.toString())
            saveSettings()
        }
        b.addView(qS.view)

        b.addView(ui.section("Samospoušť a série"))
        val timers = listOf(0, 2, 5, 10)
        b.addView(ui.choice(timers.map { if (it == 0) "Vyp" else "$it s" }, max(0, timers.indexOf(s.timerSec))) { p ->
            s.timerSec = timers[p]
            saveSettings()
        }.view)
        var cS: Ui.Slider? = null
        val total = ui.hint("")
        fun seriesInfo() {
            val sec = s.seriesCount * (s.exposureNs / 1e9 + s.seriesGapMs / 1000.0)
            total.text = "Celková doba série ≈ " + (if (sec >= 120) Fmt.f0(sec / 60.0) + " min" else Fmt.f0(sec) + " s")
        }
        cS = ui.slider("Počet snímků", 199, s.seriesCount - 1, s.seriesCount.toString()) { p, _ ->
            s.seriesCount = p + 1
            cS?.setValueText?.invoke(s.seriesCount.toString())
            seriesInfo()
            saveSettings()
        }
        b.addView(cS.view)
        var gS: Ui.Slider? = null
        gS = ui.slider("Pauza mezi snímky", 60, (s.seriesGapMs / 1000L).toInt(), (s.seriesGapMs / 1000L).toString() + " s") { p, _ ->
            s.seriesGapMs = p * 1000L
            gS?.setValueText?.invoke("$p s")
            seriesInfo()
            saveSettings()
        }
        b.addView(gS.view)
        seriesInfo()
        b.addView(total)
        b.addView(ui.hint("Série se hodí pro hluboký vesmír (stackování), hvězdné stopy a meteory. Snímky se ukládají do Obrázky/AstroLens."))
    }

    private fun applyPreset(id: String) {
        val c = caps
        val p = Presets.byId(id)
        if (c == null || p == null) {
            Toast.makeText(this, "Fotoaparát se ještě nespustil", Toast.LENGTH_SHORT).show()
            return
        }
        val f = engine.frame()
        val wasRaw = cam.settings.saveRaw
        val note = Presets.apply(p, cam.settings, c, f.moon.mag, f.moon.illum)
        overlay.zoom = cam.settings.zoom
        changed()
        if (wasRaw != cam.settings.saveRaw) cam.rebuildSession()
        Toast.makeText(this, p.name + ": " + note, Toast.LENGTH_LONG).show()
        if (currentTab >= 0) showTab(currentTab)
    }

    private fun buildModesPanel(b: LinearLayout) {
        b.addView(ui.hint("Klepnutím na režim se nastaví ISO, čas, zoom, ostření, série i vyvážení bílé. Potom můžete vše doladit v záložce Expozice."))
        for (p in Presets.all) {
            val card = ui.vbox()
            card.background = ui.rounded(Ui.CARD, 12f)
            ui.pad(card, 14, 10, 14, 10)
            card.addView(ui.text(p.name, 15.5f, Ui.TEXT, true))
            card.addView(ui.text(p.description, 12.5f, Ui.DIM))
            card.layoutParams = ui.lp(-1, -2).also { it.setMargins(0, ui.dp(5), 0, 0) }
            card.setOnClickListener { applyPreset(p.id) }
            b.addView(card)
        }
        b.addView(ui.section("Jas náhledu"))
        val s = cam.settings
        b.addView(ui.choice(listOf("Rychlý", "Noční", "Super noční"), s.previewMode) { p ->
            s.previewMode = p
            changed()
        }.view)
        b.addView(ui.hint("Noční a super noční náhled zesvětlí živý obraz, aby šla složit záběr. Snímek se vždy exponuje podle nastavených hodnot."))
    }

    private fun buildLayersPanel(b: LinearLayout) {
        fun sw(label: String, key: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            b.addView(ui.toggle(label, get()) { v ->
                set(v)
                prefs.put(key, v)
            }.view)
        }
        b.addView(ui.section("Vrstvy"))
        sw("Překryv oblohy zapnutý", K.OVERLAY_ON, { opts.overlayOn }, { opts.overlayOn = it })
        sw("Hvězdy", K.STARS, { opts.stars }, { opts.stars = it })
        sw("Jména hvězd", K.STAR_NAMES, { opts.starNames }, { opts.starNames = it })
        sw("Souhvězdí (čáry)", K.CONSTELLATIONS, { opts.constellations }, { opts.constellations = it })
        sw("Jména souhvězdí", K.CONSTELLATION_NAMES, { opts.constellationNames }, { opts.constellationNames = it })
        sw("Planety, Slunce a Měsíc", K.PLANETS, { opts.planets }, { opts.planets = it })
        sw("Mlhoviny, galaxie, hvězdokupy", K.DSO, { opts.dso }, { opts.dso = it })
        sw("Jména objektů hlubokého vesmíru", K.DSO_NAMES, { opts.dsoNames }, { opts.dsoNames = it })
        sw("Mléčná dráha", K.MILKY_WAY, { opts.milkyWay }, { opts.milkyWay = it })
        sw("Obzor a světové strany", K.HORIZON, { opts.horizon }, { opts.horizon = it })
        sw("Ekliptika (dráha planet)", K.ECLIPTIC, { opts.ecliptic }, { opts.ecliptic = it })
        sw("Nebeský rovník", K.EQUATOR, { opts.equator }, { opts.equator = it })
        sw("Mřížka výška/azimut", K.GRID_ALTAZ, { opts.gridAltAz }, { opts.gridAltAz = it })
        sw("Mřížka rektascenze/deklinace", K.GRID_EQ, { opts.gridEq }, { opts.gridEq = it })
        sw("Zaměřovací kříž", K.CROSSHAIR, { opts.crosshair }, { opts.crosshair = it })

        b.addView(ui.section("Podrobnost"))
        b.addView(ui.toggle("Automatický limit jasnosti podle záběru", opts.magAuto) { v ->
            opts.magAuto = v
            prefs.put(K.MAG_AUTO, v)
        }.view)
        var mS: Ui.Slider? = null
        mS = ui.slider("Nejslabší hvězdy (ruční)", 70, ((opts.magLimit - 2f) * 10).toInt(), Fmt.f1(opts.magLimit.toDouble()) + " mag") { p, _ ->
            opts.magLimit = 2f + p / 10f
            mS?.setValueText?.invoke(Fmt.f1(opts.magLimit.toDouble()) + " mag")
            prefs.put(K.MAG_LIMIT, opts.magLimit)
        }
        b.addView(mS.view)
        b.addView(ui.text("Popisků", 14f).also { ui.pad(it, 2, 8, 2, 4) })
        b.addView(ui.choice(listOf("Málo", "Středně", "Hodně"), opts.labelDensity) { p ->
            opts.labelDensity = p
            prefs.put(K.LABEL_DENSITY, p)
        }.view)
        var aS: Ui.Slider? = null
        aS = ui.slider("Průhlednost překryvu", 70, ((opts.alpha - 0.3f) * 100).toInt(), Fmt.f0(opts.alpha * 100.0) + " %") { p, _ ->
            opts.alpha = 0.3f + p / 100f
            aS?.setValueText?.invoke(Fmt.f0(opts.alpha * 100.0) + " %")
            prefs.put(K.OVERLAY_ALPHA, opts.alpha)
        }
        b.addView(aS.view)

        b.addView(ui.section("Souhlas se skutečným záběrem"))
        var fS: Ui.Slider? = null
        fS = ui.slider("Doladění zorného pole", 60, ((opts.fovScale - 0.7f) * 100).toInt(), Fmt.f2(opts.fovScale.toDouble()) + "×") { p, _ ->
            opts.fovScale = 0.7f + p / 100f
            fS?.setValueText?.invoke(Fmt.f2(opts.fovScale.toDouble()) + "×")
            prefs.put(K.FOV_SCALE, opts.fovScale)
        }
        b.addView(fS.view)
        b.addView(ui.hint("Pokud jsou hvězdy ve středu na svém místě, ale na okrajích se rozcházejí s obrazem, upravte tuto hodnotu. Větší číslo roztáhne překryv."))
    }

    // ------------------------------------------------------------------ search

    private fun buildSearchPanel(b: LinearLayout) {
        val et = ui.edit("Hledat: Jupiter, Orion, M31, Vega…", "", false)
        et.layoutParams = ui.lp(-1, -2)
        b.addView(et)
        val results = ui.vbox()
        b.addView(results)
        fun refresh(q: String) {
            results.removeAllViews()
            val cat = engine.catalog
            if (cat == null) {
                results.addView(ui.hint("Katalog se ještě načítá…"))
                return
            }
            if (q.isBlank()) {
                buildTonight(results, cat)
            } else {
                val nq = TextUtil.norm(q.trim())
                val matches = searchItems.filter { it.norm.contains(nq) }
                    .sortedWith(compareBy({ if (TextUtil.norm(it.title).startsWith(nq)) 0 else 1 }, { it.title.length }))
                    .take(40)
                if (matches.isEmpty()) results.addView(ui.hint("Nic nenalezeno."))
                for (m in matches) results.addView(resultRow(m.title, m.subtitle, SkyTarget(m.kind, m.index)))
            }
        }
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { refresh(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        refresh("")
    }

    private fun resultRow(title: String, sub: String, t: SkyTarget): View {
        val row = ui.vbox()
        row.background = ui.rounded(Ui.CARD, 10f)
        ui.pad(row, 12, 8, 12, 8)
        row.layoutParams = ui.lp(-1, -2).also { it.setMargins(0, ui.dp(4), 0, 0) }
        row.addView(ui.text(title, 15f, Ui.TEXT, true))
        row.addView(ui.text(sub, 12f, Ui.DIM))
        row.setOnClickListener {
            showTab(-1)
            select(t)
        }
        return row
    }

    private fun buildTonight(results: LinearLayout, cat: Catalog) {
        val f = engine.frame()
        val sunAlt = f.sunAltDeg()
        results.addView(ui.section("Právě teď na obloze"))
        results.addView(ui.hint(Ephemeris.twilightName(sunAlt) + " · Slunce " + Fmt.signedDeg(sunAlt) + " · Měsíc " + f.moon.extra + " (" + Fmt.f0(f.moon.illum * 100.0) + " %)"))
        if (sunAlt > -12 && sunAlt < 0) results.addView(ui.hint("Pro slabé objekty počkejte na astronomickou tmu (Slunce pod −18°)."))

        // planets and Moon
        for ((i, bd) in f.bodies.withIndex()) {
            if (bd.kind == BodyKind.SUN) continue
            val enu = f.bodyEnu(bd)
            val alt = AstroMath.altitudeDeg(enu)
            if (alt > 3.0) {
                val sub = (if (bd.kind == BodyKind.MOON) bd.extra else Fmt.f1(bd.mag) + " mag") + " · " + InfoText.altAzLine(enu)
                results.addView(resultRow(bd.nameCz, sub, SkyTarget(SearchItem.BODY, i)))
            }
        }

        // bright deep sky objects
        class Cand(val idx: Int, val mag: Float, val alt: Double)
        val cands = ArrayList<Cand>()
        for ((i, d) in cat.dsos.withIndex()) {
            if (d.mag > 8.0f || d.mag >= 90f) continue
            val alt = AstroMath.altitudeDeg(f.j2000ToEnu(d.raDeg, d.decDeg))
            if (alt > 20.0) cands.add(Cand(i, d.mag, alt))
        }
        cands.sortBy { it.mag }
        if (cands.isNotEmpty()) results.addView(ui.section("Mlhoviny, galaxie a hvězdokupy"))
        for (cd in cands.take(14)) {
            val d = cat.dsos[cd.idx]
            results.addView(resultRow(d.displayName(), d.idLabel() + " · " + d.typeCz() + " · " + Fmt.f1(d.mag.toDouble()) + " mag · " + Fmt.f0(cd.alt) + "° nad obzorem", SkyTarget(SearchItem.DSO, cd.idx)))
        }

        // bright stars
        var shown = 0
        var headerAdded = false
        for (i in cat.labelledStars) {
            if (cat.starMag[i] > 1.6f) break
            val alt = AstroMath.altitudeDeg(f.j2000ToEnu(cat.starRa[i].toDouble(), cat.starDec[i].toDouble()))
            if (alt < 10.0) continue
            if (!headerAdded) {
                results.addView(ui.section("Jasné hvězdy"))
                headerAdded = true
            }
            results.addView(resultRow(cat.starName(i), Fmt.f1(cat.starMag[i].toDouble()) + " mag · " + Fmt.f0(alt) + "° nad obzorem", SkyTarget(SearchItem.STAR, i)))
            if (++shown >= 8) break
        }
    }

    // ------------------------------------------------------------------ settings

    private fun buildSettingsPanel(b: LinearLayout) {
        b.addView(ui.section("Kalibrace směru"))
        val sel = selected
        b.addView(ui.hint("Překryv se nastavuje podle snímačů v telefonu, které mívají odchylku několik stupňů. Zaměřte jasný objekt (Měsíc, planetu, jasnou hvězdu) přesně do středu a klepněte na něj v překryvu, nebo ho najděte ve Hledat. Pak stiskněte tlačítko."))
        val calStatus = ui.hint(if (orientation.calibrated) "Kalibrace je aktivní." else "Kalibrace není provedena.")
        b.addView(calStatus)
        val calRow = ui.hbox()
        val calBtn = ui.accentButton(if (sel != null) "Kalibrovat na vybraný objekt" else "Nejdřív vyberte objekt") {
            val t = selected
            val cat = engine.catalog
            if (t == null || cat == null) {
                Toast.makeText(this, "Vyberte objekt klepnutím v překryvu nebo ve Hledat", Toast.LENGTH_SHORT).show()
            } else {
                val enu = InfoText.enuOf(engine.frame(), cat, t)
                if (enu != null) {
                    orientation.calibrateTo(enu)
                    calStatus.text = "Kalibrace je aktivní."
                    Toast.makeText(this, "Překryv zarovnán na vybraný objekt", Toast.LENGTH_SHORT).show()
                }
            }
        }
        calBtn.layoutParams = ui.lp(0, -2, 1f).also { it.rightMargin = ui.dp(6) }
        calRow.addView(calBtn)
        val resetBtn = ui.button("Zrušit") {
            orientation.resetCalibration()
            calStatus.text = "Kalibrace není provedena."
        }
        calRow.addView(resetBtn)
        b.addView(calRow)

        b.addView(ui.section("Snímač natočení"))
        b.addView(ui.choice(listOf("Kompas + gyroskop", "Jen gyroskop"), prefs.int(K.SENSOR_SOURCE, 0)) { p ->
            prefs.put(K.SENSOR_SOURCE, p)
            orientation.start(p)
        }.view)
        b.addView(ui.hint("Při jen gyroskopu neví telefon, kde je sever. Nejdřív proveďte kalibraci na známý objekt, pak je překryv velmi stabilní i v blízkosti kovu."))
        var smS: Ui.Slider? = null
        smS = ui.slider("Vyhlazení pohybu", 97, (prefs.float(K.SMOOTHING, 0.15f) * 100).toInt() - 3, smoothText(prefs.float(K.SMOOTHING, 0.15f))) { p, _ ->
            val v = (p + 3) / 100f
            prefs.put(K.SMOOTHING, v)
            orientation.smoothing = v.toDouble()
            smS?.setValueText?.invoke(smoothText(v))
        }
        b.addView(smS.view)
        b.addView(ui.toggle("Opravit magnetickou deklinaci", prefs.bool(K.USE_DECLINATION, true)) { v ->
            prefs.put(K.USE_DECLINATION, v)
            updateDeclination()
        }.view)

        b.addView(ui.section("Poloha"))
        val auto = prefs.bool(K.LOC_AUTO, true)
        b.addView(ui.toggle("Určovat polohu automaticky (GPS)", auto) { v ->
            prefs.put(K.LOC_AUTO, v)
            if (v) {
                requestNeededPermissions()
                location.start()
            } else location.stop()
            showTab(4)
        }.view)
        b.addView(ui.hint("Aktuálně: " + placeName()))
        if (!auto) {
            val lat = ui.edit("Šířka (°)", engine.latDeg.toString(), true)
            val lon = ui.edit("Délka (°)", engine.lonDeg.toString(), true)
            val row = ui.hbox()
            lat.layoutParams = ui.lp(0, -2, 1f).also { it.rightMargin = ui.dp(6) }
            lon.layoutParams = ui.lp(0, -2, 1f)
            row.addView(lat)
            row.addView(lon)
            b.addView(row)
            b.addView(ui.button("Použít polohu") {
                val la = lat.text.toString().replace(',', '.').toDoubleOrNull()
                val lo = lon.text.toString().replace(',', '.').toDoubleOrNull()
                if (la == null || lo == null || abs(la) > 90 || abs(lo) > 180) {
                    Toast.makeText(this, "Neplatné souřadnice", Toast.LENGTH_SHORT).show()
                } else {
                    engine.latDeg = la
                    engine.lonDeg = lo
                    prefs.put(K.LOC_LAT, la.toFloat())
                    prefs.put(K.LOC_LON, lo.toFloat())
                    updateDeclination()
                    Toast.makeText(this, "Poloha nastavena", Toast.LENGTH_SHORT).show()
                    showTab(4)
                }
            }.also { it.layoutParams = ui.lp(-1, -2).also { p -> p.topMargin = ui.dp(6) } })
        }

        b.addView(ui.section("Čas"))
        b.addView(ui.hint("Posun času ovlivní jen překryv, ne skutečné snímky. Hodí se pro plánování, třeba jak bude obloha vypadat za dvě hodiny."))
        val timeRow = ui.hbox()
        val tv = ui.text(dateFmt.format(Date(engine.nowMs())), 14f, Ui.ACCENT, true)
        fun shift(ms: Long) {
            engine.timeOffsetMs += ms
            tv.text = dateFmt.format(Date(engine.nowMs()))
        }
        timeRow.addView(tv)
        b.addView(timeRow)
        val steps = ui.hbox()
        val defs = listOf("−1 d" to -86_400_000L, "−1 h" to -3_600_000L, "+1 h" to 3_600_000L, "+1 d" to 86_400_000L)
        for ((l, ms) in defs) {
            val bt = ui.button(l) { shift(ms) }
            bt.layoutParams = ui.lp(0, -2, 1f).also { it.setMargins(ui.dp(2), 0, ui.dp(2), 0) }
            steps.addView(bt)
        }
        b.addView(steps)
        b.addView(ui.button("Zpět na skutečný čas") {
            engine.timeOffsetMs = 0L
            tv.text = dateFmt.format(Date(engine.nowMs()))
        }.also { it.layoutParams = ui.lp(-1, -2).also { p -> p.topMargin = ui.dp(6) } })

        b.addView(ui.section("Vzhled"))
        b.addView(ui.toggle("Noční režim (červený)", opts.night) { v ->
            opts.night = v
            prefs.put(K.NIGHT_MODE, v)
            applyNight()
        }.view)

        val c = caps
        if (c != null) {
            b.addView(ui.section("Fotoaparát"))
            if (lenses.size > 1) {
                val idx = max(0, lenses.indexOfFirst { it.id == c.id })
                b.addView(ui.text("Objektiv", 14f).also { ui.pad(it, 2, 4, 2, 4) })
                b.addView(ui.choice(lenses.map { it.label }, idx) { p ->
                    prefs.put(K.LENS_ID, lenses[p].id)
                    cam.settings.zoom = 1f
                    tryOpen()
                }.view)
            }
            b.addView(ui.text("Potlačení šumu", 14f).also { ui.pad(it, 2, 8, 2, 4) })
            val nrModes = intArrayOf(0, 1, 2)
            b.addView(ui.choice(listOf("Vypnuto", "Rychlé", "Kvalitní"), max(0, nrModes.indexOf(cam.settings.noiseReduction))) { p ->
                cam.settings.noiseReduction = nrModes[p]
                changed()
            }.view)
            if (c.oisSupported) {
                b.addView(ui.toggle("Optická stabilizace (na stativu vypnout)", cam.settings.ois) { v ->
                    cam.settings.ois = v
                    changed()
                }.view)
            }
            b.addView(ui.hint(
                "Ohnisko " + Fmt.f1(c.focalMm.toDouble()) + " mm (ekv. " + Fmt.f0(c.equivMm.toDouble()) + " mm) · clona f/" + Fmt.f1(c.aperture.toDouble()) +
                    " · zorné pole " + Fmt.f0(c.fovLongDeg) + "° · ISO " + c.isoMin + "–" + c.isoMax + " · čas " + Fmt.exposure(c.expMinNs) + "–" + Fmt.exposure(c.exposureSteps.last())
            ))
        }

        b.addView(ui.section("O aplikaci"))
        b.addView(ui.hint(
            "Hvězdy, názvy a souhvězdí: d3-celestial (Olaf Frohn, BSD-3). Mlhoviny a galaxie: OpenNGC (Mattia Verga, CC BY-SA 4.0). " +
                "Polohy planet, Slunce a Měsíce se počítají přímo v telefonu (JPL aproximace, Meeus), nepotřebují internet."
        ))
        b.addView(ui.spacer(10))
    }

    private fun smoothText(v: Float): String = if (v < 0.08f) "velmi silné" else if (v < 0.2f) "střední" else if (v < 0.5f) "slabé" else "žádné"
}

/** ScrollView that never grows beyond [maxPx]; shrinks to its content when shorter. */
class MaxHeightScroll(context: android.content.Context, var maxPx: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = View.MeasureSpec.makeMeasureSpec(maxPx, View.MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, spec)
    }
}
