package com.mypet.core

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class ActionResult {
    OK,
    ATE_MEAL,
    ATE_SNACK,
    FULL,
    REFUSED,
    CLEANED,
    NOTHING_TO_CLEAN,
    DOSE_GIVEN,
    CURED,
    NOT_SICK,
    LIGHTS_ON,
    LIGHTS_OFF,
    SCOLD_GOOD,
    SCOLD_BAD,
    GAME_WON,
    GAME_LOST,
    ASLEEP,
    DARK,
    STILL_EGG,
    NO_PET,
}

/**
 * The rules engine. It is a pure function of the save data and the clock, so the
 * Android app, background worker and unit tests all drive it the same way.
 *
 * Default rules (see README for the full table):
 *  - Time runs in real time at 1x; 2x and 4x are optional in settings.
 *  - Egg 5 min, baby 1 hour, child 1 day, teen 3 days, then adult (scaled per edition).
 *  - An unanswered call becomes a care mistake after 15 game minutes.
 *  - Classic editions can end in death; Sprout ends with the pet heading home.
 */
class PetGame(val save: SaveData) {

    val pet: PetState? get() = save.pet

    // ------------------------------------------------------------------ lifecycle

    fun newEgg(editionId: String, name: String, nowMillis: Long, localMinuteOfDay: Int, seed: Long = nowMillis) {
        val edition = Editions.byId(editionId)
        save.pet = PetState(
            editionId = edition.id,
            name = name.trim().ifEmpty { edition.baby.name }.take(MAX_NAME),
            formId = edition.egg.id,
            clockMinute = localMinuteOfDay.toLong(),
            rngState = seed,
        )
        save.lastRealMillis = nowMillis
        save.carryMillis = 0
        save.paused = false
        discover(edition.egg)
    }

    /** Runs the simulation forward to [nowMillis]. Returns game minutes simulated. */
    fun advanceTo(nowMillis: Long): Int {
        val p = save.pet
        val elapsed = nowMillis - save.lastRealMillis
        if (p == null || !p.alive || save.paused || elapsed <= 0) {
            save.lastRealMillis = nowMillis
            return 0
        }
        val total = elapsed * save.settings.speed.coerceIn(1, 4) + save.carryMillis
        var minutes = total / MILLIS_PER_MINUTE
        save.carryMillis = total % MILLIS_PER_MINUTE
        save.lastRealMillis = nowMillis
        minutes = min(minutes, MAX_CATCH_UP_MINUTES)
        var done = 0
        while (done < minutes && p.alive) {
            step(p)
            done++
        }
        return done
    }

    /** For testing on a real device: jump ahead without waiting. */
    fun skipMinutes(minutes: Int) {
        val p = save.pet ?: return
        repeat(minutes) { if (p.alive) step(p) }
    }

    fun setPaused(paused: Boolean, nowMillis: Long) {
        advanceTo(nowMillis)
        save.paused = paused
        save.lastRealMillis = nowMillis
    }

    fun setSpeed(speed: Int, nowMillis: Long) {
        advanceTo(nowMillis)
        save.settings.speed = speed.coerceIn(1, 4)
    }

    // ------------------------------------------------------------------ simulation

