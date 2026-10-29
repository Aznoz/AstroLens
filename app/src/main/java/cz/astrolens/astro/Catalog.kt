package cz.astrolens.astro

import java.io.BufferedReader
import java.io.DataInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.text.Normalizer
import kotlin.math.cos
import kotlin.math.sin

/** Proper name / designation of a catalog star. */
class StarLabel(val hip: Int, val name: String, val bayer: String, val flam: String, val con: String) {
    /** Short label for the overlay, e.g. "Sirius" or "α CMa". */
    fun shortLabel(): String = when {
        name.isNotEmpty() -> name
        bayer.isNotEmpty() -> if (con.isNotEmpty()) "$bayer $con" else bayer
        flam.isNotEmpty() -> if (con.isNotEmpty()) "$flam $con" else flam
        else -> ""
    }

    fun designation(): String = when {
        bayer.isNotEmpty() -> if (con.isNotEmpty()) "$bayer $con" else bayer
        flam.isNotEmpty() -> if (con.isNotEmpty()) "$flam $con" else flam
        else -> ""
    }
}

class Constellation(
    val abbr: String,
    val nameCz: String,
    val nameLat: String,
    val nameEn: String,
    val raDeg: Double,
    val decDeg: Double
) {
    /** Each polyline is a flat array x,y,z,x,y,z... of J2000 equatorial unit vectors. */
    val lines = ArrayList<FloatArray>()
    val center: Vec3 = AstroMath.unitVector(raDeg, decDeg)
}

class Dso(
    val id: String,
    val nameCz: String,
    val nameEn: String,
    val type: String,
    val raDeg: Double,
    val decDeg: Double,
    val mag: Float,
    val majArcmin: Float,
    val minArcmin: Float,
    val posAngle: Float,
    val con: String,
    val distance: String,
    val description: String
) {
    val x: Float
    val y: Float
    val z: Float

    init {
        val v = AstroMath.unitVector(raDeg, decDeg)
        x = v.x.toFloat()
        y = v.y.toFloat()
        z = v.z.toFloat()
    }

    /** "M31", "NGC 7000", "IC 405" ... */
    fun idLabel(): String {
        if (id.length > 1 && id[0] == 'M' && id[1].isDigit()) return id
        var i = 0
        while (i < id.length && !id[i].isDigit()) i++
        return if (i in 1 until id.length) id.substring(0, i) + " " + id.substring(i) else id
    }

    fun displayName(): String = when {
        nameCz.isNotEmpty() -> nameCz
        nameEn.isNotEmpty() -> nameEn
        else -> idLabel()
    }

    fun typeCz(): String = when (type) {
        "G" -> "Galaxie"
        "GCl" -> "Kulová hvězdokupa"
        "OCl" -> "Otevřená hvězdokupa"
        "PN" -> "Planetární mlhovina"
        "HII" -> "Emisní mlhovina (oblast H II)"
        "EmN" -> "Emisní mlhovina"
        "Neb" -> "Mlhovina"
        "RfN" -> "Reflexní mlhovina"
        "DrkN" -> "Temná mlhovina"
        "SNR" -> "Pozůstatek supernovy"
        "Cl+N" -> "Hvězdokupa s mlhovinou"
        "*Ass" -> "Hvězdná asociace"
        "**" -> "Dvojhvězda"
        "GPair" -> "Dvojice galaxií"
        "GTrpl" -> "Trojice galaxií"
        "GGroup" -> "Skupina galaxií"
        "Nova" -> "Nova"
        else -> "Objekt hlubokého vesmíru"
    }
}

class MilkyWayLevel(val level: Int) {
    /** Each ring is a flat array of x,y,z unit vectors (J2000). */
    val rings = ArrayList<FloatArray>()
}

/** Entry for the search list. */
class SearchItem(
    val kind: Int,
    val index: Int,
    val title: String,
    val subtitle: String,
    val norm: String
) {
    companion object {
        const val STAR = 0
        const val DSO = 1
        const val BODY = 2
        const val CONSTELLATION = 3
    }
}

