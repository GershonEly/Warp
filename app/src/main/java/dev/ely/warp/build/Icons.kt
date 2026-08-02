package dev.ely.warp.build

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import java.io.File
import kotlin.math.min

/**
 * Every icon file Android wants, from one square image.
 *
 * §8 lists the sizes; this is the half of it that needs no network. Generation —
 * *"make the icon a blue weather cloud"* — replaces only the **source image**.
 * Everything downstream is here, so when an image model arrives it plugs into a
 * pipeline that already works.
 *
 * It matters now for a plainer reason: the scaffold declared no icon at all, so
 * every app Warp built installed with Android's blank default. You made it; it
 * should not look like nobody did.
 *
 * **Adaptive icons are a solid colour plus artwork**, not two bitmaps. A
 * background PNG at five densities is five files that are all the same colour,
 * and a colour resource is one line that scales perfectly.
 */
object Icons {

    /**
     * Density buckets, as multiples of mdpi.
     *
     * The launcher icon is 48 dp and the adaptive canvas is 108 dp, so both come
     * from the same numbers — which is the whole reason these live in one place
     * rather than as two lists that can drift apart.
     */
    private val DENSITIES = listOf(
        "mdpi" to 1f,
        "hdpi" to 1.5f,
        "xhdpi" to 2f,
        "xxhdpi" to 3f,
        "xxxhdpi" to 4f,
    )

    private const val LEGACY_DP = 48
    private const val ADAPTIVE_DP = 108

    /**
     * The safe zone, as a fraction of the adaptive canvas.
     *
     * Android masks the outer 18 dp of 108 and may move what is left for
     * parallax, so anything outside the middle 72 dp can be cropped on somebody
     * else's launcher. Artwork is drawn into that centre and nowhere else.
     */
    private const val SAFE = 72f / ADAPTIVE_DP

    /**
     * A face for an app that has not been given one.
     *
     * The initial on a flat colour — the same thing the shelf draws today, but
     * as a real file rather than a tile that only exists inside Warp. §9h asks
     * for the icon to arrive on the first message rather than after the first
     * build, so a project has a face, and therefore a colour, from the start.
     */
    fun drawDefault(name: String, background: Int): Bitmap {
        val size = 1024
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)

        val letter = name.trim().firstOrNull()?.uppercase() ?: "?"
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.46f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        // Centred on the glyph's own bounds rather than on the font's baseline:
        // a capital letter sits noticeably high if you only halve the text size,
        // and on an icon that reads as a mistake rather than as a style.
        val bounds = Rect()
        paint.getTextBounds(letter, 0, letter.length, bounds)
        canvas.drawText(letter, size / 2f, size / 2f + bounds.height() / 2f, paint)

        return bitmap
    }

    /**
     * Make sure a project has icons, without overwriting one it already has.
     *
     * Called before every build, so the apps made before any of this existed
     * gain a face the next time they are compiled rather than staying blank for
     * ever. Cheap when there is nothing to do: one file check.
     *
     * @return true when something was written.
     */
    fun ensure(project: File, name: String, colour: Int): Boolean {
        if (File(project, "res/mipmap-anydpi-v26/ic_launcher.xml").isFile) return false
        return runCatching { write(project, drawDefault(name, colour), colour) }.isSuccess
    }

    /**
     * Write the whole set into a project.
     *
     * @param artwork a square image. Anything not square is centre-cropped
     *   rather than squashed — a stretched icon is worse than a cropped one.
     * @return the files written, for the card that reports it.
     */
    fun write(project: File, artwork: Bitmap, background: Int): List<String> {
        val square = artwork.toSquare()
        val written = mutableListOf<String>()

        DENSITIES.forEach { (bucket, scale) ->
            val legacy = File(project, "res/mipmap-$bucket").apply { mkdirs() }

            val px = (LEGACY_DP * scale).toInt()
            square.scaled(px).writePng(File(legacy, "ic_launcher.png"))
            square.rounded(px).writePng(File(legacy, "ic_launcher_round.png"))

            // The adaptive layer is a bigger canvas with the artwork inside the
            // safe zone, transparent everywhere else — the launcher supplies the
            // background and the mask.
            val canvasPx = (ADAPTIVE_DP * scale).toInt()
            square.onAdaptiveCanvas(canvasPx).writePng(File(legacy, "ic_launcher_foreground.png"))

            written += "res/mipmap-$bucket/ (3 files)"
        }

        File(project, "res/mipmap-anydpi-v26").apply { mkdirs() }
        File(project, "res/mipmap-anydpi-v26/ic_launcher.xml").writeText(ADAPTIVE_XML)
        File(project, "res/mipmap-anydpi-v26/ic_launcher_round.xml").writeText(ADAPTIVE_XML)
        written += "res/mipmap-anydpi-v26/ (2 files)"

        File(project, "res/values").apply { mkdirs() }
        File(project, "res/values/ic_launcher_background.xml").writeText(
            backgroundColour(background)
        )
        written += "res/values/ic_launcher_background.xml"

        return written
    }

    /**
     * The colour an icon is mostly made of.
     *
     * Averaged over a small sample rather than every pixel: a 1024 square is a
     * million reads for a number that decides a background tint, and the answer
     * is the same either way.
     *
     * Fully transparent pixels are skipped, or an icon that is mostly empty
     * canvas averages towards nothing and every app ends up the same grey.
     */
    fun dominantColour(bitmap: Bitmap): Int {
        val step = maxOf(1, min(bitmap.width, bitmap.height) / 32)
        var r = 0L; var g = 0L; var b = 0L; var n = 0L

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) > 32) {
                    r += Color.red(pixel); g += Color.green(pixel); b += Color.blue(pixel); n++
                }
                x += step
            }
            y += step
        }
        if (n == 0L) return Color.rgb(90, 100, 120)
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    // ── the shapes ───────────────────────────────────────────────────────

    private fun Bitmap.toSquare(): Bitmap {
        if (width == height) return this
        val side = min(width, height)
        return Bitmap.createBitmap(this, (width - side) / 2, (height - side) / 2, side, side)
    }

    private fun Bitmap.scaled(px: Int): Bitmap = Bitmap.createScaledBitmap(this, px, px, true)

    /** The same artwork, masked to a circle, for launchers that ask for one. */
    private fun Bitmap.rounded(px: Int): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val shader = android.graphics.BitmapShader(
            scaled(px),
            android.graphics.Shader.TileMode.CLAMP,
            android.graphics.Shader.TileMode.CLAMP,
        )
        paint.shader = shader
        canvas.drawCircle(px / 2f, px / 2f, px / 2f, paint)
        return out
    }

    /** Artwork inside the safe zone of a transparent 108 dp canvas. */
    private fun Bitmap.onAdaptiveCanvas(canvasPx: Int): Bitmap {
        val out = Bitmap.createBitmap(canvasPx, canvasPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val inner = (canvasPx * SAFE).toInt()
        val offset = (canvasPx - inner) / 2
        canvas.drawBitmap(
            scaled(inner),
            offset.toFloat(),
            offset.toFloat(),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return out
    }

    private fun Bitmap.writePng(file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val ADAPTIVE_XML = """
        <?xml version="1.0" encoding="utf-8"?>
        <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
            <background android:drawable="@color/ic_launcher_background" />
            <foreground android:drawable="@mipmap/ic_launcher_foreground" />
            <monochrome android:drawable="@mipmap/ic_launcher_foreground" />
        </adaptive-icon>
    """.trimIndent()

    private fun backgroundColour(colour: Int) = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <color name="ic_launcher_background">#%06X</color>
        </resources>
    """.trimIndent().format(colour and 0xFFFFFF)
}
