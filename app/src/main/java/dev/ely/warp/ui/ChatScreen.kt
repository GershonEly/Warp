package dev.ely.warp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.util.Locale
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.outlined.Check
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedVisibility
import kotlinx.coroutines.flow.MutableStateFlow
import dev.ely.warp.voice.VoiceInput
import androidx.core.content.ContextCompat
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.pm.PackageManager
import android.Manifest
import androidx.compose.ui.graphics.graphicsLayer
import dev.ely.warp.ui.theme.HairlineWidth
import dev.ely.warp.ui.theme.LocalCodeSurface
import dev.ely.warp.ui.theme.LocalIsDark
import dev.ely.warp.ui.theme.WarpRadius
import dev.ely.warp.ui.theme.WarpAccent
import dev.ely.warp.ui.theme.WarpIndigo
import dev.ely.warp.ui.theme.aurora
import dev.ely.warp.ui.theme.WarpSpace
import dev.ely.warp.ui.theme.LocalAnimationsEnabled
import dev.ely.warp.data.Appearance
import dev.ely.warp.data.Identity
import dev.ely.warp.ai.Effort
import dev.ely.warp.ai.AiError
import dev.ely.warp.ai.ChatEngine
import dev.ely.warp.ai.SlashCommand
import dev.ely.warp.ai.matchingCommands
import dev.ely.warp.ai.parseSlashCommand
import dev.ely.warp.data.Rules
import dev.ely.warp.tools.PermissionRequest
import dev.ely.warp.tools.PermissionDesk
import dev.ely.warp.tools.Decision
import dev.ely.warp.ai.ChatMessage
import dev.ely.warp.ai.Role
import dev.ely.warp.ai.ToolCall
import dev.ely.warp.ui.theme.WarpMono
import dev.ely.warp.ui.theme.WarpMotion
import dev.ely.warp.ui.theme.WarpSuccess
import dev.ely.warp.ui.theme.WarpWarning
import dev.ely.warp.ui.theme.motionDuration
import dev.ely.warp.ui.theme.warpTween
import kotlin.math.sin

/**
 * The blue the room is lit by.
 *
 * A wash rising from behind the composer, and it is **state rather than
 * decoration** — its depth follows how hard the model has been asked to think.
 * That is worth doing because effort is the one setting whose consequences
 * cannot be seen: you pick High, you wait longer, you pay more, and nothing on
 * screen acknowledges any of it. Here the room simply gets warmer.
 *
 * Three rules keep it from being a gimmick:
 *
 * - **It never sits behind text.** It lives in the bottom third, behind the
 *   composer, and fades to nothing well before the transcript. Contrast is not
 *   negotiable and this must never be what costs a legible message.
 * - **You should not be able to catch it.** If you can point at it and say it
 *   just changed, it is too strong. It is meant to be noticed by its absence.
 * - **It is drawn, not laid out.** `drawBehind` costs no measure pass and no
 *   composable, so an always-present effect is not an always-present cost.
 *
 * Off entirely when [Appearance.ambient] is off — atmosphere is the one thing
 * here that could reasonably annoy somebody.
 */
@Composable
fun Modifier.ambientWash(effort: Effort?): Modifier {
    val on by Appearance.get(LocalContext.current).ambient.collectAsState()
    if (!on) return this

    val dark = LocalIsDark.current

    // Roughly four times the first attempt. Measured, that one peaked at +4 of
    // 255 — under two per cent, which is not subtle, it is invisible, and what
    // it produced was a flat grey screen with a smudge near the bottom.
    val target = when (effort) {
        null, Effort.LOW -> 0.55f
        Effort.MEDIUM -> 0.80f
        Effort.HIGH -> 1.10f
        Effort.MAX -> 1.35f
    } * if (dark) 1f else 0.30f

    // Eased, so changing effort settles rather than cuts. Slower than anything
    // else in the app: this is weather, not feedback.
    val strength by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(motionDuration(900)),
        label = "auroraStrength",
    )

    // One slow value, three different responses to it inside `aurora`. Forty
    // seconds a cycle, which is far too slow to watch and exactly fast enough
    // that the screen is never twice the same.
    val drift = if (LocalAnimationsEnabled.current) {
        val t = rememberInfiniteTransition(label = "aurora")
        val phase by t.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(40_000, easing = LinearEasing)),
            label = "auroraDrift",
        )
        sin(phase)
    } else {
        0f
    }

    return aurora(
        strength = strength,
        drift = drift,
        primary = MaterialTheme.colorScheme.primary,
        accent = WarpAccent,
        indigo = WarpIndigo,
    )
}

