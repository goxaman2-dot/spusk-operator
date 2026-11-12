package ru.spusk.operator

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.util.UUID
import kotlin.math.sin

enum class Mode(val title: String) {
    IDLE("ожидание"), NAV("навигация по профилю"), TRACE("трассировка"),
    EDGE_L("запись левого края"), EDGE_R("запись правого края"), SIM("симуляция"),
}

/** Общее состояние приложения: режим, профиль, навигатор, запись. Живёт в процессе. */
object Hub {
    lateinit var app: Context; private set
    lateinit var speaker: Speaker; private set
    val beeper = Beeper()
    lateinit var store: ProfileStore; private set
    private val main = Handler(Looper.getMainLooper())

    var mode = Mode.IDLE; private set
    var profiles: List<Profile> = emptyList(); private set
    var profile: Profile? = null
    var presetIdx = 1
    var nav: Navigator? = null; private set
    var navState: NavState? = null; private set
    var lastFix: Fix? = null; private set
    var lastCmd = ""; private set
    var finishedNav: Navigator? = null; private set

    val rec = ArrayList<Pt>()
    val pendingMarks = ArrayList<Mark>()
    private var prevFix: Fix? = null

    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyUi() = listeners.toList().forEach { it() }

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        speaker = Speaker(app)
        store = ProfileStore(app)
        reload()
    }

    fun reload(selectId: String? = profile?.id) {
        profiles = store.list()
        profile = profiles.firstOrNull { it.id == selectId } ?: profiles.firstOrNull()
    }

    fun toast(msg: String) = main.post { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }

    val ownAvatar: File get() = File(app.filesDir, "avatar_own.jpg")

    /** Параметры выбранного уровня: три базовых пресета или «Свой» – параметры из файла профиля. */
    fun params(): Params =
        if (presetIdx < Presets.list.size) Presets.list[presetIdx].params
        else profile?.params ?: Presets.list[1].params

    fun say(c: Cmd) {
        lastCmd = c.text
        speaker.say(c)
        notifyUi()
    }

    // ---------- навигация ----------
    fun startNav(withGps: Boolean = true): String? {
        val p = profile ?: return "Сначала запишите или импортируйте профиль"
        if (p.line.size < 2) return "В профиле нет линии спуска"
        stopAll()
        nav = Navigator(p, params()) { say(it) }
        navState = null
        mode = if (withGps) Mode.NAV else Mode.SIM
        if (withGps) LocationService.start(app)
        speaker.sayText((if (withGps) "Навигация" else "Симуляция") + ". Профиль ${p.name}")
        notifyUi()
        return null
    }

    // ---------- запись ----------
    fun startRecord(m: Mode): String? {
        if (m == Mode.EDGE_L || m == Mode.EDGE_R) {
            val p = profile ?: return "Края записываются к выбранному профилю – сначала сделайте трассировку"
            if (p.line.size < 2) return "В профиле нет линии спуска"
        }
        stopAll()
        rec.clear()
        if (m == Mode.TRACE) pendingMarks.clear()
        mode = m
        LocationService.start(app)
        speaker.sayText(m.title)
        notifyUi()
        return null
    }

    /** Завершение трассировки: прореживание [Simplify] с допуском 2 м и создание профиля. */
    fun finishTrace(name: String, method: String, season: String, author: String): String? {
        if (rec.size < 5) { val k = rec.size; rec.clear(); return "Слишком короткая запись ($k точек)" }
        val proj = Proj(rec[0].lat, rec[0].lon)
        val keep = Simplify.douglasPeucker(rec.map { proj.xy(it) }, 2.0)
        val base = Presets.list[1].params
        val p = Profile(
            id = UUID.randomUUID().toString(), name = name.ifBlank { "Спуск ${profiles.size + 1}" },
            author = author, season = season, method = method,
            line = keep.map { rec[it] }.toMutableList(), params = base,
            marks = ArrayList(pendingMarks),
        )
        store.save(p)
        rec.clear(); pendingMarks.clear()
        reload(p.id)
        notifyUi()
        return null
    }

    /** Завершение записи края: при записи снизу вверх точки разворачиваются. */
    fun finishEdge(left: Boolean): String? {
        val p = profile ?: return "Профиль не выбран"
        if (rec.size < 3) { rec.clear(); return "Слишком короткая запись края" }
        val start = p.line.first(); val end = p.line.last()
        val pts = ArrayList(rec)
        if (Geo.dist(pts.first(), end) < Geo.dist(pts.first(), start)) pts.reverse()
        val proj = Proj(pts[0].lat, pts[0].lon)
        val keep = Simplify.douglasPeucker(pts.map { proj.xy(it) }, 1.5).map { pts[it] }.toMutableList()
        if (left) p.edgeL = keep else p.edgeR = keep
        store.save(p)
        rec.clear()
        notifyUi()
        return null
    }

    fun stopAll(): Mode {
        val was = mode
        if (was == Mode.NAV || was == Mode.SIM) finishedNav = nav
        nav = null
        beeper.stop()
        simRunning = false
        mode = Mode.IDLE
        LocationService.stop(app)
        notifyUi()
        return was
    }

    // ---------- отсчёты ----------
    fun onFix(f0: Fix) {
        var f = f0
        // скорость по двум отсчётам, если приёмник её не дал
        if (f.speed < 0) {
            val p = prevFix
            f = if (p != null && f.time > p.time)
                f.copy(speed = Geo.haversine(p.lat, p.lon, f.lat, f.lon) / ((f.time - p.time) / 1000.0))
            else f.copy(speed = 0.0)
        }
        prevFix = f
        lastFix = f
        when (mode) {
            Mode.NAV, Mode.SIM -> {
                navState = nav?.onFix(f)
                navState?.let { beeper.update(it.beepSide, it.beepLevel) }
            }
            Mode.TRACE, Mode.EDGE_L, Mode.EDGE_R -> {
                val last = rec.lastOrNull()
                if (f.acc <= 25 && (last == null || Geo.haversine(last.lat, last.lon, f.lat, f.lon) >= 1.0))
                    rec.add(Pt(f.lat, f.lon, f.alt, f.speed, f.time))
            }
            Mode.IDLE -> {}
        }
        notifyUi()
    }

    // ---------- ориентиры ----------
    fun newPhotoFile(): File = File(File(app.filesDir, "photos").apply { mkdirs() }, "mark_${System.currentTimeMillis()}.jpg")

    fun addMark(photo: String?): String? {
        val f = lastFix ?: return "Нет координат: дождитесь сигнала GPS"
        if (mode == Mode.TRACE) {
            pendingMarks.add(Mark(f.lat, f.lon, f.bearing, "ориентир ${pendingMarks.size + 1}", photo))
            notifyUi(); return null
        }
        val p = profile ?: return "Профиль не выбран"
        p.marks.add(Mark(f.lat, f.lon, f.bearing, "ориентир ${p.marks.size + 1}", photo))
        store.save(p)
        notifyUi()
        return null
    }

    // ---------- симуляция для проверки дома ----------
    private var simRunning = false

    /** Проезд по линии профиля с колебаниями скорости и бокового положения, без GPS. */
    fun startSim(): String? {
        startNav(withGps = false)?.let { return it }
        val n = nav ?: return "Ошибка навигатора"
        simRunning = true
        var s = 0.0
        var t = 0
        val tick = object : Runnable {
            override fun run() {
                if (!simRunning || mode != Mode.SIM || nav !== n) return
                val base = n.line.pointAt(s)
                val ahead = n.line.pointAt(s + 2)
                val d = ahead - base
                val len = d.len().coerceAtLeast(1e-6)
                val normal = V2(-d.y / len, d.x / len)
                val hit = n.line.nearest(base)
                val (w, c) = n.corridorAt(hit)
                val vref = n.vrefAt(hit)
                val v = vref * (1.05 + 0.35 * sin(t / 7.0))
                val off = c + 0.6 * (w / 2) * sin(t / 9.0) * (if (t % 40 in 25..32) 1.9 else 1.0)
                val p = base + normal * off
                onFix(Fix(n.proj.lat(p), n.proj.lon(p), 0.0, v, bearingOf(d), true, 4.0, System.currentTimeMillis()))
                s += v; t++
                if (s > n.line.total + 5) { stopAll(); speaker.sayText("Симуляция завершена") }
                else main.postDelayed(this, 1000)
            }
        }
        main.post(tick)
        return null
    }

    fun deleteProfile(p: Profile) {
        store.delete(p)
        p.marks.forEach { m -> m.photo?.let { File(File(app.filesDir, "photos"), it).delete() } }
        reload(null)
        notifyUi()
    }

    fun saveProfile(p: Profile) { store.save(p); reload(p.id); notifyUi() }
}
