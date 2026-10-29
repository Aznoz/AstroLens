package cz.astrolens.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import cz.astrolens.astro.BodyKind
import cz.astrolens.astro.BodyState
import cz.astrolens.astro.Catalog
import cz.astrolens.astro.DEG2RAD
import cz.astrolens.astro.Dso
import cz.astrolens.astro.Mat3
import cz.astrolens.astro.RAD2DEG
import cz.astrolens.astro.SearchItem
import cz.astrolens.astro.SkyEngine
import cz.astrolens.astro.Vec3
import cz.astrolens.util.Fmt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Draws the sky (stars, constellations, deep-sky objects, planets, Moon, Sun, grids)
 * onto any Canvas. Used for the live camera overlay and for annotating saved photos.
 */
class SkyRenderer {
    companion object {
        private const val NEAR = 0.08f
        private const val MAX_PICKS = 500
        private const val MAX_LABELS = 600

        private val BV_ANCHORS = floatArrayOf(-0.3f, 0.0f, 0.3f, 0.6f, 1.0f, 1.5f, 2.0f)
        private val BV_COLORS = arrayOf(
            intArrayOf(150, 170, 255), intArrayOf(190, 205, 255), intArrayOf(245, 245, 255),
            intArrayOf(255, 240, 215), intArrayOf(255, 215, 160), intArrayOf(255, 175, 100), intArrayOf(255, 140, 70)
        )
        private val PLANET_COLORS = intArrayOf(
            Color.rgb(200, 200, 205), Color.rgb(255, 250, 220), Color.rgb(255, 120, 90), Color.rgb(255, 230, 190),
            Color.rgb(255, 225, 150), Color.rgb(160, 230, 240), Color.rgb(120, 150, 255)
        )
    }

    // ---- pick list (filled during render, read by the view for taps)
    val pickX = FloatArray(MAX_PICKS)
    val pickY = FloatArray(MAX_PICKS)
    val pickKind = IntArray(MAX_PICKS)
    val pickIndex = IntArray(MAX_PICKS)
    val pickPrio = IntArray(MAX_PICKS)
    var pickN = 0
        private set

    // ---- paints
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val lines = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val path = Path()

    // ---- per-render state
    private var cx = 0f
    private var cy = 0f
    private var fpx = 1f
    private var vw = 0f
    private var vh = 0f
    private var scale = 1f
    private var alphaK = 1f
    private var night = false
    private val mEnu = FloatArray(9)
    private val mEq = FloatArray(9)
    private val mJ = FloatArray(9)
    private val mEcl = FloatArray(9)

    private var segs = FloatArray(4 * 2048)
    private var segN = 0
    private var curCanvas: Canvas? = null
    private var curPaint: Paint? = null
    private var clipBuf = FloatArray(3 * 512)

    // ---- label placement
    private val lab = FloatArray(MAX_LABELS * 4)
    private var labN = 0

    // ---- static geometry (unit vectors), built once
    private var horizonLine: FloatArray? = null
    private var altAzLines: List<FloatArray> = emptyList()
    private var eqLines: List<FloatArray> = emptyList()
    private var equatorLine: FloatArray? = null
    private var eclipticLine: FloatArray? = null
    private var cachedCat: Catalog? = null
    private var isMessier = BooleanArray(0)
    private val starRgb = Array(64) { IntArray(3) }

    init {
        for (i in 0 until 64) {
            val bv = -0.3f + 2.3f * i / 63f
            var k = 0
            while (k < BV_ANCHORS.size - 2 && bv > BV_ANCHORS[k + 1]) k++
            val t = ((bv - BV_ANCHORS[k]) / (BV_ANCHORS[k + 1] - BV_ANCHORS[k])).coerceIn(0f, 1f)
            for (c in 0 until 3) starRgb[i][c] = (BV_COLORS[k][c] + (BV_COLORS[k + 1][c] - BV_COLORS[k][c]) * t).toInt()
        }
        buildStatic()
    }

    private fun buildStatic() {
        // horizon (ENU frame)
        val hz = FloatArray(3 * 181)
        for (i in 0..180) {
            val a = i * 2.0 * DEG2RAD
            hz[3 * i] = sin(a).toFloat(); hz[3 * i + 1] = cos(a).toFloat(); hz[3 * i + 2] = 0f
        }
        horizonLine = hz

        val aa = ArrayList<FloatArray>()
        for (alt in intArrayOf(30, 60)) aa.add(circleOfLatitude(alt.toDouble(), 3.0))
        var az = 0
        while (az < 360) {
            aa.add(meridianLine(az.toDouble(), 0.0, 90.0, 3.0, true))
            az += 30
        }
        altAzLines = aa

        // equatorial frame
        equatorLine = circleOfLatitude(0.0, 2.0)
        val eg = ArrayList<FloatArray>()
        for (dec in intArrayOf(-60, -30, 30, 60)) eg.add(circleOfLatitude(dec.toDouble(), 3.0))
        var ra = 0
        while (ra < 360) {
            eg.add(meridianLine(ra.toDouble(), -85.0, 85.0, 3.0, false))
            ra += 30
        }
        eqLines = eg

        // ecliptic (ecliptic frame, latitude 0)
        eclipticLine = circleOfLatitude(0.0, 2.0)
    }