/**
 * The chat.
 *
 * Laid out as in the plan: the assistant speaks in plain text behind a ✦ rather
 * than in a bubble, so long answers read like prose instead of a chat log; only
 * the user's own messages are bubbled. Cards — tools, plans, errors — sit
 * outside the text as their own objects.
 *
 * Which AI is behind it is never visible here. That is the harness working.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ChatScreen(
    engine: ChatEngine,
    onOpenSettings: () -> Unit,
    modelLabel: String,
    onPickModel: () -> Unit,
    effort: Effort?,
    /** Where a tool goes to ask you. Null leaves the cards read-only. */
    permission: PermissionDesk? = null,
    modifier: Modifier = Modifier,
) {
    val messages by engine.messages.collectAsState()
    val busy by engine.busy.collectAsState()
    val rules = LocalContext.current.let { remember(it) { Rules.get(it) } }
    var showRules by remember { mutableStateOf(false) }

    /** A one-line answer to a command, shown until the next thing you type. */
    var ruleFeedback by remember { mutableStateOf<String?>(null) }
    val asking by (permission?.pending ?: remember { MutableStateFlow(null) }).collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current

    // Null when the phone has no recogniser at all, and the mic is then simply
    // absent rather than dead. MIUI has surprised this project twice already,
    // and a button that does nothing is worse than a button that is not there.
    val context = LocalContext.current
    val voice = remember {
        if (VoiceInput.isAvailable(context)) VoiceInput(context) else null
    }

    // Follow the newest text as it streams in.
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    // The mark is one continuous object across both states. When the first
    // message is sent it does not vanish and get replaced — it travels from the
    // centre of the empty screen down into its place beside the reply, shrinking
    // as it goes. A shared element, so the movement is the real thing moving
    // rather than two things cross-fading.
    SharedTransitionLayout(modifier = modifier.fillMaxSize()) {
        // Read here, in composable scope: the transition block is not one, and
        // these carry the reduce-animations setting.
        val bodyIn = motionDuration(WarpMotion.SLOW)
        val bodyOut = motionDuration(WarpMotion.QUICK)

        // Hoisted for the same reason: the item scope inside a LazyColumn is not
        // a place these can be read.
        val itemSpec = warpTween<Float>(WarpMotion.NORMAL)
        val itemPlacement = warpTween<IntOffset>(WarpMotion.NORMAL)

        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = messages.isEmpty(),
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    fadeIn(tween(bodyIn)) togetherWith fadeOut(tween(bodyOut))
                },
                label = "chatBody",
            ) { empty ->
                // The key ties the two marks together; Compose interpolates
                // position and size between them.
                val markModifier = Modifier.sharedElement(
                    rememberSharedContentState(MARK_KEY),
                    animatedVisibilityScope = this@AnimatedContent,
                )

                if (empty) {
                    EmptyState(markModifier = markModifier, onPick = { input = it })
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = WarpSpace.screen,
                            vertical = WarpSpace.large,
                        ),
                        verticalArrangement = Arrangement.spacedBy(WarpSpace.message),
                    ) {
                        itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
                            // animateItem moves neighbours smoothly as the list
                            // grows; Appear handles each message's own entrance.
                            //
                            // Given explicit specs rather than left on the
                            // default: animateItem's built-in spring is the one
                            // animation in the app that was ignoring the
                            // reduce-motion setting, because it never passed
                            // through warpTween.
                            Appear(
                                modifier = Modifier.animateItem(
                                    fadeInSpec = itemSpec,
                                    placementSpec = itemPlacement,
                                    fadeOutSpec = itemSpec,
                                )
                            ) {
                                MessageItem(
                                    message = message,
                                    // Only the first reply inherits the travelling
                                    // mark — later ones simply appear.
                                    markModifier = if (index == 1) markModifier else Modifier,
                                    asking = asking,
                                    onDecide = { permission?.answer(it) },
                                )
                            }
                        }
                    }
                }
            }

            Composer(
                value = input,
                onValueChange = {
                    input = it
                    if (ruleFeedback != null) ruleFeedback = null
                },
                busy = busy,
                modelLabel = modelLabel,
                onPickModel = onPickModel,
                onSend = {
                    // A light tap on send. Haptics are for things that happen,
                    // never for navigation — a phone that buzzes at everything
                    // stops meaning anything.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val sent = input
                    input = ""
                    ruleFeedback = runComposed(sent, engine, rules) { showRules = true }
                },
                onStop = { engine.stop() },
                voice = voice,
                note = ruleFeedback,
                onNoteShown = { ruleFeedback = null },
                matches = matchingCommands(input),
                // A trailing space, so the argument is typed rather than
                // rubbing up against the command name.
                onPickCommand = { input = it.typed + " " },
            )

            if (showRules) {
                RulesSheet(rules = rules, onDismiss = { showRules = false })
            }
        }
    }
}

/**
 * What pressing send does, command or not.
 *
 * Pulled out of the button so the debug surface can press exactly the same
 * thing. Two copies — one for the screen, one for a test — would eventually
 * disagree, and the one under test would be the one nobody uses.
 *
 * @return a line to show, or null when the message simply went to the model.
 */
