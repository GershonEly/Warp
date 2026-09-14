package dev.ely.warp.build

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.ely.warp.ai.ImageGen
import java.io.File

/**
 * From a sentence to an app icon — §8.
 *
 * One path, used by both the chat tool and the Assets screen, because they are
 * the same act arrived at from two moods: *"make the icon a green flame"* while
 * you are building, and going to look at your app's face when you are not.
 * Two implementations of this would drift, and the one that drifted would be
 * whichever was not being looked at.
 *
 * [Icons] already did the hard half — five densities, the adaptive layers, the
 * manifest, the colour. The only thing it never had was a real picture.
 */
object IconStudio {

    /**
     * The biggest written icons, for showing back.
     *
     * Both, because the two are what a launcher will actually pick between —
     * showing one and calling it "the icon" hides the half of the answer that
     * goes wrong. The round one is where artwork drawn to the edges loses its
     * corners, and seeing that before shipping is the point of a preview.
     */
    private const val PREVIEW = "res/mipmap-xxxhdpi/ic_launcher.png"
    private const val PREVIEW_ROUND = "res/mipmap-xxxhdpi/ic_launcher_round.png"

    sealed interface Outcome {
        data class Drawn(
            val files: List<String>,
            val colour: Int,
            /** What the provider charged, or null when it did not say. */
            val costUsd: Double?,
        ) : Outcome

        data class Failed(val message: String) : Outcome
    }

    /**
     * Draw one, and put it everywhere Android looks.
     *
     * Blocking, on the network. Callers are already off the main thread.
     */
    fun draw(context: Context, project: File, description: String): Outcome {
        if (description.isBlank()) return Outcome.Failed("Say what the icon should be.")
        if (!NewProject.exists(project)) {
            return Outcome.Failed("There is no app here yet to put an icon on.")
        }

        return when (val result = ImageGen.draw(context, brief(description))) {
            is ImageGen.Result.Failed -> Outcome.Failed(result.message)
            is ImageGen.Result.Drawn -> {
                val artwork = BitmapFactory.decodeByteArray(result.png, 0, result.png.size)
                    ?: return Outcome.Failed(
                        "The model sent something back that is not a picture."
                    )
                install(project, artwork, result.costUsd)
            }
        }
    }

    /**
     * Write the artwork in and remember its colour.
     *
     * Separate from [draw] so the picture and the paying for it stay apart: this
     * half is free, repeatable, and the part worth having if a picture ever
     * arrives from somewhere else.
     */
    fun install(project: File, artwork: Bitmap, costUsd: Double? = null): Outcome =
        runCatching {
            val colour = Icons.dominantColour(artwork)
            val files = Icons.write(project, artwork, colour)
            // The app's colour is now its own rather than a hash of its id. The
            // shelf and the drawer read it from here without being told.
            NewProject.recordColour(project, colour)
            Outcome.Drawn(files, colour, costUsd)
        }.getOrElse {
            Outcome.Failed(it.message ?: "The icon could not be written.")
        }

    /** The icon as it stands, or null before there is one. */
    fun current(project: File): File? = File(project, PREVIEW).takeIf { it.isFile }

    /** The same icon as a launcher that prefers circles will show it. */
    fun currentRound(project: File): File? =
        File(project, PREVIEW_ROUND).takeIf { it.isFile }

    /**
     * What is actually sent, around what the person typed.
     *
     * Every line is a failure that happens without it, and the first two are the
     * expensive ones. **Text**: models write words into icons unasked, the words
     * come out misspelled at 48dp, and at 48dp no word is legible anyway.
     * **Margin**: Android masks adaptive icons to a circle on most launchers, so
     * artwork drawn to the edges loses its corners — and the corner it loses is
     * usually where the model put the interesting part.
     *
     * §5o is the argument for doing this at all: the same session that produced
     * black-and-purple squares everywhere was a model left to infer what was
     * wanted. Saying it plainly costs nothing and is not a guess about taste —
     * these are the constraints of the medium, not opinions about it.
     */
    private fun brief(description: String) = buildString {
        append("An Android app launcher icon: ")
        append(description.trim())
        append(". ")
        append("Flat vector style, one clear subject, centred, bold simple shapes. ")
        // Roughly the safe zone: 72 of 108 dp, so about a third is maskable.
        append("Leave generous empty margin around the subject — the outer third ")
        append("of the image may be cropped to a circle. ")
        append("No text, no letters, no words, no numbers. ")
        append("No drop shadow, no gradient mesh, no photographic detail, ")
        append("no mockup of a phone, no border or frame. ")
        append("Solid simple background. Square image.")
    }
}
