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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
enum class WarpDestination(val label: String, val icon: ImageVector) {
    // Outlined throughout: filled icons are heavier than the mark's own line
    // and mixing the two weights is one of the quiet things that reads as
    // unfinished.
    CHAT("Chat", Icons.Outlined.ChatBubbleOutline),
    FILES("Files", Icons.Outlined.FolderOpen),
    EDITOR("Editor", Icons.Outlined.Code),
    BUILD("Build", Icons.Outlined.Build),
    ASSETS("Assets", Icons.Outlined.Palette),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

@Composable
fun WarpShell(
    title: String,
    onTitleClick: () -> Unit,
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
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerShape = RoundedCornerShape(
                    topEnd = WarpRadius.large,
                    bottomEnd = WarpRadius.large,
                ),
            ) {
                DrawerContents(
                    current = destination,
                    onSelect = {
                        onDestinationChange(it)
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
private fun WarpTopBar(title: String, onMenu: () -> Unit, onTitleClick: () -> Unit) {
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

        Avatar()
    }
}

/** Stands in for a profile picture until there are accounts. */
@Composable
private fun Avatar() {
    Box(
        modifier = Modifier
            .padding(6.dp)
            .size(30.dp)
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

@Composable
private fun DrawerContents(
    current: WarpDestination,
    onSelect: (WarpDestination) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(WarpSpace.large),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = WarpSpace.small, bottom = WarpSpace.section),
        ) {
            WarpMark(size = 28.dp)
            Spacer(Modifier.size(WarpSpace.medium))
            Text("Warp", style = MaterialTheme.typography.titleLarge)
        }

        WarpDestination.entries.forEach { destination ->
            DrawerRow(
                destination = destination,
                selected = destination == current,
                onClick = { onSelect(destination) },
            )
        }

        Spacer(Modifier.weight(1f))

        Text(
            "Saved conversations arrive with project storage.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(WarpSpace.medium),
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
            .padding(vertical = 2.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = WarpSpace.large, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            destination.icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(WarpSpace.large))
        Text(
            destination.label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
        )
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