internal fun runComposed(
    sent: String,
    engine: ChatEngine,
    rules: Rules,
    onShowRules: () -> Unit,
): String? {
    val parsed = parseSlashCommand(sent) ?: run {
        engine.send(sent)
        return null
    }

    return when (parsed.command.name) {
        // Sent as an ordinary message with the mode set, so the transcript shows
        // what you actually typed. Hiding the command would leave a plan-only
        // reply with nothing on screen explaining why it planned.
        "plan" -> {
            engine.send(sent, ChatEngine.Mode.PLAN)
            null
        }

        // No note for the bare form. The sheet that just opened *is* the
        // answer, and a line underneath saying "showing your rules" while your
        // rules are on screen is the app narrating itself.
        "rules" -> if (parsed.argument.isBlank()) {
            onShowRules()
            null
        } else {
            applyRuleCommand(rules, parsed.argument)
        }

        // In the menu, greyed out, and refused here as well. A command that
        // silently did nothing would be worse than one that says so.
        else -> "${parsed.command.typed} is not built yet."
    }
}

/** How long a command's answer stays under the composer. */
private const val NOTE_LINGER_MS = 6_000L

/** Ties the empty state's mark to the first reply's mark. */
private const val MARK_KEY = "warp-mark"

/**
 * Fades content in while it rises into place.
 *
 * Nothing in Warp should pop into existence. Both values collapse to instant
 * when the system's reduce-animations setting is on.
 */
@Composable
private fun Appear(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = warpTween(WarpMotion.NORMAL, WarpMotion.Enter),
        label = "appear",
    )

    Box(
        modifier = modifier
            .alpha(progress)
            .padding(top = ((1f - progress) * WarpMotion.RISE_DP).dp),
    ) { content() }
}

/**
 * A quiet note that answers are scripted.
 *
 * Previously a full-width amber band with an emoji, which read as a system
 * warning — the loudest thing on a screen whose subject is a conversation.
 * Demo mode is a fact worth stating once, not an alarm.
 */
