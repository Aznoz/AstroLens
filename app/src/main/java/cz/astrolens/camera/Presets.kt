package cz.astrolens.camera

import android.hardware.camera2.CameraMetadata
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class Preset(val id: String, val name: String, val description: String)

object Presets {
    val all: List<Preset> = listOf(
        Preset("sky", "Hvězdná obloha", "Široký záběr, vysoké ISO, čas podle pravidla 500."),
        Preset("milkyway", "Mléčná dráha", "Nejvyšší rozumné ISO a nejdelší čas bez stop hvězd, RAW pokud jde."),
        Preset("constellations", "Souhvězdí", "Rychlý snímek 8 s s ISO 1600, čisté a méně zašuměné."),
        Preset("deepsky", "Hluboký vesmír (série)", "Série 20 snímků pro stackování mlhovin a galaxií, RAW pokud jde."),
        Preset("moon", "Měsíc", "Krátký čas podle aktuální fáze Měsíce, maximální zoom, ostření na nekonečno."),
        Preset("planets", "Planety", "Krátký čas, střední ISO, maximální zoom. Jupiter a Venuše jsou velmi jasné."),
        Preset("trails", "Hvězdné stopy (série)", "60 snímků po 30 s ke složení do stop hvězd."),
        Preset("meteors", "Meteory (série)", "Dlouhá série 100 snímků na široký záběr."),
        Preset("aurora", "Polární záře", "ISO 1600, 6 s, automatické vyvážení bílé."),
        Preset("twilight", "Soumrak / krajina", "Automatická expozice s ostřením AF."),
        Preset("auto", "Automatika", "Všechno automaticky, jako v běžném fotoaparátu.")
    )

    fun byId(id: String): Preset? = all.firstOrNull { it.id == id }

    /** Longest exposure (seconds) before stars start to trail: strict and classic (500 rule) limits. */
    fun trailLimits(c: CameraCaps, zoom: Float): Pair<Double, Double> {
        val f = max(1.0, c.equivMm.toDouble() * zoom.toDouble())
        return Pair(300.0 / f, 500.0 / f)
    }

    private fun ns(sec: Double): Long = (sec * 1e9).toLong()

    /**
     * Applies a preset to [s], clamped to what camera [c] supports.
     * [moonMag] and [moonIllum] describe the current Moon (used by the Moon preset).
     * Returns a short text describing what was set.
     */
    fun apply(p: Preset, s: CamSettings, c: CameraCaps, moonMag: Double, moonIllum: Double): String {
        val wide = c.zoomMin
        val tele = c.zoomMax
        s.autoExposure = false
        s.focusManual = c.minFocusDiopter > 0f
        s.focusDiopter = 0f
        s.seriesCount = 1
        s.seriesGapMs = 1000L
        s.timerSec = if (p.id == "twilight" || p.id == "auto") 0 else max(s.timerSec, 2)
        s.awbMode = CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
        s.noiseReduction = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
        s.ois = false
        s.previewMode = 1
        var note = ""

        when (p.id) {
            "sky" -> {
                s.zoom = wide
                s.iso = c.clampIso(min(3200, c.isoMax))
                s.exposureNs = c.clampExposure(ns(min(25.0, trailLimits(c, s.zoom).second)))
                s.saveRaw = c.hasRaw && s.saveRaw
                note = "ISO ${s.iso}, čas dle pravidla 500."
            }
            "milkyway" -> {
                s.zoom = wide
                s.iso = c.clampIso(min(6400, c.isoMax))
                s.exposureNs = c.clampExposure(ns(min(25.0, trailLimits(c, s.zoom).second)))
                s.saveRaw = c.hasRaw
                s.previewMode = 2
                note = "ISO ${s.iso}" + if (c.hasRaw) ", DNG zapnuto." else "."
            }
            "constellations" -> {
                s.zoom = max(1f, wide)
                s.iso = c.clampIso(1600)
                s.exposureNs = c.clampExposure(ns(8.0))
                note = "ISO ${s.iso}, 8 s."
            }
            "deepsky" -> {
                s.zoom = c.clampZoom(1f)
                s.iso = c.clampIso(1600)
                s.exposureNs = c.clampExposure(ns(min(30.0, trailLimits(c, s.zoom).second)))
                s.seriesCount = 20
                s.seriesGapMs = 500L
                s.saveRaw = c.hasRaw
                s.previewMode = 2
                note = "Série 20 snímků, ISO ${s.iso}."
            }
            "moon" -> {
                s.zoom = tele
                s.iso = c.clampIso(100)
                val ratio = 10.0.pow(-0.4 * (moonMag + 12.7))
                val surface = max(0.02, ratio / max(moonIllum, 0.05))
                val t = (1.0 / 2500.0) * (100.0 / s.iso) / surface
                s.exposureNs = c.clampExposure(ns(t))
                s.awbMode = CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                s.previewMode = 0
                note = "ISO ${s.iso}, čas podle fáze, zoom ${"%.1f".format(s.zoom)}×."
            }
            "planets" -> {
                s.zoom = tele
                s.iso = c.clampIso(400)
                s.exposureNs = c.clampExposure(ns(1.0 / 60.0))
                s.awbMode = CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                s.previewMode = 0
                note = "ISO ${s.iso}, 1/60 s, zoom ${"%.1f".format(s.zoom)}×."
            }
            "trails" -> {
                s.zoom = wide
                s.iso = c.clampIso(800)
                s.exposureNs = c.clampExposure(ns(30.0))
                s.seriesCount = 60
                s.seriesGapMs = 200L
                s.saveRaw = false
                note = "60 snímků, ISO ${s.iso}."
            }
            "meteors" -> {
                s.zoom = wide
                s.iso = c.clampIso(3200)
                s.exposureNs = c.clampExposure(ns(min(15.0, trailLimits(c, s.zoom).second)))
                s.seriesCount = 100
                s.seriesGapMs = 300L
                s.saveRaw = false
                note = "100 snímků, ISO ${s.iso}."
            }
            "aurora" -> {
                s.zoom = wide
                s.iso = c.clampIso(1600)
                s.exposureNs = c.clampExposure(ns(6.0))
                s.awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
                note = "ISO ${s.iso}, 6 s."
            }
            "twilight" -> {
                s.autoExposure = true
                s.evSteps = 0
                s.zoom = c.clampZoom(1f)
                s.focusManual = false
                s.awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
                s.previewMode = 0
                note = "Automatická expozice."
            }
            else -> {
                s.autoExposure = true
                s.evSteps = 0
                s.zoom = c.clampZoom(1f)
                s.focusManual = false
                s.awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
                s.previewMode = 0
                s.saveRaw = false
                note = "Vše automaticky."
            }
        }
        if (!c.hasManual && !s.autoExposure) {
            s.autoExposure = true
            note += " (Tento fotoaparát nepodporuje ruční expozici, běží automatika.)"
        }
        return note
    }

    /** Preset id that suits a given kind of target. */
    fun suggestFor(isMoon: Boolean, isPlanet: Boolean, isDso: Boolean): String = when {
        isMoon -> "moon"
        isPlanet -> "planets"
        isDso -> "deepsky"
        else -> "constellations"
    }
}
