package dev.ely.warp.ai

/**
 * The commands you can type into the composer.
 *
 * A command rather than a sentence, and that distinction is the whole point.
 * "Could you plan this first" is a request the model weighs against everything
 * else you have said; `/plan` is not negotiable. §5f puts it plainly about
 * `/build` — *not "consider building"* — and the same holds for all of them.
 *
 * Parsed here rather than in the composer so the debug surface and the screen
 * agree about what a command is. Two parsers would eventually disagree, and the
 * one that mattered would be whichever was not being tested.
 */
data class SlashCommand(
    val name: String,
    /** Shown beside the name in the menu. One line, lower case. */
    val hint: String,
    /** What to say when it is typed with nothing after it. */
    val argumentHint: String? = null,
    /** True once it is wired to something. False rows are shown greyed out. */
    val ready: Boolean = true,
) {
    val typed: String get() = "/$name"
}

/**
 * Every command, in the order §5f lists them.
 *
 * Commands that are not built yet are **present and marked unready** rather than
 * hidden. A menu that quietly omits them teaches you they do not exist; a greyed
 * row with a reason teaches you they are coming, which is true.
 */
val SLASH_COMMANDS = listOf(
    SlashCommand(
        name = "plan",
        hint = "plan it, don't do it",
        argumentHint = "what should it plan?",
    ),
    SlashCommand(
        name = "rules",
        hint = "what Warp must never do",
        argumentHint = null,
    ),
    SlashCommand(
        name = "goal",
        hint = "work until this is true",
        argumentHint = "when is it finished?",
        ready = false,
    ),
    SlashCommand(
        name = "grill-me",
        hint = "question the plan, one at a time",
    ),
    SlashCommand(
        name = "build",
        hint = "compile it now",
        ready = false,
    ),
)

/** A command and whatever followed it. */
data class ParsedCommand(
    val command: SlashCommand,
    /** Everything after the name, trimmed. Empty when nothing followed. */
    val argument: String,
)

/**
 * Read a command off the front of what was typed, or null.
 *
 * Only the very start counts. A slash inside a sentence is a slash — asking
 * about `and/or` or a path like `src/main` must not be swallowed as a command,
 * and the failure would be silent, which is the worst kind here.
 */
fun parseSlashCommand(text: String): ParsedCommand? {
    val trimmed = text.trimStart()
    if (!trimmed.startsWith("/")) return null

    val word = trimmed.drop(1).takeWhile { !it.isWhitespace() }
    val command = SLASH_COMMANDS.firstOrNull { it.name.equals(word, ignoreCase = true) }
        ?: return null

    return ParsedCommand(command, trimmed.drop(1 + word.length).trim())
}

/**
 * What to offer while someone is typing, or null when they are not.
 *
 * Null and empty are different answers: null means this is not a command at all
 * and the menu should not be on screen, while empty means it is a command shape
 * that matches nothing, and the menu should say so rather than vanish.
 */
fun matchingCommands(text: String): List<SlashCommand>? {
    val trimmed = text.trimStart()
    if (!trimmed.startsWith("/")) return null
    // Once there is a space, the name is settled and you are typing the
    // argument. Keeping the menu up would cover the thing being typed.
    if (trimmed.any { it.isWhitespace() }) return null

    val typed = trimmed.drop(1)
    return SLASH_COMMANDS.filter { it.name.startsWith(typed, ignoreCase = true) }
}

/**
 * What `/grill-me` adds, for one turn.
 *
 * The instruction that carries the most weight is *one question at a time*. A
 * model asked to "clarify the requirements" produces a numbered list of eight,
 * which gets skimmed and half-answered — and half-answered is worse than
 * unanswered, because it looks resolved.
 *
 * Second is the recommendation. Most questions are asked of somebody who has no
 * opinion yet; a question with a recommended answer can be settled with a thumb,
 * and one without it becomes homework.
 */
const val GRILL_DIRECTIVE = """
The user has asked you to INTERROGATE THE PLAN.

Ask ONE question at a time, using the `ask` tool. Never ask two at once, and
never write a numbered list of questions.

Each question must:
- be about a decision only they can make — not something you could look up
- offer 2 to 4 concrete options, each a few words
- name which option you recommend, and say why in one line

Read the project first if it would make the question sharper. Do not write or
change anything.

Stop when the remaining choices no longer change what gets built. Then give a
short summary of what was decided, and nothing else.
"""

/**
 * What `/plan` adds to the system prompt, for one turn only.
 *
 * Written as a hard boundary rather than a preference, because a model asked to
 * "focus on planning" will still helpfully write the file. The last line is the
 * load-bearing one: without somewhere to put the urge to act, it acts.
 */
const val PLAN_DIRECTIVE = """
The user has asked you to PLAN only.

Do not call any tool that changes anything. Do not write, edit or create files.
Reading in order to plan is fine and encouraged.

Produce a short, concrete plan: what you would do, in what order, and what could
go wrong. If a step is uncertain, say what you would check first. End by asking
whether to proceed — do not begin.
"""
