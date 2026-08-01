package dev.ely.warp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.ely.warp.ui.theme.LocalIsDark

/**
 * Colour for code, in the editor and in chat.
 *
 * Hand-written for the same reason the markdown parser was: the alternatives are
 * a syntax library measured in megabytes, in an APK that already ships a 172 MB
 * toolchain, to colour three languages we control the shape of.
 *
 * **It is deliberately shallow.** It knows keywords, strings, comments, numbers
 * and annotations, and nothing about scope, types or generics. A half-correct
 * highlighter that guesses at semantics is worse than an obviously simple one:
 * you learn to distrust the colours, and then they cost attention without paying
 * anything back.
 *
 * Line by line, carrying one flag for block comments, because the editor renders
 * a line at a time so it can keep its gutter aligned. That is also why a block
 * comment opened on line ten still colours line eleven.
 *
 * Written without the literal slash-star anywhere in these comments: Kotlin
 * block comments **nest**, so writing the characters inside a doc comment opens
 * a second one and swallows the rest of the file. This project has been caught
 * by that once before, in a comment about glob patterns.
 */
object Syntax {

    enum class Language { KOTLIN, XML, JSON, NONE }

    fun of(name: String?): Language = when (name?.substringAfterLast('.')?.lowercase()) {
        "kt", "kts", "kotlin", "java" -> Language.KOTLIN
        "xml" -> Language.XML
        "json" -> Language.JSON
        else -> Language.NONE
    }

    /** The state a line hands to the next one. Only block comments need it. */
    data class Carry(val inBlockComment: Boolean = false)

    internal val KOTLIN_KEYWORDS = setOf(
        "package", "import", "class", "interface", "object", "fun", "val", "var",
        "if", "else", "when", "for", "while", "do", "return", "break", "continue",
        "try", "catch", "finally", "throw", "in", "is", "as", "by", "this", "super",
        "null", "true", "false", "override", "private", "public", "internal",
        "protected", "abstract", "open", "final", "const", "lateinit", "companion",
        "data", "sealed", "enum", "suspend", "operator", "inline", "vararg", "out",
        "typealias", "init", "constructor", "where", "reified", "annotation",
        "static", "void", "new", "extends", "implements",
    )
}

/**
 * One line, coloured.
 *
 * @param carry what the previous line left open. Pass [Syntax.Carry] back in for
 *   the next line, or a block comment opened on one line stops colouring the next.
 */
@Composable
fun highlightLine(
    line: String,
    language: Syntax.Language,
    carry: Syntax.Carry = Syntax.Carry(),
): Pair<AnnotatedString, Syntax.Carry> {
    val dark = LocalIsDark.current
    val palette = if (dark) DarkPalette else LightPalette

    if (language == Syntax.Language.NONE) {
        return AnnotatedString(line) to carry
    }

    var open = carry.inBlockComment
    val text = buildAnnotatedString {
        var i = 0
        fun peek(n: Int) = if (i + n <= line.length) line.substring(i, i + n) else ""

        while (i < line.length) {
            if (open) {
                val end = line.indexOf("*/", i)
                val stop = if (end == -1) line.length else end + 2
                withStyle(SpanStyle(color = palette.comment)) { append(line.substring(i, stop)) }
                i = stop
                if (end != -1) open = false
                continue
            }

            when {
                peek(2) == "/*" -> { open = true; continue }

                // Everything after // is a comment, including anything that
                // would otherwise look like code. Stopping at the first quote
                // is how a commented-out string turns the rest of the file red.
                peek(2) == "//" || (language == Syntax.Language.KOTLIN && peek(1) == "#") -> {
                    withStyle(SpanStyle(color = palette.comment)) { append(line.substring(i)) }
                    i = line.length
                }

                peek(4) == "<!--" -> {
                    withStyle(SpanStyle(color = palette.comment)) { append(line.substring(i)) }
                    i = line.length
                }

                line[i] == '"' || line[i] == '\'' -> {
                    val quote = line[i]
                    var j = i + 1
                    while (j < line.length && line[j] != quote) {
                        if (line[j] == '\\') j++
                        j++
                    }
                    val stop = (j + 1).coerceAtMost(line.length)
                    withStyle(SpanStyle(color = palette.string)) { append(line.substring(i, stop)) }
                    i = stop
                }

                line[i] == '@' -> {
                    var j = i + 1
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                    withStyle(SpanStyle(color = palette.annotation)) { append(line.substring(i, j)) }
                    i = j
                }

                line[i].isDigit() -> {
                    var j = i
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '.')) j++
                    withStyle(SpanStyle(color = palette.number)) { append(line.substring(i, j)) }
                    i = j
                }

                line[i].isLetter() || line[i] == '_' -> {
                    var j = i
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                    val word = line.substring(i, j)
                    val isKeyword = language == Syntax.Language.KOTLIN &&
                        word in Syntax.KOTLIN_KEYWORDS
                    // In XML a word straight after < or </ is the tag itself.
                    val isTag = language == Syntax.Language.XML &&
                        (i >= 1 && line[i - 1] == '<' || i >= 2 && line.substring(i - 2, i) == "</")
                    if (isKeyword || isTag) {
                        withStyle(SpanStyle(color = palette.keyword)) { append(word) }
                    } else {
                        append(word)
                    }
                    i = j
                }

                else -> { append(line[i]); i++ }
            }
        }
    }

    return text to Syntax.Carry(inBlockComment = open)
}

/**
 * Colours chosen to sit on Warp's greys, not borrowed from an IDE theme.
 *
 * Four hues and no more. §9c's rule that contrast is not negotiable applies
 * hardest here: code is the least forgiving text in the app, so every one of
 * these is checked against the surface it sits on rather than picked for looks.
 */
private data class Palette(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val annotation: Color,
)

private val DarkPalette = Palette(
    keyword = Color(0xFF7FA6FF),
    string = Color(0xFF9BD17F),
    // Dimmer than the code, on purpose: a comment you notice before the code is
    // a comment competing with the thing it explains.
    comment = Color(0xFF6B7280),
    number = Color(0xFFE0A458),
    annotation = Color(0xFFC792EA),
)

private val LightPalette = Palette(
    keyword = Color(0xFF2C5AA0),
    string = Color(0xFF2E7D32),
    comment = Color(0xFF8A8F98),
    number = Color(0xFF9A5B00),
    annotation = Color(0xFF7B3FA0),
)


/**
 * A whole snippet, coloured.
 *
 * The editor colours line by line because it draws a line at a time to keep its
 * gutter aligned; a chat block is one Text, so it is assembled here instead —
 * same highlighter, same carried block-comment state, one string out.
 */
@Composable
internal fun colouredCode(code: String, language: Syntax.Language): AnnotatedString {
    if (language == Syntax.Language.NONE) return AnnotatedString(code)
    var carry = Syntax.Carry()
    val lines = code.lines()
    return buildAnnotatedString {
        lines.forEachIndexed { index, line ->
            val (coloured, next) = highlightLine(line, language, carry)
            carry = next
            append(coloured)
            if (index != lines.lastIndex) append('\n')
        }
    }
}
