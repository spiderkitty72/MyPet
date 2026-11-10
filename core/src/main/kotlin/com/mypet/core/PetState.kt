package com.mypet.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Why the pet is asking for attention. */
enum class CallKind { HUNGER, HAPPY, SICK, LIGHTS, FAKE }

/** How a pet's life ended. */
enum class Ending(val classic: String, val gentle: String) {
    OLD_AGE("Lived a long, full life.", "Grew up and headed home."),
    STARVED("Went hungry for too long.", "Wandered off to find food."),
    ILLNESS("Was sick for too long.", "Went home to get better."),
}

/**
 * Everything about the current pet. Times are in game minutes; one game minute is one
 * real minute at 1x speed.
 */
@Serializable
data class PetState(
    val editionId: String,
    var name: String,
    var formId: String,
    /** Game minutes simulated since the egg appeared. */
    var gameMinutes: Long = 0,
    /** The pet's wall clock in minutes; `clockMinute % 1440` is the time of day. */
    var clockMinute: Long = 0,
    var stageStartMinute: Long = 0,
    var hatchMinute: Long = -1,
    var adultStartMinute: Long = -1,

    var hunger: Int = 0,
    var happy: Int = 0,
    /** Discipline in quarters: 0..4 means 0%..100%. */
    var discipline: Int = 0,
    var weight: Int = 1,

    var hungerTimer: Int = 0,
    var happyTimer: Int = 0,
    var poopTimer: Int = 0,
    var poops: Int = 0,
    var oldestPoopMinute: Long = -1,

    var sick: Boolean = false,
    var dosesNeeded: Int = 0,
    var sickMinutes: Int = 0,
    var snackCount: Int = 0,
    var snackDecayTimer: Int = 0,
    var starvingMinutes: Int = 0,

    var asleep: Boolean = false,
    var lightsOn: Boolean = true,

    var call: CallKind? = null,
    var callStartMinute: Long = 0,
    /** Needs already counted as a care mistake; they will not call again until fixed. */
    var penalized: Set<CallKind> = emptySet(),

    var careMistakes: Int = 0,
    var stageMistakes: Int = 0,
    var adultMistakes: Int = 0,
    var disciplineMisses: Int = 0,

    var ending: Ending? = null,
    var endedAtMinute: Long = -1,
    /** Game minute of the last growth, so the UI can play an evolution flash. */
    var evolvedAtMinute: Long = -1,
    var rngState: Long = 1L,
) {
    val edition: Edition get() = Editions.byId(editionId)
    val form: Form get() = edition.form(formId)
    val stage: Stage get() = form.stage
    val alive: Boolean get() = ending == null

    /** Age in whole pet days since hatching. */
    val ageDays: Int get() = if (hatchMinute < 0) 0 else ((gameMinutes - hatchMinute) / MINUTES_PER_DAY).toInt()

    val minuteOfDay: Int get() = Math.floorMod(clockMinute, MINUTES_PER_DAY.toLong()).toInt()

    companion object {
        const val MINUTES_PER_DAY = 1440
    }
}

@Serializable
data class HistoryEntry(
    val name: String,
    val editionId: String,
    val formId: String,
    val ageDays: Int,
    val ending: Ending,
    val careMistakes: Int,
)

@Serializable
data class Settings(
    /** Game minutes per real minute: 1 (classic real time), 2 or 4. */
    var speed: Int = 1,
    var notifications: Boolean = true,
)

/** The whole save file. */
@Serializable
data class SaveData(
    var pet: PetState? = null,
    var lastRealMillis: Long = 0,
    var carryMillis: Long = 0,
    var paused: Boolean = false,
    var settings: Settings = Settings(),
    var discovered: Set<String> = emptySet(),
    var history: List<HistoryEntry> = emptyList(),
    val version: Int = 1,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun fromJson(text: String?): SaveData =
            if (text.isNullOrBlank()) SaveData() else runCatching { json.decodeFromString(serializer(), text) }
                .getOrElse { SaveData() }
    }
}