    /** Circle at constant "latitude" in a frame whose Z axis is the pole and whose longitude runs along X->Y. */
    private fun circleOfLatitude(latDeg: Double, stepDeg: Double): FloatArray {
        val n = (360.0 / stepDeg).toInt() + 1
        val out = FloatArray(3 * n)
        val la = latDeg * DEG2RAD
        for (i in 0 until n) {
            val lo = i * stepDeg * DEG2RAD
            out[3 * i] = (cos(la) * cos(lo)).toFloat()
            out[3 * i + 1] = (cos(la) * sin(lo)).toFloat()
            out[3 * i + 2] = sin(la).toFloat()
        }
        return out
    }

    /**
     * Line of constant longitude. In the ENU frame azimuth runs clockwise from North, so
     * [enu] swaps to (sin az, cos az); otherwise it is plain (cos ra, sin ra).
     */
    private fun meridianLine(lonDeg: Double, fromLat: Double, toLat: Double, stepDeg: Double, enu: Boolean): FloatArray {
        val n = ((toLat - fromLat) / stepDeg).toInt() + 1
        val out = FloatArray(3 * n)
        val lo = lonDeg * DEG2RAD
        for (i in 0 until n) {
            val la = (fromLat + i * stepDeg) * DEG2RAD
            if (enu) {
                out[3 * i] = (cos(la) * sin(lo)).toFloat()
                out[3 * i + 1] = (cos(la) * cos(lo)).toFloat()
            } else {
                out[3 * i] = (cos(la) * cos(lo)).toFloat()
                out[3 * i + 1] = (cos(la) * sin(lo)).toFloat()
            }
            out[3 * i + 2] = sin(la).toFloat()
        }
        return out
    }

    // ------------------------------------------------------------------ helpers

    private fun toF(m: Mat3, out: FloatArray) {
        for (i in 0 until 9) out[i] = m.m[i].toFloat()
    }

    private fun col(r: Int, g: Int, b: Int, a: Float): Int {
        val alpha = (a * alphaK * 255f).toInt().coerceIn(0, 255)
        if (night) {
            val lum = r * 0.3f + g * 0.59f + b * 0.11f
            return Color.argb(alpha, min(255f, lum * 1.15f + 20f).toInt(), (lum * 0.13f).toInt(), (lum * 0.10f).toInt())
        }
        return Color.argb(alpha, r, g, b)
    }

    private fun starColor(bv: Float, a: Float): Int {
        val i = ((bv + 0.3f) / 2.3f * 63f).toInt().coerceIn(0, 63)
        val c = starRgb[i]
        return col(c[0], c[1], c[2], a)
    }

    private fun clearLabels() { labN = 0 }

    private fun labelFits(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        if (x2 < 0f || x1 > vw || y2 < 0f || y1 > vh) return false
        var i = 0
        while (i < labN) {
            if (x1 < lab[i + 2] && x2 > lab[i] && y1 < lab[i + 3] && y2 > lab[i + 1]) return false
            i += 4
        }
        return true
    }

    private fun labelAdd(x1: Float, y1: Float, x2: Float, y2: Float) {
        if (labN + 4 > lab.size) return
        lab[labN] = x1; lab[labN + 1] = y1; lab[labN + 2] = x2; lab[labN + 3] = y2
        labN += 4
    }

    /** Draws [s] near (x, y), trying three positions; returns false when no free spot exists. */
    private fun label(canvas: Canvas, s: String, x: Float, y: Float, off: Float): Boolean {
        val tw = text.measureText(s)
        val th = text.textSize
        for (k in 0 until 3) {
            val lx: Float
            val ly: Float
            when (k) {
                0 -> { lx = x + off; ly = y + th * 0.35f }
                1 -> { lx = x - off - tw; ly = y + th * 0.35f }
                else -> { lx = x - tw / 2f; ly = y - off - 2f }
            }
            val x1 = lx - 2f
            val y1 = ly - th * 0.95f
            val x2 = lx + tw + 2f
            val y2 = ly + th * 0.3f
            if (labelFits(x1, y1, x2, y2)) {
                labelAdd(x1, y1, x2, y2)
                canvas.drawText(s, lx, ly, text)
                return true
            }
        }
        return false
    }

    private fun addPick(x: Float, y: Float, kind: Int, index: Int, prio: Int) {
        if (pickN >= MAX_PICKS) return
        pickX[pickN] = x; pickY[pickN] = y; pickKind[pickN] = kind; pickIndex[pickN] = index; pickPrio[pickN] = prio
        pickN++
    }

