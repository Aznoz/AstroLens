package cz.astrolens.astro

import kotlin.math.abs
import kotlin.math.log10

/**
 * Holds the observer (location, time offset) and the catalog and computes, per frame,
 * the matrices needed to turn catalog vectors into local horizon coordinates.
 */
class SkyEngine {
    @Volatile
    var catalog: Catalog? = null

    var latDeg: Double = 50.0755
    var lonDeg: Double = 14.4378
    var altM: Double = 0.0

    /** Time travel: added to the system clock. */
    var timeOffsetMs: Long = 0L

    private var bodiesCache: List<BodyState> = emptyList()
    private var bodiesJd: Double = Double.NaN

    class Frame(
        val timeMs: Long,
        val jd: Double,
        val lstDeg: Double,
        /** Equatorial of date -> East/North/Up. */
        val eqToEnu: Mat3,
        /** J2000 equatorial -> equatorial of date. */
        val precession: Mat3,
        val bodies: List<BodyState>,
        val epsDeg: Double
    ) {
        val sun: BodyState get() = bodies[0]
        val moon: BodyState get() = bodies[1]

        fun bodyEnu(b: BodyState): Vec3 = Ephemeris.toEnu(b, eqToEnu)

        fun sunAltDeg(): Double = AstroMath.altitudeDeg(bodyEnu(sun))

        /** Catalog (J2000) direction to local ENU. */
        fun j2000ToEnu(raDeg: Double, decDeg: Double): Vec3 =
            eqToEnu * (precession * AstroMath.unitVector(raDeg, decDeg))
    }

    fun nowMs(): Long = System.currentTimeMillis() + timeOffsetMs

    fun frame(): Frame {
        val ms = nowMs()
        val jd = AstroMath.julianDay(ms)
        if (bodiesJd.isNaN() || abs(jd - bodiesJd) > 15.0 / 86400.0) {
            bodiesCache = Ephemeris.allBodies(jd)
            bodiesJd = jd
        }
        return build(ms, jd, bodiesCache, latDeg, lonDeg)
    }

    /** A frame for an arbitrary instant and place (used for annotating saved photos). */
    fun frameAt(ms: Long, lat: Double, lon: Double): Frame {
        val jd = AstroMath.julianDay(ms)
        return build(ms, jd, Ephemeris.allBodies(jd), lat, lon)
    }

    private fun build(ms: Long, jd: Double, bodies: List<BodyState>, lat: Double, lon: Double): Frame {
        val lst = AstroMath.lstDeg(jd, lon)
        val t = AstroMath.centuries(jd)
        return Frame(
            ms, jd, lst, AstroMath.equatorialToEnu(lst, lat),
            AstroMath.precessionMatrix(t), bodies, AstroMath.meanObliquityDeg(t)
        )
    }

    companion object {
        /** Automatic star magnitude limit for a given field of view along the long axis (degrees). */
        fun autoStarLimit(fovLongDeg: Double): Double {
            val v = 6.0 + log2(60.0 / fovLongDeg.coerceAtLeast(2.0))
            return v.coerceIn(4.0, 8.0)
        }

        fun autoDsoLimit(fovLongDeg: Double): Double {
            val v = 7.5 + 1.2 * log2(60.0 / fovLongDeg.coerceAtLeast(2.0))
            return v.coerceIn(6.0, 12.0)
        }

        private fun log2(x: Double): Double = log10(x) / log10(2.0)
    }
}
