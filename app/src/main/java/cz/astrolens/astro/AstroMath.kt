package cz.astrolens.astro

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

const val DEG2RAD = PI / 180.0
const val RAD2DEG = 180.0 / PI
const val AU_KM = 149597870.7
const val EARTH_RADIUS_KM = 6378.14

/** Immutable 3D vector (double precision). */
class Vec3(@JvmField val x: Double, @JvmField val y: Double, @JvmField val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm(): Double = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 {
        val n = norm()
        return if (n == 0.0) this else Vec3(x / n, y / n, z / n)
    }
}

/** Immutable 3x3 matrix, row-major. */
class Mat3(@JvmField val m: DoubleArray) {
    operator fun times(o: Mat3): Mat3 {
        val r = DoubleArray(9)
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                var s = 0.0
                for (k in 0 until 3) s += m[i * 3 + k] * o.m[k * 3 + j]
                r[i * 3 + j] = s
            }
        }
        return Mat3(r)
    }

    operator fun times(v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z
    )

    fun transpose() = Mat3(doubleArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]))

    companion object {
        fun identity() = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        /** Active rotation about X by [a] radians (counter-clockwise looking down the axis). */
        fun rotX(a: Double): Mat3 {
            val c = cos(a)
            val s = sin(a)
            return Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c))
        }

        /** Active rotation about Z by [a] radians. */
        fun rotZ(a: Double): Mat3 {
            val c = cos(a)
            val s = sin(a)
            return Mat3(doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0))
        }

        /** Rotation taking unit vector [from] onto unit vector [to] along the shortest arc. */
        fun between(from: Vec3, to: Vec3): Mat3 {
            val f = from.normalized()
            val t = to.normalized()
            val axis = f.cross(t)
            val s = axis.norm()
            val c = f.dot(t)
            if (s < 1e-12) return identity()
            val k = axis * (1.0 / s)
            // Rodrigues formula
            val kx = k.x
            val ky = k.y
            val kz = k.z
            val v = 1.0 - c
            return Mat3(
                doubleArrayOf(
                    c + kx * kx * v, kx * ky * v - kz * s, kx * kz * v + ky * s,
                    ky * kx * v + kz * s, c + ky * ky * v, ky * kz * v - kx * s,
                    kz * kx * v - ky * s, kz * ky * v + kx * s, c + kz * kz * v
                )
            )
        }
    }
}

object AstroMath {
    fun julianDay(unixMillis: Long): Double = unixMillis / 86400000.0 + 2440587.5

    fun centuries(jd: Double): Double = (jd - 2451545.0) / 36525.0

    fun normDeg(x: Double): Double {
        var r = x % 360.0
        if (r < 0) r += 360.0
        return r
    }

    fun normDeg180(x: Double): Double {
        var r = normDeg(x)
        if (r > 180.0) r -= 360.0
        return r
    }

    /** Greenwich mean sidereal time in degrees (Meeus 12.4). */
    fun gmstDeg(jd: Double): Double {
        val d = jd - 2451545.0
        val t = d / 36525.0
        return normDeg(280.46061837 + 360.98564736629 * d + 0.000387933 * t * t - t * t * t / 38710000.0)
    }

    /** Local mean sidereal time in degrees for east longitude [lonEastDeg]. */
    fun lstDeg(jd: Double, lonEastDeg: Double): Double = normDeg(gmstDeg(jd) + lonEastDeg)

    /** Mean obliquity of the ecliptic in degrees (Meeus 22.2). */
    fun meanObliquityDeg(t: Double): Double =
        23.0 + 26.0 / 60.0 + 21.448 / 3600.0 - (46.8150 * t + 0.00059 * t * t - 0.001813 * t * t * t) / 3600.0

    /** Unit vector for right ascension / declination (degrees). */
    fun unitVector(raDeg: Double, decDeg: Double): Vec3 {
        val ra = raDeg * DEG2RAD
        val dec = decDeg * DEG2RAD
        val cd = cos(dec)
        return Vec3(cd * cos(ra), cd * sin(ra), sin(dec))
    }

    fun raDecOf(v: Vec3): Pair<Double, Double> {
        val ra = normDeg(atan2(v.y, v.x) * RAD2DEG)
        val dec = asin((v.z / v.norm()).coerceIn(-1.0, 1.0)) * RAD2DEG
        return Pair(ra, dec)
    }

