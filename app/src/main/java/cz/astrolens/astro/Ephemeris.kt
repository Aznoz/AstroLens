package cz.astrolens.astro

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Kind of solar-system body. */
object BodyKind {
    const val SUN = 0
    const val MOON = 1
    const val PLANET = 2
}

/**
 * State of a solar-system body at one instant.
 * [vecDate] is a unit vector in the equatorial frame of date (geocentric).
 */
class BodyState(
    val id: String,
    val nameCz: String,
    val kind: Int,
    val vecDate: Vec3,
    val distAu: Double,
    val helioAu: Double,
    val mag: Double,
    val illum: Double,
    val phaseAngleDeg: Double,
    val elongDeg: Double,
    val angDiamArcsec: Double,
    /** Distance in Earth radii when topocentric parallax matters (Moon), otherwise 0. */
    val parallaxEarthRadii: Double,
    val eclLonJ2000: Double,
    val extra: String
) {
    val raDeg: Double get() = AstroMath.raDecOf(vecDate).first
    val decDeg: Double get() = AstroMath.raDecOf(vecDate).second
}

class MoonGeo(val lonDeg: Double, val latDeg: Double, val distKm: Double)

object Ephemeris {
    private const val EPS2000_DEG = 23.4392911

    val PLANET_IDS = arrayOf("mercury", "venus", "mars", "jupiter", "saturn", "uranus", "neptune")
    val PLANET_NAMES_CZ = arrayOf("Merkur", "Venuše", "Mars", "Jupiter", "Saturn", "Uran", "Neptun")
    private val PLANET_ELEMENT_INDEX = intArrayOf(0, 1, 3, 4, 5, 6, 7)
    private val PLANET_RADIUS_KM = doubleArrayOf(2439.7, 6051.8, 3396.2, 71492.0, 60268.0, 25559.0, 24764.0)

    // JPL "Keplerian elements for approximate positions of the major planets", valid 1800-2050.
    // a, da, e, de, I, dI, L, dL, long.peri, dlong.peri, long.node, dlong.node  (AU, deg, per century)
    private val ELEMENTS = arrayOf(
        doubleArrayOf(0.38709927, 0.00000037, 0.20563593, 0.00001906, 7.00497902, -0.00594749, 252.25032350, 149472.67411175, 77.45779628, 0.16047689, 48.33076593, -0.12534081),
        doubleArrayOf(0.72333566, 0.00000390, 0.00677672, -0.00004107, 3.39467605, -0.00078890, 181.97909950, 58517.81538729, 131.60246718, 0.00268329, 76.67984255, -0.27769418),
        doubleArrayOf(1.00000261, 0.00000562, 0.01671123, -0.00004392, -0.00001531, -0.01294668, 100.46457166, 35999.37244981, 102.93768193, 0.32327364, 0.0, 0.0),
        doubleArrayOf(1.52371034, 0.00001847, 0.09339410, 0.00007882, 1.84969142, -0.00813131, -4.55343205, 19140.30268499, -23.94362959, 0.44441088, 49.55953891, -0.29257343),
        doubleArrayOf(5.20288700, -0.00011607, 0.04838624, -0.00013253, 1.30439695, -0.00183714, 34.39644051, 3034.74612775, 14.72847983, 0.21252668, 100.47390909, 0.20469106),
        doubleArrayOf(9.53667594, -0.00125060, 0.05386179, -0.00050991, 2.48599187, 0.00193609, 49.95424423, 1222.49362201, 92.59887831, -0.41897216, 113.66242448, -0.28867794),
        doubleArrayOf(19.18916464, -0.00196176, 0.04725744, -0.00004397, 0.77263783, -0.00242939, 313.23810451, 428.48202785, 170.95427630, 0.40805281, 74.01692503, 0.04240589),
        doubleArrayOf(30.06992276, 0.00026291, 0.00859048, 0.00005105, 1.77004347, 0.00035372, -55.12002969, 218.45945325, 44.96476227, -0.32241464, 131.78422574, -0.00508664)
    )

    private const val EARTH_INDEX = 2

