package ru.spusk.operator

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Геодезия: формулы (1), (2), (4) технического задания. */
object Geo {
    const val R = 6_371_000.0

    /** Формула (1): расстояние по гаверсинусу, м. */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R * asin(sqrt(min(1.0, h)))
    }

    fun dist(a: Pt, b: Pt) = haversine(a.lat, a.lon, b.lat, b.lon)

    /** Формула (4): приведение угла к интервалу [-180; 180). */
    fun wrap180(a: Double): Double = ((a % 360.0) + 540.0) % 360.0 - 180.0
}

/** Точка на локальной плоскости: x – на восток, y – на север, метры. */
data class V2(val x: Double, val y: Double) {
    operator fun plus(o: V2) = V2(x + o.x, y + o.y)
    operator fun minus(o: V2) = V2(x - o.x, y - o.y)
    operator fun times(k: Double) = V2(x * k, y * k)
    fun dot(o: V2) = x * o.x + y * o.y
    fun cross(o: V2) = x * o.y - y * o.x
    fun len() = sqrt(x * x + y * y)
}

/** Азимут вектора от севера по часовой стрелке, градусы (аналог формулы (2) на плоскости). */
fun bearingOf(d: V2): Double = (Math.toDegrees(atan2(d.x, d.y)) + 360.0) % 360.0

/** Равнопромежуточная проекция вокруг начала профиля; на масштабе склона ошибка < 0,1 %. */
class Proj(private val lat0: Double, private val lon0: Double) {
    private val k = cos(Math.toRadians(lat0))
    fun xy(lat: Double, lon: Double) =
        V2(Math.toRadians(lon - lon0) * Geo.R * k, Math.toRadians(lat - lat0) * Geo.R)

    fun xy(p: Pt) = xy(p.lat, p.lon)
    fun lat(v: V2) = lat0 + Math.toDegrees(v.y / Geo.R)
    fun lon(v: V2) = lon0 + Math.toDegrees(v.x / (Geo.R * k))
}

/** Ломаная с накопленной длиной и поиском ближайшей точки. */
class Polyline(val pts: List<V2>) {
    val s = DoubleArray(pts.size)
    val total: Double

    init {
        for (i in 1 until pts.size) s[i] = s[i - 1] + (pts[i] - pts[i - 1]).len()
        total = if (pts.isEmpty()) 0.0 else s[pts.size - 1]
    }

    /** seg – номер отрезка, t – доля отрезка, along – путь от начала, dist – расстояние, «+» слева. */
    data class Hit(val seg: Int, val t: Double, val along: Double, val dist: Double)

    fun nearest(p: V2, from: Int = 0, to: Int = pts.size - 2): Hit {
        var best = Hit(0, 0.0, 0.0, Double.MAX_VALUE)
        val lo = from.coerceIn(0, maxOf(0, pts.size - 2))
        val hi = to.coerceIn(lo, maxOf(0, pts.size - 2))
        for (i in lo..hi) {
            val a = pts[i]
            val d = pts[i + 1] - a
            val l2 = d.dot(d)
            val t = if (l2 < 1e-9) 0.0 else ((p - a).dot(d) / l2).coerceIn(0.0, 1.0)
            val q = a + d * t
            val dist = (p - q).len()
            if (dist < abs(best.dist)) {
                val sign = if (d.cross(p - a) >= 0) 1.0 else -1.0
                best = Hit(i, t, s[i] + t * sqrt(l2), sign * dist)
            }
        }
        return best
    }

    fun pointAt(along: Double): V2 {
        if (pts.size == 1) return pts[0]
        val a = along.coerceIn(0.0, total)
        var i = 0
        while (i < pts.size - 2 && s[i + 1] < a) i++
        val segLen = s[i + 1] - s[i]
        val t = if (segLen < 1e-9) 0.0 else (a - s[i]) / segLen
        return pts[i] + (pts[i + 1] - pts[i]) * t
    }

    fun segBearing(i: Int): Double {
        val j = i.coerceIn(0, pts.size - 2)
        return bearingOf(pts[j + 1] - pts[j])
    }
}

/** Прореживание Дугласа – Пекера [3], допуск в метрах. Возвращает индексы сохранённых точек. */
object Simplify {
    fun douglasPeucker(p: List<V2>, eps: Double): List<Int> {
        if (p.size < 3) return p.indices.toList()
        val keep = BooleanArray(p.size)
        keep[0] = true
        keep[p.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to p.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            val d = p[b] - p[a]
            val len = d.len()
            var maxD = -1.0
            var idx = -1
            for (i in a + 1 until b) {
                val dist = if (len < 1e-9) (p[i] - p[a]).len() else abs(d.cross(p[i] - p[a])) / len
                if (dist > maxD) { maxD = dist; idx = i }
            }
            if (idx >= 0 && maxD > eps) {
                keep[idx] = true
                stack.addLast(a to idx)
                stack.addLast(idx to b)
            }
        }
        return p.indices.filter { keep[it] }
    }
}
