package com.kira3.ametrine.data.memcard

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The first shape of a save's icon (a PS2 `.ico`, a small 3D model with a 128x128 texture),
 * enough to draw it from the front as a flat picture.
 *
 * [vertices] holds x, y, z, u, v per vertex and [colors] the vertex color as 0xRRGGBB, three
 * vertices to a triangle. [texture] is ARGB.
 */
class SaveIconModel(val vertices: FloatArray, val colors: IntArray, val texture: IntArray)

/** The name of the icon file a save lists for its list view, from its icon.sys; null if it has none. */
internal fun iconSysIconName(iconSys: ByteArray): String? {
    if (iconSys.size < ICON_NAME_OFFSET + 1 || String(iconSys, 0, 4, Charsets.ISO_8859_1) != "PS2D") return null
    var end = ICON_NAME_OFFSET
    while (end < minOf(iconSys.size, ICON_NAME_OFFSET + 64) && iconSys[end].toInt() != 0) end++
    return String(iconSys, ICON_NAME_OFFSET, end - ICON_NAME_OFFSET, Charsets.ISO_8859_1).ifEmpty { null }
}

private const val ICON_NAME_OFFSET = 0x104
private const val TEXTURE_SIZE = 128
private const val TEXTURE_COMPRESSED = 0x08

/** Reads an icon file; null if it is not one or is cut short. */
fun parseSaveIcon(ico: ByteArray): SaveIconModel? {
    return try {
        parse(ico)
    } catch (e: IndexOutOfBoundsException) {
        null
    }
}

private fun parse(ico: ByteArray): SaveIconModel? {
    val b = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN)
    if (b.getInt(0) != 0x10000) return null
    val shapes = b.getInt(4)
    val textureType = b.getInt(8)
    val count = b.getInt(16)
    if (shapes < 1 || count < 3 || count % 3 != 0 || count > 20_000) return null

    val vertices = FloatArray(count * 5)
    val colors = IntArray(count)
    var o = 20
    for (i in 0 until count) {
        // Coordinates of every shape (x, y, z and a padding value each); the first one is drawn.
        for (axis in 0 until 3) vertices[i * 5 + axis] = b.getShort(o + axis * 2) / 4096f
        o += shapes * 8
        o += 8 // normal
        vertices[i * 5 + 3] = b.getShort(o) / 4096f
        vertices[i * 5 + 4] = b.getShort(o + 2) / 4096f
        o += 4
        colors[i] = ((ico[o].toInt() and 0xFF) shl 16) or ((ico[o + 1].toInt() and 0xFF) shl 8) or (ico[o + 2].toInt() and 0xFF)
        o += 4
    }

    // The texture is the last thing in the file, after the animation data whose length varies.
    val pixels = IntArray(TEXTURE_SIZE * TEXTURE_SIZE)
    if (textureType and TEXTURE_COMPRESSED != 0) {
        // A byte count first, then run-length coded 16-bit pixels.
        var start = -1
        for (t in o until ico.size - 4) {
            if (b.getInt(t) == ico.size - t - 4) {
                start = t + 4
                break
            }
        }
        if (start < 0) return null
        var p = start
        var n = 0
        while (n < pixels.size && p + 2 <= ico.size) {
            val code = b.getShort(p).toInt() and 0xFFFF
            p += 2
            if (code and 0xFF00 == 0xFF00) {
                // A literal run: 0x10000 - code pixels follow.
                var run = 0x10000 - code
                while (run-- > 0 && n < pixels.size && p + 2 <= ico.size) {
                    pixels[n++] = argb(b.getShort(p).toInt() and 0xFFFF)
                    p += 2
                }
            } else {
                // A repeated pixel: [code] times the one that follows.
                if (p + 2 > ico.size) return null
                val color = argb(b.getShort(p).toInt() and 0xFFFF)
                p += 2
                for (i in 0 until code) if (n < pixels.size) pixels[n++] = color
            }
        }
        if (n < pixels.size) return null
    } else {
        val start = ico.size - pixels.size * 2
        if (start < o) return null
        for (i in pixels.indices) pixels[i] = argb(b.getShort(start + i * 2).toInt() and 0xFFFF)
    }
    return SaveIconModel(vertices, colors, pixels)
}

/** A 15-bit color (5 bits each of red, green and blue, red lowest) as opaque ARGB. */
private fun argb(c: Int): Int {
    fun channel(v: Int) = (v shl 3) or (v shr 2)
    return (0xFF shl 24) or (channel(c and 31) shl 16) or (channel((c shr 5) and 31) shl 8) or channel((c shr 10) and 31)
}

/**
 * Draws the icon from the front into a square bitmap of [size] pixels, the picture taking up
 * [fill] of its width or height. The rest is transparent, or with a [fill] under 1 (for an adaptive
 * icon, which is masked) the color at the picture's edge, so that it has no black around it.
 */
