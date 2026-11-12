package ru.spusk.operator

/** Библиотека команд оператора (таблица 4 ТЗ). */
enum class Code { TURN_L, TURN_R, STR, SLOW, BRAKE, STOP, EDGE_L, EDGE_R, BACK, MARK, FAST, FIN }

class Cmd(val code: Code, val text: String, val priority: Int, val vibro: LongArray) {
    /** Ключ антиповтора: повороты с разным углом считаются разными командами. */
    val key: String get() = if (code == Code.TURN_L || code == Code.TURN_R) "${code}_$text" else code.name

    companion object {
        private val SHORT2 = longArrayOf(0, 120, 120, 120)
        private val SHORT3 = longArrayOf(0, 120, 120, 120, 120, 120)
        private val LONG1 = longArrayOf(0, 450)
        private val LONG2 = longArrayOf(0, 400, 150, 400)
        private val LONG3 = longArrayOf(0, 400, 150, 400, 150, 400)

        fun turn(left: Boolean, angle: Int?): Cmd {
            val word = if (left) "Лево" else "Право"
            val text = if (angle == null) word else "$word $angle"
            return Cmd(if (left) Code.TURN_L else Code.TURN_R, text, 3, if (left) SHORT2 else SHORT3)
        }

        val STRAIGHT = Cmd(Code.STR, "Прямо", 5, longArrayOf())
        val SLOW = Cmd(Code.SLOW, "Снизь скорость", 2, LONG1)
        val BRAKE = Cmd(Code.BRAKE, "Тормози", 1, LONG3)
        val STOP = Cmd(Code.STOP, "Стоп", 1, longArrayOf(0, 1000))
        val EDGE_L = Cmd(Code.EDGE_L, "Левый край", 2, LONG2)
        val EDGE_R = Cmd(Code.EDGE_R, "Правый край", 2, LONG2)
        val BACK = Cmd(Code.BACK, "Вернись на трассу", 1, longArrayOf(0, 200, 100, 200, 100, 200, 100, 200))
        val FAST = Cmd(Code.FAST, "Можно быстрее", 6, longArrayOf())
        val FIN = Cmd(Code.FIN, "Финиш", 5, LONG1)
        fun mark(label: String) = Cmd(Code.MARK, "Внимание, $label", 4, longArrayOf(0, 120))

        /** Набор кнопок пульта оператора. */
        val PANEL: List<Cmd> = listOf(
            turn(true, 15), turn(true, 30), turn(true, 45), turn(true, 60), turn(true, 90),
            turn(false, 15), turn(false, 30), turn(false, 45), turn(false, 60), turn(false, 90),
            STRAIGHT, SLOW, BRAKE, STOP, EDGE_L, EDGE_R, BACK, FIN,
        )
    }
}

/** Разбор голосовой фразы оператора по закрытой грамматике (раздел 7 ТЗ). */
object CommandParser {
    private val numbers = listOf(
        "девяносто" to 90, "восемьдесят" to 80, "семьдесят" to 70, "шестьдесят" to 60,
        "пятьдесят" to 50, "сорок пять" to 45, "сорок" to 40, "тридцать" to 30,
        "двадцать" to 20, "пятнадцать" to 15, "десять" to 10,
    )

    fun parse(raw: String): Cmd? {
        val s = raw.lowercase().replace('ё', 'е')
        when {
            "тормоз" in s -> return Cmd.BRAKE
            Regex("(?U)\\bсто(п|й)\\b").containsMatchIn(s) -> return Cmd.STOP
            "тише" in s || "медленн" in s || "снизь" in s || "сбавь" in s -> return Cmd.SLOW
            "верн" in s -> return Cmd.BACK
            "финиш" in s -> return Cmd.FIN
            "прямо" in s -> return Cmd.STRAIGHT
            "быстрее" in s -> return Cmd.FAST
        }
        val left = Regex("(?U)\\b(лево|влево|левее|налево)\\b").containsMatchIn(s) || (s.contains("лев") && !s.contains("прав"))
        val right = Regex("(?U)\\b(право|вправо|правее|направо)\\b").containsMatchIn(s) || (s.contains("прав") && !s.contains("лев"))
        if ("край" in s) {
            if (left && !right) return Cmd.EDGE_L
            if (right && !left) return Cmd.EDGE_R
            return null
        }
        if (left == right) return null
        val digits = Regex("\\d{1,2}").find(s)?.value?.toIntOrNull()
        val angle = digits ?: numbers.firstOrNull { it.first in s }?.second
        val rounded = angle?.let { (((it + 7) / 15) * 15).coerceIn(15, 90) }
        return Cmd.turn(left, rounded)
    }
}