    private fun step(p: PetState) {
        val rules = p.edition.rules
        p.gameMinutes++
        p.clockMinute++

        if (p.stage == Stage.EGG) {
            if (p.gameMinutes - p.stageStartMinute >= EGG_MINUTES) hatch(p)
            return
        }

        updateSleep(p, rules)

        if (!p.asleep) {
            val baby = p.stage == Stage.BABY
            // Hearts drain.
            if (--p.hungerTimer <= 0) {
                p.hunger = max(0, p.hunger - 1)
                p.hungerTimer = if (baby) rules.hungerMinutes / 2 else rules.hungerMinutes
            }
            if (--p.happyTimer <= 0) {
                p.happy = max(0, p.happy - 1)
                p.happyTimer = if (baby) rules.happyMinutes / 2 else rules.happyMinutes
            }
            // Droppings.
            if (--p.poopTimer <= 0) {
                if (p.poops < MAX_POOPS) p.poops++
                if (p.oldestPoopMinute < 0) p.oldestPoopMinute = p.gameMinutes
                p.poopTimer = if (baby) rules.poopMinutes / 3 else rules.poopMinutes
            }
            // Snacks wear off.
            if (p.snackCount > 0 && --p.snackDecayTimer <= 0) {
                p.snackCount--
                p.snackDecayTimer = SNACK_DECAY_MINUTES
            }
            // Random bugs, more likely when overweight.
            if (!p.sick) {
                val overweight = p.weight >= baseWeight(p.stage) + OVERWEIGHT_MARGIN
                val perMinute = rules.sicknessPerHour / 60.0 * (if (overweight) 4.0 else 1.0)
                if (roll(p) < perMinute) makeSick(p)
            }
            // Long neglect ends the pet's life.
            p.starvingMinutes = if (p.hunger == 0) p.starvingMinutes + 1 else 0
            if (p.sick) p.sickMinutes++
            if (p.starvingMinutes >= STARVE_MINUTES) return end(p, Ending.STARVED)
            if (p.sick && p.sickMinutes >= ILLNESS_MINUTES) return end(p, Ending.ILLNESS)
        }

        // Droppings left too long make the pet sick (even overnight).
        if (p.poops > 0 && !p.sick && p.gameMinutes - p.oldestPoopMinute >= DIRTY_MINUTES) {
            makeSick(p)
            p.oldestPoopMinute = p.gameMinutes
        }

        updateCalls(p, rules)

        if (!p.asleep) {
            checkGrowth(p, rules)
            checkOldAge(p, rules)
        }
    }

    private fun hatch(p: PetState) {
        val e = p.edition
        p.hatchMinute = p.gameMinutes
        p.hunger = 1
        p.happy = 1
        p.weight = baseWeight(Stage.BABY)
        p.hungerTimer = e.rules.hungerMinutes / 2
        p.happyTimer = e.rules.happyMinutes / 2
        p.poopTimer = e.rules.poopMinutes / 3
        evolve(p, e.baby)
    }

    private fun updateSleep(p: PetState, rules: EditionRules) {
        val sleeps = p.stage != Stage.BABY && isBedtime(p.minuteOfDay, rules)
        if (sleeps && !p.asleep) {
            p.asleep = true
            if (p.call != CallKind.LIGHTS) p.call = null
        } else if (!sleeps && p.asleep) {
            p.asleep = false
            p.lightsOn = true
            if (p.call == CallKind.LIGHTS) p.call = null
        }
    }

    private fun updateCalls(p: PetState, rules: EditionRules) {
        val needs = buildList {
            if (p.asleep) {
                if (p.lightsOn) add(CallKind.LIGHTS)
            } else {
                if (p.sick) add(CallKind.SICK)
                if (p.hunger == 0) add(CallKind.HUNGER)
                if (p.happy == 0) add(CallKind.HAPPY)
            }
        }
        p.penalized = p.penalized.filterTo(mutableSetOf()) { it in needs }
        val current = p.call
        if (current != null && current != CallKind.FAKE && current !in needs) p.call = null

        if (p.call == null) {
            val next = needs.firstOrNull { it !in p.penalized }
            if (next != null) {
                startCall(p, next)
            } else if (!p.asleep && needs.isEmpty() && p.discipline < 4 &&
                roll(p) < rules.fakeCallPerHour / 60.0
            ) {
                startCall(p, CallKind.FAKE)
            }
        }

        val call = p.call ?: return
        if (p.gameMinutes - p.callStartMinute >= CALL_WINDOW_MINUTES) {
            if (call == CallKind.FAKE) {
                p.disciplineMisses++
            } else {
                p.careMistakes++
                p.stageMistakes++
                if (p.stage == Stage.ADULT || p.stage == Stage.SECRET) p.adultMistakes++
                p.penalized = p.penalized + call
            }
            p.call = null
        }
    }

    private fun startCall(p: PetState, kind: CallKind) {
        p.call = kind
        p.callStartMinute = p.gameMinutes
    }

