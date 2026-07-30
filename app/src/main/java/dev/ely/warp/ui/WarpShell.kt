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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ely.warp.data.ConversationOrder
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import dev.ely.warp.data.Conversation
import dev.ely.warp.data.Folder
import dev.ely.warp.data.Identity
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
    onNewChat: () -> Unit,
    /** Sits in the middle of the top bar. Empty for most screens. */
    topBarCenter: @Composable () -> Unit = {},
    destination: WarpDestination,
    onDestinationChange: (WarpDestination) -> Unit,
    conversations: List<Conversation> = emptyList(),
    folders: List<Folder> = emptyList(),
    searchQuery: String = "",
    searchResults: List<Conversation> = emptyList(),
    searchOrder: ConversationOrder = ConversationOrder.RECENT,
    onSearchChange: (String) -> Unit = {},
    onSearchOrderChange: (ConversationOrder) -> Unit = {},
    collapsedFolders: Set<String> = emptySet(),
    openConversationId: String? = null,
    onOpenConversation: (String) -> Unit = {},
    onRenameConversation: (String, String) -> Unit = { _, _ -> },
    onPinConversation: (String, Boolean) -> Unit = { _, _ -> },
    onDeleteConversation: (String) -> Unit = {},
    onUndoDelete: (String) -> Unit = {},
    onMoveConversation: (String, String?) -> Unit = { _, _ -> },
    onToggleFolder: (String) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onRenameFolder: (String, String) -> Unit = { _, _ -> },
    onDeleteFolder: (Folder) -> Unit = {},
    onReorderFolders: (List<Folder>) -> Unit = {},
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
    var moving by remember { mutableStateOf<Conversation?>(null) }
    var managingFolders by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                // 320dp, not Material's stock 360. The stock width leaves a
                // sliver of the screen showing that is too narrow to read and
                // too wide to ignore.
                modifier = Modifier.width(320.dp).drawerGlass(),
                // Transparent, because `drawerGlass` paints the panel. A colour
                // here would sit on top of the gradient and flatten it back out.
                drawerContainerColor = Color.Transparent,
                drawerShape = RoundedCornerShape(
                    topEnd = WarpRadius.large,
                    bottomEnd = WarpRadius.large,
                ),
            ) {
                DrawerContents(
                    current = destination,
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
                    searchQuery = searchQuery,
                    searchResults = searchResults,
                    searchOrder = searchOrder,
                    onSearchChange = onSearchChange,
                    onSearchOrderChange = onSearchOrderChange,
                    folders = folders,
                    collapsedFolders = collapsedFolders,
                    onToggleFolder = onToggleFolder,
                    onManageFolders = {
                        managingFolders = true
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
            // Transparent, because the ambient light is drawn at the root and
            // Scaffold otherwise paints its own opaque background straight over
            // it. That is what made the wash disappear the moment it stopped
            // being a modifier on the chat screen and became one on the app.
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbars) },
            topBar = {
                WarpTopBar(
                    onMenu = { scope.launch { drawerState.open() } },
                    onAvatarClick = { onDestinationChange(WarpDestination.SETTINGS) },
                    center = topBarCenter,
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
            onMove = {
                menuFor = null
                moving = conversation
            },
            onDelete = {
                menuFor = null
                confirmingDelete = conversation
            },
        )
    }

    moving?.let { conversation ->
        MoveToFolderSheet(
            conversation = conversation,
            folders = folders,
            onDismiss = { moving = null },
            onPick = { folderId ->
                moving = null
                onMoveConversation(conversation.id, folderId)
            },
            onManageFolders = {
                moving = null
                managingFolders = true
            },
        )
    }

    if (managingFolders) {
        ManageFoldersSheet(
            folders = folders,
            onDismiss = { managingFolders = false },
            onCreate = onCreateFolder,
            onRename = onRenameFolder,
            onDelete = onDeleteFolder,
            onReorder = onReorderFolders,
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
    onMove: () -> Unit,
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
            MenuRow(Icons.Outlined.FolderOpen, "Move to folder", onMove)
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
    selected: Boolean = false,
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
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Marks where the conversation already is, so the sheet answers "where
        // is this?" as well as "where should it go?".
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Where to put this conversation.
 *
 * Recent is offered as a destination rather than as a "remove from folder"
 * action, because that is what it is — the place conversations live when they
 * are not filed. Naming it as a choice means moving out of a folder and moving
 * between folders are the same gesture instead of two different ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveToFolderSheet(
    conversation: Conversation,
    folders: List<Folder>,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
    onManageFolders: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = WarpSpace.large),
        ) {
            Text(
                "Move to",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    start = WarpSpace.screen,
                    end = WarpSpace.screen,
                    bottom = WarpSpace.medium,
                ),
            )

            MenuRow(
                icon = Icons.Outlined.ChatBubbleOutline,
                label = "Recent",
                onClick = { onPick(null) },
                selected = conversation.folderId == null,
            )

            folders.forEach { folder ->
                MenuRow(
                    icon = Icons.Outlined.FolderOpen,
                    label = folder.name,
                    onClick = { onPick(folder.id) },
                    selected = conversation.folderId == folder.id,
                )
            }

            // The way out of an empty list. Without it, someone who has never
            // made a folder opens this and finds one row that does nothing they
            // wanted, with no hint that folders are a thing they can create.
            MenuRow(
                icon = Icons.Outlined.CreateNewFolder,
                label = if (folders.isEmpty()) "New folder" else "Manage folders",
                onClick = onManageFolders,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
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
    onMenu: () -> Unit,
    onAvatarClick: () -> Unit,
    center: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Scaffold applies contentWindowInsets to its content slot only —
            // topBar and bottomBar must inset themselves, or they draw at y=0
            // underneath the clock and battery.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(horizontal = WarpSpace.small, vertical = WarpSpace.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Inside a filled circle rather than bare on the background. A lone icon
        // floating on a dark screen has nothing holding it and reads as a mark
        // rather than a control — giving it a surface is most of what gives all
        // three reference apps' bars their structure.
        IconButtonBox(onClick = onMenu) {
            Icon(
                Icons.Outlined.Menu,
                contentDescription = "Menu",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The model name used to live here and has moved into the composer,
        // where it belongs: it is a property of the message you are about to
        // send, not of the app you are in. What is left is a slot, used by the
        // one notice that has to sit above everything — see `topBarCenter`.
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) { center() }

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
                .padding(WarpSpace.tiny),
        ) {
            Avatar(size = 32.dp)
        }
    }
}

/**
 * Stands in for a profile picture until there are accounts.
 *
 * The letter comes from the name you set, and there is no fallback letter. A
 * circle holding "U" for "User" is a worse answer than a circle holding Warp's
 * own mark, and the mark is always true. This used to be the literal `"E"`,
 * which greeted everyone who sideloads Warp as its author.
 */
@Composable
private fun Avatar(size: Dp) {
    val initial = currentInitial()

    Box(
        modifier = Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (initial == null) {
            // A plain person glyph, not Warp's mark. The mark has fine internal
            // lines and at 18dp inside a circle it collapses into a blue blob —
            // seen on the phone, and unreadable. The mark keeps the places big
            // enough to show it.
            Icon(
                Icons.Outlined.Person,
                contentDescription = null,
                modifier = Modifier.size(size * 0.6f),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            Text(
                initial,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * The avatar letter, kept current.
 *
 * Collected from [Identity]'s flow rather than read inside `remember`. The
 * first version did the latter and cached it: you set your name in Settings,
 * came back, and the avatar still showed no name because nothing had told it to
 * look again.
 */
@Composable
private fun currentInitial(): String? {
    val context = LocalContext.current
    val identity = remember { Identity.get(context) }
    val name by identity.name.collectAsState()
    return Identity.initialOf(name)
}

@Composable
private fun IconButtonBox(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .padding(WarpSpace.tiny)
            .size(40.dp)
            // Clipped before the background, or the ripple spreads square out of
            // a round button.
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * The drawer, lit.
 *
 * It was a flat slab with a hard edge sliding over another flat slab, and that
 * was the largest single reason the surface read as cheap — not the type, not the
 * spacing. A panel that slides over an app should look like a panel, which means
 * it has to catch light somewhere.
 *
 * Three things, all cheap:
 *
 * - **A vertical fall** from a step above the surface colour at the top to the
 *   surface colour at the bottom. This is what a real panel lit from above does,
 *   and one step is enough — two looks like a button.
 * - **A bloom behind the mark**, so the drawer has a light source of its own
 *   rather than borrowing the chat's.
 * - **A bright hairline down the leading edge.** One line, and it is the whole
 *   glass-panel cue: an edge that catches light is an edge that is *in front of*
 *   something.
 */
@Composable
private fun Modifier.drawerGlass(): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    val top = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f).compositeOver(surface)
    val bloom = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val edge = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)

    return drawBehind {
        drawRect(Brush.verticalGradient(listOf(top, surface)))

        // Behind the mark and the navigation, off the left edge so only the
        // falloff shows — the same trick as the aurora, for the same reason.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(bloom, Color.Transparent),
                center = Offset(size.width * 0.1f, size.height * 0.06f),
                radius = size.width * 0.9f,
            )
        )

        drawRect(
            color = edge,
            topLeft = Offset(size.width - 1f, 0f),
            size = androidx.compose.ui.geometry.Size(1f, size.height),
        )
    }
}

/**
 * The drawer, third version.
 *
 * The second was a conversation list, which was the right idea and the wrong
 * execution: with real content in it, four small grey uppercase labels stacked
 * in one column read as a terminal. Six screens from Gemini, ChatGPT and Claude
 * were compared against it, and they agree on eight things — see
 * [§9g](IMPLEMENTATION_PLAN.md). The ones that shape this file:
 *
 * - **Navigation on top, conversations below.** The top group is short and
 *   fixed; the list is long and grows. Underneath, the fixed thing walks further
 *   down the screen every week — which is exactly why this used to need a
 *   compact mode to survive landscape, and why it no longer does.
 * - **Navigation is quieter than the list.** The conversations are the bright
 *   thing. Warp had these the same weight, so the drawer argued with itself
 *   about what it was for.
 * - **One line per conversation, no icon, no preview.** Two lines plus an icon
 *   meant five conversations filled the room nine should, and two chats with
 *   similar previews read as the same chat twice.
 * - **Sentence case at reading size.** "Pinned", not `PINNED`.
 */
@Composable
private fun DrawerContents(
    current: WarpDestination,
    conversations: List<Conversation>,
    folders: List<Folder>,
    collapsedFolders: Set<String>,
    searchQuery: String,
    searchResults: List<Conversation>,
    searchOrder: ConversationOrder,
    onSearchChange: (String) -> Unit,
    onSearchOrderChange: (ConversationOrder) -> Unit,
    openConversationId: String?,
    onSelect: (WarpDestination) -> Unit,
    onNewChat: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onConversationMenu: (Conversation) -> Unit,
    onToggleFolder: (String) -> Unit,
    onManageFolders: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = WarpSpace.medium),
    ) {
        // Everything scrolls except the bottom bar. There is no compact mode any
        // more: it existed because Workspace sat under a growing list and fell
        // off the fold on a short screen. With the fixed group on top, the thing
        // that scrolls is the thing that is supposed to.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
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
                // The wordmark, and the one place in the drawer that gets to be
                // heavy. A brand name set at the same weight as a menu item is a
                // brand name nobody reads as one.
                Text(
                    "Warp",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Search sits directly under the wordmark, and it is the largest
            // control in the drawer.
            //
            // Everything else here is a way of *browsing* what you have; this is
            // the way of *finding* it, and finding beats browsing the moment
            // there are more conversations than fit on a screen. Putting it
            // fourth, in a row the size of the others, made it a thing you had to
            // notice rather than a thing you reach for.
            SearchField(query = searchQuery, onChange = onSearchChange)

            Spacer(Modifier.size(WarpSpace.small))

            // Fixed rows above a growing list, for the same structural reason
            // navigation is: below the list they walk a little further down the
            // drawer with every conversation, until the day you have enough of
            // them that the only way to make a folder is to scroll past
            // everything you were trying to organise.
            DrawerRowPlain(
                icon = Icons.Outlined.CreateNewFolder,
                label = if (folders.isEmpty()) "New folder" else "Manage folders",
                onClick = onManageFolders,
            )

            WarpDestination.workspace.forEach { destination ->
                DrawerRow(
                    destination = destination,
                    selected = destination == current,
                    onClick = { onSelect(destination) },
                )
            }

            Spacer(Modifier.size(WarpSpace.medium))
            DrawerDivider()
            Spacer(Modifier.size(WarpSpace.large))

            // Pinned first, and the label only exists when something is pinned.
            // An always-present "Pinned" heading over an empty space is a
            // permanent reminder of a feature you are not using.
            val pinned = conversations.filter { it.pinned }
            // Pinning outranks filing. A pinned conversation is at the top
            // because you put it there, and a folder quietly reclaiming it would
            // undo the one thing pinning is for.
            val filed = conversations.filterNot { it.pinned }.groupBy { it.folderId }
            val recent = filed[null].orEmpty()

            if (searchQuery.isNotBlank()) {
                // Search replaces the list rather than filtering it in place.
                // Pinned, folders and Recent are ways of organising conversations
                // you already know about; a search is a question about all of
                // them at once, and answering it inside those groupings would
                // scatter three matches across three headings.
                // The count and the ordering on one line, and the ordering
                // exists only here. Outside a search the drawer's arrangement is
                // its own — pinned above folders above recent — and a sort
                // control there would be offering to break it.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel(
                        if (searchResults.isEmpty()) "No matches"
                        else "${searchResults.size} found"
                    )
                    Spacer(Modifier.weight(1f))
                    if (searchResults.size > 1) {
                        SortToggle(order = searchOrder, onChange = onSearchOrderChange)
                    }
                }
                searchResults.forEach { conversation ->
                    ConversationRow(
                        title = conversation.title,
                        selected = current == WarpDestination.CHAT &&
                            conversation.id == openConversationId,
                        pinned = conversation.pinned,
                        onClick = { onOpenConversation(conversation.id) },
                        onLongClick = { onConversationMenu(conversation) },
                    )
                }
                Spacer(Modifier.size(WarpSpace.section))
                return@Column
            }

            if (pinned.isNotEmpty()) {
                SectionLabel("Pinned")
                pinned.forEach { conversation ->
                    ConversationRow(
                        title = conversation.title,
                        selected = current == WarpDestination.CHAT &&
                            conversation.id == openConversationId,
                        pinned = true,
                        onClick = { onOpenConversation(conversation.id) },
                        onLongClick = { onConversationMenu(conversation) },
                    )
                }
                Spacer(Modifier.size(WarpSpace.large))
            }

            folders.forEach { folder ->
                val inside = filed[folder.id].orEmpty()
                val open = folder.id !in collapsedFolders

                FolderHeader(
                    name = folder.name,
                    count = inside.size,
                    expanded = open,
                    onClick = { onToggleFolder(folder.id) },
                )
                if (open) {
                    inside.forEach { conversation ->
                        ConversationRow(
                            title = conversation.title,
                            selected = current == WarpDestination.CHAT &&
                                conversation.id == openConversationId,
                            onClick = { onOpenConversation(conversation.id) },
                            onLongClick = { onConversationMenu(conversation) },
                        )
                    }
                    if (inside.isEmpty()) {
                        Text(
                            "Empty",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = WarpSpace.medium + 26.dp,
                                top = WarpSpace.tiny,
                                bottom = WarpSpace.small,
                            ),
                        )
                    }
                }
            }

            if (folders.isNotEmpty()) {
                Spacer(Modifier.size(WarpSpace.large))
            }

            SectionLabel("Recent")

            if (recent.isEmpty() && pinned.isEmpty()) {
                // The chat open right now has not been saved yet — a
                // conversation is created by the first message, not by opening
                // one. Showing a row for it would be a row that vanishes when
                // you tap New chat, so the slot says what it is for instead.
                //
                Text(
                    "Conversations appear here once you send a message.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = WarpSpace.medium,
                        end = WarpSpace.medium,
                        top = WarpSpace.small,
                        bottom = WarpSpace.small,
                    ),
                )
            } else {
                recent.forEach { conversation ->
                    ConversationRow(
                        title = conversation.title,
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

            // So the last conversation is not hard against the bottom bar, and
            // so there is somewhere for the list to end.
            Spacer(Modifier.size(WarpSpace.section))
        }

        // The bottom bar: the one filled control, and the way into Settings.
        //
        // A pill rather than the old full-width button, at the bottom rather
        // than the top. It is where the thumb already rests, and a full-width
        // filled bar above a list of quiet rows was shouting the loudest thing
        // in the drawer at the moment you were trying to read the list.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = WarpSpace.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NewChatButton(onClick = onNewChat)
            Spacer(Modifier.weight(1f))
            AccountButton(
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
            // A pill that fits its label, not a bar that fills the drawer. The
            // full-width version was the loudest thing on screen at exactly the
            // moment you were trying to read the quiet list underneath it.
            .clip(CircleShape)
            // Solid `primary`, not `primaryContainer`. The selected row already
            // wears primaryContainer, and when the button wore it too the two
            // were indistinguishable — the primary action has to outrank a
            // selection state, or "the only filled thing" means nothing.
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = WarpSpace.large, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Add,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(Modifier.size(WarpSpace.small))
        Text(
            "New chat",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/**
 * The way into Settings, as an avatar.
 *
 * The full-width account row it replaces carried the model name as a subtitle,
 * which the chat's own top bar already shows — so it was a whole row spent
 * repeating something visible one tap away, sitting across the bottom of the
 * drawer in the same grey as everything else.
 */
@Composable
private fun AccountButton(selected: Boolean, onClick: () -> Unit) {
    val initial = currentInitial()

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHighest
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (initial == null) {
            Icon(
                Icons.Outlined.Person,
                contentDescription = "Settings",
                modifier = Modifier.size(20.dp),
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                initial,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A folder, as a header you can fold.
 *
 * Built to read like [SectionLabel] rather than like [ConversationRow], because
 * that is what it is — a heading over a group, not another item in the list. The
 * count sits on the right so a collapsed folder still says how much is inside;
 * without it, folding a folder hides the fact that it has anything in it.
 */
@Composable
private fun FolderHeader(
    name: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    // Rotated rather than swapped for a second icon: the turn is what says the
    // two states are the same control, and a cut animation between two glyphs
    // reads as the row being replaced.
    val turn by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = warpTween(WarpMotion.QUICK),
        label = "folderChevron",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WarpRadius.small))
            .clickable(onClick = onClick)
            .padding(horizontal = WarpSpace.medium, vertical = WarpSpace.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A folder icon, because this is a folder. The previous version had a
        // chevron alone and set the name in the same grey uppercase as Warp's
        // own section labels — so the one thing in the drawer a person had made
        // themselves was disguised as a heading the app invented, and it was the
        // only row that *is* a folder without the icon for one.
        Icon(
            Icons.Outlined.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(WarpSpace.small))
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = turn },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(WarpSpace.small))
        if (count > 0) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A drawer row that is an action rather than a place. */
@Composable
private fun DrawerRowPlain(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WarpRadius.small))
            .clickable(onClick = onClick)
            .padding(horizontal = WarpSpace.medium, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(WarpSpace.medium))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Search, across every message ever sent.
 *
 * The moment conversations persist a list becomes an archive, and an archive
 * without search is a drawer you stop opening. The index behind this — an FTS4
 * shadow of every message body — has existed since the store was written and had
 * never been read from.
 *
 * It searches **message text, not titles.** Titles are four words the model
 * guessed; the thing you actually remember is something you said.
 */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = WarpSpace.medium, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(WarpSpace.medium))
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
            if (query.isEmpty()) {
                Text(
                    "Search your chats",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Only while there is something to clear. A permanent X on an empty
        // field is a control that does nothing most of the time.
        if (query.isNotEmpty()) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Clear search",
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onChange("") }
                    .padding(2.dp)
                    .size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Newest, or by name.
 *
 * A toggle rather than a menu: there are two answers and a menu for two answers
 * is a menu that costs a tap to tell you what you already knew. The label shows
 * the order you are *in*, not the one you would switch to — a button that names
 * the thing it is not is the most reliable way to make somebody press it twice.
 *
 * Only while searching, and only with more than one result. One result has no
 * order.
 */
@Composable
private fun SortToggle(order: ConversationOrder, onChange: (ConversationOrder) -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .clickable {
                onChange(
                    if (order == ConversationOrder.RECENT) ConversationOrder.ALPHABETICAL
                    else ConversationOrder.RECENT
                )
            }
            .padding(horizontal = WarpSpace.small, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.Sort,
            contentDescription = "Change order",
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            order.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A heading over a group.
 *
 * **Sentence case at reading size**, and that is the single change that does
 * most to stop this drawer looking like a console. It used to be small, grey,
 * letterspaced and uppercase — which is fine once and reads as a command prompt
 * four times, and with pinned, a folder, recent and workspace all present there
 * were four. Gemini, ChatGPT and Claude all set theirs as ordinary words.
 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        // SemiBold, not Medium. A heading needs to outweigh the rows under it or
        // it is just another row — and with everything in the drawer previously
        // sitting between 400 and 500, changing the typeface changed nothing
        // anyone could see. A face only announces itself through contrast.
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = WarpSpace.medium,
            top = WarpSpace.small,
            bottom = WarpSpace.small,
        ),
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
 * A conversation. One line: what it was about.
 *
 * It used to carry a second line — a relative time and a slice of the last
 * message — on the reasoning that bare titles give you nothing to tell two
 * similarly named conversations apart by. On the phone the opposite happened:
 * two conversations whose last message came from the same scripted mock showed
 * *identical* second lines and read as the same chat listed twice, while every
 * row cost double the height and the list read as a wall.
 *
 * Gemini, ChatGPT and Claude all show the title alone. The title is the thing
 * being distinguished; anything under it is competing with the thing you are
 * trying to read.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    title: String,
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
            .padding(horizontal = WarpSpace.medium, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            // The bright thing in the drawer. Navigation above it is
            // deliberately dimmer: the list is what this surface is for.
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // On the far side, small, and only when pinned — a mark on the row
        // rather than a second icon column that every unpinned row pays for.
        if (pinned) {
            Spacer(Modifier.size(WarpSpace.small))
            Icon(
                Icons.Outlined.PushPin,
                contentDescription = "Pinned",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
            .padding(horizontal = WarpSpace.medium, vertical = 12.dp),
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
        // One line, and grey. Navigation is deliberately dimmer than the
        // conversations below it — that inversion is the fourth finding in §9g,
        // and Warp had it backwards: three destinations shouting as loudly as
        // the list meant the drawer argued with itself about what it was for.
        //
        // The explanatory second line went with it. It was added when six flat
        // rows made every one an unexplained noun; with three, and named Files,
        // Build and Assets, the noun explains itself, and the blurb was three
        // extra lines of grey competing with the titles it sat above.
        Text(
            destination.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
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
