package com.medreminder.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DosePlannerTest {

    @Test
    fun matchesTheVectorsSharedWithTheServer() {
        val text = javaClass.classLoader!!.getResource("dose-plan-vectors.json")!!.readText()
        val cases = JSONObject(text).getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val s = c.getJSONObject("schedule")
            val times = s.getJSONArray("times").let { a -> (0 until a.length()).map { a.getString(it) } }
            val schedule = DosePlanner.Schedule(
                times = times,
                startDate = s.getString("startDate"),
                endDate = if (s.isNull("endDate")) null else s.getString("endDate"),
                active = s.getBoolean("active"),
            )
            val actual = DosePlanner.plan(
                c.getString("medicineId"),
                schedule,
                ZoneId.of(c.getString("timeZone")),
                LocalDate.parse(c.getString("from")),
                LocalDate.parse(c.getString("to")),
            ).map { it.id to Instant.ofEpochMilli(it.scheduledAtMillis).toString() }

            val expected = c.getJSONArray("expected").let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { e -> e.getString("id") to e.getString("scheduledAt") } }
            }
            assertEquals(c.getString("name"), expected, actual)
        }
    }

    @Test
    fun medicineIdOfStripsDateAndTime() {
        assertEquals("abc_def", DosePlanner.medicineIdOf("abc_def_20261019_0800"))
    }
}

class ReminderTextTest {

    private val bp = Medicine(
        id = "m1",
        name = "Amlodipine 5mg",
        spokenName = "BP की गोली",
        doseAmount = 1.0,
        doseUnit = DoseUnit.TABLET,
        foodRule = FoodRule.AFTER,
        times = listOf("08:00"),
        startDate = "2026-10-19",
    )

    @Test
    fun hindiSpeechUsesTheFamilyWords() {
        assertEquals("मां, BP की गोली का समय हो गया। एक गोली, खाने के बाद।", ReminderText.speech(Language.HINDI, "मां", bp))
    }

    @Test
    fun englishFallbackNeverReadsDevanagari() {
        assertEquals("Maa, time for your medicine. 1 tablet, after food.", ReminderText.englishSpeech("Maa", bp))
        assertEquals(
            "time for your sugar tablet. half tablet.",
            ReminderText.englishSpeech("", bp.copy(spokenName = "sugar tablet", doseAmount = 0.5, foodRule = FoodRule.ANY)),
        )
    }

    @Test
    fun doseLabels() {
        assertEquals("½ गोली", ReminderText.doseLabel(Language.HINDI, 0.5, DoseUnit.TABLET))
        assertEquals("2 tablets", ReminderText.doseLabel(Language.ENGLISH, 2.0, DoseUnit.TABLET))
        assertEquals("1½ spoons", ReminderText.doseLabel(Language.ENGLISH, 1.5, DoseUnit.SPOON))
        assertEquals("5 ml", ReminderText.doseLabel(Language.ENGLISH, 5.0, DoseUnit.ML))
    }

    @Test
    fun timeOfDayBuckets() {
        assertEquals(
            listOf(TimeOfDay.MORNING, TimeOfDay.MORNING, TimeOfDay.AFTERNOON, TimeOfDay.NIGHT),
            listOf("00:30", "11:59", "12:00", "17:00").map(TimeOfDay::of),
        )
    }
}
