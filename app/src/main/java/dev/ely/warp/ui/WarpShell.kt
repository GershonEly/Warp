package dev.ely.warp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ely.warp.data.Conversation
import dev.ely.warp.ui.theme.HairlineWidth
import dev.ely.warp.ui.theme.WarpMotion
import dev.ely.warp.ui.theme.WarpRadius
import dev.ely.warp.ui.theme.WarpSpace
import dev.ely.warp.ui.theme.warpTween
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long the undo bar stays up after a delete.
 *
 * Six seconds. Long enough to read a bar you were not expecting and reach it;
 * short enough that it is gone before you have started doing something else.
 */
private const val UNDO_VISIBLE_MS = 6_000L

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
    conversations: List<Conversation> = emptyList(),
    openConversationId: String? = null,
    onOpenConversation: (String) -> Unit = {},
    onRenameConversation: (String, String) -> Unit = { _, _ -> },
    onPinConversation: (String, Boolean) -> Unit = { _, _ -> },
    onDeleteConversation: (String) -> Unit = {},
    onUndoDelete: (String) -> Unit = {},
    content: @Composable (WarpDestination) -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }

    // The long-press menu, and the rename dialog it can open. Held here rather
    // than inside the drawer because both outlive it: deleting closes the
    // drawer, and the undo has to survive that.
    var menuFor by remember { mutableStateOf<Conversation?>(null) }
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var confirmingDelete by remember { mutableStateOf<Conversation?>(null) }

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
                    conversations = conversations,
                    openConversationId = openConversationId,
                    onSelect = {
                        onDestinationChange(it)
                        scope.launch { drawerState.close() }
                    },
                    onNewChat = {
                        onNewChat()
                        scope.launch { drawerState.close() }
                    },
                    onOpenConversation = {
                        onOpenConversation(it)
                        scope.launch { drawerState.close() }
                    },
                    onConversationMenu = { menuFor = it },
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
            snackbarHost = { SnackbarHost(snackbars) },
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

    menuFor?.let { conversation ->
        ConversationMenu(
            conversation = conversation,
            onDismiss = { menuFor = null },
            onRename = {
                menuFor = null
                renaming = conversation
            },
            onTogglePin = {
                menuFor = null
                onPinConversation(conversation.id, !conversation.pinned)
            },
            onDelete = {
                menuFor = null
                confirmingDelete = conversation
            },
        )
    }

    confirmingDelete?.let { conversation ->
        DeleteDialog(
            conversation = conversation,
            onDismiss = { confirmingDelete = null },
            onConfirm = {
                confirmingDelete = null
                onDeleteConversation(conversation.id)
                // The drawer closes because the snackbar cannot be seen through
                // it — the drawer draws above the Scaffold. An undo you cannot
                // reach is not an undo.
                scope.launch {
                    drawerState.close()
                    // Indefinite plus an explicit timeout, because Material
                    // offers 4 seconds or 10 and neither is right. Four is not
                    // long enough to notice a bar you were not expecting; ten
                    // leaves it sitting over the composer long after the moment
                    // has passed.
                    val result = withTimeoutOrNull(UNDO_VISIBLE_MS) {
                        snackbars.showSnackbar(
                            message = "Deleted \"${conversation.title}\"",
                            actionLabel = "Undo",
                            withDismissAction = false,
                            duration = SnackbarDuration.Indefinite,
                        )
                    }
                    if (result == SnackbarResult.ActionPerformed) {
                        onUndoDelete(conversation.id)
                    }
                }
            },
        )
    }

    renaming?.let { conversation ->
        RenameDialog(
            conversation = conversation,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                onRenameConversation(conversation.id, name)
            },
        )
    }
}

