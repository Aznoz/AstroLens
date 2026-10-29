package cz.astrolens.ui

import cz.astrolens.astro.AstroMath
import cz.astrolens.astro.BodyKind
import cz.astrolens.astro.Catalog
import cz.astrolens.astro.Ephemeris
import cz.astrolens.astro.SearchItem
import cz.astrolens.astro.SkyEngine
import cz.astrolens.astro.Vec3
import cz.astrolens.util.Fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Text for the info card of a selected object. */
class Info(
    val title: String,
    val subtitle: String,
    val lines: List<String>,
    val text: String,
    /** Preset id that suits this object, or "" when none should be offered. */
    val preset: String,
    val warning: String
)

object InfoText {
    private const val AU_KM = 149597870.7

    private val BODY_TEXT = mapOf(
        "sun" to "Naše hvězda, kulová masa žhavého plynu o průměru 1,39 milionu km. Světlo z ní k nám letí asi 8 minut a 20 sekund.",
        "moon" to "Jediný přirozený satelit Země. Obíhá ve vzdálenosti zhruba 384 000 km a k Zemi je stále obrácen stejnou stranou. Na terminátoru, hranici mezi dnem a nocí, jsou krátery nejlépe vidět.",
        "mercury" to "Nejmenší a Slunci nejbližší planeta. Je vidět jen krátce po západu nebo před východem Slunce, protože se od něj nikdy příliš nevzdálí.",
        "venus" to "Nejjasnější planeta oblohy, zahalená hustými mraky kyseliny sírové. Jako Měsíc prochází fázemi, které jdou vidět i malým dalekohledem.",
        "mars" to "Rudá planeta. Její barvu dává oxid železitý v povrchovém prachu. Nejblíž Zemi je jednou za dva roky, a to je nejlepší doba k focení.",
        "jupiter" to "Největší planeta sluneční soustavy. I s krátkou expozicí a maximálním zoomem se dají zachytit čtyři Galileovy měsíce jako drobné body v řadě vedle kotouče.",
        "saturn" to "Planeta s nejnápadnějšími prstenci. Telefonem je uvidíte jako mírně protažený bod, k rozlišení prstenců je potřeba skutečný dalekohled.",
        "uranus" to "Ledový obr s jasností na hranici viditelnosti pouhým okem. Pro tmavou oblohu hledejte slabý nazelenalý bod.",
        "neptune" to "Nejvzdálenější planeta. Okem ji nezahlédnete, telefon s dlouhou expozicí ji zachytí jako obyčejnou hvězdičku."
    )

    private fun fmtTime(jd: Double): String {
        if (jd.isNaN()) return "—"
        val ms = ((jd - 2440587.5) * 86400000.0).toLong()
        val f = SimpleDateFormat("HH:mm", Locale("cs", "CZ"))
        f.timeZone = TimeZone.getDefault()
        return f.format(Date(ms))
    }

    fun bvColorName(bv: Float): String = when {
        bv < -0.15f -> "modrá"
        bv < 0.15f -> "modrobílá"
        bv < 0.45f -> "bílá"
        bv < 0.75f -> "žlutobílá"
        bv < 1.1f -> "žlutá až oranžová"
        bv < 1.5f -> "oranžová"
        else -> "červená"
    }

    fun enuOf(frame: SkyEngine.Frame, cat: Catalog, t: SkyTarget): Vec3? = when (t.kind) {
        SearchItem.STAR -> if (t.index in 0 until cat.starCount) {
            frame.j2000ToEnu(cat.starRa[t.index].toDouble(), cat.starDec[t.index].toDouble())
        } else null
        SearchItem.DSO -> if (t.index in cat.dsos.indices) {
            val d = cat.dsos[t.index]
            frame.j2000ToEnu(d.raDeg, d.decDeg)
        } else null
        SearchItem.CONSTELLATION -> if (t.index in cat.constellations.indices) {
            val c = cat.constellations[t.index]
            frame.j2000ToEnu(c.raDeg, c.decDeg)
        } else null
        SearchItem.BODY -> if (t.index in frame.bodies.indices) frame.bodyEnu(frame.bodies[t.index]) else null
        else -> null
    }

    fun altAzLine(enu: Vec3): String {
        val alt = AstroMath.altitudeDeg(enu)
        val az = AstroMath.azimuthDeg(enu)
        val s = "Výška " + Fmt.signedDeg(alt) + " · azimut " + Fmt.f0(az) + "° " + Fmt.compass(az)
        return if (alt < 0) "$s (pod obzorem)" else s
    }