    /** Heliocentric ecliptic (J2000) rectangular coordinates in AU for ELEMENTS row [idx]. */
    fun heliocentric(idx: Int, t: Double): Vec3 {
        val e = ELEMENTS[idx]
        val a = e[0] + e[1] * t
        val ecc = e[2] + e[3] * t
        val inc = (e[4] + e[5] * t) * DEG2RAD
        val l = e[6] + e[7] * t
        val peri = e[8] + e[9] * t
        val nodeDeg = e[10] + e[11] * t
        val node = nodeDeg * DEG2RAD
        val w = (peri - nodeDeg) * DEG2RAD
        var m = AstroMath.normDeg(l - peri)
        if (m > 180.0) m -= 360.0
        val mr = m * DEG2RAD
        var ea = mr + ecc * sin(mr)
        for (i in 0 until 40) {
            val d = (mr - (ea - ecc * sin(ea))) / (1.0 - ecc * cos(ea))
            ea += d
            if (abs(d) < 1e-12) break
        }
        val xp = a * (cos(ea) - ecc)
        val yp = a * sqrt(1.0 - ecc * ecc) * sin(ea)
        val cw = cos(w)
        val sw = sin(w)
        val co = cos(node)
        val so = sin(node)
        val ci = cos(inc)
        val si = sin(inc)
        val x = (cw * co - sw * so * ci) * xp + (-sw * co - cw * so * ci) * yp
        val y = (cw * so + sw * co * ci) * xp + (-sw * so + cw * co * ci) * yp
        val z = (sw * si) * xp + (cw * si) * yp
        return Vec3(x, y, z)
    }

    private fun eclipticJ2000ToEquatorial(v: Vec3): Vec3 {
        val e = EPS2000_DEG * DEG2RAD
        return Vec3(v.x, v.y * cos(e) - v.z * sin(e), v.y * sin(e) + v.z * cos(e))
    }

    // ------------------------------------------------------------------ Sun

    /** Apparent geocentric Sun (low-precision, about 0.01 degrees). */
    fun sun(jd: Double): BodyState {
        val t = AstroMath.centuries(jd)
        val l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        val m = 357.52911 + 35999.05029 * t - 0.0001537 * t * t
        val ecc = 0.016708634 - 0.000042037 * t - 0.0000001267 * t * t
        val mr = m * DEG2RAD
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(mr) +
            (0.019993 - 0.000101 * t) * sin(2.0 * mr) + 0.000289 * sin(3.0 * mr)
        val trueLon = l0 + c
        val nu = (m + c) * DEG2RAD
        val rAu = 1.000001018 * (1.0 - ecc * ecc) / (1.0 + ecc * cos(nu))
        val omega = (125.04 - 1934.136 * t) * DEG2RAD
        val lambda = trueLon - 0.00569 - 0.00478 * sin(omega)
        val eps = AstroMath.meanObliquityDeg(t) + 0.00256 * cos(omega)
        val v = AstroMath.eclipticToEquatorial(lambda, 0.0, eps)
        val diam = 2.0 * asin(695700.0 / (rAu * AU_KM)) * RAD2DEG * 3600.0
        return BodyState(
            "sun", "Slunce", BodyKind.SUN, v, rAu, 0.0, -26.74, 1.0, 0.0, 0.0, diam, 0.0,
            AstroMath.normDeg(lambda - 1.396971 * t), ""
        )
    }

    // ----------------------------------------------------------------- Moon

