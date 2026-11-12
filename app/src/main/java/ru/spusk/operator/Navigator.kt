package ru.spusk.operator

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Отсчёт приёмника: скорость м/с, курс в градусах, точность м, время мс. */
data class Fix(
    val lat: Double, val lon: Double, val alt: Double, val speed: Double,
    val bearing: Double, val hasBearing: Boolean, val acc: Double, val time: Long,
)

/** Классы ошибок спуска (таблица 5 ТЗ). */
enum class ErrClass(val title: String, val color: Int) {
    OK("норма", 0xFF2E7D32.toInt()),
    SHIFT("смещение в коридоре", 0xFFF9A825.toInt()),
    OUT("выход за край", 0xFFC62828.toInt()),
    SPEED("превышение скорости", 0xFFEF6C00.toInt()),
    LATE("поздний поворот", 0xFF6A1B9A.toInt()),
    CUT("срез поворота", 0xFF1565C0.toInt()),
    BAD("недостоверный участок", 0xFF9E9E9E.toInt()),
}

class NavState(
    val along: Double, val total: Double, val e: Double, val lambda: Double, val eps: Double,
    val vhat: Double, val vref: Double, val dy: Double, val width: Double, val cls: ErrClass, val q: Double,
)

data class TrailPt(val p: V2, val cls: ErrClass)

/**
 * Сопоставление движения с профилем и выдача команд.
 * Формулы (3)–(11) ТЗ реализованы на локальной плоскости проекции [Proj].
 */
class Navigator(val prof: Profile, val prm: Params, private val emit: (Cmd) -> Unit) {
    val proj = Proj(prof.line[0].lat, prof.line[0].lon)
    val line = Polyline(prof.line.map { proj.xy(it) })
    private val left = if (prof.edgeL.size >= 2) Polyline(prof.edgeL.map { proj.xy(it) }) else null
    private val right = if (prof.edgeR.size >= 2) Polyline(prof.edgeR.map { proj.xy(it) }) else null
    private val n = line.pts.size

    private val vArr = DoubleArray(n)   // эталонная скорость v_э(s), м/с
    private val dArr = DoubleArray(n)   // изменение курса в окрестности вершины, градусы
    val widthConditional: Boolean = left == null && right == null

    class Turn(val along: Double, val delta: Double, val bOut: Double) {
        var announced = false
        var straightDone = false
    }

    class MarkOnLine(val along: Double, val label: String) { var announced = false }

    val turns = mutableListOf<Turn>()
    private val marks = mutableListOf<MarkOnLine>()

    // состояние
    private var first = true
    private var idx = 0
    var vhat = -1.0; private set
    private var curAlong = 0.0
    private var slowCount = 0
    private var outSince = -1L
    private var finished = false
    private val lastEmit = HashMap<String, Long>()
    private var qSum = 0.0
    private var qN = 0
    val counts = java.util.EnumMap<ErrClass, Int>(ErrClass::class.java)
    val trail = ArrayList<TrailPt>()
    val log = StringBuilder("time;lat;lon;acc;v;vref;e;lambda;eps;class;command\n")
    private var pendingLogCmd = ""

    val q: Double get() = if (qN == 0) 1.0 else 1.0 - qSum / qN

