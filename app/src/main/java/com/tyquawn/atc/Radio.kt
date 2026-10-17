package com.tyquawn.atc

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class Radio(ctx: Context) {
    data class Line(val who: Int, val text: String) // 0 = ATC, 1 = pilot, 2 = ATIS/system
    val log = ArrayList<Line>()
    var voice = true
    private var ready = false
    private val queued = AtomicInteger(0)
    private var n = 0
    private lateinit var tts: TextToSpeech
    init {
        tts = TextToSpeech(ctx.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) { ready = true; try { tts.language = Locale.US } catch (_: Exception) {} }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { queued.decrementAndGet() }
            @Deprecated("") override fun onError(id: String?) { queued.decrementAndGet() }
        })
    }

    /** who: 0 controller, 1 pilot, 2 atis. low = drop speech if backlog */
    fun say(who: Int, text: String, low: Boolean = false) {
        synchronized(log) { log.add(Line(who, text)); if (log.size > 60) log.removeAt(0) }
        if (!voice || !ready) return
        if (low && queued.get() > 2) return
        if (queued.get() > 6) return
        when (who) { 0 -> { tts.setPitch(1.0f); tts.setSpeechRate(1.2f) }; 1 -> { tts.setPitch(0.8f); tts.setSpeechRate(1.3f) }; else -> { tts.setPitch(1.1f); tts.setSpeechRate(1.1f) } }
        queued.incrementAndGet()
        tts.speak(speakify(text), TextToSpeech.QUEUE_ADD, null, "u${n++}")
    }
    fun stop() { if (ready && voice || ready) tts.stop(); queued.set(0) }
    fun shutdown() { tts.shutdown() }

    companion object {
        private val digitWord = arrayOf("zero", "one", "two", "tree", "four", "five", "six", "seven", "eight", "niner")
        fun digits(s: String) = s.map { if (it.isDigit()) digitWord[it - '0'] else it.toString() }.joinToString(" ")
        fun speakify(t: String): String {
            var s = t
            s = Regex("\\b([A-Z]{3})(\\d{1,4})\\b").replace(s) { m -> (TELEPHONY[m.groupValues[1]] ?: m.groupValues[1]) + " " + digits(m.groupValues[2]) }
            val nato = mapOf("A" to "Alpha", "B" to "Bravo", "C" to "Charlie", "D" to "Delta", "E" to "Echo", "F" to "Foxtrot", "G" to "Golf", "H" to "Hotel")
            s = Regex("\\b([A-H])([1-4])?\\b").replace(s) { m -> nato[m.groupValues[1]] + (if (m.groupValues[2].isNotEmpty()) " " + digits(m.groupValues[2]) else "") }
            s = Regex("heading (\\d{3})").replace(s) { "heading " + digits(it.groupValues[1]) }
            s = Regex("(Left|Right|left|right) (\\d{3})\\b").replace(s) { it.groupValues[1] + " " + digits(it.groupValues[2]) }
            s = Regex("FL(\\d{3})").replace(s) { "flight level " + digits(it.groupValues[1]) }
            s = Regex("runways? (\\d{1,2})([LRC]?)").replace(s) { m ->
                val side = when (m.groupValues[2]) { "L" -> " left"; "R" -> " right"; "C" -> " center"; else -> "" }
                m.value.substringBefore(' ') + " " + digits(m.groupValues[1]) + side }
            s = Regex("\\b(\\d{1,2}),?([1-9])00\\b").replace(s) { it.groupValues[1] + " thousand " + it.groupValues[2] + " hundred" }
            s = Regex("\\b(\\d{1,2}),?000\\b").replace(s) { it.groupValues[1] + " thousand" }
            s = Regex("wind (\\d{3}) at (\\d+)").replace(s) { "wind " + digits(it.groupValues[1]) + " at " + digits(it.groupValues[2]) }
            return s
        }
    }
}