@Composable
fun DemoChip(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = WarpSpace.small),
        horizontalArrangement = Arrangement.Center,
    ) {
        // Tappable, and it leads to the place that fixes it. It wears the exact
        // shape of the suggestion pills a few lines below, so by the rule that
        // shape is a promise it had to either stop looking like a pill or start
        // behaving like one — and a notice that names a problem should always
        // lead somewhere.
        Surface(
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(HairlineWidth, MaterialTheme.colorScheme.outlineVariant),
            onClick = onOpenSettings,
        ) {
            Row(
                modifier = Modifier.padding(
                    start = WarpSpace.medium,
                    end = WarpSpace.small,
                    top = 6.dp,
                    bottom = 6.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Demo mode · add a key",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(
    markModifier: Modifier,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current

    // Two arrangements, chosen by the shape of the space rather than by a
    // device category. Rotating the phone cut this screen to about 400dp of
    // height and a plain Column clipped everything below the mark — heading and
    // both suggestions simply gone. Making it scroll stopped the loss, but a
    // tall stack on a wide, short screen is still the wrong shape: it leaves
    // the width empty and pushes the words under the composer.
    //
    // So on a wide-short screen the mark moves *beside* the text instead of
    // above it, which is the arrangement that space was always asking for.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val short = maxHeight < 480.dp
        // A genuinely wide screen, not merely a squashed one. `maxWidth >
        // maxHeight` was true in portrait the moment the keyboard opened — the
        // content area is shorter than it is wide long before the phone is
        // landscape — so typing threw the empty state into the side-by-side
        // arrangement and jammed it into the top-left corner. The width floor is
        // what tells a landscape phone from a portrait one wearing a keyboard.
        val beside = short && maxWidth > maxHeight && maxWidth >= 600.dp

        // A slow, tiny rise and fall. Off entirely when the system's animation
        // scale is zero — an infinite animation is exactly the kind someone who
        // turned animations off meant to be rid of, and it would otherwise run
        // forever behind a screen they are trying to read.
        val drift = if (LocalAnimationsEnabled.current) {
            val float = rememberInfiniteTransition(label = "float")
            val phase by float.animateFloat(
                initialValue = 0f,
                targetValue = (2 * Math.PI).toFloat(),
                animationSpec = infiniteRepeatable(
                    animation = tween(5000, easing = LinearEasing),
                ),
                label = "floatPhase",
            )
            with(LocalDensity.current) { (sin(phase) * 6f).dp.toPx() }
        } else {
            0f
        }

        val markSize = if (short) 44.dp else 76.dp
        val glowSize = when {
            short -> 120.dp
            LocalIsDark.current -> 260.dp
            else -> 190.dp
        }

        // Scrollable as a floor, not as a feature: at some combination of small
        // screen and large font any fixed layout runs out of room, and content
        // you cannot reach is worse than content you have to scroll to.
        val scroll = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            // The camera sits in the left edge in landscape, and nothing above
            // this handles it: the Scaffold insets only the navigation bar, so
            // the greeting was printed underneath the lens. `safeDrawing` on the
            // horizontal sides clears the cutout on whichever edge it lands —
            // which is the other edge entirely if the phone is turned the other
            // way, so a fixed left padding would have been wrong half the time.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = WarpSpace.screen)

        val mark: @Composable () -> Unit = {
            // One of only two places in the app allowed to glow. Restraint is
            // what makes this moment land; an app that glows everywhere glows
            // nowhere.
            //
            // Far weaker on white. Light does not add on a light background the
            // way it does on a dark one — at dark-mode strength this bloom read
            // as a grey-blue smudge behind the mark rather than as a glow.
            val dark = LocalIsDark.current
            val glow = MaterialTheme.colorScheme.primary
                .copy(alpha = if (dark) 0.18f else 0.07f)
            val glowPx = with(LocalDensity.current) { glowSize.toPx() }

            // Drawn behind, not laid out. As a sized Box the glow was a 260dp
            // tall element in the column, which is why the name it sits above
            // could never reach the middle of the screen — most of the group's
            // height was empty air belonging to a gradient.
            Box(
                modifier = Modifier
                    .size(markSize)
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(glow, Color.Transparent),
                                center = center,
                                radius = glowPx / 2,
                            ),
                            radius = glowPx / 2,
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                WarpMark(size = markSize, modifier = markModifier)
            }
        }

        // The greeting uses your name when there is one, and simply does not
        // when there is not — no "User", no "there", no placeholder. Warp
        // already knows the name; not using it was a wasted greeting, and
        // inventing one would be worse than having none.
        //
        // "We" rather than "you": Warp compiles the thing. It is not asking what
        // you will go and build, it is asking what the two of you are building.
        val name by remember { Identity.get(context).name }.collectAsState()

        // Two lines, not one, and the split is the point.
        //
        // "What are we building, <name>?" put the name at the end of a sentence,
        // where it is the last thing read and carries no more weight than the
        // words around it. Standing alone above the question it is the first
        // thing on the screen — which is what a greeting is for.
        //
        // The question then drops to a supporting size and colour. It is the
        // same information either way; the hierarchy is what changed.
        val heading: @Composable (TextAlign) -> Unit = { align ->
            Column(
                horizontalAlignment = if (align == TextAlign.Center) {
                    Alignment.CenterHorizontally
                } else {
                    Alignment.Start
                },
            ) {
                name?.let { person ->
                    Text(
                        person,
                        style = if (short) MaterialTheme.typography.headlineMedium
                        else MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = align,
                    )
                    Spacer(Modifier.size(WarpSpace.tiny))
                }
                Text(
                    "What are we building?",
                    // Without a name this line *is* the greeting, so it takes the
                    // display size. With one it steps down and greys out, because
                    // two things at 32sp is two headlines and no hierarchy.
                    style = when {
                        short -> MaterialTheme.typography.titleMedium
                        name == null -> MaterialTheme.typography.displaySmall
                        else -> MaterialTheme.typography.titleLarge
                    },
                    color = if (name == null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = align,
                )
            }
        }

        // FlowRow, not Row: at the largest font size two pills no longer fit
        // side by side, and a Row would push the second one off the screen
        // rather than move it to its own line.
        // The modifier is a parameter because beside-mode must NOT fill the
        // width: a full-width pill row makes its column full-width too, and then
        // the Row's Center arrangement has nothing left to centre — which is
        // exactly why the group stayed pinned to the left edge on the first try.
        val pills: @Composable (Alignment.Horizontal, Modifier) -> Unit = { align, mod ->
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(WarpSpace.small, align),
                verticalArrangement = Arrangement.spacedBy(WarpSpace.small),
                modifier = mod,
            ) {
                SUGGESTIONS.forEach { suggestion ->
                    SuggestionPill(suggestion) { onPick(suggestion) }
                }
            }
        }

        if (beside) {
            Row(
                // Sits to the left rather than centred as a group.
                // Centring was tried and looked worse: the composer runs the
                // full width beneath it, so a floating centred cluster has
                // nothing to line up with, where a left-aligned one shares the
                // composer's edge.
                // `heightIn` for the same reason the portrait column needs it:
                // inside a vertical scroll a Row is measured with unbounded
                // height, so `CenterVertically` has nothing to centre within and
                // quietly behaves like Top. Giving it the viewport's height is
                // what makes the alignment mean anything.
                modifier = scroll
                    .heightIn(min = maxHeight)
                    .padding(bottom = WarpSpace.large),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                mark()
                Spacer(Modifier.size(WarpSpace.large))
                Column(
                    horizontalAlignment = Alignment.Start,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    heading(TextAlign.Start)
                    Spacer(Modifier.size(WarpSpace.large))
                    pills(Alignment.Start, Modifier)
                }
            }
        } else {
            // Centred in the space, not pinned to the top.
            //
            // The first version sat at the top on the reasoning that content
            // pushed downward reads as though the screen had scrolled away from
            // something. On the phone the opposite was true: the group sat high
            // with a large dead area under it, which reads as an unfinished
            // screen rather than a patient one. Gemini and Claude both centre
            // theirs, and an empty state has one thing to say — the middle is
            // where you look for it.
            //
            // `heightIn(min = maxHeight)` is what makes centring possible at all.
            // Inside a vertical scroll the column is measured with unbounded
            // height, so `Arrangement.Center` has nothing to centre within and
            // silently behaves exactly like `Top`. Giving the content at least
            // the viewport's height fixes that, and it still scrolls when a large
            // font makes the group taller than the screen.
            Column(
                modifier = scroll.heightIn(min = maxHeight),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The mark and the greeting drift together, as one object.
                //
                // Small and slow — 6dp over five seconds. Anything you can catch
                // moving is a distraction on a screen you are about to type on;
                // this is meant to be noticed only as the screen not being quite
                // still. It carries the glow with it, which is what sells it as
                // something suspended rather than something sliding.
                Box(modifier = Modifier.graphicsLayer { translationY = drift }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        mark()
                        Spacer(Modifier.size(WarpSpace.large))
                        heading(TextAlign.Center)
                    }
                }

                Spacer(Modifier.size(WarpSpace.section))
                pills(Alignment.CenterHorizontally, Modifier.fillMaxWidth())

                // Balances what sits above the name against what sits below it,
                // so the name itself lands on the centre line rather than the
                // group's midpoint landing there. Measured on the device: the
                // mark and its gap are what the name has to be pushed down past.
                Spacer(Modifier.size(markSize + WarpSpace.large))
            }
        }
    }
}