    // Meeus Table 47.A: D, M, M', F, sum_l (1e-6 deg), sum_r (1e-3 km)
    private val MOON_LR = intArrayOf(
        0, 0, 1, 0, 6288774, -20905355,
        2, 0, -1, 0, 1274027, -3699111,
        2, 0, 0, 0, 658314, -2955968,
        0, 0, 2, 0, 213618, -569925,
        0, 1, 0, 0, -185116, 48888,
        0, 0, 0, 2, -114332, -3149,
        2, 0, -2, 0, 58793, 246158,
        2, -1, -1, 0, 57066, -152138,
        2, 0, 1, 0, 53322, -170733,
        2, -1, 0, 0, 45758, -204586,
        0, 1, -1, 0, -40923, -129620,
        1, 0, 0, 0, -34720, 108743,
        0, 1, 1, 0, -30383, 104755,
        2, 0, 0, -2, 15327, 10321,
        0, 0, 1, 2, -12528, 0,
        0, 0, 1, -2, 10980, 79661,
        4, 0, -1, 0, 10675, -34782,
        0, 0, 3, 0, 10034, -23210,
        4, 0, -2, 0, 8548, -21636,
        2, 1, -1, 0, -7888, 24208,
        2, 1, 0, 0, -6766, 30824,
        1, 0, -1, 0, -5163, -8379,
        1, 1, 0, 0, 4987, -16675,
        2, -1, 1, 0, 4036, -12831,
        2, 0, 2, 0, 3994, -10445,
        4, 0, 0, 0, 3861, -11650,
        2, 0, -3, 0, 3665, 14403,
        0, 1, -2, 0, -2689, -7003,
        2, 0, -1, 2, -2602, 0,
        2, -1, -2, 0, 2390, 10056,
        1, 0, 1, 0, -2348, 6322,
        2, -2, 0, 0, 2236, -9884,
        0, 1, 2, 0, -2120, 5751,
        0, 2, 0, 0, -2069, 0,
        2, -2, -1, 0, 2048, -4950,
        2, 0, 1, -2, -1773, 4130,
        2, 0, 0, 2, -1595, 0,
        4, -1, -1, 0, 1215, -3958,
        0, 0, 2, 2, -1110, 0,
        3, 0, -1, 0, -892, 3258,
        2, 1, 1, 0, -810, 2616,
        4, -1, -2, 0, 759, -1897,
        0, 2, -1, 0, -713, -2117,
        2, 2, -1, 0, -700, 2354,
        2, 1, -2, 0, 691, 0,
        2, -1, 0, -2, 596, 0,
        4, 0, 1, 0, 549, -1423,
        0, 0, 4, 0, 537, -1117,
        4, -1, 0, 0, 520, -1571,
        1, 0, -2, 0, -487, -1739,
        2, 1, 0, -2, -399, 0,
        0, 0, 2, -2, -381, -4421,
        1, 1, 1, 0, 351, 0,
        3, 0, -2, 0, -340, 0,
        4, 0, -3, 0, 330, 0,
        2, -1, 2, 0, 327, 0,
        0, 2, 1, 0, -323, 1165,
        1, 1, -1, 0, 299, 0,
        2, 0, 3, 0, 294, 0,
        2, 0, -1, -2, 0, 8752
    )

    // Meeus Table 47.B: D, M, M', F, sum_b (1e-6 deg)
    private val MOON_B = intArrayOf(
        0, 0, 0, 1, 5128122,
        0, 0, 1, 1, 280602,
        0, 0, 1, -1, 277693,
        2, 0, 0, -1, 173237,
        2, 0, -1, 1, 55413,
        2, 0, -1, -1, 46271,
        2, 0, 0, 1, 32573,
        0, 0, 2, 1, 17198,
        2, 0, 1, -1, 9266,
        0, 0, 2, -1, 8822,
        2, -1, 0, -1, 8216,
        2, 0, -2, -1, 4324,
        2, 0, 1, 1, 4200,
        2, 1, 0, -1, -3359,
        2, -1, -1, 1, 2463,
        2, -1, 0, 1, 2211,
        2, -1, -1, -1, 2065,
        0, 1, -1, -1, -1870,
        4, 0, -1, -1, 1828,
        0, 1, 0, 1, -1794,
        0, 0, 0, 3, -1749,
        0, 1, -1, 1, -1565,
        1, 0, 0, 1, -1491,
        0, 1, 1, 1, -1475,
        0, 1, 1, -1, -1410,
        0, 1, 0, -1, -1344,
        1, 0, 0, -1, -1335,
        0, 0, 3, 1, 1107,
        4, 0, 0, -1, 1021,
        4, 0, -1, 1, 833,
        0, 0, 1, -3, 777,
        4, 0, -2, 1, 671,
        2, 0, 0, -3, 607,
        2, 0, 2, -1, 596,
        2, -1, 1, -1, 491,
        2, 0, -2, 1, -451,
        0, 0, 3, -1, 439,
        2, 0, 2, 1, 422,
        2, 0, -3, -1, 421,
        2, 1, -1, 1, -366,
        2, 1, 0, 1, -351,
        4, 0, 0, 1, 331,
        2, -1, 1, 1, 315,
        2, -2, 0, -1, 302,
        0, 0, 1, 3, -283,
        2, 1, 1, -1, -229,
        1, 1, 0, -1, 223,
        1, 1, 0, 1, 223,
        0, 1, -2, -1, -220,
        2, 1, -1, -1, -220,
        1, 0, 1, 1, -185,
        2, -1, -2, -1, 181,
        0, 1, 2, 1, -177,
        4, 0, -2, -1, 176,
        4, -1, -1, -1, 166,
        1, 0, 1, -1, -164,
        4, 0, 1, -1, 132,
        1, 0, -1, -1, -119,
        4, -1, 0, -1, 115,
        2, -2, 0, 1, 107
    )

