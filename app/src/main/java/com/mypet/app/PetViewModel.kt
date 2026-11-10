package com.mypet.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mypet.core.ActionResult
import com.mypet.core.CallKind
import com.mypet.core.Ending
import com.mypet.core.GameKind
import com.mypet.core.MiniGame
import com.mypet.core.PetGame
import com.mypet.core.SaveData
import com.mypet.core.Stage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime

class PetViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PetStore(app)
    private val game = PetGame(store.load())
    private var miniGame: MiniGame? = null
    private var revealJob: Job? = null
    private var lastEvolvedSeen: Long = game.pet?.evolvedAtMinute ?: -1

    private val _state = MutableStateFlow(
        UiState(
            save = snapshot(),
            screen = if (game.pet == null) Screen.START else Screen.DEVICE,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        game.advanceTo(now())
        publish()
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                tick()
            }
        }
    }

    private fun now() = System.currentTimeMillis()

    private fun snapshot(): SaveData = game.save.copy(
        pet = game.save.pet?.copy(),
        settings = game.save.settings.copy(),
    )

    private fun publish(transform: (UiState) -> UiState = { it }) {
        val pet = game.pet
        var anim = _state.value.anim
        if (pet != null && pet.evolvedAtMinute != lastEvolvedSeen) {
            lastEvolvedSeen = pet.evolvedAtMinute
            anim = LcdAnim(if (pet.stage == Stage.BABY) AnimKind.HATCH else AnimKind.EVOLVE, now())
        }
        _state.value = transform(_state.value.copy(save = snapshot(), anim = anim))
    }

    fun tick() {
        val wasAlive = game.pet?.alive == true
        val minutes = game.advanceTo(now())
        if (minutes > 0) {
            if (wasAlive && game.pet?.alive == false) {
                miniGame = null
                publish { it.copy(menu = Menu.NONE, game = null) }
            } else {
                publish()
            }
            if (minutes > 30) persist()
        }
    }

    fun persist() {
        store.save(game.save)
    }

    fun onBackground() {
        game.advanceTo(now())
        persist()
        PetAlarms.schedule(getApplication<Application>(), game.save)
    }

    fun onForeground() {
        val fresh = store.load()
        // The alarm receiver may have advanced the save while we were away; keep whichever is newer.
        if (fresh.lastRealMillis > game.save.lastRealMillis) {
            game.save.pet = fresh.pet
            game.save.lastRealMillis = fresh.lastRealMillis
            game.save.carryMillis = fresh.carryMillis
            game.save.history = fresh.history
            game.save.discovered = fresh.discovered
        }
        tick()
        publish()
    }

    // ------------------------------------------------------------- navigation

    fun show(screen: Screen) = publish { it.copy(screen = screen, menu = Menu.NONE) }

    fun startNewPet(editionId: String, name: String) {
        val time = LocalTime.now()
        game.newEgg(editionId, name, now(), time.hour * 60 + time.minute)
        lastEvolvedSeen = game.pet?.evolvedAtMinute ?: -1
        miniGame = null
        persist()
        publish { it.copy(screen = Screen.DEVICE, menu = Menu.NONE, game = null, anim = null, message = null) }
    }

    // ------------------------------------------------------------- care actions

    fun onAction(action: Action) {
        val pet = game.pet ?: return
        if (!pet.alive) return
        if (_state.value.menu == Menu.PLAY) return
        when (action) {
            Action.STATUS -> {
                if (pet.stage == Stage.EGG) return say(result(ActionResult.STILL_EGG))
                publish { it.copy(menu = if (it.menu == Menu.STATUS) Menu.NONE else Menu.STATUS, statusPage = 0) }
            }
            Action.FEED -> precheck(game.canPlay().let { if (it == ActionResult.REFUSED) ActionResult.OK else it }) {
                publish { it.copy(menu = if (it.menu == Menu.FEED) Menu.NONE else Menu.FEED) }
            }
            Action.PLAY -> precheck(game.canPlay()) {
                val games = pet.edition.rules.games
                if (games.size == 1) startGame(games.first())
                else publish { it.copy(menu = Menu.PLAY_PICK) }
            }
            Action.CLEAN -> applyResult(game.clean(), AnimKind.CLEAN)
            Action.MEDICINE -> applyResult(game.giveMedicine(), AnimKind.MEDICINE)
            Action.LIGHT -> applyResult(game.toggleLights(), null)
            Action.SCOLD -> {
                val r = game.scold()
                applyResult(r, if (r == ActionResult.SCOLD_GOOD) AnimKind.SCOLD_GOOD else AnimKind.SCOLD_BAD)
            }
            Action.PAUSE -> togglePause()
        }
    }

    private inline fun precheck(r: ActionResult, ok: () -> Unit) {
        if (r == ActionResult.OK) ok() else applyResult(r, if (r == ActionResult.REFUSED) AnimKind.REFUSE else null)
    }

    fun feed(meal: Boolean) {
        val r = if (meal) game.feedMeal() else game.feedSnack()
        val anim = when (r) {
            ActionResult.ATE_MEAL -> AnimKind.EAT_MEAL
            ActionResult.ATE_SNACK -> AnimKind.EAT_SNACK
            ActionResult.REFUSED, ActionResult.FULL -> AnimKind.REFUSE
            else -> null
        }
        applyResult(r, anim, closeMenu = r != ActionResult.ATE_MEAL && r != ActionResult.ATE_SNACK && r != ActionResult.FULL)
    }

    fun closeMenu() = publish { it.copy(menu = Menu.NONE) }

    fun nextStatusPage() = publish { it.copy(statusPage = (it.statusPage + 1) % STATUS_PAGES) }

    private fun applyResult(r: ActionResult, anim: AnimKind?, closeMenu: Boolean = true) {
        val text = result(r)
        val shownAnim = when (r) {
            ActionResult.NOTHING_TO_CLEAN, ActionResult.NOT_SICK, ActionResult.ASLEEP,
            ActionResult.DARK, ActionResult.STILL_EGG, ActionResult.NO_PET -> null
            else -> anim
        }
        persist()
        publish {
            it.copy(
                menu = if (closeMenu) Menu.NONE else it.menu,
                anim = shownAnim?.let { k -> LcdAnim(k, now()) } ?: it.anim,
                message = text,
                messageAt = now(),
            )
        }
    }

    private fun say(text: String?) = publish { it.copy(message = text, messageAt = now()) }

    private fun result(r: ActionResult): String? {
        val name = game.pet?.name ?: "Your pet"
        return when (r) {
            ActionResult.ATE_MEAL -> "Yum! A proper meal."
            ActionResult.ATE_SNACK -> "A treat! So happy."
            ActionResult.FULL -> "$name is full."
            ActionResult.REFUSED -> "$name refuses! Is it testing you?"
            ActionResult.CLEANED -> "All clean."
            ActionResult.NOTHING_TO_CLEAN -> "Already spotless."
            ActionResult.DOSE_GIVEN -> "One more dose needed."
            ActionResult.CURED -> "$name feels better!"
            ActionResult.NOT_SICK -> "$name isn't sick."
            ActionResult.LIGHTS_ON -> "Lights on."
            ActionResult.LIGHTS_OFF -> "Lights off."
            ActionResult.SCOLD_GOOD -> "$name learned a lesson."
            ActionResult.SCOLD_BAD -> "That wasn't fair... $name is sad."
            ActionResult.GAME_WON -> "You won! $name is delighted."
            ActionResult.GAME_LOST -> "You lost. Still, that was fun."
            ActionResult.ASLEEP -> "Shh, $name is asleep."
            ActionResult.DARK -> "It's too dark. Turn the light on."
            ActionResult.STILL_EGG -> "The egg is still warming up."
            ActionResult.NO_PET, ActionResult.OK -> null
        }
    }

    // ------------------------------------------------------------- mini-games

    fun startGame(kind: GameKind) {
        val g = game.startGame(kind) ?: return applyResult(game.canPlay(), AnimKind.REFUSE)
        miniGame = g
        publish { it.copy(menu = Menu.PLAY, game = gameUi(g, GamePhase.WAITING, -1)) }
    }

    /** [choice]: 0 = left/lower/miss, 1 = right/higher/hit. */
    fun gameInput(choice: Int) {
        val g = miniGame ?: return
        val current = _state.value.game ?: return
        if (current.phase != GamePhase.WAITING || g.finished) return
        val before = (g as? MiniGame.HiLo)?.shown ?: -1
        when (g) {
            is MiniGame.WhichWay -> g.guess(left = choice == 0)
            is MiniGame.HiLo -> g.guess(higher = choice == 1)
            is MiniGame.Catch -> g.submit(hit = choice == 1)
        }
        publish { it.copy(game = gameUi(g, GamePhase.REVEAL, before)) }
        revealJob?.cancel()
        revealJob = viewModelScope.launch {
            delay(REVEAL_MILLIS)
            if (g.finished) {
                miniGame = null
                val r = game.finishGame(g.won)
                persist()
                publish {
                    it.copy(
                        menu = Menu.NONE,
                        game = null,
                        anim = LcdAnim(if (g.won) AnimKind.WIN else AnimKind.LOSE, now()),
                        message = result(r),
                        messageAt = now(),
                    )
                }
            } else {
                publish { it.copy(game = gameUi(g, GamePhase.WAITING, before)) }
            }
        }
    }

    fun quitGame() {
        revealJob?.cancel()
        miniGame = null
        publish { it.copy(menu = Menu.NONE, game = null) }
    }

    private fun gameUi(g: MiniGame, phase: GamePhase, previous: Int) = GameUi(
        kind = g.kind,
        round = g.round,
        wins = g.wins,
        phase = phase,
        lastCorrect = g.lastCorrect,
        petTurnedLeft = (g as? MiniGame.WhichWay)?.petTurnedLeft,
        shown = (g as? MiniGame.HiLo)?.shown ?: 0,
        previous = previous,
        catchSpeed = (g as? MiniGame.Catch)?.speed ?: 1f,
        phaseStartedAt = now(),
    )

    // ------------------------------------------------------------- settings

    fun togglePause() {
        val paused = !game.save.paused
        game.setPaused(paused, now())
        persist()
        publish {
            it.copy(message = if (paused) "Paused. Time stands still." else "Welcome back!", messageAt = now())
        }
    }

    fun setSpeed(speed: Int) {
        game.setSpeed(speed, now())
        persist()
        publish()
    }

    fun setNotifications(on: Boolean) {
        game.save.settings.notifications = on
        persist()
        publish()
    }

    /** Tester tool: jump ahead one game hour. */
    fun skipHour() {
        game.skipMinutes(60)
        persist()
        publish()
    }

    /** The current call text for the caption line, if the pet is calling. */
    fun callText(state: UiState): String? {
        val pet = state.pet ?: return null
        if (!pet.alive) return null
        return when (pet.call) {
            CallKind.HUNGER -> "${pet.name} is hungry!"
            CallKind.HAPPY -> "${pet.name} wants attention!"
            CallKind.SICK -> "${pet.name} feels sick!"
            CallKind.LIGHTS -> "${pet.name} is sleepy. Lights off?"
            CallKind.FAKE -> "${pet.name} is calling..."
            null -> null
        }
    }

    fun endingText(state: UiState): String? {
        val pet = state.pet ?: return null
        val ending: Ending = pet.ending ?: return null
        return if (pet.edition.rules.gentleEndings) ending.gentle else ending.classic
    }

    override fun onCleared() {
        persist()
    }

    companion object {
        const val STATUS_PAGES = 5
        const val REVEAL_MILLIS = 1300L
    }
}