fun SaveIconModel.render(size: Int, fill: Float = 1f): Bitmap {
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (i in colors.indices) {
        minX = min(minX, vertices[i * 5])
        maxX = max(maxX, vertices[i * 5])
        minY = min(minY, vertices[i * 5 + 1])
        maxY = max(maxY, vertices[i * 5 + 1])
    }
    val extent = max(max(maxX - minX, maxY - minY), 0.001f)
    val scale = size * fill / extent
    val centerX = (minX + maxX) / 2
    val centerY = (minY + maxY) / 2
    // The model's y axis points down, like the screen's.
    fun sx(i: Int) = (vertices[i * 5] - centerX) * scale + size / 2f
    fun sy(i: Int) = (vertices[i * 5 + 1] - centerY) * scale + size / 2f

    val out = IntArray(size * size)
    val depth = FloatArray(size * size) { Float.MAX_VALUE }
    for (t in 0 until colors.size / 3) {
        val a = t * 3
        val b = a + 1
        val c = a + 2
        val x0 = sx(a); val y0 = sy(a)
        val x1 = sx(b); val y1 = sy(b)
        val x2 = sx(c); val y2 = sy(c)
        val den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if (kotlin.math.abs(den) < 1e-6f) continue
        val fromX = max(0, floor(min(x0, min(x1, x2))).toInt())
        val toX = min(size - 1, ceil(max(x0, max(x1, x2))).toInt())
        val fromY = max(0, floor(min(y0, min(y1, y2))).toInt())
        val toY = min(size - 1, ceil(max(y0, max(y1, y2))).toInt())
        for (y in fromY..toY) {
            for (x in fromX..toX) {
                val px = x + 0.5f
                val py = y + 0.5f
                val l0 = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) / den
                val l1 = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) / den
                val l2 = 1 - l0 - l1
                if (l0 < 0 || l1 < 0 || l2 < 0) continue
                val z = l0 * vertices[a * 5 + 2] + l1 * vertices[b * 5 + 2] + l2 * vertices[c * 5 + 2]
                if (z >= depth[y * size + x]) continue
                depth[y * size + x] = z
                val u = l0 * vertices[a * 5 + 3] + l1 * vertices[b * 5 + 3] + l2 * vertices[c * 5 + 3]
                val v = l0 * vertices[a * 5 + 4] + l1 * vertices[b * 5 + 4] + l2 * vertices[c * 5 + 4]
                val tex = sample(u, v)
                var rgb = 0xFF shl 24
                for (shift in intArrayOf(16, 8, 0)) {
                    val color = l0 * ((colors[a] shr shift) and 0xFF) + l1 * ((colors[b] shr shift) and 0xFF) +
                        l2 * ((colors[c] shr shift) and 0xFF)
                    // 128 leaves the texture as it is.
                    val value = (((tex shr shift) and 0xFF) * color / 128f).toInt().coerceIn(0, 255)
                    rgb = rgb or (value shl shift)
                }
                out[y * size + x] = rgb
            }
        }
    }
    if (fill < 1f) {
        val x = size / 2
        var y = 0
        while (y < size && out[y * size + x] == 0) y++
        if (y < size) {
            val edge = out[min(y + 3, size - 1) * size + x]
            for (i in out.indices) if (out[i] == 0) out[i] = edge
        }
    }
    return Bitmap.createBitmap(out, size, size, Bitmap.Config.ARGB_8888)
}

/** The texture at [u], [v] (0..1), blended between the four nearest pixels. */
private fun SaveIconModel.sample(u: Float, v: Float): Int {
    val fx = (u * TEXTURE_SIZE - 0.5f).coerceIn(0f, TEXTURE_SIZE - 1f)
    val fy = (v * TEXTURE_SIZE - 0.5f).coerceIn(0f, TEXTURE_SIZE - 1f)
    val x0 = fx.toInt()
    val y0 = fy.toInt()
    val x1 = min(x0 + 1, TEXTURE_SIZE - 1)
    val y1 = min(y0 + 1, TEXTURE_SIZE - 1)
    val tx = fx - x0
    val ty = fy - y0
    var result = 0xFF shl 24
    for (shift in intArrayOf(16, 8, 0)) {
        fun at(x: Int, y: Int) = (texture[y * TEXTURE_SIZE + x] shr shift) and 0xFF
        val top = at(x0, y0) * (1 - tx) + at(x1, y0) * tx
        val bottom = at(x0, y1) * (1 - tx) + at(x1, y1) * tx
        result = result or ((top * (1 - ty) + bottom * ty).toInt() shl shift)
    }
    return result
}

/**
 * The game serial a save's folder name belongs to, e.g. "BASLUS-20312" or "BISCPS-17001GAMEDATA"
 * is "SLUS-20312" or "SCPS-17001"; null if the name has no serial in it.
 */
fun serialOfSave(name: String): String? =
    SAVE_SERIAL.find(name)?.let { "${it.groupValues[1]}-${it.groupValues[2]}" }

private val SAVE_SERIAL = Regex("^B.([A-Z]{4})-(\\d{5})")