object TextUtil {
    private val DIACRITICS = Regex("\\p{InCombiningDiacriticalMarks}+")

    fun norm(s: String): String =
        DIACRITICS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()
}

class Catalog(
    val starCount: Int,
    val starX: FloatArray,
    val starY: FloatArray,
    val starZ: FloatArray,
    val starRa: FloatArray,
    val starDec: FloatArray,
    val starMag: FloatArray,
    val starBv: FloatArray,
    val starHip: IntArray,
    val starLabels: Map<Int, StarLabel>,
    val constellations: List<Constellation>,
    val dsos: List<Dso>,
    val milkyWay: List<MilkyWayLevel>
) {
    /** Index into the star arrays for each labelled star (sorted by magnitude), for fast label passes. */
    val labelledStars: IntArray

    init {
        val list = ArrayList<Int>()
        for (i in 0 until starCount) {
            val lab = starLabels[starHip[i]]
            if (lab != null && lab.shortLabel().isNotEmpty()) list.add(i)
        }
        labelledStars = list.toIntArray()
    }

    private val conByAbbr: Map<String, Constellation> by lazy { constellations.associateBy { it.abbr } }

    /** Czech constellation name for an IAU abbreviation (falls back to the abbreviation). */
    fun conNameCz(abbr: String): String = conByAbbr[abbr]?.nameCz ?: abbr

    fun starName(i: Int): String {
        val lab = starLabels[starHip[i]]
        if (lab != null) {
            if (lab.name.isNotEmpty()) return lab.name
            val d = lab.designation()
            if (d.isNotEmpty()) return d
        }
        return "HIP " + starHip[i]
    }

    fun searchItems(): List<SearchItem> {
        val out = ArrayList<SearchItem>()
        for (i in 0 until starCount) {
            val lab = starLabels[starHip[i]] ?: continue
            if (lab.name.isEmpty() && starMag[i] > 4.6f) continue
            if (lab.name.isEmpty() && lab.designation().isEmpty()) continue
            val title = starName(i)
            val sub = "Hvězda · " + String.format("%.1f", starMag[i].toDouble()) + " mag" +
                (if (lab.name.isNotEmpty() && lab.designation().isNotEmpty()) " · " + lab.designation() else "")
            val norm = TextUtil.norm(title + " " + lab.designation() + " hip" + starHip[i])
            out.add(SearchItem(SearchItem.STAR, i, title, sub, norm))
        }
        for ((i, d) in dsos.withIndex()) {
            if (d.mag > 11.0f && d.nameCz.isEmpty() && d.nameEn.isEmpty()) continue
            val title = d.displayName()
            val sub = d.idLabel() + " · " + d.typeCz() + (if (d.mag < 90f) " · " + String.format("%.1f", d.mag.toDouble()) + " mag" else "")
            val norm = TextUtil.norm(title + " " + d.nameEn + " " + d.idLabel() + " " + d.id)
            out.add(SearchItem(SearchItem.DSO, i, title, sub, norm))
        }
        for ((i, c) in constellations.withIndex()) {
            val norm = TextUtil.norm(c.nameCz + " " + c.nameLat + " " + c.nameEn + " " + c.abbr)
            out.add(SearchItem(SearchItem.CONSTELLATION, i, c.nameCz, "Souhvězdí · " + c.nameLat, norm))
        }
        return out
    }
}