    fun describe(frame: SkyEngine.Frame, cat: Catalog, t: SkyTarget, lat: Double, lon: Double): Info? {
        val enu = enuOf(frame, cat, t) ?: return null
        val lines = ArrayList<String>()
        lines.add(altAzLine(enu))
        when (t.kind) {
            SearchItem.BODY -> {
                val b = frame.bodies[t.index]
                val ev = Ephemeris.dayEvents(b.id, frame.jd - 0.0, lat, lon)
                val title = b.nameCz
                var sub = ""
                var warn = ""
                var preset = ""
                when (b.kind) {
                    BodyKind.SUN -> {
                        sub = "Hvězda · střed sluneční soustavy"
                        warn = "Nikdy nemiřte telefonem ani očima na Slunce bez speciálního slunečního filtru. Sluneční svit může poškodit zrak i snímač."
                    }
                    BodyKind.MOON -> {
                        sub = b.extra + " · osvětleno " + Fmt.f0(b.illum * 100.0) + " %"
                        preset = "moon"
                    }
                    else -> {
                        sub = "Planeta · " + Fmt.f1(b.mag) + " mag"
                        preset = "planets"
                    }
                }
                if (b.kind != BodyKind.SUN && b.kind != BodyKind.MOON) {
                    lines.add("Vzdálenost od Země: " + Fmt.f2(b.distAu) + " AU (" + Fmt.f0(b.distAu * AU_KM / 1e6) + " mil. km)")
                    lines.add("Úhlový průměr: " + Fmt.f1(b.angDiamArcsec) + "″ · osvětleno " + Fmt.f0(b.illum * 100.0) + " %")
                    lines.add("Elongace od Slunce: " + Fmt.f0(b.elongDeg) + "° · přibližně v souhvězdí " + Ephemeris.zodiacConstellation(b.eclLonJ2000))
                } else if (b.kind == BodyKind.MOON) {
                    lines.add("Vzdálenost: " + Fmt.f0(b.distAu * AU_KM) + " km · průměr na obloze " + Fmt.f1(b.angDiamArcsec / 60.0) + "′")
                    lines.add("Jasnost: " + Fmt.f1(b.mag) + " mag · přibližně v souhvězdí " + Ephemeris.zodiacConstellation(b.eclLonJ2000))
                } else {
                    lines.add("Vzdálenost: " + Fmt.f2(b.distAu) + " AU · průměr na obloze " + Fmt.f1(b.angDiamArcsec / 60.0) + "′")
                }
                lines.add("Východ " + fmtTime(ev.rise) + " · kulminace " + fmtTime(ev.transit) + " (" + Fmt.f0(ev.transitAlt) + "°) · západ " + fmtTime(ev.set))
                return Info(title, sub, lines, BODY_TEXT[b.id] ?: "", preset, warn)
            }
            SearchItem.STAR -> {
                val i = t.index
                val lab = cat.starLabels[cat.starHip[i]]
                val title = cat.starName(i)
                var sub = "Hvězda · " + Fmt.f1(cat.starMag[i].toDouble()) + " mag"
                if (lab != null && lab.con.isNotEmpty()) sub += " · " + cat.conNameCz(lab.con)
                lines.add("Barva: " + bvColorName(cat.starBv[i]) + " (B−V " + Fmt.f2(cat.starBv[i].toDouble()) + ")")
                lines.add("Rektascenze " + AstroMath.formatRa(cat.starRa[i].toDouble()) + " · deklinace " + AstroMath.formatDec(cat.starDec[i].toDouble()))
                lines.add("Katalog Hipparcos: HIP " + cat.starHip[i])
                val text = if (lab != null && lab.designation().isNotEmpty() && lab.name.isNotEmpty())
                    "Označení: " + lab.designation() + ". Hvězdu uvidíte jako bod, v telefonu nebude mít kotouček ani při maximálním zoomu."
                else "Hvězdu uvidíte jako bod, i při maximálním zoomu."
                return Info(title, sub, lines, text, "constellations", "")
            }
            SearchItem.DSO -> {
                val d = cat.dsos[t.index]
                val sub = d.idLabel() + " · " + d.typeCz() + (if (d.con.isNotEmpty()) " · " + cat.conNameCz(d.con) else "")
                if (d.mag < 90f) lines.add("Jasnost: " + Fmt.f1(d.mag.toDouble()) + " mag")
                if (d.majArcmin > 0f) {
                    val size = if (d.minArcmin > 0f && Math.abs(d.minArcmin - d.majArcmin) > 0.05f)
                        Fmt.f1(d.majArcmin.toDouble()) + "′ × " + Fmt.f1(d.minArcmin.toDouble()) + "′"
                    else Fmt.f1(d.majArcmin.toDouble()) + "′"
                    lines.add("Velikost: $size (Měsíc má asi 31′)")
                }
                if (d.distance.isNotEmpty()) lines.add("Vzdálenost: " + d.distance)
                lines.add("Rektascenze " + AstroMath.formatRa(d.raDeg) + " · deklinace " + AstroMath.formatDec(d.decDeg))
                val hint = if (d.mag > 8f) "Slabý objekt: potřebujete sérii snímků a stackování." else "Zkuste sérii s dlouhou expozicí."
                val text = if (d.description.isNotEmpty()) d.description + " " + hint else hint
                return Info(d.displayName(), sub, lines, text, "deepsky", "")
            }
            else -> {
                val c = cat.constellations[t.index]
                lines.add("Latinsky: " + c.nameLat + " (" + c.abbr + ")")
                lines.add("Střed: rektascenze " + AstroMath.formatRa(c.raDeg) + " · deklinace " + AstroMath.formatDec(c.decDeg))
                return Info(c.nameCz, "Souhvězdí", lines, "Čáry znázorňují obrazec souhvězdí. Fotografie z širokého záběru je ukáže lépe než zoom.", "constellations", "")
            }
        }
    }

    /** One-line summary used by the "Tonight" list. */
    fun brief(frame: SkyEngine.Frame, cat: Catalog, t: SkyTarget): String {
        val enu = enuOf(frame, cat, t) ?: return ""
        val alt = AstroMath.altitudeDeg(enu)
        val az = AstroMath.azimuthDeg(enu)
        return Fmt.f0(alt) + "° nad obzorem · " + Fmt.compass(az)
    }
}
