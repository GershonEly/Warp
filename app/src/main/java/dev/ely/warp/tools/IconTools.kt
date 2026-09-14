package dev.ely.warp.tools

import dev.ely.warp.ai.ImageGen
import dev.ely.warp.build.IconStudio
import dev.ely.warp.data.ImageModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Give the app a face — §8.
 *
 * `WRITES` rather than `RUNS`, and the reasoning is worth stating because the
 * cost makes it look like a `RUNS`. What §5f actually guards with `RUNS` is
 * *irreversible outside the phone* — a push, an install, a launch. An icon is
 * neither: it lands in `res/`, git can undo it, and nothing leaves the account.
 * So it asks, and **Always** is offered, which is the right answer for somebody
 * iterating on an icon who would otherwise tap the same dialog nine times.
 *
 * The price is on the card, every time, before the tap. That is the part that
 * matters: an icon costs a few hundred messages' worth, and agreeing to it
 * without seeing the number is not agreeing to it.
 */
object MakeIcon : Tool {
    override val name = "make_icon"
    override val risk = Risk.WRITES

    override val description =
        "Draw a new launcher icon for this app from a description, and write it " +
            "into res/ at every density Android needs. This costs real money — " +
            "a few cents per attempt on the person's own account — so use it " +
            "when they ask for an icon, not on your own initiative, and do not " +
            "retry it after a good result. Describe the subject only: style, " +
            "margins and the no-text rule are added for you."

    override val schemaJson = """
        {"type":"object","properties":{
          "description":{"type":"string","description":
            "What the icon shows. A short phrase: 'a green paper plane', not a sentence."}},
         "required":["description"]}
    """.trimIndent()

    override fun describe(args: JSONObject): String {
        val what = args.optString("description").ifBlank { "a new icon" }
        // The price, in the place the money is agreed to. Approximate and said
        // so — the real figure comes back on the result card afterwards.
        return "draw the app icon: $what · about ${ImageGen.cents(ImageModels.lastChosen)}"
    }

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult =
        withContext(Dispatchers.IO) {
            val model = ImageModels.chosen(env.context)
            when (val outcome = IconStudio.draw(
                env.context, env.project, args.optString("description"),
            )) {
                is IconStudio.Outcome.Failed -> ToolResult.Failed(outcome.message)
                is IconStudio.Outcome.Drawn -> ToolResult.Ok(
                    "new icon written · ${spent(outcome.costUsd, model)}",
                    buildString {
                        appendLine("Drawn by ${model.name}.")
                        appendLine()
                        outcome.files.forEach { appendLine(it) }
                        appendLine()
                        append(
                            "The app's colour now comes from the icon. " +
                                "Rebuild to see it on the home screen."
                        )
                    },
                )
            }
        }

    /**
     * What it cost, said two different ways for two different certainties.
     *
     * When the provider reports a charge, that is the charge. When it does not,
     * the catalogue estimate is shown as an estimate — §5o's rule about invented
     * numbers cuts both ways, and rounding an unknown into a confident figure is
     * the same fault as a confident error message.
     */
    private fun spent(costUsd: Double?, model: ImageModels.Choice): String =
        costUsd?.let { "${Math.round(it * 100).toInt().coerceAtLeast(1)}¢" }
            ?: "about ${ImageGen.cents(model)}"
}