    /** Geocentric Moon, mean equinox of date, no nutation (Meeus chapter 47). */
    fun moonGeocentric(jd: Double): MoonGeo {
        val t = AstroMath.centuries(jd)
        val t2 = t * t
        val t3 = t2 * t
        val t4 = t3 * t
        val lp = AstroMath.normDeg(218.3164477 + 481267.88123421 * t - 0.0015786 * t2 + t3 / 538841.0 - t4 / 65194000.0)
        val d = AstroMath.normDeg(297.8501921 + 445267.1114034 * t - 0.0018819 * t2 + t3 / 545868.0 - t4 / 113065000.0) * DEG2RAD
        val m = AstroMath.normDeg(357.5291092 + 35999.0502909 * t - 0.0001536 * t2 + t3 / 24490000.0) * DEG2RAD
        val mp = AstroMath.normDeg(134.9633964 + 477198.8675055 * t + 0.0087414 * t2 + t3 / 69699.0 - t4 / 14712000.0) * DEG2RAD
        val f = AstroMath.normDeg(93.2720950 + 483202.0175233 * t - 0.0036539 * t2 - t3 / 3526000.0 + t4 / 863310000.0) * DEG2RAD
        val a1 = AstroMath.normDeg(119.75 + 131.849 * t) * DEG2RAD
        val a2 = AstroMath.normDeg(53.09 + 479264.290 * t) * DEG2RAD
        val a3 = AstroMath.normDeg(313.45 + 481266.484 * t) * DEG2RAD
        val e = 1.0 - 0.002516 * t - 0.0000074 * t2
        val lpr = lp * DEG2RAD

        var sl = 0.0
        var sr = 0.0
        var i = 0
        while (i < MOON_LR.size) {
            val arg = MOON_LR[i] * d + MOON_LR[i + 1] * m + MOON_LR[i + 2] * mp + MOON_LR[i + 3] * f
            val mm = abs(MOON_LR[i + 1])
            val ef = if (mm == 1) e else if (mm == 2) e * e else 1.0
            sl += ef * MOON_LR[i + 4] * sin(arg)
            sr += ef * MOON_LR[i + 5] * cos(arg)
            i += 6
        }
        var sb = 0.0
        i = 0
        while (i < MOON_B.size) {
            val arg = MOON_B[i] * d + MOON_B[i + 1] * m + MOON_B[i + 2] * mp + MOON_B[i + 3] * f
            val mm = abs(MOON_B[i + 1])
            val ef = if (mm == 1) e else if (mm == 2) e * e else 1.0
            sb += ef * MOON_B[i + 4] * sin(arg)
            i += 5
        }
        sl += 3958.0 * sin(a1) + 1962.0 * sin(lpr - f) + 318.0 * sin(a2)
        sb += -2235.0 * sin(lpr) + 382.0 * sin(a3) + 175.0 * sin(a1 - f) + 175.0 * sin(a1 + f) +
            127.0 * sin(lpr - mp) - 115.0 * sin(lpr + mp)

        val lon = AstroMath.normDeg(lp + sl / 1000000.0)
        val lat = sb / 1000000.0
        val dist = 385000.56 + sr / 1000.0
        return MoonGeo(lon, lat, dist)
    }

    fun moon(jd: Double, sun: BodyState): BodyState {
        val t = AstroMath.centuries(jd)
        val g = moonGeocentric(jd)
        val omega = (125.04 - 1934.136 * t) * DEG2RAD
        val lon = g.lonDeg - 0.00478 * sin(omega)
        val eps = AstroMath.meanObliquityDeg(t) + 0.00256 * cos(omega)
        val v = AstroMath.eclipticToEquatorial(lon, g.latDeg, eps)

        val sunKm = sun.distAu * AU_KM
        val psi = AstroMath.separationDeg(v, sun.vecDate) * DEG2RAD
        val phaseAngle = atan2(sunKm * sin(psi), g.distKm - sunKm * cos(psi)) * RAD2DEG
        val illum = (1.0 + cos(phaseAngle * DEG2RAD)) / 2.0
        val sunLon = AstroMath.eclipticLongitude(sun.vecDate, eps)
        val moonLon = AstroMath.eclipticLongitude(v, eps)
        val waxing = AstroMath.normDeg(moonLon - sunLon) < 180.0
        val mag = -12.7 + 0.026 * abs(phaseAngle) + 4e-9 * phaseAngle * phaseAngle * phaseAngle * phaseAngle
        val diam = 2.0 * asin(1737.4 / g.distKm) * RAD2DEG * 3600.0
        return BodyState(
            "moon", "Měsíc", BodyKind.MOON, v, g.distKm / AU_KM, 0.0, mag, illum, phaseAngle,
            psi * RAD2DEG, diam, g.distKm / EARTH_RADIUS_KM,
            AstroMath.normDeg(lon - 1.396971 * t), phaseName(illum, waxing)
        )
    }

