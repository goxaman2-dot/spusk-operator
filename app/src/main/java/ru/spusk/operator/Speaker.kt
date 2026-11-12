package ru.spusk.operator

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Голос и вибрация. Команда с более высоким приоритетом (меньшее число) прерывает звучащую. */
class Speaker(ctx: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(ctx.applicationContext, this)
    @Volatile private var ready = false
    @Volatile private var currentPrio = 99
    var status: String = "синтез речи загружается"
        private set

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)

    override fun onInit(st: Int) {
        if (st != TextToSpeech.SUCCESS) { status = "синтез речи недоступен"; return }
        val r = tts.setLanguage(Locale("ru", "RU"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            status = "нет русского голоса: установите его в настройках синтеза речи"
            return
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setSpeechRate(1.15f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { currentPrio = 99 }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { currentPrio = 99 }
        })
        ready = true
        status = "голос готов"
    }

    fun say(cmd: Cmd) {
        vibrate(cmd.vibro)
        if (!ready) return
        val mode = if (tts.isSpeaking && cmd.priority < currentPrio) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        currentPrio = minOf(currentPrio, cmd.priority)
        tts.speak(cmd.text, mode, null, "c${System.nanoTime()}")
    }

    fun sayText(text: String) {
        if (ready) tts.speak(text, TextToSpeech.QUEUE_ADD, null, "t${System.nanoTime()}")
    }

    private fun vibrate(pattern: LongArray) {
        if (pattern.size < 2) return
        runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    fun shutdown() { tts.stop(); tts.shutdown() }
}
