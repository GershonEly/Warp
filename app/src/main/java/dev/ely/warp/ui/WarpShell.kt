package dev.ely.warp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ely.warp.ui.theme.HairlineWidth
import dev.ely.warp.ui.theme.WarpMotion
import dev.ely.warp.ui.theme.WarpRadius
import dev.ely.warp.ui.theme.WarpSpace
import dev.ely.warp.ui.theme.warpTween
import kotlinx.coroutines.launch

/**
 * The app shell: side drawer, top bar, content, bottom navigation.
 *
 * Structure follows the plan's chat mockup — a hamburger, the model name with a
 * chevron, an avatar, and six destinations along the bottom. Screens that do
 * not exist yet still appear, showing what belongs there rather than being
 * hidden, so the shape of the app is visible from the start.
 */
/**
 * The places you can be.
 *
 * **Editor is deliberately not here.** The plan gave Chat and Editor equal
 * billing, copied from desktop IDEs without asking whether the reasoning
 * survives the move to a phone. On a desktop the editor is the app and chat is
 * a side panel; on a phone it is the other way round, and not by a small margin
 * — nobody writes Kotlin on a phone keyboard. An editor is still needed, to
 * read the file the AI just changed or fix one line by hand, but that is
 * something you *open*, not somewhere you *go*. It arrives as an overlay
 * launched from a file.
 *
 * A row in a drawer is a promise that you might want to start your day there.
 *
 * The `blurb` is not decoration: six unexplained nouns in a column was most of
 * what read as messy. Every row says what it is for.
 */
enum class WarpDestination(
    val label: String,
    val blurb: String,
    val icon: ImageVector,
) {
    // Outlined throughout: filled icons are heavier than the mark's own line
    // and mixing the two weights is one of the quiet things that reads as
    // unfinished.
    CHAT("Chat", "Ask, and it builds", Icons.Outlined.ChatBubbleOutline),
    FILES("Files", "Browse the project", Icons.Outlined.FolderOpen),
    BUILD("Build", "Compile and install", Icons.Outlined.Build),
    ASSETS("Assets", "Icons and images", Icons.Outlined.Palette),
    SETTINGS("Settings", "Keys, models, theme", Icons.Outlined.Settings),
    ;

    companion object {
        /** What the drawer lists under "Workspace" — Chat and Settings live elsewhere. */
        val workspace = listOf(FILES, BUILD, ASSETS)
    }
}

@Composable
fun WarpShell(
    title: String,
    onTitleClick: () -> Unit,
    onNewChat: () -> Unit,
    destination: WarpDestination,
    onDestinationChange: (WarpDestination) -> Unit,
    content: @Composable (WarpDestination) -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                // 320dp, not Material's stock 360. The stock width leaves a
                // sliver of the screen showing that is too narrow to read and
                // too wide to ignore.
                modifier = Modifier.width(320.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerShape = RoundedCornerShape(
                    topEnd = WarpRadius.large,
                    bottomEnd = WarpRadius.large,
                ),
            ) {
                DrawerContents(
                    current = destination,
                    modelLabel = title,
                    onSelect = {
                        onDestinationChange(it)
                        scope.launch { drawerState.close() }
                    },
                    onNewChat = {
                        onNewChat()
                        scope.launch { drawerState.close() }
                    },
                )
            }
        },
    ) {
        Scaffold(
            // The keyboard is handled once, for the whole shell, so the bars
            // ride above it. Scaffold cannot do this itself: with a bottom bar
            // present it gives content the bar's height instead of the insets,
            // and the keyboard inset is dropped.
            modifier = Modifier.imePadding(),
            // Bottom only. The top bar pads itself and the shell handles the
            // keyboard, but with no bottom bar left there is nothing holding
            // content off the gesture area — the composer sat on top of it.
            contentWindowInsets = WindowInsets.navigationBars,
            topBar = {
                WarpTopBar(
                    title = title,
                    onMenu = { scope.launch { drawerState.open() } },
                    onTitleClick = onTitleClick,
                    onAvatarClick = { onDestinationChange(WarpDestination.SETTINGS) },
                )
            },
            // No bottom navigation. Six stock items with labels is the single
            // most dated thing an Android app can wear, and it costs a sixth of
            // the screen permanently. Gemini, Claude and ChatGPT all put
            // navigation in the drawer and give the conversation the whole
            // surface — because the conversation is the app.
        ) { inner ->
            // Built here rather than inside transitionSpec: these read the
            // reduce-animations setting, which is only available in composable
            // scope, and the transition block is not one.
            val slide = warpTween<IntOffset>(WarpMotion.SLOW, WarpMotion.Standard)
            val fadeUp = warpTween<Float>(WarpMotion.NORMAL, WarpMotion.Enter)
            val fadeAway = warpTween<Float>(WarpMotion.QUICK, WarpMotion.Exit)

            Box(modifier = Modifier.fillMaxSize().padding(inner)) {
                // Screens slide along one axis, so moving right along the bar
                // feels like moving right — you keep your sense of place.
                AnimatedContent(
                    targetState = destination,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        val direction = if (forward) 1 else -1
                        (
                            slideInHorizontally(slide) { width -> width / 6 * direction } +
                                fadeIn(fadeUp)
                            ) togetherWith (
                            slideOutHorizontally(slide) { width -> -width / 6 * direction } +
                                fadeOut(fadeAway)
                            ) using SizeTransform(clip = false)
                    },
                    label = "screen",
                ) { screen ->
                    content(screen)
                }
            }
        }
    }
}