    private fun inside(x: Float, y: Float, margin: Float): Boolean =
        x >= -margin && x <= vw + margin && y >= -margin && y <= vh + margin

    // -------------------------------------------------------------- polylines

    private fun beginLines(canvas: Canvas, paint: Paint) {
        curCanvas = canvas
        curPaint = paint
        segN = 0
    }

    private fun flushLines() {
        val c = curCanvas
        val p = curPaint
        if (c != null && p != null && segN > 0) c.drawLines(segs, 0, segN, p)
        segN = 0
    }

    private fun addSeg(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float) {
        val x1 = cx + fpx * ax / -az
        val y1 = cy - fpx * ay / -az
        val x2 = cx + fpx * bx / -bz
        val y2 = cy - fpx * by / -bz
        val m = 40f
        if ((x1 < -m && x2 < -m) || (x1 > vw + m && x2 > vw + m) || (y1 < -m && y2 < -m) || (y1 > vh + m && y2 > vh + m)) return
        if (segN + 4 > segs.size) flushLines()
        segs[segN] = x1; segs[segN + 1] = y1; segs[segN + 2] = x2; segs[segN + 3] = y2
        segN += 4
    }

    private fun addPolyline(pts: FloatArray, m: FloatArray) {
        val n = pts.size / 3
        var px = 0f
        var py = 0f
        var pz = 0f
        var pIn = false
        for (i in 0 until n) {
            val x = pts[3 * i]
            val y = pts[3 * i + 1]
            val z = pts[3 * i + 2]
            val dx = m[0] * x + m[1] * y + m[2] * z
            val dy = m[3] * x + m[4] * y + m[5] * z
            val dz = m[6] * x + m[7] * y + m[8] * z
            val inFront = dz < -NEAR
            if (i > 0) {
                if (inFront && pIn) {
                    addSeg(px, py, pz, dx, dy, dz)
                } else if (inFront != pIn) {
                    val t = (-NEAR - pz) / (dz - pz)
                    val ix = px + t * (dx - px)
                    val iy = py + t * (dy - py)
                    if (inFront) addSeg(ix, iy, -NEAR, dx, dy, dz) else addSeg(px, py, pz, ix, iy, -NEAR)
                }
            }
            px = dx; py = dy; pz = dz; pIn = inFront
        }
    }

    private fun drawStaticLines(canvas: Canvas, list: List<FloatArray>, m: FloatArray, paint: Paint) {
        beginLines(canvas, paint)
        for (l in list) addPolyline(l, m)
        flushLines()
    }

    /** Adds a polygon ring (clipped to the camera half-space) to [path]. */
    private fun addRing(ring: FloatArray, m: FloatArray, dest: Path) {
        val n = ring.size / 3
        if (clipBuf.size < 6 * n + 12) clipBuf = FloatArray(6 * n + 12)
        var cnt = 0
        var sx = ring[3 * (n - 1)]
        var sy = ring[3 * (n - 1) + 1]
        var sz = ring[3 * (n - 1) + 2]
        var sdx = m[0] * sx + m[1] * sy + m[2] * sz
        var sdy = m[3] * sx + m[4] * sy + m[5] * sz
        var sdz = m[6] * sx + m[7] * sy + m[8] * sz
        for (i in 0 until n) {
            val ex = ring[3 * i]
            val ey = ring[3 * i + 1]
            val ez = ring[3 * i + 2]
            val edx = m[0] * ex + m[1] * ey + m[2] * ez
            val edy = m[3] * ex + m[4] * ey + m[5] * ez
            val edz = m[6] * ex + m[7] * ey + m[8] * ez
            val eIn = edz < -NEAR
            val sIn = sdz < -NEAR
            if (eIn) {
                if (!sIn) {
                    val t = (-NEAR - sdz) / (edz - sdz)
                    clipBuf[cnt++] = sdx + t * (edx - sdx)
                    clipBuf[cnt++] = sdy + t * (edy - sdy)
                    clipBuf[cnt++] = -NEAR
                }
                clipBuf[cnt++] = edx; clipBuf[cnt++] = edy; clipBuf[cnt++] = edz
            } else if (sIn) {
                val t = (-NEAR - sdz) / (edz - sdz)
                clipBuf[cnt++] = sdx + t * (edx - sdx)
                clipBuf[cnt++] = sdy + t * (edy - sdy)
                clipBuf[cnt++] = -NEAR
            }
            sdx = edx; sdy = edy; sdz = edz
        }
        if (cnt < 9) return
        var first = true
        var i = 0
        while (i < cnt) {
            val px = cx + fpx * clipBuf[i] / -clipBuf[i + 2]
            val py = cy - fpx * clipBuf[i + 1] / -clipBuf[i + 2]
            if (first) { dest.moveTo(px, py); first = false } else dest.lineTo(px, py)
            i += 3
        }
        dest.close()
    }