    private fun checkGrowth(p: PetState, rules: EditionRules) {
        val e = p.edition
        val inStage = p.gameMinutes - p.stageStartMinute
        val day = PetState.MINUTES_PER_DAY
        when (p.form.id) {
            e.baby.id -> if (inStage >= BABY_MINUTES) evolve(p, e.child)
            e.child.id -> if (inStage >= day * rules.stageScale) {
                evolve(p, if (p.stageMistakes <= 3) e.teenGood else e.teenBad)
            }
            e.teenGood.id, e.teenBad.id -> if (inStage >= 3 * day * rules.stageScale) {
                val m = p.stageMistakes
                val next = if (p.form.id == e.teenGood.id) {
                    when {
                        m <= 2 && p.discipline >= 3 -> e.adultBest
                        m <= 5 -> e.adultOkay
                        else -> e.adultRough
                    }
                } else {
                    if (m <= 2 && p.discipline >= 3) e.adultOkay else e.adultRough
                }
                p.adultStartMinute = p.gameMinutes
                evolve(p, next)
            }
            e.adultBest.id -> if (inStage >= 2 * day * rules.stageScale &&
                p.stageMistakes == 0 && p.discipline >= 4
            ) {
                evolve(p, e.secret)
            }
        }
    }

    private fun checkOldAge(p: PetState, rules: EditionRules) {
        if (p.stage != Stage.ADULT && p.stage != Stage.SECRET) return
        val day = PetState.MINUTES_PER_DAY
        val adultAge = p.adultStartMinute - p.hatchMinute
        val lifespan = max(
            (p.form.lifespanDays * rules.lifespanScale * day).toLong() - p.adultMistakes * (day / 2L),
            adultAge + day,
        )
        if (p.gameMinutes - p.hatchMinute >= lifespan) end(p, Ending.OLD_AGE)
    }

    private fun evolve(p: PetState, to: Form) {
        p.formId = to.id
        p.stageStartMinute = p.gameMinutes
        p.stageMistakes = 0
        p.weight = max(p.weight, baseWeight(to.stage))
        p.evolvedAtMinute = p.gameMinutes
        discover(to)
    }

    private fun makeSick(p: PetState) {
        p.sick = true
        p.dosesNeeded = 1 + (roll(p) * 2).toInt().coerceAtMost(1)
        p.sickMinutes = 0
    }

    private fun end(p: PetState, ending: Ending) {
        p.ending = ending
        p.endedAtMinute = p.gameMinutes
        p.call = null
        save.history = (save.history + HistoryEntry(
            name = p.name,
            editionId = p.editionId,
            formId = p.formId,
            ageDays = p.ageDays,
            ending = ending,
            careMistakes = p.careMistakes,
        )).takeLast(MAX_HISTORY)
    }

    private fun discover(form: Form) {
        save.discovered = save.discovered + form.id
    }

    private fun roll(p: PetState): Double {
        val r = Random(p.rngState)
        val v = r.nextDouble()
        p.rngState = r.nextLong()
        return v
    }

    fun nextSeed(): Long {
        val p = save.pet ?: return Random.nextLong()
        val r = Random(p.rngState)
        p.rngState = r.nextLong()
        return r.nextLong()
    }

    // ------------------------------------------------------------------ actions

    private inline fun withAwakePet(needLight: Boolean = true, block: (PetState) -> ActionResult): ActionResult {
        val p = save.pet ?: return ActionResult.NO_PET
        if (!p.alive) return ActionResult.NO_PET
        if (p.stage == Stage.EGG) return ActionResult.STILL_EGG
        if (p.asleep) return ActionResult.ASLEEP
        if (needLight && !p.lightsOn) return ActionResult.DARK
        return block(p)
    }

    fun feedMeal(): ActionResult = withAwakePet { p ->
        when {
            p.call == CallKind.FAKE -> ActionResult.REFUSED
            p.hunger >= MAX_HEARTS -> ActionResult.FULL
            else -> {
                p.hunger++
                p.weight++
                if (p.call == CallKind.HUNGER) p.call = null
                ActionResult.ATE_MEAL
            }
        }
    }

    fun feedSnack(): ActionResult = withAwakePet { p ->
        if (p.call == CallKind.FAKE) return@withAwakePet ActionResult.REFUSED
        p.happy = min(MAX_HEARTS, p.happy + 1)
        p.weight += 2
        p.snackCount++
        p.snackDecayTimer = SNACK_DECAY_MINUTES
        if (p.call == CallKind.HAPPY) p.call = null
        if (p.snackCount >= p.edition.rules.snackLimit && !p.sick) {
            makeSick(p)
            p.snackCount = 0
        }
        ActionResult.ATE_SNACK
    }

    fun clean(): ActionResult = withAwakePet { p ->
        if (p.poops == 0) {
            ActionResult.NOTHING_TO_CLEAN
        } else {
            p.poops = 0
            p.oldestPoopMinute = -1
            ActionResult.CLEANED
        }
    }