/** Hairline, no fill — a suggestion should invite, not compete with the answer. */
@Composable
private fun SuggestionPill(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(HairlineWidth, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = WarpSpace.large, vertical = 10.dp),
        )
    }
}


/**
 * The mark that stands in front of everything the AI says.
 *
 * It turns while the model is working, so "Warp is thinking" is shown by Warp's
 * own symbol rather than by a generic spinner beside it.
 */
@Composable
private fun MessageMark(thinking: Boolean, modifier: Modifier = Modifier) {
    if (thinking) {
        ThinkingMark(size = 20.dp, modifier = modifier)
    } else {
        WarpMark(size = 20.dp, modifier = modifier)
    }
}

@Composable
private fun MessageItem(
    message: ChatMessage,
    markModifier: Modifier = Modifier,
    asking: PermissionRequest? = null,
    onDecide: (Decision) -> Unit = {},
) {
    if (message.role == Role.USER) {
        UserMessage(message)
    } else {
        AssistantMessage(message, markModifier, asking, onDecide)
    }
}

@Composable
private fun UserMessage(message: ChatMessage) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun AssistantMessage(
    message: ChatMessage,
    markModifier: Modifier = Modifier,
    asking: PermissionRequest? = null,
    onDecide: (Decision) -> Unit = {},
) {
    val working = message.streaming && message.text.isEmpty() && message.toolCalls.isEmpty()

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        MessageMark(thinking = working, modifier = markModifier)
        Spacer(Modifier.size(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Before the first token there is nothing to read, so name what is
            // happening rather than leaving an empty space.
            if (working) ThinkingLine()

            // Markdown, not plain text. Only the assistant's side: what you
            // typed is what you typed, and reinterpreting somebody's own words
            // as formatting is how a message about `**` loses its asterisks.
            if (message.text.isNotEmpty()) {
                MarkdownText(message.text)
            }

            message.toolCalls.forEach { call ->
                Spacer(Modifier.size(10.dp))
                ToolCard(
                    call,
                    // Only the card being asked about grows buttons. Every card
                    // showing them would be a screen full of Allow, which is
                    // how Allow stops meaning anything.
                    asking = asking?.takeIf { it.callId == call.id },
                    onDecide = onDecide,
                )
            }

            message.error?.let { error ->
                Spacer(Modifier.size(10.dp))
                ErrorCard(error)
            }
        }
    }
}