    // ------------------------------------------------------------------ render

    fun render(canvas: Canvas, w: Float, h: Float, density: Float, inp: RenderInput) {
        val opt = inp.options
        val cat = inp.catalog
        if (cachedCat !== cat) {
            cachedCat = cat
            isMessier = BooleanArray(cat.dsos.size) { i ->
                val id = cat.dsos[i].id
                id.length > 1 && id[0] == 'M' && id[1].isDigit()
            }
        }
        vw = w
        vh = h
        scale = density
        alphaK = opt.alpha
        night = opt.night
        pickN = 0
        clearLabels()
        cx = w / 2f
        cy = h / 2f

        val tanHalf = tan(Math.toRadians(inp.fovLongDeg) / 2.0) * opt.fovScale / inp.zoom
        fpx = ((max(w, h) / 2f) / tanHalf).toFloat()
        val fovLongEff = Math.toDegrees(2.0 * atan(tanHalf))

        val fr = inp.frame
        val rt = inp.worldToDevice
        val mEqM = rt * fr.eqToEnu
        val mJM = mEqM * fr.precession
        val mEclM = mEqM * Mat3.rotX(fr.epsDeg * DEG2RAD)
        toF(rt, mEnu)
        toF(mEqM, mEq)
        toF(mJM, mJ)
        toF(mEclM, mEcl)

        text.textSize = 11.5f * density
        text.setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
        lines.strokeWidth = 1.1f * density

        if (opt.milkyWay) drawMilkyWay(canvas, cat)

        // grids and reference lines
        lines.pathEffect = null
        if (opt.gridAltAz) {
            lines.color = col(120, 200, 160, 0.30f)
            drawStaticLines(canvas, altAzLines, mEnu, lines)
        }
        if (opt.gridEq) {
            lines.color = col(130, 160, 230, 0.30f)
            drawStaticLines(canvas, eqLines, mEq, lines)
        }
        lines.pathEffect = DashPathEffect(floatArrayOf(10f * density, 7f * density), 0f)
        if (opt.equator) {
            lines.color = col(120, 190, 255, 0.55f)
            equatorLine?.let { drawStaticLines(canvas, listOf(it), mEq, lines) }
        }
        if (opt.ecliptic) {
            lines.color = col(255, 215, 110, 0.65f)
            eclipticLine?.let { drawStaticLines(canvas, listOf(it), mEcl, lines) }
        }
        lines.pathEffect = null

        if (opt.horizon) drawHorizon(canvas)
        if (opt.constellations) drawConstellations(canvas, cat, opt)
        if (opt.dso) drawDsos(canvas, cat, opt, fovLongEff)
        if (opt.stars) drawStars(canvas, cat, opt, fovLongEff)
        if (opt.planets) drawBodies(canvas, inp, fr)

        drawMarkers(canvas, inp, mJM, mEqM)

        if (opt.crosshair) {
            stroke.strokeWidth = 1.3f * density
            stroke.color = col(255, 255, 255, 0.55f)
            stroke.pathEffect = null
            val r = 16f * density
            canvas.drawCircle(cx, cy, r, stroke)
            canvas.drawLine(cx - r * 1.6f, cy, cx - r * 0.5f, cy, stroke)
            canvas.drawLine(cx + r * 0.5f, cy, cx + r * 1.6f, cy, stroke)
            canvas.drawLine(cx, cy - r * 1.6f, cx, cy - r * 0.5f, stroke)
            canvas.drawLine(cx, cy + r * 0.5f, cx, cy + r * 1.6f, stroke)
        }

        val hud = inp.hud
        if (hud != null) {
            text.textSize = 12f * density
            text.color = col(255, 255, 255, 0.95f)
            var y = 18f * density
            for (ln in hud.split('\n')) {
                canvas.drawText(ln, 10f * density, y, text)
                y += 15f * density
            }
        }
    }

    // ---------------------------------------------------------------- Milky Way

    private fun drawMilkyWay(canvas: Canvas, cat: Catalog) {
        for (lv in cat.milkyWay) {
            path.reset()
            path.fillType = Path.FillType.EVEN_ODD
            for (ring in lv.rings) addRing(ring, mJ, path)
            fill.color = col(190, 205, 255, 0.085f)
            canvas.drawPath(path, fill)
        }
        path.reset()
    }

    // ------------------------------------------------------------------ horizon

