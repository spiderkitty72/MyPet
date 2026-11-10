package com.mypet.core

import kotlin.random.Random

/** A five-round mini-game. Win three rounds to cheer the pet up. */
sealed class MiniGame(val kind: GameKind, seed: Long) {
    protected val rng = Random(seed)
    var round = 0
        protected set
    var wins = 0
        protected set
    /** Result of the most recent round, or null before the first one. */
    var lastCorrect: Boolean? = null
        protected set

    val finished: Boolean get() = round >= ROUNDS
    val won: Boolean get() = wins >= WINS_NEEDED

    protected fun record(correct: Boolean): Boolean {
        check(!finished) { "Game is over" }
        if (correct) wins++
        round++
        lastCorrect = correct
        return correct
    }

    /** Guess which way the pet will turn. */
    class WhichWay(seed: Long) : MiniGame(GameKind.WHICH_WAY, seed) {
        /** Direction the pet turned last round: true = left. */
        var petTurnedLeft: Boolean? = null
            private set

        fun guess(left: Boolean): Boolean {
            val turn = rng.nextBoolean()
            petTurnedLeft = turn
            return record(turn == left)
        }
    }

    /** Guess whether the next digit is higher or lower than the shown one. */
    class HiLo(seed: Long) : MiniGame(GameKind.HI_LO, seed) {
        var shown: Int = rng.nextInt(1, 10)
            private set
        var revealed: Int? = null
            private set

        fun guess(higher: Boolean): Boolean {
            var next: Int
            do next = rng.nextInt(1, 10) while (next == shown)
            revealed = next
            val correct = (next > shown) == higher
            shown = next
            return record(correct)
        }
    }

    /** A timing game; the app animates the star and reports whether the tap landed. */
    class Catch(seed: Long) : MiniGame(GameKind.CATCH, seed) {
        /** Speed multiplier for the current round, so each round feels a little different. */
        var speed: Float = 1f + rng.nextFloat() * 0.6f
            private set

        fun submit(hit: Boolean): Boolean {
            val r = record(hit)
            speed = 1f + rng.nextFloat() * 0.6f + round * 0.15f
            return r
        }
    }

    companion object {
        const val ROUNDS = 5
        const val WINS_NEEDED = 3

        fun create(kind: GameKind, seed: Long): MiniGame = when (kind) {
            GameKind.WHICH_WAY -> WhichWay(seed)
            GameKind.HI_LO -> HiLo(seed)
            GameKind.CATCH -> Catch(seed)
        }
    }
}