    init {
        val vmax = prm.vmaxKmh / 3.6
        val win = 20.0
        for (i in 0 until n) {
            val sv = line.s[i]
            val s1 = max(0.0, sv - win)
            val s2 = min(line.total, sv + win)
            if (sv - s1 >= 3 && s2 - sv >= 3) {
                val bIn = bearingOf(line.pointAt(sv) - line.pointAt(s1))
                val bOut = bearingOf(line.pointAt(s2) - line.pointAt(sv))
                dArr[i] = Geo.wrap180(bOut - bIn)
            }
        }
        // повороты – локальные максимумы |Δψ| не меньше 15°, разнесённые не менее чем на 25 м
        for (i in 1 until n - 1) {
            val d = abs(dArr[i])
            val sv = line.s[i]
            val prev = if (sv - line.s[i - 1] < win) abs(dArr[i - 1]) else 0.0
            val nextD = if (line.s[i + 1] - sv < win) abs(dArr[i + 1]) else 0.0
            if (d >= 15 && d >= prev && d >= nextD) {
                val bOut = bearingOf(line.pointAt(min(line.total, sv + win)) - line.pointAt(sv))
                val last = turns.lastOrNull()
                if (last != null && sv - last.along < 25) {
                    if (d > abs(last.delta)) turns[turns.size - 1] = Turn(sv, dArr[i], bOut)
                } else turns.add(Turn(sv, dArr[i], bOut))
            }
        }
        // эталонная скорость: из записи или по кривизне, формула (9)
        val recorded = prof.speedFromRecord && prof.line.any { it.v > 0.5 }
        for (i in 0 until n) {
            vArr[i] = if (recorded) {
                var sum = 0.0; var k = 0
                for (j in max(0, i - 2)..min(n - 1, i + 2)) { sum += prof.line[j].v; k++ }
                min(vmax, sum / k)
            } else {
                val dRad = Math.toRadians(abs(dArr[i]))
                val r = if (dRad < 1e-3) Double.MAX_VALUE else 2 * win / dRad
                min(vmax, sqrt(prm.aLat * r))
            }
            vArr[i] = max(1.5, vArr[i])
        }
        for ((k, m) in prof.marks.withIndex()) {
            val h = line.nearest(proj.xy(m.lat, m.lon))
            marks.add(MarkOnLine(h.along, m.label.ifBlank { "ориентир ${k + 1}" }))
        }
        marks.sortBy { it.along }
    }

    private fun interp(arr: DoubleArray, h: Polyline.Hit): Double =
        if (n < 2) arr[0] else arr[h.seg] + (arr[min(h.seg + 1, n - 1)] - arr[h.seg]) * h.t

    /** Ширина коридора w(s) и смещение его центра (влево «+») в точке линии по записанным краям. */
    fun corridorAt(h: Polyline.Hit): Pair<Double, Double> {
        val q = line.pointAt(h.along)
        val dl = left?.nearest(q)?.dist?.let { abs(it) }
        val dr = right?.nearest(q)?.dist?.let { abs(it) }
        return when {
            dl != null && dr != null -> max(2.0, dl + dr) to (dl - dr) / 2
            dl != null -> max(2.0, 2 * dl) to 0.0
            dr != null -> max(2.0, 2 * dr) to 0.0
            else -> 20.0 to 0.0
        }
    }
    fun vrefAt(h: Polyline.Hit) = interp(vArr, h)

