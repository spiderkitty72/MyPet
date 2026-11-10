package com.mypet.app

import com.mypet.core.GameKind
import com.mypet.core.SaveData

// Plain state types shared by the view model and the LCD renderer (no Android imports,
// so the renderer can be previewed on a plain JVM).

enum class Screen { START, DEVICE, COLLECTION }

enum class Menu { NONE, FEED, PLAY_PICK, PLAY, STATUS }

enum class Action { STATUS, FEED, PLAY, CLEAN, MEDICINE, LIGHT, SCOLD, PAUSE }

enum class AnimKind(val millis: Long) {
    EAT_MEAL(2400), EAT_SNACK(2400), REFUSE(1600), CLEAN(1400), MEDICINE(1800),
    SCOLD_GOOD(1600), SCOLD_BAD(1600), WIN(2200), LOSE(1800), EVOLVE(2600), HATCH(1600),
}

data class LcdAnim(val kind: AnimKind, val startedAt: Long)

enum class GamePhase { WAITING, REVEAL }

/** Read-only snapshot of a mini-game for the screen. */
data class GameUi(
    val kind: GameKind,
    val round: Int,
    val wins: Int,
    val phase: GamePhase,
    val lastCorrect: Boolean?,
    val petTurnedLeft: Boolean?,
    val shown: Int,
    val previous: Int,
    val catchSpeed: Float,
    val phaseStartedAt: Long,
)

data class UiState(
    val save: SaveData,
    val screen: Screen,
    val menu: Menu = Menu.NONE,
    val statusPage: Int = 0,
    val game: GameUi? = null,
    val anim: LcdAnim? = null,
    val message: String? = null,
    val messageAt: Long = 0,
) {
    val pet get() = save.pet
}
