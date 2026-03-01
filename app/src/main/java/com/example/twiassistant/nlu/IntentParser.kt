package com.example.twiassistant.nlu

import java.util.Locale

class IntentParser {
    private val menuNumbers = mapOf(
        "0" to 0, "zero" to 0, "nɔma" to 0, "nɔma nͻ" to 0,
        "1" to 1, "one" to 1, "baako" to 1,
        "2" to 2, "two" to 2, "mmienu" to 2, "abien" to 2,
        "3" to 3, "three" to 3, "mmiensa" to 3,
        "4" to 4, "four" to 4, "ɛnane" to 4, "ɛnan" to 4,
        "5" to 5, "five" to 5, "enum" to 5,
        "6" to 6, "six" to 6, "nsia" to 6,
        "7" to 7, "seven" to 7, "nson" to 7,
        "8" to 8, "eight" to 8, "nwɔtwe" to 8,
        "9" to 9, "nine" to 9, "nkron" to 9,
        "10" to 10, "ten" to 10, "du" to 10
    )

    private fun parseMenuSelection(text: String): Int? {
        val tokens = text.split(" ")
        tokens.forEach { token ->
            menuNumbers[token.trim()]?.let { return it }
        }
        menuNumbers[text.trim()]?.let { return it }
        return null
    }

    fun parse(text: String, allowMenuSelection: Boolean = false): IntentResult {
        val raw = text.trim()
        if (raw.isBlank()) return IntentResult.Unknown

        val lower = raw.lowercase(Locale.ROOT)

        // --- Menu selection (numbers) ---
        if (allowMenuSelection) {
            parseMenuSelection(lower)?.let { num ->
                return IntentResult.MenuSelection(option = num, c = 0.9f)
            }
        }

        // --- Read messages ---
        if (
            lower.contains("kenkan krataa") ||
            lower.contains("nkyerɛw a menkenkan") ||
            lower.contains("hwɛ nkyerɛw")
        ) {
            return IntentResult.ReadMessages(unreadOnly = true, c = 0.85f)
        }

        // --- Call ---
        // Twi: "frɛ Ama" / "frɛ 024..."
        val callPrefixes = listOf("frɛ")
        val callPrefix = callPrefixes.firstOrNull { lower.startsWith(it) }
        if (callPrefix != null) {
            val target = lower.removePrefix(callPrefix).trim().trimStart(':').trim()
            if (target.isNotBlank()) {
                val number = extractPhoneNumber(target)
                return if (number != null) {
                    IntentResult.CallNumber(number = number, c = 0.9f)
                } else {
                    IntentResult.CallContact(name = target, c = 0.85f)
                }
            }
        }

        // --- SMS / Message ---
        // Twi: "krataa ..." / "sms ..."
        // Twi: "kyɛrɛ ..." (send a message)
        if (
            lower.startsWith("krataa") || lower.startsWith("kyɛrɛ") || lower.startsWith("sms")
        ) {
            val sms = parseSms(lower)
            if (sms != null) return sms
        }

        // --- Open app ---
        // Twi: bue ("open")
        val openPrefixes = listOf("bue", "buee")
        val openPrefix = openPrefixes.firstOrNull { lower.startsWith(it) }
        if (openPrefix != null) {
            val appName = lower.removePrefix(openPrefix).trim().trimStart(':').trim()
            if (appName.isNotBlank()) {
                return IntentResult.OpenApp(appName = appName, c = 0.75f)
            }
        }

        // --- Alarm (basic) ---
        // Twi: "siesie alarm"
        if (lower.contains("alarm")) {
            val time = extractTimeText(lower)
            if (time != null) {
                return IntentResult.SetAlarm(timeText = time, c = 0.65f)
            }
        }

        // --- Brightness ---
        // Twi: "yɛ hann no soro", "fa hann no fam"
        if (lower.contains("hann")) {
            val percent = Regex("(\\d{1,3})\\s?%?").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)

            return when {
                lower.contains("soro") || lower.contains("yɛ no kɛse") -> IntentResult.AdjustBrightness(BrightnessAction.UP, null, c = 0.7f)
                lower.contains("fam") || lower.contains("yɛ no ketewa") -> IntentResult.AdjustBrightness(BrightnessAction.DOWN, null, c = 0.7f)
                percent != null -> IntentResult.AdjustBrightness(BrightnessAction.SET, percent, c = 0.75f)
                else -> IntentResult.Unknown
            }
        }

        return IntentResult.Unknown
    }

    private fun extractPhoneNumber(s: String): String? {
        // Keep digits and + only; accept common Ghana formats.
        val cleaned = s.filter { it.isDigit() || it == '+' }
        val digits = cleaned.filter { it.isDigit() }
        if (digits.length < 8) return null
        return cleaned
    }

    private fun parseSms(lower: String): IntentResult.SendSms? {
        // Patterns supported (loosely):
        // - "text Ama hi"
        // - "sms 024xxxx hi"
        // - "send a message to Ama hi"
        // - "send sms to 024xxxx hi"
        val normalized = lower
            .replace("send a message", "send message")
            .replace("send an sms", "send sms")

        // Prefer "to" keyword separation.
        val toIdx = normalized.indexOf(" to ")
        val payload = when {
            normalized.startsWith("sms") -> normalized.removePrefix("sms").trim()
            normalized.startsWith("text") -> normalized.removePrefix("text").trim()
            normalized.startsWith("message") -> normalized.removePrefix("message").trim()
            normalized.startsWith("krataa") -> normalized.removePrefix("krataa").trim()
            normalized.startsWith("kyɛrɛ") -> normalized.removePrefix("kyɛrɛ").trim()
            normalized.startsWith("send") && toIdx >= 0 -> normalized.substring(toIdx + 4).trim()
            normalized.startsWith("send") -> normalized.removePrefix("send").trim()
            else -> normalized
        }

        if (payload.isBlank()) return null

        // Split target and body: "Ama hi there" => target=Ama body="hi there"
        val parts = payload.split(Regex("\\s+"), limit = 2)
        if (parts.isEmpty()) return null

        val target = parts.first().trim().trim(',').trim()
        val body = parts.getOrNull(1)?.trim().orEmpty()
        if (target.isBlank() || body.isBlank()) {
            // Not enough info; low confidence so dialog manager can keep listening.
            return IntentResult.SendSms(nameOrNumber = target, body = body, c = 0.45f)
        }

        return IntentResult.SendSms(nameOrNumber = target, body = body, c = 0.75f)
    }

    private fun extractTimeText(lower: String): String? {
        // Very small helper: 7 am / 7pm / 07:30 / 19:30
        val hhmm = Regex("\\b([01]?\\d|2[0-3]):[0-5]\\d\\b").find(lower)?.value
        if (hhmm != null) return hhmm

        val ampm = Regex("\\b(1[0-2]|0?[1-9])\\s?(am|pm)\\b").find(lower)?.value
        if (ampm != null) return ampm.replace(" ", "")

        val hourOnly = Regex("\\b(1[0-2]|0?[1-9])\\b").find(lower)?.value
        return hourOnly
    }
}