@Composable
private fun WarpTopBar(
    title: String,
    onMenu: () -> Unit,
    onTitleClick: () -> Unit,
    onAvatarClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Scaffold applies contentWindowInsets to its content slot only —
            // topBar and bottomBar must inset themselves, or they draw at y=0
            // underneath the clock and battery.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButtonBox(onClick = onMenu) {
            Icon(
                Icons.Outlined.Menu,
                contentDescription = "Menu",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The model name doubles as the picker button, as in the plan's mockup.
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onTitleClick)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                "▾",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Tappable. It is a filled circle sized exactly like a button, and for
        // weeks it did nothing at all — which is the clearest possible case of
        // the rule now held everywhere: **shape is a promise.** A pill, a
        // circle, or a filled surface says "press me", and if something looked
        // like a button then someone wanted it to be one. The honest fix is
        // almost never to restyle it.
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onAvatarClick)
                .padding(6.dp),
        ) {
            Avatar(size = 30.dp)
        }
    }
}

/** Stands in for a profile picture until there are accounts. */
@Composable
private fun Avatar(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "E",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun IconButtonBox(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .padding(4.dp)
            .size(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * The drawer.
 *
 * Rebuilt from a **navigation menu** into a **conversation list**, which is what
 * the drawer of a chat app actually is — Claude, ChatGPT and Gemini all do this,
 * and it is why theirs feel like a place and the old one felt like a settings
 * screen. Six identical rows mixing places you work, things you do, and an
 * app-level setting, all weighted the same, was most of what read as messy.
 *
 * Chat is not a row: tapping a conversation *is* going to chat. A "Chat" row
 * beside a list of chats is the kind of redundancy that reads as clutter without
 * anyone being able to name it.
 *
 * Recent is a real, labelled slot even though conversations do not persist yet.
 * A section that says what it will hold beats a paragraph apologising at the
 * bottom of the screen, and when storage arrives the list drops straight in with
 * nothing redesigned twice.
 */
@Composable
private fun DrawerContents(
    current: WarpDestination,
    modelLabel: String,
    onSelect: (WarpDestination) -> Unit,
    onNewChat: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = WarpSpace.medium),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                start = WarpSpace.medium,
                top = WarpSpace.large,
                bottom = WarpSpace.large,
            ),
        ) {
            WarpMark(size = 24.dp)
            Spacer(Modifier.size(WarpSpace.medium))
            Text("Warp", style = MaterialTheme.typography.titleMedium)
        }

        NewChatButton(onClick = onNewChat)

        Spacer(Modifier.size(WarpSpace.large))

        // Everything above the hairline is the conversation; everything below it
        // is the project. Two ideas instead of six flat rows.
        SectionLabel("Recent")
        ConversationRow(
            title = "Current session",
            subtitle = modelLabel,
            selected = current == WarpDestination.CHAT,
            onClick = { onSelect(WarpDestination.CHAT) },
        )
        Text(
            "Older conversations are kept once project storage lands.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                // Indented to the rows' text column (12 padding + 18 icon + 12
                // gap), so it reads as belonging to the list rather than as a
                // stray paragraph beside it.
                start = 42.dp,
                end = WarpSpace.medium,
                top = WarpSpace.small,
                bottom = WarpSpace.small,
            ),
        )

        Spacer(Modifier.size(WarpSpace.medium))
        DrawerDivider()
        Spacer(Modifier.size(WarpSpace.medium))

        SectionLabel("Workspace")
        WarpDestination.workspace.forEach { destination ->
            DrawerRow(
                destination = destination,
                selected = destination == current,
                onClick = { onSelect(destination) },
            )
        }

        Spacer(Modifier.weight(1f))

        DrawerDivider()
        AccountRow(
            modelLabel = modelLabel,
            selected = current == WarpDestination.SETTINGS,
            onClick = { onSelect(WarpDestination.SETTINGS) },
        )
    }
}