    private fun drawHorizon(canvas: Canvas) {
        lines.strokeWidth = 2.2f * scale
        lines.color = col(110, 210, 120, 0.9f)
        val hz = horizonLine
        if (hz != null) drawStaticLines(canvas, listOf(hz), mEnu, lines)
        lines.strokeWidth = 1.1f * scale

        text.textSize = 11f * scale
        var az = 0
        while (az < 360) {
            val a = az * DEG2RAD
            val x = sin(a).toFloat()
            val y = cos(a).toFloat()
            val dx = mEnu[0] * x + mEnu[1] * y
            val dy = mEnu[3] * x + mEnu[4] * y
            val dz = mEnu[6] * x + mEnu[7] * y
            if (dz < -NEAR) {
                val sx = cx + fpx * dx / -dz
                val sy = cy - fpx * dy / -dz
                if (inside(sx, sy, 0f)) {
                    stroke.strokeWidth = 1.6f * scale
                    stroke.color = col(110, 210, 120, 0.9f)
                    stroke.pathEffect = null
                    val tick = if (az % 90 == 0) 12f * scale else if (az % 30 == 0) 8f * scale else 4f * scale
                    canvas.drawLine(sx, sy, sx, sy - tick, stroke)
                    if (az % 90 == 0) {
                        val name = when (az) { 0 -> "S"; 90 -> "V"; 180 -> "J"; else -> "Z" }
                        text.textSize = 20f * scale
                        text.color = col(150, 255, 160, 1f)
                        val tw = text.measureText(name)
                        canvas.drawText(name, sx - tw / 2f, sy - tick - 6f * scale, text)
                        text.textSize = 11f * scale
                    } else if (az % 30 == 0) {
                        text.color = col(150, 255, 160, 0.85f)
                        val s = az.toString() + "°"
                        canvas.drawText(s, sx - text.measureText(s) / 2f, sy - tick - 4f * scale, text)
                    }
                }
            }
            az += 15
        }
    }

    // ----------------------------------------------------------- constellations

    private fun drawConstellations(canvas: Canvas, cat: Catalog, opt: OverlayOptions) {
        lines.color = col(110, 160, 255, 0.55f)
        lines.strokeWidth = 1.2f * scale
        beginLines(canvas, lines)
        for (c in cat.constellations) for (l in c.lines) addPolyline(l, mJ)
        flushLines()
        lines.strokeWidth = 1.1f * scale

        if (opt.constellationNames) {
            text.textSize = 12.5f * scale
            text.color = col(140, 180, 255, 0.75f)
            for (c in cat.constellations) {
                val x = c.center.x.toFloat()
                val y = c.center.y.toFloat()
                val z = c.center.z.toFloat()
                val dz = mJ[6] * x + mJ[7] * y + mJ[8] * z
                if (dz >= -NEAR) continue
                val sx = cx + fpx * (mJ[0] * x + mJ[1] * y + mJ[2] * z) / -dz
                val sy = cy - fpx * (mJ[3] * x + mJ[4] * y + mJ[5] * z) / -dz
                if (!inside(sx, sy, 0f)) continue
                val name = c.nameCz.uppercase()
                val tw = text.measureText(name)
                val x1 = sx - tw / 2f
                val y1 = sy - text.textSize
                if (labelFits(x1, y1, x1 + tw, sy + 4f)) {
                    labelAdd(x1, y1, x1 + tw, sy + 4f)
                    canvas.drawText(name, x1, sy, text)
                }
            }
        }
    }

    // ------------------------------------------------------------------ DSOs

    private fun dsoColor(type: String, a: Float): Int = when (type) {
        "G", "GPair", "GTrpl", "GGroup" -> col(255, 175, 110, a)
        "GCl", "OCl", "*Ass", "**" -> col(150, 215, 255, a)
        "PN" -> col(120, 255, 225, a)
        else -> col(150, 255, 170, a)
    }

    private fun drawDsos(canvas: Canvas, cat: Catalog, opt: OverlayOptions, fovLong: Double) {
        val limit = if (opt.magAuto) SkyEngine.autoDsoLimit(fovLong) else (opt.magLimit + 3.0)
        stroke.pathEffect = null
        stroke.strokeWidth = 1.4f * scale
        text.textSize = 11f * scale
        val dash = DashPathEffect(floatArrayOf(4f * scale, 3f * scale), 0f)
        for ((i, d) in cat.dsos.withIndex()) {
            if (d.mag > limit && !isMessier[i]) continue
            val dz = mJ[6] * d.x + mJ[7] * d.y + mJ[8] * d.z
            if (dz >= -NEAR) continue
            val sx = cx + fpx * (mJ[0] * d.x + mJ[1] * d.y + mJ[2] * d.z) / -dz
            val sy = cy - fpx * (mJ[3] * d.x + mJ[4] * d.y + mJ[5] * d.z) / -dz
            if (!inside(sx, sy, 20f)) continue
            val sizePx = d.majArcmin / 60f * (PI / 180.0).toFloat() * fpx / 2f
            val r = max(6f * scale, min(sizePx, 0.4f * min(vw, vh)))
            stroke.color = dsoColor(d.type, 0.95f)
            drawDsoSymbol(canvas, d, sx, sy, r, dash)
            addPick(sx, sy, SearchItem.DSO, i, 2)
            if (opt.dsoNames && (isMessier[i] || d.mag < limit - 0.8 || opt.labelDensity == 2)) {
                text.color = dsoColor(d.type, 0.95f)
                val s = when {
                    d.nameCz.isNotEmpty() && opt.labelDensity >= 1 -> d.idLabel() + " " + d.nameCz
                    isMessier[i] -> d.idLabel()
                    d.nameEn.isNotEmpty() && opt.labelDensity >= 1 -> d.idLabel() + " " + d.nameEn
                    else -> d.idLabel()
                }
                label(canvas, s, sx, sy, r + 3f * scale)
            }
        }
        stroke.pathEffect = null
    }

