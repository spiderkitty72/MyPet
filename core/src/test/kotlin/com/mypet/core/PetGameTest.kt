package com.mypet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PetGameTest {

    private val day = PetState.MINUTES_PER_DAY

    /** A new game whose pet clock starts at 10:00, safely before bedtime. */
    private fun newGame(edition: String = "pebble", seed: Long = 42): PetGame {
        val game = PetGame(SaveData())
        game.newEgg(edition, "Testy", nowMillis = 0, localMinuteOfDay = 10 * 60, seed = seed)
        return game
    }

    private fun PetGame.pet() = pet!!

    /** Answers every call the way an attentive owner would. */
    private fun PetGame.perfectCare() {
        val p = pet()
        if (!p.alive || p.stage == Stage.EGG) return
        if (p.asleep) {
            if (p.lightsOn) toggleLights()
            return
        }
        if (!p.lightsOn) toggleLights()
        when (p.call) {
            CallKind.FAKE -> scold()
            else -> Unit
        }
        if (p.sick) giveMedicine()
        if (p.poops > 0) clean()
        while (p.hunger < 4 && feedMeal() == ActionResult.ATE_MEAL) Unit
        while (p.happy < 4) {
            startGame(GameKind.WHICH_WAY) ?: break
            finishGame(won = true)
        }
    }

    private fun PetGame.run(minutes: Int, care: Boolean) {
        repeat(minutes) {
            skipMinutes(1)
            if (care) perfectCare()
        }
    }

    @Test
    fun everySpriteFitsTheScreen() {
        for (edition in Editions.all) {
            assertEquals(9, edition.forms.map { it.id }.toSet().size, "unique forms in ${edition.id}")
            for (form in edition.forms) {
                assertTrue(form.sprite.width in 6..16, "${form.id} width ${form.sprite.width}")
                assertTrue(form.sprite.height in 5..16, "${form.id} height ${form.sprite.height}")
            }
        }
    }

    @Test
    fun eggHatchesAfterFiveMinutes() {
        val game = newGame()
        game.skipMinutes(4)
        assertEquals(Stage.EGG, game.pet().stage)
        game.skipMinutes(1)
        assertEquals(Stage.BABY, game.pet().stage)
        assertEquals("pebble_baby", game.pet().formId)
        assertTrue("pebble_baby" in game.save.discovered)
    }

    @Test
    fun ignoredHungerBecomesACareMistake() {
        val game = newGame()
        game.skipMinutes(5)
        val p = game.pet()
        p.happy = 4
        p.hunger = 0
        game.skipMinutes(1)
        assertEquals(CallKind.HUNGER, p.call)
        game.skipMinutes(PetGame.CALL_WINDOW_MINUTES)
        assertEquals(1, p.careMistakes)
        assertNull(p.call)
        // It does not count twice for the same unmet need.
        game.skipMinutes(30)
        assertEquals(1, p.careMistakes)
    }

    @Test
    fun feedingAnswersTheCallAndFullPetsRefuse() {
        val game = newGame()
        game.skipMinutes(5)
        val p = game.pet()
        p.hunger = 0
        game.skipMinutes(1)
        assertEquals(CallKind.HUNGER, p.call)
        assertEquals(ActionResult.ATE_MEAL, game.feedMeal())
        assertNull(p.call)
        repeat(3) { game.feedMeal() }
        assertEquals(4, p.hunger)
        assertEquals(ActionResult.FULL, game.feedMeal())
        assertEquals(0, p.careMistakes)
    }

    @Test
    fun tooManySnacksCauseATummyAche() {
        val game = newGame()
        game.skipMinutes(5)
        repeat(Editions.byId("pebble").rules.snackLimit) { game.feedSnack() }
        val p = game.pet()
        assertTrue(p.sick)
        var result: ActionResult
        do result = game.giveMedicine() while (result == ActionResult.DOSE_GIVEN)
        assertEquals(ActionResult.CURED, result)
        assertFalse(p.sick)
    }

    @Test
    fun scoldingOnlyHelpsDuringAFakeCall() {
        val game = newGame()
        game.skipMinutes(5)
        val p = game.pet()
        p.happy = 3
        assertEquals(ActionResult.SCOLD_BAD, game.scold())
        assertEquals(2, p.happy)
        p.call = CallKind.FAKE
        p.callStartMinute = p.gameMinutes
        assertEquals(ActionResult.REFUSED, game.feedMeal())
        assertEquals(ActionResult.SCOLD_GOOD, game.scold())
        assertEquals(1, p.discipline)
    }

    @Test
    fun petSleepsAtBedtimeAndWantsTheLightOff() {
        val game = newGame()
        // Hatch, then leave the baby stage so the pet keeps a bedtime.
        game.run(5 + PetGame.BABY_MINUTES + 1, care = true)
        val p = game.pet()
        assertEquals(Stage.CHILD, p.stage)
        val untilBed = 21 * 60 - p.minuteOfDay
        game.run(untilBed - 1, care = true)
        assertFalse(p.asleep)
        game.skipMinutes(1)
        assertTrue(p.asleep)
        game.skipMinutes(1)
        assertEquals(CallKind.LIGHTS, p.call)
        game.toggleLights()
        assertNull(p.call)
        assertEquals(ActionResult.ASLEEP, game.feedMeal())
        // Wakes up at 8:00 with the light back on.
        game.skipMinutes(11 * 60)
        assertFalse(p.asleep)
        assertTrue(p.lightsOn)
    }

    @Test
    fun goodCareGrowsTheBestAdult() {
        val game = newGame()
        // Keep discipline high so the best branch is open.
        game.run(10, care = true)
        game.pet().discipline = 4
        game.run(5 * day, care = true)
        val p = game.pet()
        assertEquals(Stage.ADULT, p.stage)
        assertEquals("pebble_adult_best", p.formId)
        assertEquals(0, p.careMistakes)
        assertTrue(p.alive)
    }

    @Test
    fun perfectCareUnlocksTheSecretForm() {
        val game = newGame()
        game.run(10, care = true)
        game.pet().discipline = 4
        game.run(7 * day, care = true)
        assertEquals(Stage.SECRET, game.pet().stage)
    }

    @Test
    fun neglectGrowsTheRoughBranchOrEndsTheLife() {
        val game = newGame()
        game.run(5 * day, care = false)
        val p = game.pet()
        assertTrue(p.careMistakes > 3)
        assertTrue(!p.alive || p.formId == "pebble_adult_rough" || p.formId == "pebble_teen_bad")
    }

    @Test
    fun totalNeglectEventuallyEndsTheGame() {
        val game = newGame()
        game.run(4 * day, care = false)
        val p = game.pet()
        assertNotNull(p.ending)
        assertEquals(1, game.save.history.size)
    }

    @Test
    fun wellKeptPetLivesOutItsLifespan() {
        val game = newGame()
        game.run(25 * day, care = true)
        val p = game.pet()
        assertEquals(Ending.OLD_AGE, p.ending)
        val expected = p.form.lifespanDays.toInt()
        assertTrue(p.ageDays in expected - 1..expected, "age ${p.ageDays}, form ${p.formId}")
        assertEquals(p.formId, game.save.history.single().formId)
    }

    @Test
    fun novaGrowsUpTwiceAsFast() {
        val game = newGame("nova")
        game.run(10, care = true)
        game.pet().discipline = 4
        game.run((2.6 * day).toInt(), care = true)
        assertEquals(Stage.ADULT, game.pet().stage)
    }

    @Test
    fun realTimeCatchUpRespectsSpeedAndPause() {
        val game = newGame()
        val minute = PetGame.MILLIS_PER_MINUTE
        assertEquals(10, game.advanceTo(10 * minute))
        game.setSpeed(4, 10 * minute)
        assertEquals(40, game.advanceTo(20 * minute))
        game.setPaused(true, 20 * minute)
        assertEquals(0, game.advanceTo(80 * minute))
        game.setPaused(false, 80 * minute)
        assertEquals(4, game.advanceTo(81 * minute))
    }

    @Test
    fun saveRoundTripsThroughJson() {
        val game = newGame("tide")
        game.run(200, care = true)
        val json = game.save.toJson()
        val restored = SaveData.fromJson(json)
        assertEquals(game.save, restored)
        assertEquals("tide", restored.pet!!.editionId)
    }

    @Test
    fun corruptSaveStartsFresh() {
        assertNull(SaveData.fromJson("{not json").pet)
        assertNull(SaveData.fromJson(null).pet)
    }

    @Test
    fun miniGamesNeedThreeWinsOfFive() {
        val game = MiniGame.WhichWay(7)
        repeat(MiniGame.ROUNDS) { game.guess(left = true) }
        assertTrue(game.finished)
        assertEquals(game.wins >= 3, game.won)

        val hiLo = MiniGame.HiLo(3)
        repeat(MiniGame.ROUNDS) {
            val before = hiLo.shown
            val correct = hiLo.guess(higher = true)
            assertEquals(hiLo.revealed!! > before, correct)
        }
        assertTrue(hiLo.finished)

        val catch = MiniGame.Catch(1)
        repeat(3) { catch.submit(true) }
        repeat(2) { catch.submit(false) }
        assertTrue(catch.won)
    }

    @Test
    fun bedtimeWrapsPastMidnight() {
        val rules = Editions.byId("nova").rules // 23:00 to 09:00
        assertTrue(PetGame.isBedtime(23 * 60 + 30, rules))
        assertTrue(PetGame.isBedtime(3 * 60, rules))
        assertFalse(PetGame.isBedtime(12 * 60, rules))
    }

    @Test
    fun predictsTheNextCallWithoutChangingTheGame() {
        val game = newGame()
        game.skipMinutes(5)
        val p = game.pet()
        p.hunger = 1
        p.happy = 4
        p.hungerTimer = 30
        p.happyTimer = 500
        val before = game.save.toJson()
        val minutes = game.minutesUntilNextCall()
        assertEquals(before, game.save.toJson())
        assertNotNull(minutes)
        assertTrue(minutes <= 30, "next call in $minutes")
        game.skipMinutes(minutes)
        assertNotNull(p.call)
    }
}