object CatalogLoader {
    /** Loads the catalog from asset-like resources; [open] returns a fresh stream for a file name. */
    fun load(open: (String) -> InputStream): Catalog {
        // ---- stars
        val din = DataInputStream(open("stars.bin").buffered(1 shl 16))
        val n = din.readInt()
        val sx = FloatArray(n)
        val sy = FloatArray(n)
        val sz = FloatArray(n)
        val sra = FloatArray(n)
        val sdec = FloatArray(n)
        val smag = FloatArray(n)
        val sbv = FloatArray(n)
        val ship = IntArray(n)
        for (i in 0 until n) {
            val ra = din.readFloat()
            val dec = din.readFloat()
            val mag = din.readShort() / 100f
            val bv = din.readShort() / 100f
            val hip = din.readInt()
            val rr = ra.toDouble() * DEG2RAD
            val dd = dec.toDouble() * DEG2RAD
            val cd = cos(dd)
            sx[i] = (cd * cos(rr)).toFloat()
            sy[i] = (cd * sin(rr)).toFloat()
            sz[i] = sin(dd).toFloat()
            sra[i] = ra
            sdec[i] = dec
            smag[i] = mag
            sbv[i] = bv
            ship[i] = hip
        }
        din.close()

        // ---- star names
        val labels = HashMap<Int, StarLabel>()
        forEachLine(open("starnames.txt")) { line ->
            val p = line.split('|')
            if (p.size >= 5) {
                val hip = p[0].toIntOrNull()
                if (hip != null) labels[hip] = StarLabel(hip, p[1], p[2], p[3], p[4])
            }
        }

        // ---- constellations
        val cons = ArrayList<Constellation>()
        val byAbbr = HashMap<String, Constellation>()
        val pending = ArrayList<Pair<String, String>>()
        forEachLine(open("constellations.txt")) { line ->
            val p = line.split('|')
            if (p.isNotEmpty() && p[0] == "C" && p.size >= 7) {
                val c = Constellation(p[1], p[2], p[3], p[4], p[5].toDouble(), p[6].toDouble())
                cons.add(c)
                byAbbr[c.abbr] = c
            } else if (p.isNotEmpty() && p[0] == "P" && p.size >= 3) {
                pending.add(Pair(p[1], p[2]))
            }
        }
        for ((abbr, pts) in pending) {
            val c = byAbbr[abbr] ?: continue
            c.lines.add(parsePoints(pts))
        }

        // ---- deep-sky objects
        val dsos = ArrayList<Dso>()
        forEachLine(open("dso.txt")) { line ->
            val p = line.split('|')
            if (p.size >= 13) {
                dsos.add(
                    Dso(
                        p[0], p[1], p[2], p[3], p[4].toDouble(), p[5].toDouble(),
                        p[6].toFloatOrNull() ?: 99f, p[7].toFloatOrNull() ?: 0f, p[8].toFloatOrNull() ?: 0f,
                        p[9].toFloatOrNull() ?: 0f, p[10], p[11], p[12]
                    )
                )
            }
        }

        // ---- Milky Way outline
        val levels = HashMap<Int, MilkyWayLevel>()
        forEachLine(open("milkyway.txt")) { line ->
            val p = line.split('|')
            if (p.size >= 3 && p[0] == "MW") {
                val lvl = p[1].toInt()
                levels.getOrPut(lvl) { MilkyWayLevel(lvl) }.rings.add(parsePoints(p[2]))
            }
        }
        val mw = levels.values.sortedBy { it.level }

        return Catalog(n, sx, sy, sz, sra, sdec, smag, sbv, ship, labels, cons, dsos, mw)
    }

    private fun parsePoints(s: String): FloatArray {
        val parts = s.split(';')
        val out = FloatArray(parts.size * 3)
        for ((i, pt) in parts.withIndex()) {
            val c = pt.split(',')
            val v = AstroMath.unitVector(c[0].toDouble(), c[1].toDouble())
            out[i * 3] = v.x.toFloat()
            out[i * 3 + 1] = v.y.toFloat()
            out[i * 3 + 2] = v.z.toFloat()
        }
        return out
    }

    private fun forEachLine(input: InputStream, action: (String) -> Unit) {
        val r = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        r.use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotEmpty()) action(line)
            }
        }
    }
}