    private fun drawDsoSymbol(canvas: Canvas, d: Dso, x: Float, y: Float, r: Float, dash: DashPathEffect) {
        stroke.pathEffect = null
        when (d.type) {
            "G", "GPair", "GTrpl", "GGroup" -> canvas.drawOval(x - r, y - r * 0.62f, x + r, y + r * 0.62f, stroke)
            "GCl" -> {
                canvas.drawCircle(x, y, r, stroke)
                canvas.drawLine(x - r, y, x + r, y, stroke)
                canvas.drawLine(x, y - r, x, y + r, stroke)
            }
            "OCl", "*Ass", "**" -> {
                stroke.pathEffect = dash
                canvas.drawCircle(x, y, r, stroke)
                stroke.pathEffect = null
            }
            "PN" -> {
                canvas.drawCircle(x, y, r * 0.7f, stroke)
                canvas.drawLine(x - r * 1.5f, y, x - r * 0.95f, y, stroke)
                canvas.drawLine(x + r * 0.95f, y, x + r * 1.5f, y, stroke)
                canvas.drawLine(x, y - r * 1.5f, x, y - r * 0.95f, stroke)
                canvas.drawLine(x, y + r * 0.95f, x, y + r * 1.5f, stroke)
            }
            else -> canvas.drawRect(x - r, y - r, x + r, y + r, stroke)
        }
    }

    // ------------------------------------------------------------------ stars

    private fun drawStars(canvas: Canvas, cat: Catalog, opt: OverlayOptions, fovLong: Double) {
        val limit = if (opt.magAuto) SkyEngine.autoStarLimit(fovLong).toFloat() else opt.magLimit
        val narrowBonus = max(0.0, Math.log(60.0 / max(fovLong, 2.0)) / Math.log(2.0)).toFloat() * 0.8f
        val nameLimit = when (opt.labelDensity) {
            0 -> 1.8f
            1 -> 2.8f + narrowBonus
            else -> 4.0f + narrowBonus
        }
        val n = cat.starCount
        for (i in 0 until n) {
            val mag = cat.starMag[i]
            if (mag > limit) break
            val x = cat.starX[i]
            val y = cat.starY[i]
            val z = cat.starZ[i]
            val dz = mJ[6] * x + mJ[7] * y + mJ[8] * z
            if (dz >= -NEAR) continue
            val sx = cx + fpx * (mJ[0] * x + mJ[1] * y + mJ[2] * z) / -dz
            val sy = cy - fpx * (mJ[3] * x + mJ[4] * y + mJ[5] * z) / -dz
            if (!inside(sx, sy, 10f)) continue
            val r = min(1.0f + (limit - mag) * 0.62f, 6.8f) * scale
            fill.color = starColor(cat.starBv[i], 0.95f)
            canvas.drawCircle(sx, sy, r, fill)
            if (mag < 4.6f) addPick(sx, sy, SearchItem.STAR, i, if (mag < 2.5f) 3 else 1)
        }
        if (opt.starNames) {
            text.textSize = 11.5f * scale
            for (i in cat.labelledStars) {
                val mag = cat.starMag[i]
                if (mag > nameLimit || mag > limit) break
                val x = cat.starX[i]
                val y = cat.starY[i]
                val z = cat.starZ[i]
                val dz = mJ[6] * x + mJ[7] * y + mJ[8] * z
                if (dz >= -NEAR) continue
                val sx = cx + fpx * (mJ[0] * x + mJ[1] * y + mJ[2] * z) / -dz
                val sy = cy - fpx * (mJ[3] * x + mJ[4] * y + mJ[5] * z) / -dz
                if (!inside(sx, sy, 0f)) continue
                val lb = cat.starLabels[cat.starHip[i]] ?: continue
                val s = lb.shortLabel()
                if (s.isEmpty()) continue
                text.color = col(235, 240, 255, 0.95f)
                label(canvas, s, sx, sy, (min(1.0f + (limit - mag) * 0.62f, 6.8f) + 2f) * scale)
            }
        }
    }

    // ------------------------------------------------------------------ bodies

