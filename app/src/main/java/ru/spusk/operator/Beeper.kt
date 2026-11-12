package ru.spusk.operator

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Стереопищалка близости края трассы, как парктроник.
 * Левый край – писк только в левом наушнике (низкий тон 700 Гц),
 * правый край – только в правом (высокий тон 1300 Гц).
 * Разные тоны позволяют различить сторону и с одним наушником.
 * Чем ближе к краю, тем чаще писк: от 900 мс до 150 мс между сигналами.
 */
class Beeper {
    @Volatile var enabled = true
    @Volatile private var side = 0            // +1 – левый край, −1 – правый, 0 – тишина
    @Volatile private var level = 0.0         // 0 – начало зоны, 1 – на самом краю и дальше
    @Volatile private var lastUpdate = 0L
    private var running = false
    private val h = Handler(Looper.getMainLooper())

    private val leftTone by lazy { tone(700.0, left = true) }
    private val rightTone by lazy { tone(1300.0, left = false) }

    /** Вызывается на каждом отсчёте навигации. */
    fun update(newSide: Int, newLevel: Double) {
        side = newSide
        level = newLevel.coerceIn(0.0, 1.0)
        lastUpdate = SystemClock.elapsedRealtime()
        if (side != 0 && enabled && !running) { running = true; h.post(tick) }
    }

    fun stop() { side = 0 }

    private val tick = object : Runnable {
        override fun run() {
            val stale = SystemClock.elapsedRealtime() - lastUpdate > 3000   // нет свежих координат – молчим
            if (side == 0 || !enabled || stale) { running = false; return }
            val t = if (side > 0) leftTone else rightTone
            try { t.stop(); t.reloadStaticData(); t.play() } catch (_: Exception) {}
            h.postDelayed(this, intervalMs(level))
        }
    }

    private fun tone(freq: Double, left: Boolean): AudioTrack {
        val rate = 44_100
        val n = rate * 90 / 1000                       // 90 мс
        val ramp = rate * 0.006                        // мягкие фронты без щелчков
        val buf = ShortArray(n * 2)                    // стерео: [Л, П, Л, П, …]
        for (i in 0 until n) {
            val env = min(1.0, min(i, n - i) / ramp)
            val v = (sin(2 * PI * freq * i / rate) * env * 0.7 * Short.MAX_VALUE).toInt().toShort()
            if (left) buf[2 * i] = v else buf[2 * i + 1] = v
        }
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(buf.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(buf, 0, buf.size)
        return t
    }

    companion object {
        fun intervalMs(level: Double): Long = (900 - 750 * level).toLong().coerceIn(150L, 900L)
    }
}
