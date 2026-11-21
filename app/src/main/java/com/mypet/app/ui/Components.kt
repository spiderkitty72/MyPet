package com.mypet.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mypet.app.UiState
import com.mypet.core.PixelArt
import kotlinx.coroutines.delay

fun argb(value: Long) = Color(value.toInt())

/** The pet's screen: a 48x32 dot-matrix LCD with faint unlit cells and soft pixel shadows. */
@Composable
fun LcdView(state: UiState, lcdColor: Color, inkColor: Color, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(50)
            now = System.currentTimeMillis()
        }
    }
    val buffer = remember { LcdBuffer() }
    Canvas(modifier.aspectRatio(LCD_W / LCD_H.toFloat())) {
        drawScene(buffer, state, frame = (now / 500).toInt(), nowMs = now)
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(lcdColor.copy(alpha = 1f), lerpColor(lcdColor, Color.Black, 0.08f))),
            cornerRadius = CornerRadius(size.minDimension * 0.04f),
        )
        val margin = size.width * 0.03f
        val cell = (size.width - margin * 2) / LCD_W
        val top = (size.height - cell * LCD_H) / 2
        val dot = cell * 0.86f
        val ghost = inkColor.copy(alpha = 0.07f)
        val shadow = inkColor.copy(alpha = 0.16f)
        val shadowOffset = cell * 0.18f
        for (y in 0 until LCD_H) for (x in 0 until LCD_W) {
            val origin = Offset(margin + x * cell, top + y * cell)
            if (buffer[x, y]) {
                drawRect(shadow, origin + Offset(shadowOffset, shadowOffset), Size(dot, dot))
            } else {
                drawRect(ghost, origin, Size(dot, dot))
            }
        }
        for (y in 0 until LCD_H) for (x in 0 until LCD_W) {
            if (buffer[x, y]) drawRect(inkColor, Offset(margin + x * cell, top + y * cell), Size(dot, dot))
        }
    }
}

fun lerpColor(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)

/** Draws a [PixelArt] icon in one colour, scaled to fit [size]. */
@Composable
fun PixelIcon(
    art: PixelArt,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    eyesClosed: Boolean = false,
) {
    Canvas(modifier.size(size)) {
        val cell = minOf(this.size.width / art.width, this.size.height / art.height)
        val ox = (this.size.width - cell * art.width) / 2
        val oy = (this.size.height - cell * art.height) / 2
        for (y in 0 until art.height) for (x in 0 until art.width) {
            if (art.isLit(x, y, eyesClosed)) {
                drawRect(color, Offset(ox + x * cell, oy + y * cell), Size(cell * 0.92f, cell * 0.92f))
            }
        }
    }
}

/** Original button icons for the care actions. */
object ActionIcons {
    val status = PixelArt.parse(
        "........#",
        "........#",
        ".....#..#",
        ".....#..#",
        "..#..#..#",
        "..#..#..#",
        "#########",
    )
    val feed = com.mypet.core.Icons.meal
    val play = PixelArt.parse(
        "..###..",
        ".#...#.",
        "#.#...#",
        "#..#..#",
        "#...#.#",
        ".#...#.",
        "..###..",
    )
    val clean = PixelArt.parse(
        "......#",
        ".....#.",
        "....#..",
        "..##...",
        ".####..",
        "#.#.#..",
        "#.#.#..",
    )
    val medicine = com.mypet.core.Icons.pill
    val light = PixelArt.parse(
        "..###..",
        ".##....",
        "##.....",
        "##.....",
        "##.....",
        ".##....",
        "..###..",
    )
    val scold = PixelArt.parse(
        "#######",
        "#..#..#",
        "#..#..#",
        "#.....#",
        "#..#..#",
        "#######",
        ".#.....",
    )
    val pause = PixelArt.parse(
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
    )
    val resume = PixelArt.parse(
        "#....",
        "###..",
        "#####",
        "###..",
        "#....",
    )
}