/**
 * The long-press menu.
 *
 * A sheet rather than a dropdown: a dropdown anchors to the row it came from,
 * and a row near the bottom of a full drawer gets a menu that opens upward,
 * off-centre, and sometimes clipped. A sheet always arrives in the same place,
 * which is what makes the second use of it faster than the first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationMenu(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = WarpSpace.large),
        ) {
            Text(
                conversation.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    start = WarpSpace.screen,
                    end = WarpSpace.screen,
                    bottom = WarpSpace.medium,
                ),
            )

            MenuRow(Icons.Outlined.Edit, "Rename", onRename)
            MenuRow(
                icon = Icons.Outlined.PushPin,
                label = if (conversation.pinned) "Unpin" else "Pin to top",
                onClick = onTogglePin,
            )
            // Delete is tinted, and it is the only tinted thing here. In a menu
            // where every row looks the same, the irreversible one is a thumb's
            // width from the reversible ones.
            MenuRow(
                icon = Icons.Outlined.Delete,
                label = "Delete",
                onClick = onDelete,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = WarpSpace.screen, vertical = WarpSpace.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
        Spacer(Modifier.size(WarpSpace.medium))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/**
 * Are you sure.
 *
 * The plan originally had undo *instead of* a confirmation, on the sound
 * reasoning that a dialog is a thing people learn to tap through without
 * reading. Using it found the hole: the undo bar is visible for seconds, and if
 * you look away during those seconds there is no second chance at all. A
 * confirmation costs one tap on a rare action; missing the bar costs the
 * conversation.
 *
 * So both, and they guard different mistakes — the dialog catches the wrong tap,
 * the undo catches the right tap made too quickly. Delete is the destructive
 * button and wears the error colour; Cancel is the plain one, because the safe
 * choice should not have to be aimed for.
 */
@Composable
private fun DeleteDialog(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete conversation?") },
        text = {
            Text(
                "\"${conversation.title}\" and everything in it will be removed. " +
                    "You will have a few seconds to undo."
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Rename.
 *
 * Opens with the current name selected, so the common case — replacing it
 * outright — is one keystroke, and the rarer case of editing it is one tap.
 * An empty name is refused rather than accepted and silently replaced, because
 * a row that quietly renames itself back looks broken.
 */
@Composable
private fun RenameDialog(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember {
        mutableStateOf(
            TextFieldValue(
                text = conversation.title,
                selection = TextRange(0, conversation.title.length),
            )
        )
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.text) },
                enabled = text.text.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
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
    conversations: List<Conversation>,
    openConversationId: String?,
    onSelect: (WarpDestination) -> Unit,
    onNewChat: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onConversationMenu: (Conversation) -> Unit,
) {
    // Compact when the drawer is short — a landscape phone, or a portrait one at
    // the largest system font. Scrolling alone was not enough: everything was
    // *reachable*, but Workspace was entirely below the fold, so the drawer
    // opened showing half its purpose. Dropping the least important line and
    // tightening the vertical rhythm buys back about 100dp, which is the
    // difference between scrolling to discover a section and scrolling to
    // finish reading one.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val compact = maxHeight < 520.dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = WarpSpace.medium),
    ) {
        // Everything scrolls except the account row.
        //
        // Pinning the header as well seemed tidier and was wrong: in landscape
        // the drawer is about 400dp tall against roughly 490dp of content, and
        // holding the header and "New chat" in place spent the entire overflow
        // on the Workspace group — the label showed with nothing underneath it
        // and no sign there was anything to scroll to. Letting the header move
        // instead leaves a row half-visible at the bottom edge, which is its own
        // invitation to scroll.
        //
        // The account row stays pinned because it is the way into Settings, and
        // the way into Settings must never be the thing that falls off.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(
                    start = WarpSpace.medium,
                    top = if (compact) WarpSpace.small else WarpSpace.large,
                    bottom = if (compact) WarpSpace.small else WarpSpace.large,
                ),
            ) {
                WarpMark(size = 24.dp)
                Spacer(Modifier.size(WarpSpace.medium))
                Text("Warp", style = MaterialTheme.typography.titleMedium)
            }

            NewChatButton(onClick = onNewChat)

            Spacer(Modifier.size(if (compact) WarpSpace.small else WarpSpace.large))

            // Everything above the hairline is the conversation; everything
            // below it is the project. Two ideas instead of six flat rows.

            // Pinned first, and the label only exists when something is pinned.
            // An always-present "Pinned" heading over an empty space is a
            // permanent reminder of a feature you are not using.
            val pinned = conversations.filter { it.pinned }
            val recent = conversations.filterNot { it.pinned }

            if (pinned.isNotEmpty()) {
                SectionLabel("Pinned")
                pinned.forEach { conversation ->
                    ConversationRow(
                        title = conversation.title,
                        subtitle = conversation.subtitle(),
                        selected = current == WarpDestination.CHAT &&
                            conversation.id == openConversationId,
                        pinned = true,
                        onClick = { onOpenConversation(conversation.id) },
                        onLongClick = { onConversationMenu(conversation) },
                    )
                }
                Spacer(Modifier.size(if (compact) WarpSpace.small else WarpSpace.medium))
            }

            SectionLabel("Recent")

            if (recent.isEmpty() && pinned.isEmpty()) {
                // The chat open right now has not been saved yet — a
                // conversation is created by the first message, not by opening
                // one. Showing a row for it would be a row that vanishes when
                // you tap New chat, so the slot says what it is for instead.
                //
                // First thing dropped when space is tight: explaining an absence
                // matters less than showing the sections that are there.
                if (!compact) {
                    Text(
                        "Conversations appear here once you send a message.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            // Indented to the rows' text column (12 padding + 18
                            // icon + 12 gap), so it reads as belonging to the
                            // list rather than as a stray paragraph beside it.
                            start = 42.dp,
                            end = WarpSpace.medium,
                            top = WarpSpace.small,
                            bottom = WarpSpace.small,
                        ),
                    )
                }
            } else {
                recent.forEach { conversation ->
                    ConversationRow(
                        title = conversation.title,
                        subtitle = conversation.subtitle(),
                        // Selected means "this is the conversation on screen",
                        // which is only true while Chat is the visible screen.
                        // Leaving a row highlighted from Settings claims you are
                        // somewhere you are not.
                        selected = current == WarpDestination.CHAT &&
                            conversation.id == openConversationId,
                        onClick = { onOpenConversation(conversation.id) },
                        onLongClick = { onConversationMenu(conversation) },
                    )
                }
            }

            Spacer(Modifier.size(if (compact) WarpSpace.small else WarpSpace.medium))
            DrawerDivider()
            Spacer(Modifier.size(if (compact) WarpSpace.small else WarpSpace.medium))

            SectionLabel("Workspace")
            WarpDestination.workspace.forEach { destination ->
                DrawerRow(
                    destination = destination,
                    selected = destination == current,
                    onClick = { onSelect(destination) },
                )
            }
        }

        DrawerDivider()
        AccountRow(
            modelLabel = modelLabel,
            selected = current == WarpDestination.SETTINGS,
            onClick = { onSelect(WarpDestination.SETTINGS) },
        )
    }
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

