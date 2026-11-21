package com.mypet.app.ui

import com.mypet.app.AnimKind
import com.mypet.app.GamePhase
import com.mypet.app.GameUi
import com.mypet.app.Menu
import com.mypet.app.UiState
import com.mypet.core.GameKind
import com.mypet.core.Icons
import com.mypet.core.PetState
import com.mypet.core.PixelArt
import com.mypet.core.PixelFont
import com.mypet.core.Stage
import kotlin.math.abs
import kotlin.math.sin

const val LCD_W = 48
const val LCD_H = 32
private const val GROUND = LCD_H - 1

/** A 1-bit framebuffer for the pet's screen. */
class LcdBuffer {
    val pixels = BooleanArray(LCD_W * LCD_H)

    fun clear(lit: Boolean = false) = pixels.fill(lit)

    operator fun get(x: Int, y: Int) = x in 0 until LCD_W && y in 0 until LCD_H && pixels[y * LCD_W + x]

    fun set(x: Int, y: Int, lit: Boolean = true) {
        if (x in 0 until LCD_W && y in 0 until LCD_H) pixels[y * LCD_W + x] = lit
    }

    fun draw(
        art: PixelArt,
        x: Int,
        y: Int,
        flip: Boolean = false,
        eyesClosed: Boolean = false,
        scale: Int = 1,
        erase: Boolean = false,
        skipColumns: Int = 0,
    ) {
        for (ay in 0 until art.height) for (ax in skipColumns until art.width) {
            val sx = if (flip) art.width - 1 - ax else ax
            if (!art.isLit(sx, ay, eyesClosed)) continue
            for (dy in 0 until scale) for (dx in 0 until scale) {
                set(x + ax * scale + dx, y + ay * scale + dy, !erase)
            }
        }
    }

    fun text(s: String, x: Int, y: Int, erase: Boolean = false, scale: Int = 1) {
        var cx = x
        for (c in s) {
            draw(PixelFont.glyph(c), cx, y, erase = erase, scale = scale)
            cx += (PixelFont.WIDTH + 1) * scale
        }
    }

    fun textCentered(s: String, y: Int, scale: Int = 1) =
        text(s, (LCD_W - PixelFont.measure(s) * scale) / 2, y, scale = scale)

    fun rect(x0: Int, y0: Int, x1: Int, y1: Int, fill: Boolean) {
        for (y in y0..y1) for (x in x0..x1) {
            if (fill || x == x0 || x == x1 || y == y0 || y == y1) set(x, y)
        }
    }
}

/** Horizontal position of the falling star in the Star Catch game. */
fun catchStarX(game: GameUi, nowMs: Long): Int {
    val range = LCD_W - Icons.star.width - 2
    val period = (1400 / game.catchSpeed).toLong().coerceAtLeast(400)
    val t = ((nowMs - game.phaseStartedAt) % (2 * period)).toFloat() / period
    val f = if (t < 1f) t else 2f - t
    return 1 + (f * range).toInt()
}

const val CATCH_BASKET_X = (LCD_W - 7) / 2

fun catchIsHit(starX: Int): Boolean =
    abs((starX + Icons.star.width / 2) - (CATCH_BASKET_X + Icons.basket.width / 2)) <= 2

/**
 * Draws one frame of the LCD. [frame] advances twice a second and drives the
 * walking and blinking; [nowMs] drives smoother one-off animations.
 */
fun drawScene(buf: LcdBuffer, state: UiState, frame: Int, nowMs: Long) {
    buf.clear()
    val pet = state.pet ?: return
    if (!pet.alive) return drawEnding(buf, pet, nowMs)

    val anim = state.anim?.takeIf { nowMs - it.startedAt < it.kind.millis }
    if (anim != null && (anim.kind == AnimKind.EVOLVE || anim.kind == AnimKind.HATCH)) {
        return drawEvolve(buf, pet, anim.kind, nowMs - anim.startedAt, frame)
    }
    if (pet.stage == Stage.EGG) return drawEgg(buf, pet, frame)

    if (!pet.lightsOn) {
        buf.clear(lit = true)
        if (pet.asleep) {
            val bob = frame % 2
            buf.draw(Icons.zzz, 34, 4 + bob, erase = true)
        }
        return
    }

    val game = state.game
    if (state.menu == Menu.PLAY && game != null) return drawGame(buf, pet, game, frame, nowMs)
    when (state.menu) {
        Menu.STATUS -> return drawStatus(buf, pet, state.statusPage)
        Menu.FEED -> if (anim == null) return drawFeedMenu(buf)
        Menu.PLAY_PICK -> return buf.textCentered("GAMES", 13)
        else -> Unit
    }
    if (anim != null) return drawAnim(buf, pet, anim.kind, nowMs - anim.startedAt, frame)
    drawIdle(buf, pet, frame)
}

