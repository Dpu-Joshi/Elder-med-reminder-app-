package com.medreminder.data

import kotlin.math.floor

/**
 * Words the elder sees and hears. Everyday words (dawai, goli, khana) instead of medical terms;
 * the medicine's name is secondary to its photo. Kept in code rather than string resources
 * because the elder's language is chosen by the family, not by the phone's locale, and the
 * same words feed the text-to-speech line.
 */
object ReminderText {

    fun doseLabel(lang: Language, amount: Double, unit: DoseUnit): String {
        val number = fraction(amount)
        return when (lang) {
            Language.HINDI -> "$number ${hindiUnit(unit)}"
            Language.ENGLISH -> "$number ${englishUnit(unit, amount)}"
        }
    }

    fun foodLabel(lang: Language, rule: FoodRule): String? = when (rule) {
        FoodRule.BEFORE -> if (lang == Language.HINDI) "खाने से पहले" else "Before food"
        FoodRule.AFTER -> if (lang == Language.HINDI) "खाने के बाद" else "After food"
        FoodRule.ANY -> null
    }

    fun timeOfDayLabel(lang: Language, part: TimeOfDay): String = when (part) {
        TimeOfDay.MORNING -> if (lang == Language.HINDI) "सुबह" else "Morning"
        TimeOfDay.AFTERNOON -> if (lang == Language.HINDI) "दोपहर" else "Afternoon"
        TimeOfDay.NIGHT -> if (lang == Language.HINDI) "रात" else "Night"
    }

    fun timeOfDayIcon(part: TimeOfDay): String = when (part) {
        TimeOfDay.MORNING -> "☀️"
        TimeOfDay.AFTERNOON -> "🌤️"
        TimeOfDay.NIGHT -> "🌙"
    }

    /** Spoken at dose time, for example "मां, BP की गोली का समय हो गया। एक गोली, खाने के बाद।" */
    fun speech(lang: Language, nickname: String, medicine: Medicine): String {
        val name = medicine.spokenName?.takeIf { it.isNotBlank() }
        val food = foodLabel(lang, medicine.foodRule)
        return when (lang) {
            Language.HINDI -> buildString {
                if (nickname.isNotBlank()) append(nickname).append(", ")
                append(name ?: "दवाई").append(" का समय हो गया। ")
                append(hindiSpokenAmount(medicine.doseAmount)).append(' ').append(hindiUnit(medicine.doseUnit))
                if (food != null) append(", ").append(food)
                append('।')
            }
            Language.ENGLISH -> englishSpeech(nickname, medicine)
        }
    }

    /** Used when the phone has no Hindi voice: Devanagari read by an English voice says nothing useful. */
    fun englishSpeech(nickname: String, medicine: Medicine): String = buildString {
        if (nickname.isNotBlank()) append(nickname).append(", ")
        append("time for your ")
        append(medicine.spokenName?.takeIf { it.isNotBlank() && it.all { c -> c.code < 128 } } ?: "medicine")
        append(". ")
        append(doseLabel(Language.ENGLISH, medicine.doseAmount, medicine.doseUnit).replace("½", "half"))
        foodLabel(Language.ENGLISH, medicine.foodRule)?.let { append(", ").append(it.lowercase()) }
        append('.')
    }

    fun taken(lang: Language) = if (lang == Language.HINDI) "ले ली" else "Taken"
    fun remindLater(lang: Language) = if (lang == Language.HINDI) "10 मिनट बाद याद दिलाना" else "Remind me in 10 minutes"
    fun sayAgain(lang: Language) = if (lang == Language.HINDI) "फिर से सुनें" else "Say it again"
    fun done(lang: Language) = if (lang == Language.HINDI) "हो गया" else "Done"
    fun willRingAgain(lang: Language) = if (lang == Language.HINDI) "10 मिनट बाद फिर बजेगा" else "Will ring again in 10 minutes"
    fun alreadyTaken(lang: Language, clock: String) =
        if (lang == Language.HINDI) "पहले ही ले ली\n$clock बजे" else "Already taken\nat $clock"
    fun today(lang: Language) = if (lang == Language.HINDI) "आज की दवाई" else "Today's medicines"
    fun nothingToday(lang: Language) = if (lang == Language.HINDI) "आज कोई दवाई नहीं" else "No medicines today"
    fun call(lang: Language, name: String) =
        if (lang == Language.HINDI) "$name को फ़ोन करें" else "Call $name"
    fun privacy(lang: Language) = if (lang == Language.HINDI) "गोपनीयता" else "Privacy"

