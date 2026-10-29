package cz.astrolens.ui

import cz.astrolens.astro.Catalog
import cz.astrolens.astro.Mat3
import cz.astrolens.astro.SkyEngine
import cz.astrolens.astro.Vec3
import cz.astrolens.util.K
import cz.astrolens.util.Prefs

/** Which layers are drawn and how. Shared by the live overlay and the photo annotator. */
class OverlayOptions {
    var stars = true
    var starNames = true
    var constellations = true
    var constellationNames = true
    var planets = true
    var dso = true
    var dsoNames = true
    var milkyWay = true
    var horizon = true
    var ecliptic = false
    var equator = false
    var gridAltAz = false
    var gridEq = false
    var crosshair = true
    var overlayOn = true

    var magAuto = true
    var magLimit = 6.5f

    /** 0 = few labels, 1 = normal, 2 = many labels. */
    var labelDensity = 1
    var alpha = 0.9f
    var night = false

    /** User correction of the field of view (multiplies the half-angle tangent). */
    var fovScale = 1.0f

    fun load(p: Prefs) {
        stars = p.bool(K.STARS, true)
        starNames = p.bool(K.STAR_NAMES, true)
        constellations = p.bool(K.CONSTELLATIONS, true)
        constellationNames = p.bool(K.CONSTELLATION_NAMES, true)
        planets = p.bool(K.PLANETS, true)
        dso = p.bool(K.DSO, true)
        dsoNames = p.bool(K.DSO_NAMES, true)
        milkyWay = p.bool(K.MILKY_WAY, true)
        horizon = p.bool(K.HORIZON, true)
        ecliptic = p.bool(K.ECLIPTIC, false)
        equator = p.bool(K.EQUATOR, false)
        gridAltAz = p.bool(K.GRID_ALTAZ, false)
        gridEq = p.bool(K.GRID_EQ, false)
        crosshair = p.bool(K.CROSSHAIR, true)
        overlayOn = p.bool(K.OVERLAY_ON, true)
        magAuto = p.bool(K.MAG_AUTO, true)
        magLimit = p.float(K.MAG_LIMIT, 6.5f)
        labelDensity = p.int(K.LABEL_DENSITY, 1)
        alpha = p.float(K.OVERLAY_ALPHA, 0.9f)
        night = p.bool(K.NIGHT_MODE, false)
        fovScale = p.float(K.FOV_SCALE, 1.0f)
    }

    fun copyForSnapshot(): OverlayOptions {
        val o = OverlayOptions()
        o.stars = stars; o.starNames = starNames; o.constellations = constellations
        o.constellationNames = constellationNames; o.planets = planets; o.dso = dso; o.dsoNames = dsoNames
        o.milkyWay = false; o.horizon = horizon; o.ecliptic = ecliptic; o.equator = equator
        o.gridAltAz = false; o.gridEq = false; o.crosshair = false; o.overlayOn = true
        o.magAuto = magAuto; o.magLimit = magLimit; o.labelDensity = labelDensity; o.alpha = alpha
        o.night = false; o.fovScale = fovScale
        return o
    }
}

/** A selectable sky object: [kind] is one of SearchItem.STAR / DSO / BODY / CONSTELLATION. */
class SkyTarget(val kind: Int, val index: Int) {
    override fun equals(other: Any?): Boolean = other is SkyTarget && other.kind == kind && other.index == index
    override fun hashCode(): Int = kind * 100003 + index
}

class RenderInput(
    val catalog: Catalog,
    val frame: SkyEngine.Frame,
    val worldToDevice: Mat3,
    val cameraAxis: Vec3,
    /** Long-axis field of view of the camera at zoom 1, degrees. */
    val fovLongDeg: Double,
    val zoom: Float,
    val options: OverlayOptions,
    val goto: SkyTarget?,
    val selected: SkyTarget?,
    val hud: String?
)

/** Orientation and time captured when the shutter opens, used to label the saved photo. */
class SkySnapshot(
    val timeMs: Long,
    val worldToDevice: Mat3,
    val cameraAxis: Vec3,
    val fovLongDeg: Double,
    val zoom: Float,
    val latDeg: Double,
    val lonDeg: Double,
    val place: String
)