    /** Ecliptic (lon, lat in degrees) to equatorial unit vector for obliquity [epsDeg]. */
    fun eclipticToEquatorial(lonDeg: Double, latDeg: Double, epsDeg: Double): Vec3 {
        val l = lonDeg * DEG2RAD
        val b = latDeg * DEG2RAD
        val e = epsDeg * DEG2RAD
        val x = cos(b) * cos(l)
        val y = cos(b) * sin(l) * cos(e) - sin(b) * sin(e)
        val z = cos(b) * sin(l) * sin(e) + sin(b) * cos(e)
        return Vec3(x, y, z)
    }

    /** Ecliptic longitude (degrees) of an equatorial vector for obliquity [epsDeg]. */
    fun eclipticLongitude(v: Vec3, epsDeg: Double): Double {
        val e = epsDeg * DEG2RAD
        val y = v.y * cos(e) + v.z * sin(e)
        return normDeg(atan2(y, v.x) * RAD2DEG)
    }

    /** Precession matrix from J2000.0 to the mean equinox of date; [t] in Julian centuries since J2000. */
    fun precessionMatrix(t: Double): Mat3 {
        val zeta = (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) / 3600.0 * DEG2RAD
        val z = (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) / 3600.0 * DEG2RAD
        val th = (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) / 3600.0 * DEG2RAD
        val cz = cos(zeta)
        val sz = sin(zeta)
        val cZ = cos(z)
        val sZ = sin(z)
        val ct = cos(th)
        val st = sin(th)
        return Mat3(
            doubleArrayOf(
                cz * ct * cZ - sz * sZ, -sz * ct * cZ - cz * sZ, -st * cZ,
                cz * ct * sZ + sz * cZ, -sz * ct * sZ + cz * cZ, -st * sZ,
                cz * st, -sz * st, ct
            )
        )
    }

    /**
     * Matrix rotating an equatorial-of-date vector into the local horizon frame
     * with axes East, North, Up.
     */
    fun equatorialToEnu(lstDeg: Double, latDeg: Double): Mat3 {
        val l = lstDeg * DEG2RAD
        val p = latDeg * DEG2RAD
        val sl = sin(l)
        val cl = cos(l)
        val sp = sin(p)
        val cp = cos(p)
        return Mat3(
            doubleArrayOf(
                -sl, cl, 0.0,
                -sp * cl, -sp * sl, cp,
                cp * cl, cp * sl, sp
            )
        )
    }

    /** Altitude (degrees) of an East/North/Up vector. */
    fun altitudeDeg(enu: Vec3): Double = asin((enu.z / enu.norm()).coerceIn(-1.0, 1.0)) * RAD2DEG

    /** Azimuth (degrees, from North towards East) of an East/North/Up vector. */
    fun azimuthDeg(enu: Vec3): Double = normDeg(atan2(enu.x, enu.y) * RAD2DEG)

    fun enuFromAltAz(altDeg: Double, azDeg: Double): Vec3 {
        val a = altDeg * DEG2RAD
        val z = azDeg * DEG2RAD
        return Vec3(cos(a) * sin(z), cos(a) * cos(z), sin(a))
    }

    /** Angular separation in degrees between two vectors. */
    fun separationDeg(a: Vec3, b: Vec3): Double {
        val c = a.cross(b).norm()
        val d = a.dot(b)
        return atan2(c, d) * RAD2DEG
    }

    fun formatRa(raDeg: Double): String {
        val h = normDeg(raDeg) / 15.0
        var hh = h.toInt()
        var mm = ((h - hh) * 60.0).toInt()
        var ss = Math.round(((h - hh) * 60.0 - mm) * 60.0).toInt()
        if (ss == 60) { ss = 0; mm += 1 }
        if (mm == 60) { mm = 0; hh += 1 }
        if (hh == 24) hh = 0
        return String.format("%dh %02dm %02ds", hh, mm, ss)
    }

    fun formatDec(decDeg: Double): String {
        val sign = if (decDeg < 0) "-" else "+"
        val a = Math.abs(decDeg)
        var d = a.toInt()
        var m = ((a - d) * 60.0).toInt()
        var s = Math.round(((a - d) * 60.0 - m) * 60.0).toInt()
        if (s == 60) { s = 0; m += 1 }
        if (m == 60) { m = 0; d += 1 }
        return String.format("%s%d° %02d′ %02d″", sign, d, m, s)
    }
}
