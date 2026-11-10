package com.mypet.core

/**
 * A tiny monochrome bitmap for the LCD. Sprites are written as text so they are
 * easy to read, diff and port to the HTML5 version later.
 *
 * Characters:
 *  - `#` lit pixel
 *  - `.` or space: unlit pixel
 *  - `E` upper eye pixel, `e` lower eye pixel. Both are lit when the eyes are open;
 *    when the pet blinks or sleeps only the lower one is lit, which reads as a closed eye.
 */
class PixelArt private constructor(
    val width: Int,
    val height: Int,
    private val cells: CharArray,
) {
    fun isLit(x: Int, y: Int, eyesClosed: Boolean = false): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        return when (cells[y * width + x]) {
            '#', 'e' -> true
            'E' -> !eyesClosed
            else -> false
        }
    }

    /** Mirror image, used when the pet walks the other way. */
    fun flipped(): PixelArt {
        val out = CharArray(cells.size)
        for (y in 0 until height) for (x in 0 until width) {
            out[y * width + x] = cells[y * width + (width - 1 - x)]
        }
        return PixelArt(width, height, out)
    }

    companion object {
        fun parse(vararg rows: String): PixelArt {
            require(rows.isNotEmpty()) { "Sprite needs at least one row" }
            val w = rows.maxOf { it.length }
            val cells = CharArray(w * rows.size) { '.' }
            rows.forEachIndexed { y, row ->
                row.forEachIndexed { x, c -> cells[y * w + x] = c }
            }
            return PixelArt(w, rows.size, cells)
        }
    }
}