    /** What the app does and keeps, in plain words; also shown on the Privacy screen. */
    fun consentBody(lang: Language): String = when (lang) {
        Language.HINDI ->
            "आपके परिवार ने आपकी दवाई का समय याद दिलाने के लिए यह ऐप लगाया है। " +
                "दवाई के समय फ़ोन बजेगा। आप \"ले ली\" दबाएंगे तो परिवार को पता चलेगा। " +
                "आधे घंटे तक नहीं दबाया तो परिवार को खबर जाएगी। " +
                "सिर्फ़ आपकी दवाइयों के नाम और समय रखे जाते हैं। आप जब चाहें इसे बंद करके सब मिटा सकते हैं।"
        Language.ENGLISH ->
            "Your family has set up this app to remind you when your medicine is due. " +
                "The phone will ring at dose time. When you tap \"Taken\", your family will know. " +
                "If it is not tapped within half an hour, your family gets a message. " +
                "Only your medicine names and times are kept. You can turn it off and delete everything whenever you want."
    }

    /** Read aloud on the elder's phone after pairing; the elder, not the family, agrees. */
    fun consent(lang: Language): String =
        consentBody(lang) + if (lang == Language.HINDI) " क्या यह ठीक है?" else " Is that all right?"

    fun consentYes(lang: Language) = if (lang == Language.HINDI) "हाँ, ठीक है" else "Yes, that's fine"
    fun consentNo(lang: Language) = if (lang == Language.HINDI) "नहीं" else "No"
    fun deleteMyData(lang: Language) = if (lang == Language.HINDI) "ऐप बंद करें और मेरी जानकारी मिटाएं" else "Turn off and delete my data"

    /** 0.5 → "½", 1.5 → "1½", 2.0 → "2". */
    fun fraction(amount: Double): String {
        val whole = floor(amount).toInt()
        val half = amount - whole >= 0.5
        return when {
            whole == 0 && half -> "½"
            half -> "$whole½"
            else -> whole.toString()
        }
    }

    private fun hindiSpokenAmount(amount: Double): String = when (amount) {
        0.5 -> "आधी"
        1.0 -> "एक"
        1.5 -> "डेढ़"
        2.0 -> "दो"
        2.5 -> "ढाई"
        3.0 -> "तीन"
        4.0 -> "चार"
        5.0 -> "पांच"
        else -> fraction(amount)
    }

    private fun hindiUnit(unit: DoseUnit): String = when (unit) {
        DoseUnit.TABLET -> "गोली"
        DoseUnit.CAPSULE -> "कैप्सूल"
        DoseUnit.SPOON -> "चम्मच"
        DoseUnit.DROPS -> "बूंद"
        DoseUnit.PUFF -> "पफ"
        DoseUnit.INJECTION -> "इंजेक्शन"
        DoseUnit.ML -> "ml"
    }

    private fun englishUnit(unit: DoseUnit, amount: Double): String {
        val plural = amount > 1.0 && unit != DoseUnit.ML && unit != DoseUnit.DROPS
        val base = when (unit) {
            DoseUnit.TABLET -> "tablet"
            DoseUnit.CAPSULE -> "capsule"
            DoseUnit.SPOON -> "spoon"
            DoseUnit.DROPS -> "drops"
            DoseUnit.PUFF -> "puff"
            DoseUnit.INJECTION -> "injection"
            DoseUnit.ML -> "ml"
        }
        return if (plural) base + "s" else base
    }
}
