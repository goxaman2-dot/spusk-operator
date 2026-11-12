package ru.spusk.operator

/** Точка трека: координаты, высота, скорость м/с, время мс. */
data class Pt(val lat: Double, val lon: Double, val alt: Double = 0.0, val v: Double = 0.0, val t: Long = 0L)

/** Ориентир: точка с фотографией. */
data class Mark(val lat: Double, val lon: Double, val heading: Double, val label: String, val photo: String? = null)

/** Параметры профиля (таблица 2 ТЗ). */
data class Params(
    val vmaxKmh: Double,     // предельная скорость
    val lambdaMax: Double,   // допустимое |λ|
    val tReact: Double,      // время реакции, с
    val decel: Double,       // допустимое замедление, м/с²
    val epsSlow: Double,     // порог «Снизь скорость»
    val aLat: Double,        // допустимое боковое ускорение для формулы (9), м/с²
)

data class Preset(val name: String, val avatar: String, val params: Params)

object Presets {
    val list = listOf(
        Preset("Новичок", "avatar_novice", Params(25.0, 0.50, 1.5, 2.0, 0.10, 2.5)),
        Preset("Опытный", "avatar_expert", Params(40.0, 0.70, 1.2, 3.0, 0.15, 3.5)),
        Preset("Мастер", "avatar_master", Params(60.0, 0.85, 1.0, 4.0, 0.20, 5.0)),
    )
    const val OWN_NAME = "Свой"
    const val OWN_INDEX = 3
}

val METHODS = listOf("велосипед", "лыжи", "бег", "пешком")
val SEASONS = listOf("лето", "зима")

class Profile(
    var id: String,
    var name: String,
    var author: String = "",
    var season: String = "лето",
    var method: String = "велосипед",
    var line: MutableList<Pt> = mutableListOf(),
    var edgeL: MutableList<Pt> = mutableListOf(),
    var edgeR: MutableList<Pt> = mutableListOf(),
    var params: Params = Presets.list[1].params,
    var marks: MutableList<Mark> = mutableListOf(),
) {
    /** Скорость с записи берётся только для лыж и велосипеда; для бега и пешей записи – формула (9). */
    val speedFromRecord: Boolean get() = method == "лыжи" || method == "велосипед"
}
