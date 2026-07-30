package dev.ely.warp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.ely.warp.ui.theme.LocalCodeSurface
import dev.ely.warp.ui.theme.WarpMono
import dev.ely.warp.ui.theme.WarpRadius
import dev.ely.warp.ui.theme.WarpSpace
import kotlinx.coroutines.delay

/**
 * What the assistant says, rendered.
 *
 * Warp is a tool for writing Kotlin on a phone that, until this file existed,
 * could not display Kotlin. Every answer arrived as one undifferentiated block
 * of prose with the code buried in it.
 *
 * Written by hand rather than pulled in as a dependency, for one reason that
 * decides it: **the text arrives a token at a time.** A markdown library parses
 * a finished document, and re-parsing the whole message on every token dropped
 * frames on a long answer. This parses incrementally and, more importantly,
 * renders an *unterminated* block as the block it is clearly becoming — so a
 * code fence does not flicker between prose and code while it is being typed.
 */

// ── the pieces a message is made of ──────────────────────────────────────

private sealed interface Block {
    data class Prose(val text: String) : Block
    data class Heading(val text: String, val level: Int) : Block
    data class Bullet(val text: String, val ordered: Boolean, val marker: String) : Block
    data class Code(val language: String?, val text: String, val closed: Boolean) : Block
    data object Rule : Block
}

/**
 * Split text into blocks.
 *
 * The one rule that matters: **an unclosed fence is still a code block.** The
 * naive version treats ```` ```kot ```` as prose until its closing fence arrives,
 * which means every code block in a streaming answer appears as plain text and
 * then jumps into a box. The jump is the tell that something is being parsed at
 * you rather than written for you.
 */
private fun parse(markdown: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val lines = markdown.lines()
    var i = 0
    val prose = StringBuilder()

    fun flushProse() {
        val text = prose.toString().trim()
        if (text.isNotEmpty()) blocks += Block.Prose(text)
        prose.clear()
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        when {
            trimmed.startsWith("```") -> {
                flushProse()
                val language = trimmed.removePrefix("```").trim().takeIf { it.isNotEmpty() }
                val body = StringBuilder()
                var closed = false
                i++
                while (i < lines.size) {
                    if (lines[i].trimStart().startsWith("```")) { closed = true; i++; break }
                    body.appendLine(lines[i])
                    i++
                }
                blocks += Block.Code(language, body.toString().trimEnd('\n'), closed)
                continue
            }

            trimmed.startsWith("#") -> {
                val level = trimmed.takeWhile { it == '#' }.length
                if (level in 1..6 && trimmed.getOrNull(level) == ' ') {
                    flushProse()
                    blocks += Block.Heading(trimmed.drop(level).trim(), level)
                    i++
                    continue
                }
            }

            trimmed == "---" || trimmed == "***" -> {
                flushProse()
                blocks += Block.Rule
                i++
                continue
            }

            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushProse()
                blocks += Block.Bullet(trimmed.drop(2), ordered = false, marker = "•")
                i++
                continue
            }

            trimmed.matches(ORDERED) -> {
                flushProse()
                val marker = trimmed.takeWhile { it.isDigit() }
                blocks += Block.Bullet(
                    trimmed.dropWhile { it.isDigit() }.removePrefix(".").trim(),
                    ordered = true,
                    marker = "$marker.",
                )
                i++
                continue
            }
        }

        prose.appendLine(line)
        i++
    }

    flushProse()
    return blocks
}

private val ORDERED = Regex("""^\d+\.\s+.*""")

/**
 * Bold, italic and inline code, within one paragraph.
 *
 * Inline code is **tinted rather than boxed**. A box inside a sentence breaks
 * the line rhythm — the text stops being a line of prose and becomes a row of
 * containers.
 */
@Composable
private fun inline(text: String): AnnotatedString {
    val code = MaterialTheme.colorScheme.primary
    return remember(text, code) {
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                val rest = text.substring(i)
                val bold = rest.startsWith("**")
                val tick = rest.startsWith("`")
                val italic = rest.startsWith("*") && !bold

                val marker = when {
                    bold -> "**"
                    tick -> "`"
                    italic -> "*"
                    else -> null
                }

                if (marker == null) {
                    append(text[i]); i++; continue
                }

                val close = text.indexOf(marker, i + marker.length)
                if (close < 0) { append(text[i]); i++; continue }

                val inner = text.substring(i + marker.length, close)
                when {
                    bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inner) }
                    italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inner) }
                    tick -> withStyle(
                        SpanStyle(fontFamily = WarpMono.fontFamily, color = code)
                    ) { append(inner) }
                }
                i = close + marker.length
            }
        }
    }
}

// ── rendering ────────────────────────────────────────────────────────────

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    // Keyed on the text, so a streaming message reparses once per token rather
    // than once per recomposition — Compose recomposes for reasons that have
    // nothing to do with the message changing.
    val blocks = remember(text) { parse(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(WarpSpace.small)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Prose -> Text(
                    inline(block.text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                is Block.Heading -> Text(
                    inline(block.text),
                    // Bounded at three levels. A chat bubble is not a document,
                    // and a fourth-level heading in a phone-width message is a
                    // distinction nobody can see.
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = WarpSpace.small),
                )

                is Block.Bullet -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        block.marker,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = WarpSpace.small),
                    )
                    Text(
                        inline(block.text),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                is Block.Code -> CodeBlock(block)

                Block.Rule -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = WarpSpace.small)
                        .size(height = 1.dp, width = 0.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
    }
}

/**
 * Code, in a box you can copy from.
 *
 * **It scrolls sideways rather than wrapping.** Wrapped code is code whose
 * indentation lies to you, and indentation is most of how Kotlin is read.
 *
 * The copy button is on the block, not only on the message: copying a whole
 * answer to get one function is a small daily annoyance, and this is the tool
 * whose entire output is code.
 */
@Composable
private fun CodeBlock(block: Block.Code) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) { delay(1600); copied = false }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WarpRadius.small))
            .background(LocalCodeSurface.current)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = WarpSpace.medium, end = WarpSpace.small, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                block.language ?: "code",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(WarpRadius.small))
                    .clickable {
                        clipboard.setText(AnnotatedString(block.text))
                        copied = true
                    }
                    .padding(horizontal = WarpSpace.small, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                    contentDescription = "Copy",
                    modifier = Modifier.size(13.dp),
                    tint = if (copied) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    if (copied) "Copied" else "Copy",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (copied) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(WarpSpace.medium)
        ) {
            Text(
                block.text,
                style = WarpMono,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