/**
 * The second line of a conversation row.
 *
 * Time and preview together on one line, because the row is already two lines
 * tall and a third would turn a list you scan into a list you read. The time
 * leads: it is short, it is always there, and it is what tells two similarly
 * named conversations apart at a glance.
 */
private fun Conversation.subtitle(now: Long = System.currentTimeMillis()): String {
    val age = ago(now - updatedAt)
    return if (preview.isBlank()) age else "$age · $preview"
}

/**
 * A duration, as short as it can be said.
 *
 * No "ago" — in a column where every value is an age, the word is on every row
 * and tells you nothing. Days stop at a week because past that the exact number
 * has stopped meaning anything to anyone.
 */
private fun ago(millis: Long): String {
    val minutes = millis / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 60 * 24 -> "${minutes / 60}h"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d"
        else -> "${minutes / (60 * 24 * 7)}w"
    }
}

/** A conversation. Two lines: what it was about, and what answered. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    pinned: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
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
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                // Named so TalkBack announces the gesture rather than leaving
                // the only way to rename a conversation undiscoverable to
                // anyone not holding their finger down by accident.
                onLongClickLabel = "Conversation options",
            )
            .padding(horizontal = WarpSpace.medium, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A leading icon here too, even though chat lists usually have none.
        // Without it the conversation titles and the workspace labels sat on two
        // different left margins — a misalignment you feel before you can name
        // it, and one of the things that read as messy.
        Icon(
            if (pinned) Icons.Outlined.PushPin else Icons.Outlined.ChatBubbleOutline,
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
        modifier = modifier
            .fillMaxSize()
            // Scrollable for the same reason as the chat's empty state: in
            // landscape, or at the largest system font, a centred column simply
            // clips whatever does not fit.
            .verticalScroll(rememberScrollState())
            .padding(WarpSpace.section),
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