private fun petSprite(pet: PetState) = pet.form.sprite

private fun groundY(art: PixelArt) = GROUND - art.height + 1

private fun drawIdle(buf: LcdBuffer, pet: PetState, frame: Int) {
    val art = petSprite(pet)
    val poopArea = if (pet.poops > 0) 12 else 0
    val minX = 1
    val maxX = (LCD_W - poopArea - art.width - 1).coerceAtLeast(minX)

    // Droppings, stacked on the right, with wavy stink lines.
    for (i in 0 until pet.poops) {
        val py = GROUND - Icons.poop.height + 1 - i * (Icons.poop.height + 1)
        buf.draw(Icons.poop, LCD_W - Icons.poop.width - 1, py)
        if ((frame + i) % 2 == 0) buf.draw(Icons.stink, LCD_W - Icons.poop.width - 6, py - 1)
    }

    if (pet.asleep) {
        val x = (minX + maxX) / 2
        buf.draw(art, x, groundY(art), eyesClosed = true)
        buf.draw(Icons.zzz, (x + art.width + 1).coerceAtMost(LCD_W - 9), 2 + frame % 2)
        return
    }

    val range = maxX - minX
    val x: Int
    var flip = false
    var hop = 0
    if (pet.sick || range <= 0) {
        x = (minX + maxX) / 2
    } else {
        // Pace back and forth, pausing briefly at each end.
        val period = range + 3
        val t = (frame * 2) % (2 * period)
        val leg = t / period
        val pos = (t % period).coerceAtMost(range)
        x = if (leg == 0) minX + pos else maxX - pos
        flip = leg == 1
        hop = if (frame % 2 == 0 && pos in 1 until range) 1 else 0
    }
    val blink = frame % 9 == 0 || pet.sick && frame % 3 == 0
    buf.draw(art, x, groundY(art) - hop, flip = flip, eyesClosed = blink)

    if (pet.sick) buf.draw(Icons.sick, (x + art.width / 2 - 4).coerceIn(0, LCD_W - 8), (groundY(art) - 8).coerceAtLeast(0))
    if (pet.call != null && frame % 2 == 0) buf.draw(Icons.bang, 1, 1)
}

private fun drawEgg(buf: LcdBuffer, pet: PetState, frame: Int) {
    val art = petSprite(pet)
    val wobble = when (frame % 4) { 1 -> -1; 3 -> 1; else -> 0 }
    buf.draw(art, (LCD_W - art.width) / 2 + wobble, groundY(art))
}

private fun drawEvolve(buf: LcdBuffer, pet: PetState, kind: AnimKind, elapsed: Long, frame: Int) {
    val art = petSprite(pet)
    val x = (LCD_W - art.width) / 2
    if (elapsed < 1200) {
        // Flash between the old egg/shape silhouette and an inverted screen.
        val on = (elapsed / 150) % 2 == 0L
        if (kind == AnimKind.HATCH) {
            val egg = pet.edition.egg.sprite
            buf.draw(egg, (LCD_W - egg.width) / 2 + if (on) 1 else -1, groundY(egg))
        } else {
            buf.clear(lit = on)
            buf.draw(art, x, groundY(art), erase = on)
        }
        return
    }
    buf.draw(art, x, groundY(art), eyesClosed = frame % 2 == 0)
    val s = Icons.sparkle
    if (frame % 2 == 0) {
        buf.draw(s, x - 7, 8)
        buf.draw(s, x + art.width + 2, 12)
    } else {
        buf.draw(s, x - 6, 14)
        buf.draw(s, x + art.width + 1, 6)
    }
}

