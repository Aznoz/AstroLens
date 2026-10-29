package cz.astrolens.util

import java.util.Locale

object Fmt {
    private val cs = Locale("cs", "CZ")

    fun f1(v: Double): String = String.format(cs, "%.1f", v)
    fun f2(v: Double): String = String.format(cs, "%.2f", v)
    fun f0(v: Double): String = String.format(cs, "%.0f", v)

    /** Exposure time in nanoseconds as "1/250 s" or "15 s". */
    fun exposure(ns: Long): String {
        val s = ns / 1e9
        return when {
            s >= 10.0 -> String.format(cs, "%.0f s", s)
            s >= 1.0 -> {
                val t = String.format(cs, "%.1f", s)
                (if (t.endsWith(",0")) t.dropLast(2) else t) + " s"
            }
            s >= 0.3 -> String.format(cs, "%.1f s", s)
            else -> "1/" + Math.round(1.0 / s) + " s"
        }
    }

    fun compass(azDeg: Double): String {
        val names = arrayOf("S", "SV", "V", "JV", "J", "JZ", "Z", "SZ")
        val i = ((azDeg + 22.5) / 45.0).toInt() % 8
        return names[i]
    }

    fun signedDeg(v: Double): String = (if (v >= 0) "+" else "−") + f1(Math.abs(v)) + "°"
}