    private fun bodyColor(b: BodyState, idx: Int): Int {
        if (b.kind == BodyKind.PLANET) return col(
            Color.red(PLANET_COLORS[idx - 2]), Color.green(PLANET_COLORS[idx - 2]), Color.blue(PLANET_COLORS[idx - 2]), 1f
        )
        return col(255, 240, 200, 1f)
    }

    private fun drawBodies(canvas: Canvas, inp: RenderInput, fr: SkyEngine.Frame) {
        val rt = inp.worldToDevice
        val bodies = fr.bodies
        val sunDev = rt * fr.bodyEnu(bodies[0])
        for ((i, b) in bodies.withIndex()) {
            val d = rt * fr.bodyEnu(b)
            if (d.z >= -NEAR) continue
            val sx = cx + fpx * (d.x / -d.z).toFloat()
            val sy = cy - fpx * (d.y / -d.z).toFloat()
            if (!inside(sx, sy, 60f)) continue
            val radiusAng = Math.toRadians(b.angDiamArcsec / 3600.0 / 2.0)
            val rAng = (radiusAng * fpx).toFloat()
            text.textSize = 12.5f * scale
            when (b.kind) {
                BodyKind.SUN -> {
                    val r = max(10f * scale, rAng)
                    fill.color = col(255, 200, 60, 0.35f)
                    canvas.drawCircle(sx, sy, r * 2.0f, fill)
                    fill.color = col(255, 230, 120, 1f)
                    canvas.drawCircle(sx, sy, r, fill)
                    stroke.color = col(255, 210, 90, 0.9f)
                    stroke.strokeWidth = 1.6f * scale
                    stroke.pathEffect = null
                    for (k in 0 until 12) {
                        val a = k * PI / 6.0
                        canvas.drawLine(
                            sx + (cos(a) * r * 1.3).toFloat(), sy + (sin(a) * r * 1.3).toFloat(),
                            sx + (cos(a) * r * 1.8).toFloat(), sy + (sin(a) * r * 1.8).toFloat(), stroke
                        )
                    }
                    text.color = col(255, 220, 120, 1f)
                    label(canvas, "Slunce", sx, sy, r * 2f + 4f * scale)
                    text.color = col(255, 120, 80, 1f)
                    text.textSize = 11f * scale
                    canvas.drawText("Nemiřit bez slunečního filtru!", max(6f, sx - 90f * scale), sy + r * 2f + 16f * scale, text)
                }
                BodyKind.MOON -> {
                    val r = max(11f * scale, rAng)
                    val t = sunDev - d * sunDev.dot(d)
                    val rot = if (t.norm() > 1e-6) Math.toDegrees(atan2(-t.y, t.x)).toFloat() else 0f
                    drawMoonDisc(canvas, sx, sy, r, b.phaseAngleDeg, rot)
                    text.color = col(255, 255, 235, 1f)
                    label(canvas, "Měsíc " + Fmt.f0(b.illum * 100.0) + " %", sx, sy, r + 5f * scale)
                }
                else -> {
                    val pr = (2.6f + (2.0f - b.mag.toFloat()) * 0.9f).coerceIn(2.6f, 7.5f) * scale
                    fill.color = bodyColor(b, i)
                    canvas.drawCircle(sx, sy, pr, fill)
                    stroke.color = bodyColor(b, i)
                    stroke.strokeWidth = 1.2f * scale
                    stroke.pathEffect = null
                    canvas.drawCircle(sx, sy, pr + 4f * scale, stroke)
                    if (b.id == "saturn") {
                        canvas.drawOval(sx - pr * 2.1f, sy - pr * 0.55f, sx + pr * 2.1f, sy + pr * 0.55f, stroke)
                    }
                    text.color = bodyColor(b, i)
                    label(canvas, b.nameCz + " " + Fmt.f1(b.mag), sx, sy, pr + 6f * scale)
                }
            }
            addPick(sx, sy, SearchItem.BODY, i, 5)
        }
    }

    private fun drawMoonDisc(canvas: Canvas, x: Float, y: Float, r: Float, phaseAngleDeg: Double, rotDeg: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rotDeg)
        fill.color = col(70, 75, 95, 0.85f)
        canvas.drawCircle(0f, 0f, r, fill)
        val ci = cos(phaseAngleDeg * DEG2RAD).toFloat()
        path.reset()
        val steps = 28
        for (k in 0..steps) {
            val th = (-90.0 + 180.0 * k / steps) * DEG2RAD
            val px = (r * cos(th)).toFloat()
            val py = (r * sin(th)).toFloat()
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        for (k in 0..steps) {
            val th = (90.0 - 180.0 * k / steps) * DEG2RAD
            path.lineTo((-r * ci * cos(th)).toFloat(), (r * sin(th)).toFloat())
        }
        path.close()
        fill.color = col(245, 243, 225, 1f)
        canvas.drawPath(path, fill)
        stroke.color = col(255, 255, 240, 0.9f)
        stroke.strokeWidth = 1.2f * scale
        stroke.pathEffect = null
        canvas.drawCircle(0f, 0f, r, stroke)
        canvas.restore()
        path.reset()
    }