    fun phaseName(illum: Double, waxing: Boolean): String {
        return when {
            illum < 0.02 -> "Nov"
            illum > 0.98 -> "Úplněk"
            illum in 0.47..0.53 -> if (waxing) "První čtvrť" else "Poslední čtvrť"
            illum < 0.5 -> if (waxing) "Dorůstající srpek" else "Ubývající srpek"
            else -> if (waxing) "Dorůstající měsíc" else "Couvající měsíc"
        }
    }

    // -------------------------------------------------------------- Planets

    /**
     * Planet [index] (0 = Mercury ... 6 = Neptune) as seen from Earth at [jd].
     * Includes a one-step light-time correction.
     */
    fun planet(index: Int, jd: Double, sun: BodyState): BodyState {
        val el = PLANET_ELEMENT_INDEX[index]
        val t = AstroMath.centuries(jd)
        val earth = heliocentric(EARTH_INDEX, t)
        var pl = heliocentric(el, t)
        var geo = pl - earth
        val lightDays = geo.norm() * 0.0057755183
        val t2 = t - lightDays / 36525.0
        pl = heliocentric(el, t2)
        geo = pl - earth

        val delta = geo.norm()
        val r = pl.norm()
        val bigR = earth.norm()
        val eq2000 = eclipticJ2000ToEquatorial(geo).normalized()
        val vDate = AstroMath.precessionMatrix(t) * eq2000

        val cosI = ((r * r + delta * delta - bigR * bigR) / (2.0 * r * delta)).coerceIn(-1.0, 1.0)
        val iDeg = acos(cosI) * RAD2DEG
        val cosPsi = ((delta * delta + bigR * bigR - r * r) / (2.0 * delta * bigR)).coerceIn(-1.0, 1.0)
        val elong = acos(cosPsi) * RAD2DEG
        val illum = (1.0 + cosI) / 2.0
        val lg = 5.0 * log10(r * delta)
        val mag = when (index) {
            0 -> -0.42 + lg + 0.0380 * iDeg - 0.000273 * iDeg * iDeg + 0.000002 * iDeg * iDeg * iDeg
            1 -> -4.40 + lg + 0.0009 * iDeg + 0.000239 * iDeg * iDeg - 0.00000065 * iDeg * iDeg * iDeg
            2 -> -1.52 + lg + 0.016 * iDeg
            3 -> -9.40 + lg + 0.005 * iDeg
            4 -> {
                // Saturn with ring tilt; pole direction RA 40.589, Dec 83.537 (J2000)
                val pole = AstroMath.unitVector(40.589, 83.537)
                val sinB = -pole.dot(eq2000)
                val sb = abs(sinB)
                -8.88 + lg - 2.60 * sb + 1.25 * sinB * sinB
            }
            5 -> -7.19 + lg
            else -> -6.87 + lg
        }
        val diam = 2.0 * asin((PLANET_RADIUS_KM[index] / (delta * AU_KM)).coerceIn(0.0, 1.0)) * RAD2DEG * 3600.0
        val eclLon = AstroMath.eclipticLongitude(eq2000, EPS2000_DEG)
        return BodyState(
            PLANET_IDS[index], PLANET_NAMES_CZ[index], BodyKind.PLANET, vDate, delta, r, mag, illum, iDeg, elong,
            diam, 0.0, eclLon, ""
        )
    }

    /** Sun, Moon and the seven planets. */
    fun allBodies(jd: Double): List<BodyState> {
        val sun = sun(jd)
        val list = ArrayList<BodyState>(9)
        list.add(sun)
        list.add(moon(jd, sun))
        for (i in 0 until 7) list.add(planet(i, jd, sun))
        return list
    }