private fun drawStatus(buf: LcdBuffer, pet: PetState, page: Int) {
    fun hearts(n: Int) {
        val x0 = (LCD_W - (4 * 7 + 3 * 2)) / 2
        for (i in 0 until 4) buf.draw(if (i < n) Icons.heartFull else Icons.heartEmpty, x0 + i * 9, 17)
    }
    when (page) {
        0 -> { buf.textCentered("HUNGER", 5); hearts(pet.hunger) }
        1 -> { buf.textCentered("HAPPY", 5); hearts(pet.happy) }
        2 -> {
            buf.textCentered("TRAINING", 5)
            buf.rect(4, 16, 43, 23, fill = false)
            val filled = pet.discipline * 9
            if (filled > 0) buf.rect(6, 18, 5 + filled, 21, fill = true)
        }
        3 -> {
            buf.textCentered("AGE ${pet.ageDays}", 5)
            buf.textCentered("WT ${pet.weight}G", 18)
        }
        else -> {
            buf.textCentered(pet.name.take(11), 5)
            buf.textCentered(pet.form.name.take(11), 18)
        }
    }
}

private fun drawFeedMenu(buf: LcdBuffer) {
    buf.draw(Icons.meal, 4, 5, scale = 2)
    buf.draw(Icons.snack, 30, 3, scale = 2)
    buf.text("MEAL", 4, 24)
    buf.text("SNACK", 26, 24)
}

private fun drawAnim(buf: LcdBuffer, pet: PetState, kind: AnimKind, elapsed: Long, frame: Int) {
    val art = petSprite(pet)
    val center = (LCD_W - art.width) / 2
    val gy = groundY(art)
    when (kind) {
        AnimKind.EAT_MEAL, AnimKind.EAT_SNACK -> {
            val food = if (kind == AnimKind.EAT_MEAL) Icons.meal else Icons.snack
            val bite = (elapsed / 600).toInt().coerceAtMost(3)
            val chew = (elapsed / 200) % 2 == 0L
            buf.draw(art, 4, gy, eyesClosed = chew && bite > 0)
            if (bite < 3) {
                val fx = 4 + art.width + 3
                buf.draw(food, fx, GROUND - food.height + 1, skipColumns = bite * food.width / 3)
            } else {
                buf.draw(Icons.note, 4 + art.width + 4, 6 + frame % 2)
            }
        }
        AnimKind.REFUSE -> {
            val flip = (elapsed / 200) % 2 == 0L
            buf.draw(art, center + if (flip) -1 else 1, gy, flip = flip)
            buf.textCentered("NO!", 2)
        }
        AnimKind.CLEAN -> {
            drawIdle(buf, pet, frame)
            val lineX = LCD_W - 1 - (elapsed * LCD_W / kind.millis).toInt()
            for (x in lineX until LCD_W) for (y in 0 until LCD_H) buf.set(x, y, false)
            for (y in 0 until LCD_H) buf.set(lineX, y, true)
            if (lineX + 2 < LCD_W) buf.draw(Icons.sparkle, (lineX + 3).coerceAtMost(LCD_W - 5), 4 + frame % 2 * 10)
        }
        AnimKind.MEDICINE -> {
            buf.draw(art, center, gy, eyesClosed = true)
            val travel = (elapsed / 60).toInt().coerceAtMost(14)
            if (elapsed < 900) {
                buf.draw(Icons.pill, LCD_W - 8 - travel, 4 + travel / 2)
            } else {
                val on = (elapsed / 200) % 2 == 0L
                buf.draw(Icons.sparkle, center - 6, if (on) 6 else 10)
                buf.draw(Icons.sparkle, center + art.width + 1, if (on) 10 else 6)
            }
        }
        AnimKind.SCOLD_GOOD, AnimKind.SCOLD_BAD -> {
            val shake = if (kind == AnimKind.SCOLD_BAD && (elapsed / 150) % 2 == 0L) 1 else 0
            buf.draw(art, center + shake, gy, eyesClosed = true)
            buf.draw(Icons.angry, center + art.width + 1, (gy - 6).coerceAtLeast(0))
            if (kind == AnimKind.SCOLD_BAD) buf.draw(Icons.heartEmpty, center - 9, gy) else buf.draw(Icons.bang, center - 4, gy)
        }
        AnimKind.WIN -> {
            val jump = (abs(sin(elapsed / 160.0)) * 5).toInt()
            buf.draw(art, center, gy - jump)
            buf.draw(Icons.note, 2, 6 + frame % 2)
            buf.draw(Icons.note, LCD_W - 7, 8 - frame % 2)
            buf.textCentered("WIN!", 1)
        }
        AnimKind.LOSE -> {
            buf.draw(art, center, gy, eyesClosed = true)
            buf.textCentered("LOSE", 1)
        }
        AnimKind.EVOLVE, AnimKind.HATCH -> drawEvolve(buf, pet, kind, elapsed, frame)
    }
}

