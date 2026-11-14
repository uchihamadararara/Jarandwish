package com.jarvis.assistant.wellbeing

import java.util.Locale

/**
 * Understands "aaj sirf 2 ghante phone chalaunga" / "roz 3 ghante se zyada phone use nahi karunga"
 * directly from what Boss said (Roman or Devanagari Hinglish), so setting a limit does not depend on
 * Gemini repeating an exact phrase.
 */
object WellbeingVoiceParser {

    sealed class Command {
        /** daily = every day, otherwise only today. */
        data class SetLimit(val minutes: Int, val daily: Boolean) : Command()

        /** daily = true/false removes only that limit, null removes both. */
        data class RemoveLimit(val daily: Boolean?) : Command()
    }

    private val PHONE = listOf("phone", "mobile", "screen", "फोन", "फ़ोन", "मोबाइल", "स्क्रीन")
    private val LIMIT_WORD = listOf("limit", "लिमिट", "सीमा")
    private val REMOVE = listOf(
        "hata", "hatao", "hatado", "remove", "cancel", "delete", "band kar", "off kar", "khatam",
        "हटा", "रद्द", "बंद कर", "खत्म"
    )
    private val LIMIT_CUE = listOf(
        "sirf", "sirph", "only", "max", "maximum", "zyada", "jyada", "zyaada", "adhik", "limit", "tak",
        "सिर्फ", "केवल", "ज्यादा", "ज़्यादा", "अधिक", "लिमिट", "तक"
    )
    private val USAGE_VERB = listOf(
        "chalau", "chalana", "chalani", "istemal", "istemaal", "use", "इस्तेमाल", "चलाऊं", "चलाऊँ", "चलाना", "चलाउंगा", "चलाऊंगा"
    )
    private val DAILY = listOf(
        "roz", "rozana", "daily", "everyday", "har din", "har roz", "every day", "रोज", "रोजाना", "हर दिन", "हर रोज"
    )
    private val TODAY = listOf("aaj", "today", "आज")
    private val REMINDER = listOf("yaad dila", "yaad dil", "remind", "याद दिला", "रिमाइंडर")

    private const val NOT_BEFORE = "(?<![\\p{L}\\p{M}\\p{N}.])"
    private const val NOT_AFTER = "(?![\\p{L}\\p{M}\\p{N}])"
    private const val NUM =
        "(\\d+(?:\\.\\d+)?|ek|one|do|two|teen|three|char|chaar|four|paanch|panch|five|chhe|chhah|che|six|saat|seven|aath|eight|nau|nine|das|ten|gyarah|barah|aadha|adha|half|dedh|derh|dhai|dhaai|sava|एक|दो|तीन|चार|पांच|पाँच|छह|छः|सात|आठ|नौ|दस|ग्यारह|बारह|आधा|डेढ़|डेढ|ढाई|सवा)"
    private const val UNIT =
        "(ghante|ghanta|ghantey|ghantay|ghanto|hours?|hrs?|घंटे|घंटा|घण्टे|घण्टा|minutes?|mins?|मिनट|मिनिट)"
    private val DURATION = Regex("$NOT_BEFORE$NUM\\s*(?:aur\\s+)?$UNIT$NOT_AFTER")

    private val WORD_VALUES = mapOf(
        "ek" to 1.0, "one" to 1.0, "do" to 2.0, "two" to 2.0, "teen" to 3.0, "three" to 3.0,
        "char" to 4.0, "chaar" to 4.0, "four" to 4.0, "paanch" to 5.0, "panch" to 5.0, "five" to 5.0,
        "chhe" to 6.0, "chhah" to 6.0, "che" to 6.0, "six" to 6.0, "saat" to 7.0, "seven" to 7.0,
        "aath" to 8.0, "eight" to 8.0, "nau" to 9.0, "nine" to 9.0, "das" to 10.0, "ten" to 10.0,
        "gyarah" to 11.0, "barah" to 12.0, "aadha" to 0.5, "adha" to 0.5, "half" to 0.5,
        "dedh" to 1.5, "derh" to 1.5, "dhai" to 2.5, "dhaai" to 2.5, "sava" to 1.25,
        "एक" to 1.0, "दो" to 2.0, "तीन" to 3.0, "चार" to 4.0, "पांच" to 5.0, "पाँच" to 5.0,
        "छह" to 6.0, "छः" to 6.0, "सात" to 7.0, "आठ" to 8.0, "नौ" to 9.0, "दस" to 10.0,
        "ग्यारह" to 11.0, "बारह" to 12.0, "आधा" to 0.5, "डेढ़" to 1.5, "डेढ" to 1.5, "ढाई" to 2.5, "सवा" to 1.25
    )

    fun parse(raw: String): Command? {
        val t = normalize(raw)
        if (t.isBlank()) return null
        if (REMINDER.any { has(t, it, true) }) return null // reminders are handled elsewhere

        val phone = PHONE.any { has(t, it, true) }
        val limitWord = LIMIT_WORD.any { has(t, it, true) }
        if (!phone && !limitWord) return null

        val daily = DAILY.any { has(t, it, false) }
        val today = TODAY.any { has(t, it, false) }

        if (limitWord && REMOVE.any { has(t, it, true) }) {
            return Command.RemoveLimit(if (daily) true else if (today) false else null)
        }

        val minutes = totalMinutes(t)
        if (minutes <= 0) return null
        val cue = LIMIT_CUE.any { has(t, it, false) } ||
            (USAGE_VERB.any { has(t, it, true) } && (daily || today))
        if (!cue) return null
        return Command.SetLimit(minutes.coerceIn(1, 1440), daily)
    }

    private fun totalMinutes(t: String): Int {
        var total = 0.0
        for (m in DURATION.findAll(t)) {
            val numText = m.groupValues[1]
            val value = numText.toDoubleOrNull() ?: WORD_VALUES[numText] ?: continue
            val unit = m.groupValues[2]
            val perUnit = if (unit.startsWith("m") || unit.startsWith("म")) 1.0 else 60.0
            total += value * perUnit
        }
        return Math.round(total).toInt()
    }

    private fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw.lowercase(Locale.ROOT)) {
            sb.append(if (ch in '\u0966'..'\u096F') ('0' + (ch - '\u0966')) else ch) // Devanagari digits
        }
        return sb.toString()
            .replace(Regex("(?<=\\d),(?=\\d)"), ".")
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}.\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Whole-word match (or word-start match when [prefix]); phrases with a space use plain contains. */
    private fun has(t: String, entry: String, prefix: Boolean): Boolean {
        if (entry.contains(' ')) return t.contains(entry)
        val re = "(?<![\\p{L}\\p{M}\\p{N}])" + Regex.escape(entry) + (if (prefix) "" else NOT_AFTER)
        return Regex(re).containsMatchIn(t)
    }
}