@Composable
private fun ThinkingLine() {
    // No spinner beside it: the mark itself is already turning, and two things
    // spinning at once reads as clutter.
    Text(
        "Thinking…",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ToolCard(
    call: ToolCall,
    asking: PermissionRequest? = null,
    onDecide: (Decision) -> Unit = {},
) {
    // Collapsed by default, and only openable when there is something inside.
    // The summary line is the point of the card — four hundred lines of a file
    // in the middle of a conversation is not reading, it is scrolling.
    var open by remember(call.id) { mutableStateOf(false) }
    val body = call.body?.takeIf { it.isNotBlank() }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (body == null) Modifier
                else Modifier.clickable { open = !open }
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A drawn icon rather than an emoji: emoji are another vendor's
                // artwork, they change shape per phone, and they sit at a
                // different weight to everything around them.
                Icon(
                    Icons.Outlined.Build,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(WarpSpace.small))
                Text(
                    call.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when (call.status) {
                        ToolCall.Status.PENDING -> "waiting"
                        ToolCall.Status.ASKING -> "needs your OK"
                        ToolCall.Status.RUNNING -> "running"
                        ToolCall.Status.DONE -> "done"
                        ToolCall.Status.FAILED -> "failed"
                        ToolCall.Status.DENIED -> "you said no"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (call.status) {
                        ToolCall.Status.DONE -> WarpSuccess
                        ToolCall.Status.FAILED -> MaterialTheme.colorScheme.error
                        // Not an error colour. Refusing is a normal answer, and
                        // painting it red would make saying no feel like a fault.
                        ToolCall.Status.ASKING -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.size(6.dp))
            // Arguments are JSON, so they read left to right whatever the
            // phone's language is.
            Ltr {
                Text(
                    call.argumentsJson,
                    style = WarpMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // What the tool actually did. A card that shows only what was asked
            // for stops halfway through the story.
            call.result?.let { result ->
                Spacer(Modifier.size(6.dp))
                Ltr {
                    Text(
                        result,
                        style = WarpMono,
                        color = if (call.status == ToolCall.Status.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            WarpSuccess
                        },
                    )
                }
            }
            body?.let { text ->
                Spacer(Modifier.size(8.dp))
                if (open) {
                    // Scrolls sideways rather than wrapping, for the same reason
                    // code blocks do: a wrapped path or a wrapped match line is
                    // a different string from the one on disk.
                    Ltr {
                        Text(
                            text,
                            style = WarpMono,
                            softWrap = false,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState()),
                        )
                    }
                } else {
                    // Says how much is hidden. "Show output" alone gives no way
                    // to judge whether opening it is worth the scroll.
                    Text(
                        "Show output · ${text.lines().size} lines",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            asking?.let { request ->
                Spacer(Modifier.size(10.dp))
                Text(
                    // Names the file, not the tool. "write_file wants to run" is
                    // a sentence about software; "this will change src/Main.kt"
                    // is a sentence about your work.
                    "This will change ${request.summary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Allow first and Always second, in that order on purpose.
                    // The safer answer is the one under your thumb; Always is a
                    // standing decision and should cost one extra moment.
                    //
                    // "in this chat" is on the button rather than in a footnote.
                    // A button that overstates its scope is how somebody grants
                    // more than they meant to and never finds out.
                    Button(
                        onClick = { onDecide(Decision.ONCE) },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) { Text("Allow", style = MaterialTheme.typography.labelLarge) }

                    TextButton(
                        onClick = { onDecide(Decision.ALWAYS) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text("Always in this chat", style = MaterialTheme.typography.labelLarge) }

                    Spacer(Modifier.weight(1f))

                    TextButton(
                        onClick = { onDecide(Decision.DENY) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "No",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(error: AiError) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            error.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/**
 * The composer, as one card.
 *
 * It was a pill with the send button floating outside it — two objects doing one
 * job, with a gap between them that belonged to neither. Claude, ChatGPT and
 * Gemini all put everything the message needs inside a single surface, and the
 * reason is not tidiness: what you are writing and what you are about to do with
 * it are one act, and a control that sits outside the thing it acts on has to be
 * aimed at separately.
 *
 * **The model chip moved in here from the top bar.** It is a property of the
 * message you are about to send, not of the app you are in — you change it
 * *because* of what you are about to ask. Claude puts it here for the same
 * reason, and it leaves the top bar with nothing in it but navigation.
 *
 * Attachments and voice belong here too, per the plan. They are left out until
 * they do something: a button that does nothing is worse than no button.
 */
@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    modelLabel: String,
    onPickModel: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    voice: VoiceInput?,
    /** A short answer to a command, shown above the field and then gone. */
    note: String? = null,
    onNoteShown: () -> Unit = {},
    /** Commands matching what is typed, or null when this is not a command. */
    matches: List<SlashCommand>? = null,
    onPickCommand: (SlashCommand) -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }

    val voiceState by (voice?.state ?: remember { MutableStateFlow(VoiceInput.State.Idle) })
        .collectAsState()
    val level by (voice?.level ?: remember { MutableStateFlow(0f) }).collectAsState()
    val listening = voiceState is VoiceInput.State.Listening

    // How far up the finger has travelled, as 0..1 of the cancel distance.
    var cancelProgress by remember { mutableStateOf(0f) }
    val cancelPx = with(LocalDensity.current) { CANCEL_DISTANCE.toPx() }
    val haptics = LocalHapticFeedback.current
    var buzzed by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Started straight from the callback: someone who has just granted the
        // microphone asked to talk, and making them tap the mic a second time is
        // the app forgetting what they were doing.
        if (granted) voice?.start { heard -> onValueChange(merge(value, heard)) }
    }

    val context = LocalContext.current
    fun beginListening() {
        val allowed = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        cancelProgress = 0f
        buzzed = false
        if (allowed) {
            voice?.start { heard -> onValueChange(merge(value, heard)) }
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    var pickingLanguage by remember { mutableStateOf(false) }

    if (pickingLanguage && voice != null) {
        VoiceLanguageSheet(
            current = voice.language,
            options = remember { VoiceInput.languages(context) },
            onPick = {
                voice.language = it
                pickingLanguage = false
            },
            onDismiss = { pickingLanguage = false },
        )
    }

    // Let go of the microphone if the screen goes away mid-sentence.
    DisposableEffect(voice) { onDispose { voice?.release() } }

    // Half a second in. Someone who taps the mic and starts talking immediately
    // never needs to be told about a gesture they were not going to use; someone
    // still deciding is exactly who the hint is for.
    var hintShown by remember { mutableStateOf(false) }
    LaunchedEffect(listening) {
        hintShown = false
        if (listening) {
            delay(500)
            hintShown = true
        }
    }

    // The border eases to the accent on focus rather than switching, so the
    // card wakes up instead of blinking.
    val border by animateColorAsState(
        targetValue = if (focused) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = warpTween(WarpMotion.NORMAL),
        label = "composerBorder",
    )

    // Texture, without a shadow. The plan settled long ago that depth here comes
    // from a hairline and a step in lightness rather than from a drop shadow —
    // a black shadow is invisible on a dark background and looks cheap on a
    // light one. So the card is lit instead of raised: very slightly brighter
    // along its top edge, falling away down the face, which is what a real
    // surface catching light from above actually does.
    val face = MaterialTheme.colorScheme.surfaceContainer
    val lit = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f).compositeOver(face),
            face,
        )
    )

    Column {
        // The command menu, and the answer to the last command, both above the
        // card. They belong to what is being typed, so they grow out of the
        // composer rather than covering the conversation.
        matches?.let {
            CommandMenu(
                matches = it,
                onPick = onPickCommand,
                modifier = Modifier.padding(
                    horizontal = WarpSpace.medium,
                    vertical = WarpSpace.small,
                ),
            )
        }

        note?.let { line ->
            // Goes on its own, and that is the fix for a real bug: it used to
            // clear only when the field next changed, so a confirmation sat
            // under the composer for ever if you did not type again.
            //
            // Long enough to read one short line twice. Typing still clears it
            // sooner — someone who has moved on has already read it.
            LaunchedEffect(line) {
                delay(NOTE_LINGER_MS)
                onNoteShown()
            }
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = WarpSpace.medium + WarpSpace.small,
                    vertical = WarpSpace.small,
                ),
            )
        }

        // Above the card, and only while recording. Delayed rather than instant:
        // help for someone who paused, not clutter for someone who did not.
        AnimatedVisibility(
            visible = listening && hintShown,
            enter = fadeIn(warpTween(WarpMotion.NORMAL)),
            exit = fadeOut(warpTween(WarpMotion.QUICK)),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            CancelHint(progress = cancelProgress)
        }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WarpSpace.medium, vertical = WarpSpace.medium),
        // Rounded, not a circle. A pill only reads right around a single line,
        // and this grows to six.
        shape = RoundedCornerShape(WarpRadius.large),
        color = Color.Transparent,
        border = BorderStroke(HairlineWidth, border),
    ) {
        Box(modifier = Modifier.background(lit)) {
        // One row, not two, and the reason is the send button. It is a 52dp
        // circle, so any row holding it is 52dp tall — and a row holding only the
        // chip and the button pins them to opposite corners and leaves the middle
        // empty. Tightening the padding moved that gap by four pixels, because
        // padding was never what made it.
        //
        // Gemini and ChatGPT both put everything on one line. Claude uses two,
        // and gets away with it by filling the second row with four controls;
        // with two, the emptiness is the loudest thing in the card.
        //
        // `Bottom` rather than centred: as the message grows to six lines the
        // text rises and the controls stay where your thumb left them.
        if (listening) {
            VoiceBar(
                level = level,
                progress = cancelProgress,
                onCancel = { voice?.cancel(); cancelProgress = 0f },
                onDone = { voice?.stop() },
                // The drag lives on the whole bar rather than on one control:
                // the finger that started the recording is already somewhere on
                // this card, and asking it to find a handle first would defeat
                // the point of a gesture.
                modifier = Modifier.pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            // Only cancelling happens here. Releasing short of
                            // the threshold puts everything back and keeps
                            // listening — it must not finish the recording,
                            // because then any stray wobble on the card ends the
                            // sentence, and a finger resting on a phone always
                            // wobbles. Finishing is the tick, and only the tick.
                            if (cancelProgress >= 1f) voice?.cancel()
                            cancelProgress = 0f
                            buzzed = false
                        },
                        onDragCancel = { cancelProgress = 0f; buzzed = false },
                        onVerticalDrag = { change, delta ->
                            change.consume()
                            // Only upward travel counts, and sideways drift is
                            // ignored entirely — `detectVerticalDragGestures`
                            // reports the vertical component of whatever arc the
                            // thumb makes, which is exactly the forgiveness this
                            // gesture needs. A thumb sliding up a phone curves.
                            cancelProgress =
                                (cancelProgress - delta / cancelPx).coerceIn(0f, 1f)

                            // One buzz, at the moment it becomes true. Buzzing
                            // continuously past the line would be the phone
                            // nagging rather than telling.
                            if (cancelProgress >= 1f && !buzzed) {
                                buzzed = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            } else if (cancelProgress < 1f) {
                                buzzed = false
                            }
                        },
                    )
                },
            )
        } else {
        Row(
            modifier = Modifier.padding(WarpSpace.tiny),
            verticalAlignment = Alignment.Bottom,
        ) {
            // Far left, before the message. It is the other way into writing
            // one, so it sits where writing one starts.
            if (voice != null) {
                MicButton(
                    onClick = ::beginListening,
                    onLongClick = { pickingLanguage = true },
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    // The same 44dp as the chip and the send button, with the
                    // text centred inside it. Without the floor this box was
                    // text-height plus its own padding — about 53dp — and with
                    // everything bottom-aligned the bottoms lined up while the
                    // centres did not, leaving the message sitting visibly higher
                    // than the two controls beside it.
                    .heightIn(min = 44.dp)
                    // Asymmetric by 4dp, which lifts the centred text 2dp. An
                    // optical correction, not a fudge: centring a *line box*
                    // centres the room reserved for ascenders and descenders, so
                    // a string with descenders — "Message Warp" has g and p —
                    // always reads low beside a symmetric glyph like the send
                    // arrow. Measured at 5.5px on this screen, which is 2dp.
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = WarpSpace.tiny,
                        bottom = WarpSpace.tiny + 4.dp,
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                // BasicTextField rather than OutlinedTextField: the stock field
                // brings a label, a container and an outline that cannot be
                // fully removed, and its shape is one of the things that reads
                // as a generic Android app.
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    // The Box is load-bearing. Emitting the placeholder and the
                    // field as two siblings stacks them, so an empty composer was
                    // two lines tall instead of one — and bottom-aligned in the
                    // row, that pushed the placeholder about 5px above the chip
                    // and the send arrow. Measured, not guessed: three attempts
                    // at "centre it" failed because the box was the wrong height,
                    // not the alignment.
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            // Regular weight, and grey. Light was tried and
                            // looked like a *different typeface* rather than a
                            // quieter one — Rubik Light is thin enough that
                            // beside Medium text it stops reading as the same
                            // family. The colour is what makes a placeholder
                            // quiet; the weight is what keeps it Rubik.
                            Text(
                                "Message Warp",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        field()
                        }
                    },
                )
            }

            ModelChip(label = modelLabel, onClick = onPickModel)
            SendButton(busy = busy, enabled = busy || value.isNotBlank()) {
                if (busy) onStop() else onSend()
            }
        }
        }
        }
    }
    }
}

/**
 * The way in to talking instead of typing.
 *
 * At the far left of the card, before the message: it is the other way to write
 * one, so it belongs where writing one starts. Quiet — the same weight as the
 * model chip — because the loud control in this card is Send.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MicButton(onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "Choose language",
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Mic,
            contentDescription = "Speak",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Which language the mic listens in.
 *
 * Behind a long-press rather than sitting in the composer, because it is set
 * rarely and changing it is not part of sending a message. The mic itself is the
 * right place for it — the setting belongs to the thing it changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceLanguageSheet(
    current: String,
    options: List<Locale>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = WarpSpace.large)) {
            Text(
                "Speak in",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    start = WarpSpace.screen,
                    end = WarpSpace.screen,
                    bottom = WarpSpace.small,
                ),
            )
            Text(
                "Taken from the languages your keyboards are set up for.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = WarpSpace.screen,
                    end = WarpSpace.screen,
                    bottom = WarpSpace.medium,
                ),
            )

            options.forEach { locale ->
                val chosen = Locale.forLanguageTag(current).language == locale.language
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(locale.toLanguageTag()) }
                        .padding(horizontal = WarpSpace.screen, vertical = WarpSpace.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Named in its own language, not in the app's. Somebody
                    // looking for Hebrew is looking for "עברית".
                    Text(
                        locale.getDisplayLanguage(locale).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (chosen) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Add spoken words to what is already typed.
 *
 * Voice fills the field; it does not own it. Someone who typed half a sentence
 * and then spoke the rest meant both, and replacing what they wrote would be the
 * app deciding the earlier half was a mistake.
 */
private fun merge(existing: String, heard: String): String =
    if (existing.isBlank()) heard else existing.trimEnd() + " " + heard

/**
 * Which model will answer, as a chip you can press.
 *
 * Quiet on purpose. It has to be legible and reachable without competing with
 * the send button beside it — there is one loud control in this card and it is
 * not this one.
 */
@Composable
private fun ModelChip(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            // Matches the send button's height, so with both bottom-aligned in a
            // row that grows upward their centres stay level.
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Capped. Sharing one row with the message means a long model name
            // would otherwise eat the space you are trying to type in.
            modifier = Modifier.widthIn(max = 108.dp),
        )
        Spacer(Modifier.size(4.dp))
        Icon(
            Icons.Outlined.KeyboardArrowDown,
            contentDescription = "Change model",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SendButton(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    // Both colours ease rather than switch, so arming the button as you type is
    // a wake-up rather than a flash.
    val background by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = warpTween(WarpMotion.NORMAL),
        label = "sendFill",
    )
    val content by animateColorAsState(
        targetValue = if (enabled) Color.White
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = warpTween(WarpMotion.NORMAL),
        label = "sendInk",
    )

    Surface(
        shape = CircleShape,
        color = background,
        // A hairline when idle rather than a filled grey disc. A grey circle
        // reads as broken; an outline reads as waiting.
        border = if (enabled) null
        else BorderStroke(HairlineWidth, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(44.dp),
        onClick = onClick,
        enabled = enabled,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (busy) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send,
                contentDescription = if (busy) "Stop" else "Send",
                tint = content,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private val SUGGESTIONS = listOf("Weather app", "Todo app")
