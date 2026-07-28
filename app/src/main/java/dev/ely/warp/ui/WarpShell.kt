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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ely.warp.ui.theme.WarpMotion
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
    CHAT("Chat", Icons.Filled.Chat),
    FILES("Files", Icons.Filled.Folder),
    EDITOR("Editor", Icons.Filled.Edit),
    BUILD("Build", Icons.Filled.Build),
    ASSETS("Assets", Icons.Filled.Palette),
    SETTINGS("Settings", Icons.Filled.Settings),
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
            ModalDrawerSheet {
                DrawerContents(onClose = { scope.launch { drawerState.close() } })
            }
        },
    ) {
        Scaffold(
            // safeDrawing covers status bar, navigation bar, cutout and the
            // keyboard in one place — handled here so no screen has to.
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                WarpTopBar(
                    title = title,
                    onMenu = { scope.launch { drawerState.open() } },
                    onTitleClick = onTitleClick,
                )
            },
            bottomBar = {
                WarpBottomBar(
                    current = destination,
                    onSelect = onDestinationChange,
                )
            },
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
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButtonBox(onClick = onMenu) {
            Icon(
                Icons.Filled.Menu,
                contentDescription = "Conversations",
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
private fun WarpBottomBar(current: WarpDestination, onSelect: (WarpDestination) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        // The bar sits inside the Scaffold's insets, so it must not add its own.
        windowInsets = WindowInsets(0),
    ) {
        WarpDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = destination == current,
                onClick = { onSelect(destination) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = {
                    Text(destination.label, style = MaterialTheme.typography.labelSmall)
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun DrawerContents(onClose: () -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WarpMark(size = 26.dp)
            Spacer(Modifier.size(10.dp))
            Text(
                "Warp",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.size(16.dp))
        Text(
            "Conversations",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "Saved conversations arrive with project storage. For now Warp keeps " +
                "one chat, which lasts as long as the app is open.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