    fun onFix(f: Fix): NavState {
        val p = proj.xy(f.lat, f.lon)
        var hit = if (first) line.nearest(p) else line.nearest(p, idx - 3, idx + 30)
        if (!first && abs(hit.dist) > 60) hit = line.nearest(p)
        first = false
        idx = hit.seg
        curAlong = hit.along

        val v = max(0.0, f.speed)
        vhat = if (vhat < 0) v else 0.4 * v + 0.6 * vhat                       // формула (7)
        val (w, c) = corridorAt(hit)
        val vref = vrefAt(hit)
        val lambda = (hit.dist - c) / (w / 2)                                    // формула (8)
        val eps = (vhat - vref) / vref                                           // формула (10)
        val dy = vhat * prm.tReact + vhat * vhat / (2 * prm.decel) + 10.0        // формула (6)
        val heading = if (f.hasBearing && v > 1.5) f.bearing else line.segBearing(hit.seg)
        val now = f.time
        val reliable = f.acc <= 15.0
        pendingLogCmd = ""

        // финиш
        if (!finished && hit.along >= line.total - 10) { finished = true; say(Cmd.FIN, now) }

        // поворот: формулы (4), (5)
        val next = turns.firstOrNull { !it.announced && it.along > hit.along - 5 }
        if (next != null && next.along - hit.along <= dy) {
            val d = if (f.hasBearing && v > 2) Geo.wrap180(next.bOut - heading) else next.delta
            next.announced = true
            if (abs(d) >= 10) {
                val alpha = ((abs(d) / 15.0).roundToInt() * 15).coerceIn(15, 90)
                if (alpha >= 45 && eps > 0) say(Cmd.BRAKE, now)
                say(Cmd.turn(d < 0, alpha), now)
            }
        }
        // «Прямо» после выхода из поворота
        for (t in turns) {
            if (!t.announced || t.straightDone) continue
            if (hit.along > t.along + 60) { t.straightDone = true; continue }
            if (hit.along > t.along + 8 && v > 1.5 &&
                abs(Geo.wrap180(heading - line.segBearing(hit.seg))) < 10
            ) { t.straightDone = true; say(Cmd.STRAIGHT, now) }
        }
        // скорость
        if (eps > 0.35) { say(Cmd.BRAKE, now); slowCount = 0 }
        else if (eps > prm.epsSlow) { slowCount++; if (slowCount >= 3) say(Cmd.SLOW, now) }
        else slowCount = 0
        // края коридора: только когда коридор заметно шире погрешности приёмника
        if (reliable && !widthConditional && w > 3 * f.acc) {
            if (lambda > prm.lambdaMax) say(Cmd.EDGE_L, now)
            else if (lambda < -prm.lambdaMax) say(Cmd.EDGE_R, now)
        }
        if (reliable && abs(lambda) > 1) {
            if (outSince < 0) outSince = now
            if (now - outSince > 3000) say(Cmd.BACK, now)
        } else outSince = -1
        // ориентиры
        for (m in marks) {
            if (!m.announced && m.along > hit.along && m.along - hit.along <= dy) {
                m.announced = true; say(Cmd.mark(m.label), now)
            }
        }

        // классификация ошибки, таблица 5
        val turnWin = turns.firstOrNull { hit.along >= it.along && hit.along <= it.along + max(15.0, dy) }
        val cls = when {
            !reliable -> ErrClass.BAD
            abs(lambda) > prm.lambdaMax && turnWin != null ->
                if ((turnWin.delta > 0 && lambda > 0) || (turnWin.delta < 0 && lambda < 0)) ErrClass.LATE else ErrClass.CUT
            abs(lambda) > 1 -> ErrClass.OUT
            abs(lambda) > prm.lambdaMax -> ErrClass.SHIFT
            eps > prm.epsSlow -> ErrClass.SPEED
            else -> ErrClass.OK
        }
        counts[cls] = (counts[cls] ?: 0) + 1
        if (cls != ErrClass.BAD) {                                               // формула (11)
            qSum += 0.6 * min(1.0, abs(lambda)) + 0.4 * min(1.0, abs(eps))
            qN++
        }
        trail.add(TrailPt(p, cls))
        log.append("$now;${"%.6f".format(java.util.Locale.US, f.lat)};${"%.6f".format(java.util.Locale.US, f.lon)};")
            .append("${f.acc.r1()};${v.r1()};${vref.r1()};${hit.dist.r1()};${lambda.r2()};${eps.r2()};${cls.name};$pendingLogCmd\n")

        return NavState(hit.along, line.total, hit.dist, lambda, eps, vhat, vref, dy, w, cls, q)
    }

    /** Антиповтор и подавление второстепенных команд перед поворотом (раздел 7 ТЗ). */
    private fun say(cmd: Cmd, now: Long) {
        val interval = when (cmd.code) { Code.BRAKE -> 2000L; Code.STOP -> 0L; else -> 4000L }
        val last = lastEmit[cmd.key]
        if (last != null && now - last < interval) return
        if (cmd.priority > 3) {
            val next = turns.firstOrNull { !it.announced && it.along > curAlong }
            if (next != null && vhat > 0 && (next.along - curAlong) / vhat < 2.0) return
        }
        lastEmit[cmd.key] = now
        pendingLogCmd = if (pendingLogCmd.isEmpty()) cmd.text else "$pendingLogCmd, ${cmd.text}"
        emit(cmd)
    }

    fun summary(): String {
        val total = counts.values.sum().coerceAtLeast(1)
        val sb = StringBuilder()
        sb.append("Показатель соответствия Q = ${"%.2f".format(q)}\n\n")
        for (c in ErrClass.values()) {
            val k = counts[c] ?: 0
            if (k > 0) sb.append("${c.title}: $k отсч. (${k * 100 / total} %)\n")
        }
        return sb.toString()
    }
}

private fun Double.r1() = "%.1f".format(java.util.Locale.US, this)
private fun Double.r2() = "%.2f".format(java.util.Locale.US, this)