    // ------------------------------------------------------------- Helpers

    /** Approximate zodiac constellation (Czech name) for an ecliptic longitude of J2000. */
    fun zodiacConstellation(lonJ2000: Double): String {
        val l = AstroMath.normDeg(lonJ2000)
        return when {
            l >= 351.6 || l < 29.1 -> "Ryby"
            l < 53.5 -> "Beran"
            l < 90.1 -> "Býk"
            l < 118.0 -> "Blíženci"
            l < 138.0 -> "Rak"
            l < 173.9 -> "Lev"
            l < 217.8 -> "Panna"
            l < 241.1 -> "Váhy"
            l < 247.7 -> "Štír"
            l < 266.6 -> "Hadonoš"
            l < 299.7 -> "Střelec"
            l < 327.5 -> "Kozoroh"
            else -> "Vodnář"
        }
    }

    /** Topocentric East/North/Up unit vector of [body] for an observer; [m] is the equatorial-of-date to ENU matrix. */
    fun toEnu(body: BodyState, m: Mat3): Vec3 {
        val v = m * body.vecDate
        if (body.parallaxEarthRadii > 0.0) {
            val geo = v * body.parallaxEarthRadii
            val topo = Vec3(geo.x, geo.y, geo.z - 1.0)
            return topo.normalized()
        }
        return v
    }

    /** Observer-relative altitude (degrees) of [body] at [jd]. */
    fun altitudeOf(body: BodyState, jd: Double, latDeg: Double, lonEastDeg: Double): Double {
        val m = AstroMath.equatorialToEnu(AstroMath.lstDeg(jd, lonEastDeg), latDeg)
        return AstroMath.altitudeDeg(toEnu(body, m))
    }

    /** Result of a rise/set search (Julian dates, NaN if the event does not happen in the window). */
    class DayEvents(val rise: Double, val transit: Double, val set: Double, val transitAlt: Double)

    /**
     * Searches 24 hours from [jdStart] for rise, culmination and set of body [bodyId]
     * ("sun", "moon" or a planet id).
     */
    fun dayEvents(bodyId: String, jdStart: Double, latDeg: Double, lonEastDeg: Double): DayEvents {
        val step = 10.0 / 1440.0
        val n = 144
        val h0 = when (bodyId) {
            "sun", "moon" -> -0.833
            else -> -0.567
        }
        fun alt(jd: Double): Double {
            val sun = sun(jd)
            val b: BodyState = when (bodyId) {
                "sun" -> sun
                "moon" -> moon(jd, sun)
                else -> planet(PLANET_IDS.indexOf(bodyId), jd, sun)
            }
            return altitudeOf(b, jd, latDeg, lonEastDeg)
        }
        var rise = Double.NaN
        var set = Double.NaN
        var bestAlt = -999.0
        var bestJd = Double.NaN
        var prev = alt(jdStart)
        if (prev > bestAlt) { bestAlt = prev; bestJd = jdStart }
        for (i in 1..n) {
            val jd = jdStart + i * step
            val a = alt(jd)
            if (a > bestAlt) { bestAlt = a; bestJd = jd }
            if (prev < h0 && a >= h0 && rise.isNaN()) {
                rise = jd - step + step * (h0 - prev) / (a - prev)
            } else if (prev >= h0 && a < h0 && set.isNaN()) {
                set = jd - step + step * (prev - h0) / (prev - a)
            }
            prev = a
        }
        return DayEvents(rise, bestJd, set, bestAlt)
    }

    /** Angle in degrees below which the Sun is, for twilight classification. */
    fun twilightName(sunAltDeg: Double): String = when {
        sunAltDeg > -0.833 -> "Den"
        sunAltDeg > -6.0 -> "Občanský soumrak"
        sunAltDeg > -12.0 -> "Námořní soumrak"
        sunAltDeg > -18.0 -> "Astronomický soumrak"
        else -> "Noc (astronomická tma)"
    }

    /** Standard atmospheric refraction in degrees for true altitude [altDeg] (Saemundsson). */
    fun refractionDeg(altDeg: Double): Double {
        if (altDeg < -1.0) return 0.0
        val arg = (altDeg + 10.3 / (altDeg + 5.11)) * DEG2RAD
        return 1.02 / tan(arg) / 60.0
    }
}
