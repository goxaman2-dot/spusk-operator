package ru.spusk.operator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Схема спуска: эталонная линия, края, ориентиры, пройденный путь с раскраской ошибок. */
class TrackView(ctx: Context) : View(ctx) {
    private val dp = resources.displayMetrics.density
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * dp; color = Color.rgb(40, 40, 40)
        pathEffect = DashPathEffect(floatArrayOf(10 * dp, 6 * dp), 0f)
    }
    private val pEdgeL = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * dp; color = Color.rgb(176, 58, 46) }
    private val pEdgeR = Paint(pEdgeL).apply { color = Color.rgb(30, 122, 60) }
    private val pRec = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * dp; color = Color.rgb(26, 79, 138) }
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 13 * dp }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(238, 243, 249))
        val prof = Hub.profile
        val origin = prof?.line?.firstOrNull() ?: Hub.rec.firstOrNull() ?: Hub.lastFix?.let { Pt(it.lat, it.lon) }
        if (origin == null) {
            c.drawText("Нет данных: выберите профиль или начните трассировку", 12 * dp, height / 2f, pText)
            return
        }
        val proj = Proj(origin.lat, origin.lon)
        val line = prof?.line?.map { proj.xy(it) } ?: emptyList()
        val el = prof?.edgeL?.map { proj.xy(it) } ?: emptyList()
        val er = prof?.edgeR?.map { proj.xy(it) } ?: emptyList()
        val rec = Hub.rec.map { proj.xy(it) }
        val nav = if (Hub.mode == Mode.NAV || Hub.mode == Mode.SIM) Hub.nav else Hub.finishedNav
        val trail = if (nav != null && nav.prof === prof) nav.trail.map { TrailPt(proj.xy(nav.proj.lat(it.p), nav.proj.lon(it.p)), it.cls) } else emptyList()
        val me = Hub.lastFix?.let { proj.xy(it.lat, it.lon) }

        val all = line + el + er + rec + trail.map { it.p } + listOfNotNull(me)
        var minX = all.minOf { it.x }; var maxX = all.maxOf { it.x }
        var minY = all.minOf { it.y }; var maxY = all.maxOf { it.y }
        if (maxX - minX < 40) { val m = (minX + maxX) / 2; minX = m - 20; maxX = m + 20 }
        if (maxY - minY < 40) { val m = (minY + maxY) / 2; minY = m - 20; maxY = m + 20 }
        val pad = 16 * dp
        val k = min((width - 2 * pad) / (maxX - minX), (height - 2 * pad) / (maxY - minY))
        val ox = pad + ((width - 2 * pad) - (maxX - minX) * k) / 2
        val oy = pad + ((height - 2 * pad) - (maxY - minY) * k) / 2
        fun sx(v: V2) = (ox + (v.x - minX) * k).toFloat()
        fun sy(v: V2) = (oy + (maxY - v.y) * k).toFloat()
        fun poly(pts: List<V2>, paint: Paint) {
            if (pts.size < 2) return
            val path = Path(); path.moveTo(sx(pts[0]), sy(pts[0]))
            for (i in 1 until pts.size) path.lineTo(sx(pts[i]), sy(pts[i]))
            c.drawPath(path, paint)
        }
        poly(el, pEdgeL); poly(er, pEdgeR); poly(line, pLine); poly(rec, pRec)
        for (t in trail) { pDot.color = t.cls.color; c.drawCircle(sx(t.p), sy(t.p), 3 * dp, pDot) }
        prof?.marks?.forEach { m ->
            val v = proj.xy(m.lat, m.lon); pDot.color = Color.MAGENTA
            c.drawRect(sx(v) - 5 * dp, sy(v) - 5 * dp, sx(v) + 5 * dp, sy(v) + 5 * dp, pDot)
        }
        if (line.isNotEmpty()) {
            pDot.color = Color.rgb(30, 122, 60); c.drawCircle(sx(line.first()), sy(line.first()), 5 * dp, pDot)
            pDot.color = Color.BLACK; c.drawCircle(sx(line.last()), sy(line.last()), 5 * dp, pDot)
        }
        me?.let { pDot.color = Color.rgb(199, 119, 0); c.drawCircle(sx(it), sy(it), 7 * dp, pDot) }
        val scale = max(10.0, ((maxX - minX) / 4 / 10).toInt() * 10.0)
        val y = height - 8 * dp
        c.drawLine(8 * dp, y, (8 * dp + scale * k).toFloat(), y, pText)
        c.drawText("${scale.toInt()} м", 8 * dp, y - 4 * dp, pText)
    }
}