    // ----------------------------------------------------------------- markers

    /** Device-space unit vector of a target, or null if it does not exist. */
    private fun targetDevice(inp: RenderInput, t: SkyTarget, mJM: Mat3, mEqM: Mat3): Vec3? {
        val cat = inp.catalog
        return when (t.kind) {
            SearchItem.STAR -> if (t.index in 0 until cat.starCount)
                (mJM * Vec3(cat.starX[t.index].toDouble(), cat.starY[t.index].toDouble(), cat.starZ[t.index].toDouble())).normalized()
            else null
            SearchItem.DSO -> if (t.index in cat.dsos.indices) {
                val d = cat.dsos[t.index]
                (mJM * Vec3(d.x.toDouble(), d.y.toDouble(), d.z.toDouble())).normalized()
            } else null
            SearchItem.CONSTELLATION -> if (t.index in cat.constellations.indices)
                (mJM * cat.constellations[t.index].center).normalized()
            else null
            SearchItem.BODY -> if (t.index in inp.frame.bodies.indices)
                (inp.worldToDevice * inp.frame.bodyEnu(inp.frame.bodies[t.index])).normalized()
            else null
            else -> null
        }
    }

    private fun drawMarkers(canvas: Canvas, inp: RenderInput, mJM: Mat3, mEqM: Mat3) {
        val sel = inp.selected
        if (sel != null) {
            val d = targetDevice(inp, sel, mJM, mEqM)
            if (d != null && d.z < -NEAR) {
                val sx = cx + fpx * (d.x / -d.z).toFloat()
                val sy = cy - fpx * (d.y / -d.z).toFloat()
                if (inside(sx, sy, 0f)) {
                    stroke.color = col(255, 255, 255, 0.95f)
                    stroke.strokeWidth = 1.8f * scale
                    stroke.pathEffect = null
                    val r = 20f * scale
                    val k = r * 0.45f
                    canvas.drawLine(sx - r, sy - r, sx - r + k, sy - r, stroke)
                    canvas.drawLine(sx - r, sy - r, sx - r, sy - r + k, stroke)
                    canvas.drawLine(sx + r, sy - r, sx + r - k, sy - r, stroke)
                    canvas.drawLine(sx + r, sy - r, sx + r, sy - r + k, stroke)
                    canvas.drawLine(sx - r, sy + r, sx - r + k, sy + r, stroke)
                    canvas.drawLine(sx - r, sy + r, sx - r, sy + r - k, stroke)
                    canvas.drawLine(sx + r, sy + r, sx + r - k, sy + r, stroke)
                    canvas.drawLine(sx + r, sy + r, sx + r, sy + r - k, stroke)
                }
            }
        }
        val g = inp.goto ?: return
        val d = targetDevice(inp, g, mJM, mEqM) ?: return
        val sep = Math.toDegrees(acos((-d.z).coerceIn(-1.0, 1.0)))
        var visible = false
        if (d.z < -NEAR) {
            val sx = cx + fpx * (d.x / -d.z).toFloat()
            val sy = cy - fpx * (d.y / -d.z).toFloat()
            if (inside(sx, sy, -10f)) {
                visible = true
                stroke.color = col(255, 90, 90, 1f)
                stroke.strokeWidth = 2.2f * scale
                stroke.pathEffect = null
                canvas.drawCircle(sx, sy, 26f * scale, stroke)
                canvas.drawCircle(sx, sy, 32f * scale, stroke)
            }
        }
        if (!visible) {
            var dirx = d.x.toFloat()
            var diry = -d.y.toFloat()
            val len = sqrt(dirx * dirx + diry * diry)
            if (len < 1e-4f) { dirx = 0f; diry = -1f } else { dirx /= len; diry /= len }
            val rr = min(vw, vh) * 0.40f
            val ax = cx + dirx * rr
            val ay = cy + diry * rr
            val sz = 15f * scale
            path.reset()
            path.moveTo(ax + dirx * sz, ay + diry * sz)
            path.lineTo(ax - dirx * sz * 0.6f - diry * sz * 0.8f, ay - diry * sz * 0.6f + dirx * sz * 0.8f)
            path.lineTo(ax - dirx * sz * 0.6f + diry * sz * 0.8f, ay - diry * sz * 0.6f - dirx * sz * 0.8f)
            path.close()
            fill.color = col(255, 90, 90, 1f)
            canvas.drawPath(path, fill)
            path.reset()
            text.textSize = 14f * scale
            text.color = col(255, 130, 130, 1f)
            val s = Fmt.f0(sep) + "°"
            canvas.drawText(s, ax - text.measureText(s) / 2f - dirx * sz * 2.3f, ay + text.textSize / 3f - diry * sz * 2.3f, text)
        }
    }
}
