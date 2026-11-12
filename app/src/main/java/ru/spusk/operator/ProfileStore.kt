package ru.spusk.operator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Хранение профилей в JSON (таблица 3 ТЗ), файлы *.descent во внутренней памяти. */
class ProfileStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "profiles").apply { mkdirs() }

    fun list(): List<Profile> = dir.listFiles { f -> f.name.endsWith(".descent") }
        ?.mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
        ?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun save(p: Profile) = File(dir, "${p.id}.descent").writeText(toJson(p))

    fun delete(p: Profile) = File(dir, "${p.id}.descent").delete()

    companion object {
        private fun pts(a: List<Pt>) = JSONArray().apply {
            a.forEach { put(JSONArray().put(it.lat).put(it.lon).put(it.alt).put(it.v)) }
        }

        private fun readPts(a: JSONArray?): MutableList<Pt> {
            val out = mutableListOf<Pt>()
            if (a == null) return out
            for (i in 0 until a.length()) {
                val r = a.getJSONArray(i)
                out.add(Pt(r.getDouble(0), r.getDouble(1), r.optDouble(2, 0.0), r.optDouble(3, 0.0)))
            }
            return out
        }

        private fun body(p: Profile, photos: Boolean): JSONObject = JSONObject().apply {
            put("format", "descent-1")
            put("id", p.id); put("name", p.name); put("author", p.author)
            put("season", p.season); put("method", p.method)
            put("line", pts(p.line)); put("edgeL", pts(p.edgeL)); put("edgeR", pts(p.edgeR))
            put("params", JSONObject().apply {
                put("vmax", p.params.vmaxKmh); put("lambda", p.params.lambdaMax)
                put("tReact", p.params.tReact); put("decel", p.params.decel)
                put("epsSlow", p.params.epsSlow); put("aLat", p.params.aLat)
            })
            put("marks", JSONArray().apply {
                // при обмене имена фото не передаются: по ТЗ снимки идут отдельно
                p.marks.forEach {
                    val m = JSONObject().put("lat", it.lat).put("lon", it.lon).put("heading", it.heading).put("label", it.label)
                    if (photos && it.photo != null) m.put("photo", it.photo)
                    put(m)
                }
            })
        }

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        fun toJson(p: Profile, photos: Boolean = true): String {
            val b = body(p, photos)
            val raw = b.toString()
            return b.put("hash", sha256(raw)).toString(1)
        }

        /** Разбор с проверкой контрольной суммы; при несовпадении – исключение. */
        fun fromJson(text: String): Profile {
            val o = JSONObject(text)
            val hash = o.optString("hash", "")
            if (hash.isNotEmpty()) {
                o.remove("hash")
                if (sha256(o.toString()) != hash) throw IllegalArgumentException("контрольная сумма не совпала")
            }
            val pr = o.optJSONObject("params")
            val params = if (pr == null) Presets.list[1].params else Params(
                pr.optDouble("vmax", 40.0), pr.optDouble("lambda", 0.7), pr.optDouble("tReact", 1.2),
                pr.optDouble("decel", 3.0), pr.optDouble("epsSlow", 0.15), pr.optDouble("aLat", 3.5),
            )
            val marks = mutableListOf<Mark>()
            o.optJSONArray("marks")?.let { a ->
                for (i in 0 until a.length()) {
                    val m = a.getJSONObject(i)
                    marks.add(Mark(m.getDouble("lat"), m.getDouble("lon"), m.optDouble("heading", 0.0), m.optString("label", ""), m.optString("photo", "").ifBlank { null }))
                }
            }
            return Profile(
                id = o.getString("id"), name = o.optString("name", "без названия"), author = o.optString("author", ""),
                season = o.optString("season", "лето"), method = o.optString("method", "велосипед"),
                line = readPts(o.optJSONArray("line")), edgeL = readPts(o.optJSONArray("edgeL")),
                edgeR = readPts(o.optJSONArray("edgeR")), params = params, marks = marks,
            )
        }
    }
}