/**
 * The primary action, and the only filled control in the drawer.
 *
 * One unmissable thing to press. If everything is emphasised, nothing is.
 */
@Composable
private fun NewChatButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WarpRadius.small))
            // Solid `primary`, not `primaryContainer`. The selected row already
            // wears primaryContainer, and when the button wore it too the two
            // were indistinguishable — the primary action has to outrank a
            // selection state, or "the only filled thing" means nothing.
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            // `medium`, matching every row below it, so the icon column runs
            // straight down the whole drawer.
            .padding(horizontal = WarpSpace.medium, vertical = WarpSpace.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Add,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(Modifier.size(WarpSpace.medium))
        Text(
            "New chat",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** Small, upper, quiet — present enough to group, easy enough to scan past. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = WarpSpace.medium, bottom = WarpSpace.small),
    )
}

@Composable
private fun DrawerDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WarpSpace.small)
            .height(HairlineWidth)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/** A conversation. Two lines: what it was about, and what answered. */
@Composable
private fun ConversationRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
        else Color.Transparent,
        animationSpec = warpTween(WarpMotion.NORMAL),
        label = "conversationRow",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 12dp, not the stock 56dp-tall full-round pill. Material's default
            // drawer row is the most recognisable "this is a template" signal in
            // the whole framework.
            .clip(RoundedCornerShape(WarpRadius.small))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = WarpSpace.medium, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A leading icon here too, even though chat lists usually have none.
        // Without it the conversation titles and the workspace labels sat on two
        // different left margins — a misalignment you feel before you can name
        // it, and one of the things that read as messy.
        Icon(
            Icons.Outlined.ChatBubbleOutline,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(WarpSpace.medium))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The account row, pinned to the bottom.
 *
 * Where a thumb rests, where every other app puts it, and the reason the avatar
 * in the top bar finally leads somewhere.
 */
@Composable
private fun AccountRow(modelLabel: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = WarpSpace.small)
            .clip(RoundedCornerShape(WarpRadius.small))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = WarpSpace.medium, vertical = WarpSpace.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(size = 32.dp)
        Spacer(Modifier.size(WarpSpace.medium))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Ely",
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                modelLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DrawerRow(
    destination: WarpDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // The selected row sits in a filled pill — the accent's only appearance in
    // navigation, which is what keeps it meaning "here" rather than "decor".
    val background by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
        else Color.Transparent,
        animationSpec = warpTween(WarpMotion.NORMAL),
        label = "drawerRow",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WarpRadius.small))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = WarpSpace.medium, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            destination.icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(WarpSpace.medium))
        Column {
            Text(
                destination.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
            // The line that stops a row being an unexplained noun. Six of those
            // in a column was most of what read as messy.
            Text(
                destination.blurb,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A screen that does not exist yet.
 *
 * Shown rather than hidden so the app's intended shape is visible, and so a tab
 * that leads nowhere says why instead of looking broken.
 */
@Composable
fun ComingSoonScreen(title: String, description: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(WarpSpace.section),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The mark, dimmed. An empty screen that shows nothing reads as broken;
        // one that shows the app's own symbol and says what belongs here reads
        // as deliberate.
        WarpMark(
            size = 44.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        )
        Spacer(Modifier.size(WarpSpace.large))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(WarpSpace.small))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