    fun giveMedicine(): ActionResult = withAwakePet { p ->
        if (!p.sick) return@withAwakePet ActionResult.NOT_SICK
        p.dosesNeeded--
        if (p.dosesNeeded <= 0) {
            p.sick = false
            p.sickMinutes = 0
            if (p.call == CallKind.SICK) p.call = null
            ActionResult.CURED
        } else {
            ActionResult.DOSE_GIVEN
        }
    }

    fun toggleLights(): ActionResult {
        val p = save.pet ?: return ActionResult.NO_PET
        if (!p.alive) return ActionResult.NO_PET
        p.lightsOn = !p.lightsOn
        if (!p.lightsOn && p.call == CallKind.LIGHTS) p.call = null
        return if (p.lightsOn) ActionResult.LIGHTS_ON else ActionResult.LIGHTS_OFF
    }

    fun scold(): ActionResult = withAwakePet(needLight = false) { p ->
        if (p.call == CallKind.FAKE) {
            p.discipline = min(4, p.discipline + 1)
            p.call = null
            ActionResult.SCOLD_GOOD
        } else {
            p.happy = max(0, p.happy - 1)
            ActionResult.SCOLD_BAD
        }
    }

    /** Checks whether a game can start right now; returns OK or the reason it can't. */
    fun canPlay(): ActionResult = withAwakePet { p ->
        if (p.call == CallKind.FAKE) ActionResult.REFUSED else ActionResult.OK
    }

    fun startGame(kind: GameKind): MiniGame? =
        if (canPlay() == ActionResult.OK) MiniGame.create(kind, nextSeed()) else null

    fun finishGame(won: Boolean): ActionResult = withAwakePet { p ->
        p.weight = max(baseWeight(p.stage), p.weight - 1)
        if (won) {
            p.happy = min(MAX_HEARTS, p.happy + p.edition.rules.gameReward)
            if (p.call == CallKind.HAPPY) p.call = null
            ActionResult.GAME_WON
        } else {
            ActionResult.GAME_LOST
        }
    }

    /**
     * Game minutes until the pet next starts calling (0 if it is calling now), or
     * null if nothing happens within [limitMinutes]. The simulation is deterministic,
     * so this is exact as long as the owner does nothing in the meantime.
     */
    fun minutesUntilNextCall(limitMinutes: Int = 24 * 60): Int? {
        val now = save.pet ?: return null
        if (!now.alive || save.paused) return null
        if (now.call != null) return 0
        val copy = SaveData.fromJson(save.toJson())
        val p = copy.pet ?: return null
        val sim = PetGame(copy)
        for (minute in 1..limitMinutes) {
            sim.step(p)
            if (p.call != null || !p.alive) return minute
        }
        return null
    }

    /** True when the pet is calling and a notification is worth sending. */
    fun needsAttention(): Boolean = save.pet?.let { it.alive && it.call != null } ?: false

    companion object {
        const val MAX_HEARTS = 4
        const val MAX_POOPS = 4
        const val MAX_NAME = 10
        const val MAX_HISTORY = 50
        const val EGG_MINUTES = 5
        const val BABY_MINUTES = 60
        const val CALL_WINDOW_MINUTES = 15
        const val DIRTY_MINUTES = 6 * 60
        const val STARVE_MINUTES = 12 * 60
        const val ILLNESS_MINUTES = 12 * 60
        const val SNACK_DECAY_MINUTES = 120
        const val OVERWEIGHT_MARGIN = 20
        const val MILLIS_PER_MINUTE = 60_000L
        /** Catch-up is capped at 45 game days so a long absence can't freeze the app. */
        const val MAX_CATCH_UP_MINUTES = 45L * 24 * 60

        fun baseWeight(stage: Stage): Int = when (stage) {
            Stage.EGG -> 0
            Stage.BABY -> 5
            Stage.CHILD -> 10
            Stage.TEEN -> 20
            Stage.ADULT, Stage.SECRET -> 30
        }

        fun isBedtime(minuteOfDay: Int, rules: EditionRules): Boolean =
            if (rules.bedtime > rules.wakeTime) {
                minuteOfDay >= rules.bedtime || minuteOfDay < rules.wakeTime
            } else {
                minuteOfDay >= rules.bedtime && minuteOfDay < rules.wakeTime
            }
    }
}