private fun drawGame(buf: LcdBuffer, pet: PetState, game: GameUi, frame: Int, nowMs: Long) {
    val round = (game.round + if (game.phase == GamePhase.WAITING) 1 else 0).coerceAtMost(5)
    buf.text("$round/5", 1, 1)
    buf.text("${game.wins}", LCD_W - 4, 1)
    buf.draw(Icons.heartFull, LCD_W - 12, 0)
    val result = if (game.phase == GamePhase.REVEAL) (if (game.lastCorrect == true) "YES!" else "NO") else null

    when (game.kind) {
        GameKind.WHICH_WAY -> {
            val art = petSprite(pet)
            val center = (LCD_W - art.width) / 2
            if (game.phase == GamePhase.WAITING) {
                buf.draw(art, center, groundY(art), eyesClosed = frame % 6 == 0)
                if (frame % 2 == 0) {
                    buf.draw(Icons.arrowLeft, 1, 20)
                    buf.draw(Icons.arrowRight, LCD_W - 6, 20)
                }
            } else {
                val left = game.petTurnedLeft == true
                buf.draw(art, center + if (left) -6 else 6, groundY(art), flip = left)
                buf.textCentered(result!!, 8)
            }
        }
        GameKind.HI_LO -> {
            if (game.phase == GamePhase.WAITING) {
                buf.textCentered("${game.shown}", 9, scale = 3)
                if (frame % 2 == 0) {
                    buf.text("HI", 1, 14)
                    buf.text("LO", LCD_W - 8, 14)
                }
            } else {
                buf.text("${game.previous}", 4, 16)
                buf.draw(Icons.arrowRight, 10, 16)
                buf.textCentered("${game.shown}", 12, scale = 3)
                buf.text(result!!, LCD_W - PixelFont.measure(result) - 1, 16)
            }
        }
        GameKind.CATCH -> {
            buf.draw(Icons.basket, CATCH_BASKET_X, GROUND - Icons.basket.height + 1)
            if (game.phase == GamePhase.WAITING) {
                buf.draw(Icons.star, catchStarX(game, nowMs), 8)
            } else {
                val hit = game.lastCorrect == true
                val sx = if (hit) CATCH_BASKET_X else CATCH_BASKET_X + if (frame % 2 == 0) 12 else -12
                val fall = ((nowMs - game.phaseStartedAt) / 40).toInt().coerceAtMost(12)
                buf.draw(Icons.star, sx, (8 + fall).coerceAtMost(GROUND - 9))
                buf.textCentered(result!!, 1)
            }
        }
    }
}

private fun drawEnding(buf: LcdBuffer, pet: PetState, nowMs: Long) {
    if (pet.edition.rules.gentleEndings) {
        // The pet strolls off with a little bundle, over and over.
        val art = petSprite(pet)
        val span = LCD_W + art.width + 10
        val x = ((nowMs / 120) % span).toInt() - art.width - 8
        buf.draw(Icons.bundle, x, GROUND - Icons.bundle.height + 1)
        buf.draw(art, x + 8, groundY(art), eyesClosed = (nowMs / 500) % 6 == 0L)
    } else {
        val bob = (sin(nowMs / 400.0) * 3).toInt()
        val x = (LCD_W - Icons.spirit.width) / 2
        buf.draw(Icons.halo, x, 6 + bob)
        buf.draw(Icons.spirit, x, 10 + bob)
        buf.draw(Icons.sparkle, x - 10, 14 - bob)
        buf.draw(Icons.sparkle, x + 14, 18 + bob)
    }
}
