package dev.ely.warp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ely.warp.ai.ChatEngine
import dev.ely.warp.ai.ConversationTitler
import dev.ely.warp.ai.ProviderRegistry
import dev.ely.warp.data.Conversation
import dev.ely.warp.data.ConversationOrder
import kotlinx.coroutines.delay
import androidx.compose.runtime.DisposableEffect
import dev.ely.warp.data.Appearance
import dev.ely.warp.data.Identity
import androidx.compose.foundation.isSystemInDarkTheme
import dev.ely.warp.debug.DebugBridge
import dev.ely.warp.data.DrawerPrefs
import dev.ely.warp.data.Rules
import dev.ely.warp.tools.PermissionDesk
import dev.ely.warp.tools.QuestionDesk
import dev.ely.warp.tools.ToolRunner
import dev.ely.warp.data.DrawerState
import dev.ely.warp.diag.DeviceProbe
import dev.ely.warp.ui.AppsScreen
import dev.ely.warp.ui.AssetsScreen
import dev.ely.warp.ui.BuildScreen
import dev.ely.warp.ui.EditorScreen
import dev.ely.warp.ui.FilesScreen
import dev.ely.warp.ui.ChatScreen
import dev.ely.warp.ui.ambientWash
import dev.ely.warp.ui.DemoChip
import dev.ely.warp.ui.Ltr
import dev.ely.warp.ui.ModelPickerDialog
import dev.ely.warp.ui.SettingsScreen
import dev.ely.warp.ui.WarpDestination
import dev.ely.warp.ui.WarpShell
import dev.ely.warp.ui.theme.grain
import dev.ely.warp.ui.theme.WarpMono
import dev.ely.warp.ui.theme.WarpSuccess
import dev.ely.warp.ui.theme.WarpTheme
import dev.ely.warp.ui.theme.WarpWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // So the price on an icon permission card is the real one. The tool that
        // draws it describes itself from its arguments alone — no Context gets
        // that far — and the card is where the money is agreed to.
        dev.ely.warp.data.ImageModels.warm(this)

        // The compiler sets itself up, at first launch, without being asked.
        //
        // It used to wait behind a button on the Build tab. Somebody given a
        // copy of Warp opened it, asked for an app, and watched the build fail
        // for a reason that appeared nowhere they would think to look — and
        // the person who gave it to them could not tell why either. The
        // compiler is not a feature you opt into; it is what the app is for,
        // and it already ships inside the APK.
        //
        // Cheap when there is nothing to do: one file check.
        dev.ely.warp.build.ToolchainInstaller.ensureInstalled(this)
        setContent {
            // Read here rather than inside the theme, so changing it in
            // Settings repaints the screen underneath the switch.
            val choice by Appearance.get(this).theme.collectAsState()
            WarpTheme(
                darkTheme = when (choice) {
                    Appearance.Theme.DARK -> true
                    Appearance.Theme.LIGHT -> false
                    Appearance.Theme.SYSTEM -> isSystemInDarkTheme()
                }
            ) {
                // Grain over everything, once, at the root. Applied here rather
                // than per surface so nothing can be missed and nothing gets it
                // twice — and because it must sit over the text as well as the
                // background, or the type looks cut out and pasted on.
                Surface(
                    modifier = Modifier.fillMaxSize().grain(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    WarpApp()
                }
            }
        }
    }
}

/**
 * The app, inside the shell.
 *
 * Chat is the front door — the build tools exist to serve it, not the other way
 * round — so it is the first destination.
 */
@Composable
private fun WarpApp() {
    var destination by remember { mutableStateOf(WarpDestination.CHAT) }
    var showPicker by remember { mutableStateOf(false) }

    // The engine lives here, not inside ChatScreen: moving between destinations
    // disposes the screen, and a conversation should survive a trip to Settings.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val registry = remember { ProviderRegistry(context) }
    // Taken from the Application rather than built here. The store outlives the
    // composition, and the startup sweep already opened it.
    val conversations = remember {
        (context.applicationContext as WarpApplication).conversations
    }
    val titler = remember { ConversationTitler(conversations, registry) }
    // The desk is held here, beside the engine, because the question it carries
    // has to outlive the card that asked it — a tool call must not be cancelled
    // by scrolling.
    val permission = remember { PermissionDesk(store = conversations) }
    val rules = remember { Rules.get(context) }
    val questions = remember { QuestionDesk() }
    // A helper runs on whatever the chat is running on, read fresh each time
    // rather than captured — switching model mid-conversation should change who
    // answers the next errand, not only the next message.
    val subagents = remember {
        dev.ely.warp.ai.SubagentRunner(
            context = context,
            provider = { registry.providerFor(registry.choice.providerId) },
            model = { registry.choice.modelId },
            // The same desk `/grill-me` uses, so choosing who does the reading
            // is one tap in the conversation rather than a trip to Settings.
            questions = questions,
            choices = { registry.modelChoices() },
        )
    }
    // One board, handed to both sides — §5j. The runner so the tools can change
    // the list, the engine so it can store it and the screen can watch it. Two
    // boards would be two answers to "what is left", and only one of them drawn.
    val taskBoard = remember { dev.ely.warp.tools.TaskBoard() }
    val toolRunner = remember {
        ToolRunner(context, permission, questions, subagents, taskBoard, conversations)
    }
    // The application's scope, not the screen's. A turn that outlives the
    // activity is the entire point of the service, and it cannot outlive a
    // scope that the activity owns.
    val turnScope = remember(context) {
        (context.applicationContext as WarpApplication).turnScope
    }
    val engine = remember {
        ChatEngine(
            turnScope,
            registry.selected,
            store = conversations,
            titler = titler,
            // Composed every turn, so a rule added mid-conversation applies to
            // the very next message rather than the next launch.
            systemPrompt = { conversationId ->
                listOfNotNull(
                    ChatEngine.DEFAULT_SYSTEM_PROMPT,
                    // Which Android this project is. Read off disk each turn
                    // rather than captured, because the answer changes the
                    // moment new_project runs — mid-conversation, on the very
                    // turn that creates the app.
                    //
                    // Told flatly, never hedged: §5o is what a model does with
                    // "you have Compose, unless you don't".
                    dev.ely.warp.brain.AndroidBrain.summaryFor(
                        compose = dev.ely.warp.build.NewProject.meta(
                            dev.ely.warp.build.Projects.forConversation(context, conversationId)
                        )?.compose == true
                    ),
                    // Composed every turn like the rules, so flipping the switch
                    // reaches the very next message. And told even when it is
                    // off, which is the entire point: a model that only knows it
                    // cannot search says "I have no internet access", which is
                    // true, unhelpful, and was said three times in one session
                    // while the user kept asking.
                    dev.ely.warp.data.WebSearch.promptLine(
                        context,
                        canSearch = registry.choice.providerId == "openrouter",
                    ),
                    rules.asPrompt(),
                ).joinToString(separator = System.lineSeparator() + System.lineSeparator())
            },
            // The engine still knows nothing about files — it is handed something
            // that can execute a call and hands back the outcome.
            tools = object : ChatEngine.ToolExecutor {
                override fun specs(mode: ChatEngine.Mode) = when (mode) {
                    ChatEngine.Mode.NORMAL -> dev.ely.warp.tools.ALL_TOOL_SPECS
                    ChatEngine.Mode.PLAN -> dev.ely.warp.tools.READ_TOOL_SPECS
                    ChatEngine.Mode.GRILL -> dev.ely.warp.tools.GRILL_TOOL_SPECS
                    ChatEngine.Mode.GOAL -> dev.ely.warp.tools.GOAL_TOOL_SPECS
                }
                // A tool you refused is not asked about again until you say
                // something new — the desk holds that, and this is the boundary.
                override fun startTurn() = permission.startTurn()

                override suspend fun execute(
                    call: dev.ely.warp.ai.ToolCall,
                    report: suspend (dev.ely.warp.ai.ToolCall) -> Unit,
                ) = toolRunner.run(call, report)
            },
            board = taskBoard,
        )
    }
    // Told which chat it is in, now that there is one. Read at the moment of
    // asking, so it follows you from conversation to conversation.
    remember(engine) {
        permission.conversation = { engine.conversationId.value }
        // The same supplier for the runner, so the folder a tool writes into and
        // the chat that granted permission to write are always the same chat.
        toolRunner.conversation = { engine.conversationId.value }
        // And the helper, so "which model" is remembered per chat like a write
        // permission is, rather than once for the whole app.
        subagents.conversation = { engine.conversationId.value }
    }

    /**
     * The file open in the editor, or null.
     *
     * Held here rather than inside a screen because §9d makes the editor
     * something that opens **over** whatever you were doing — it is not a
     * destination, so it cannot live inside one. Back closes it and leaves you
     * where you were.
     */
    var editing by remember { mutableStateOf<java.io.File?>(null) }

    /** Which app Files should open inside, when arriving from the shelf. */
    var filesFor by remember { mutableStateOf<dev.ely.warp.build.Projects.App?>(null) }

    var choice by remember { mutableStateOf(registry.choice) }

    // The drawer's list, straight from the database. It updates itself: sending
    // a message touches the conversation, and the row reorders without anyone
    // having to remember to refresh anything.
    val drawer by remember { conversations.observeDrawer() }
        .collectAsState(initial = DrawerState())
    val openConversationId by engine.conversationId.collectAsState()

    // Which folders are folded shut. Held as state as well as on disk, because
    // SharedPreferences is not observable and a drawer that only redrew when
    // something else changed would fold a folder on the next recomposition
    // rather than on the tap.
    val drawerPrefs = remember { DrawerPrefs(context) }
    var collapsedFolders by remember { mutableStateOf(drawerPrefs.collapsed) }

    // Search runs against the FTS index, which has been written and unused since
    // the store landed. Debounced: FTS is fast, but a query per keystroke means a
    // query for every prefix of a word nobody finished typing.
    var searchQuery by remember { mutableStateOf("") }
    var searchOrder by remember { mutableStateOf(ConversationOrder.RECENT) }
    var searchResults by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    LaunchedEffect(searchQuery, searchOrder) {
        if (searchQuery.isBlank()) {
            searchResults = emptyList()
        } else {
            // Debounced on the query only in spirit — changing the order reruns
            // immediately in practice, and a fifth of a second on a button press
            // is the difference between a control that answers and one that lags.
            delay(220)
            searchResults = conversations.searchByName(searchQuery, searchOrder)
        }
    }

    // Hand the running screen to whatever is driving from outside, and take it
    // back when the screen goes. Registered here rather than inside a screen
    // because these need the engine and the store, which live at this level.
    DisposableEffect(engine, conversations) {
        DebugBridge.state = {
            mapOf(
                "destination" to destination.name,
                "conversationId" to engine.conversationId.value,
                "messageCount" to engine.messages.value.size,
                "busy" to engine.busy.value,
                "model" to choice.label,
                "provider" to choice.providerId,
                "searchQuery" to searchQuery,
                // What is being asked of you right now, or null. A test that
                // answers a prompt has to be able to see there is one.
                "askingTool" to permission.pending.value?.toolName,
                "askingAbout" to permission.pending.value?.summary,
            )
        }
        DebugBridge.send = { text ->
            // Steer first, exactly as the composer does — §5i item 7. Sending
            // while a turn runs is a correction, and a surface that called
            // `send` here would test a path the app does not take: `send`
            // refuses while busy, so the message would vanish and the route
            // would answer with the *previous* message's id.
            if (!engine.steer(text)) engine.send(text)
            // The id of the message that was actually stored, so a caller can
            // read it back. Returning "ok" would prove only that the call
            // returned.
            engine.messages.value.lastOrNull { it.role == dev.ely.warp.ai.Role.USER }?.id
        }
        DebugBridge.subagents = subagents
        DebugBridge.tasks = taskBoard
        DebugBridge.sendWithFiles = { text, paths ->
            val id = engine.conversationId.value ?: "_scratch"
            val taken = paths.mapNotNull { path ->
                // Relative paths are resolved against the app's data directory,
                // because that is how every other path in the suite is written
                // — `run-as` starts there, so `files/projects/...` is the shape
                // the tests already speak.
                val file = java.io.File(path).let {
                    if (it.isAbsolute) it else java.io.File(context.dataDir, path)
                }
                dev.ely.warp.data.AttachmentStore
                    .take(context, id, android.net.Uri.fromFile(file))
                    .getOrNull()
            }
            engine.send(text, attachments = taken)
            engine.messages.value.lastOrNull { it.role == dev.ely.warp.ai.Role.USER }?.id
        }
        DebugBridge.navigate = { name ->
            val target = WarpDestination.entries.firstOrNull { it.name == name }
            if (target != null) { destination = target; true } else false
        }
        DebugBridge.newChat = { engine.clear(); destination = WarpDestination.CHAT }
        DebugBridge.open = { id ->
            val known = drawer.conversations.any { it.id == id }
            if (known) {
                scope.launch {
                    engine.open(id, conversations.loadMessages(id), conversations.tasksFor(id))
                    destination = WarpDestination.CHAT
                }
            }
            known
        }
        // The same function the send button calls, so a test cannot pass
        // against a code path the app does not use.
        DebugBridge.command = { text ->
            dev.ely.warp.ui.runComposed(text, engine, rules, onShowRules = {})
                ?: "sent"
        }
        DebugBridge.permission = permission
        DebugBridge.questions = questions
        DebugBridge.chooseModel = { providerId, modelId ->
            // Applied to the engine as well as the registry. Setting only the
            // registry would leave the engine sending the previous model id,
            // and the run would report a model it never used.
            val picked = dev.ely.warp.ai.ModelChoice(
                providerId = providerId,
                modelId = modelId,
                modelName = modelId.substringAfterLast('/'),
                effort = null,
                badge = null,
            )
            registry.choice = picked
            choice = picked
            engine.provider = registry.selected
            engine.model = modelId
            "$providerId | $modelId"
        }
        DebugBridge.goal = { engine.goal.value }
        DebugBridge.continueGoal = {
            val paused = engine.goal.value?.paused == true
            if (paused) engine.resumeGoal()
            paused
        }
        DebugBridge.conversation = { engine.conversationId.value }
        DebugBridge.setting = { name, value ->
            val appearance = Appearance.get(context)
            when (name) {
                "ambient" -> {
                    appearance.setAmbient(value.toBoolean())
                    appearance.ambient.value.toString()
                }
                "name" -> {
                    Identity.get(context).setName(value)
                    Identity.get(context).name.value ?: ""
                }
                "search" -> {
                    dev.ely.warp.data.WebSearch.set(context, value.toBoolean())
                    dev.ely.warp.data.WebSearch.isOn(context).toString()
                }
                else -> null
            }
        }

        onDispose {
            DebugBridge.state = null
            DebugBridge.send = null
            DebugBridge.navigate = null
            DebugBridge.newChat = null
            DebugBridge.open = null
            DebugBridge.setting = null
            DebugBridge.permission = null
            DebugBridge.questions = null
            DebugBridge.chooseModel = null
            DebugBridge.goal = null
            DebugBridge.command = null
            DebugBridge.conversation = null
            DebugBridge.tasks = null
        }
    }

    // Pick up a key added in Settings; the model itself is chosen in the picker.
    LaunchedEffect(destination) {
        if (destination == WarpDestination.CHAT) {
            choice = registry.choice
            engine.provider = registry.providerFor(choice.providerId)
            engine.model = choice.modelId
            engine.effort = choice.effort ?: dev.ely.warp.ai.Effort.LOW
        }
    }

    if (showPicker) {
        ModelPickerDialog(
            registry = registry,
            current = choice,
            onPick = { picked ->
                choice = picked
                registry.choice = picked
                engine.provider = registry.providerFor(picked.providerId)
                engine.model = picked.modelId
                engine.effort = picked.effort ?: dev.ely.warp.ai.Effort.LOW
            },
            onDismiss = { showPicker = false },
        )
    }

    // The whole UI is forced left-to-right. Every label in Warp is English, and
    // on a Hebrew phone Android mirrors the layout: navigation reverses, chat
    // bubbles swap sides, and English placeholders render with their punctuation
    // at the wrong end. Real right-to-left support means translating the app,
    // not flipping English text — that is separate work.
    Ltr {
      // The light belongs to the app, not to the chat. Settings and Build were
      // the only flat screens left, and a room that is lit in one doorway and
      // bare in the next is worse than one that is bare throughout.
      Box(modifier = Modifier.fillMaxSize().ambientWash(choice.effort)) {
        WarpShell(
            // The demo notice sits at the very top of the screen, in the space
            // the model picker left behind. It only appears on Chat: it is a
            // statement about the answer you are about to get, and on Settings
            // or Build it would be a notice about something you are not doing.
            topBarCenter = {
                if (destination == WarpDestination.CHAT && !engine.provider.requiresKey) {
                    DemoChip(onOpenSettings = { destination = WarpDestination.SETTINGS })
                }
            },
            onNewChat = {
                engine.clear()
                destination = WarpDestination.CHAT
            },
            destination = destination,
            onDestinationChange = { destination = it },
            conversations = drawer.conversations,
            searchQuery = searchQuery,
            searchResults = searchResults,
            searchOrder = searchOrder,
            onSearchChange = { searchQuery = it },
            onSearchOrderChange = { searchOrder = it },
            openConversationId = openConversationId,
            onOpenConversation = { id ->
                scope.launch {
                    // Loaded before the screen is switched, so the chat never
                    // appears holding the previous conversation's messages for a
                    // frame. A transcript flashing someone else's words, even
                    // briefly, is the one thing this list must never do.
                    engine.open(id, conversations.loadMessages(id), conversations.tasksFor(id))
                    destination = WarpDestination.CHAT
                    // The question has been answered. Leaving the query in place
                    // means the next time the drawer opens it opens on a search
                    // for something you already found.
                    searchQuery = ""
                }
            },
            onRenameConversation = { id, name ->
                scope.launch { conversations.rename(id, name, System.currentTimeMillis()) }
            },
            onPinConversation = { id, pinned ->
                scope.launch { conversations.setPinned(id, pinned, System.currentTimeMillis()) }
            },
            onDeleteConversation = { id ->
                scope.launch {
                    conversations.softDelete(id, System.currentTimeMillis())
                    // You cannot be left reading something you just deleted. The
                    // chat empties only when the deleted conversation is the one
                    // on screen; deleting any other must not disturb it.
                    if (engine.conversationId.value == id) engine.clear()
                }
            },
            onUndoDelete = { id ->
                scope.launch {
                    conversations.undoDelete(id)
                    // Put it back on screen as well as back in the list. Undo
                    // means "that did not happen", and a restored conversation
                    // you then have to go and find has only half happened.
                    engine.open(id, conversations.loadMessages(id), conversations.tasksFor(id))
                    destination = WarpDestination.CHAT
                }
            },
            folders = drawer.folders,
            collapsedFolders = collapsedFolders,
            onMoveConversation = { id, folderId ->
                scope.launch {
                    conversations.moveToFolder(id, folderId, System.currentTimeMillis())
                }
            },
            onToggleFolder = { id ->
                drawerPrefs.toggle(id)
                // Read back rather than computed here, so the one place that
                // decides what "toggled" means is the one that stores it.
                collapsedFolders = drawerPrefs.collapsed
            },
            onCreateFolder = { name ->
                scope.launch { conversations.createFolder(name, System.currentTimeMillis()) }
            },
            onRenameFolder = { id, name ->
                scope.launch { conversations.renameFolder(id, name) }
            },
            onDeleteFolder = { folder ->
                scope.launch { conversations.deleteFolder(folder) }
            },
            onReorderFolders = { ordered ->
                scope.launch { conversations.reorderFolders(ordered) }
            },
            // Observed rather than read once, so a switch you flip in the sheet
            // is reflected by the row you flipped it on. Keyed by id: the sheet
            // outlives no conversation, but the flow behind it must be replaced
            // when a different one is opened.
            // Read straight from disk rather than held in state: it is only
            // needed at the moment a dialog opens, and a cached copy would be
            // one more thing that can disagree with the filesystem.
            appFor = { id -> dev.ely.warp.build.Projects.all(context)
                .firstOrNull { it.conversationId == id } },
            grantedTools = { id ->
                remember(id) { conversations.observeGrants(id) }
                    .collectAsState(initial = emptySet()).value
            },
            onSetToolGrant = { id, tool, always ->
                scope.launch {
                    if (always) conversations.grant(id, tool)
                    else conversations.revoke(id, tool)
                }
            },
        ) { screen ->
            when (screen) {
                WarpDestination.CHAT -> ChatScreen(
                    engine = engine,
                    onOpenSettings = { destination = WarpDestination.SETTINGS },
                    // The picker moved out of the top bar and into the composer,
                    // so the screen that owns the message owns the choice too.
                    modelLabel = choice.label,
                    onPickModel = { showPicker = true },
                    effort = choice.effort,
                    modelSees = choice.canSee,
                    permission = permission,
                    questions = questions,
                )
                WarpDestination.BUILD -> BuildScreen()
                WarpDestination.SETTINGS -> SettingsScreen()

                WarpDestination.APPS -> AppsScreen(
                    // Read fresh each time the screen is shown. The shelf is
                    // derived from what is on disk, and a cached list is one
                    // more thing that can disagree with the filesystem.
                    apps = remember(destination) {
                        dev.ely.warp.build.Projects.all(context)
                    },
                    onOpenChat = { id ->
                        scope.launch {
                            engine.open(id, conversations.loadMessages(id), conversations.tasksFor(id))
                            destination = WarpDestination.CHAT
                        }
                    },
                    onOpenFiles = { app ->
                        filesFor = app
                        destination = WarpDestination.FILES
                    },
                )

                WarpDestination.FILES -> FilesScreen(
                    // Every app, not the open chat's. Files used to depend on
                    // which conversation happened to be open, which is invisible
                    // state deciding what a screen says.
                    apps = remember(destination, filesFor) {
                        dev.ely.warp.build.Projects.all(context)
                    },
                    folderFor = { dev.ely.warp.build.Projects.forConversation(context, it.conversationId) },
                    onOpen = { editing = it },
                    initial = filesFor,
                )
                WarpDestination.ASSETS -> AssetsScreen(
                    // Fresh each time, like Files, and for the same reason: the
                    // list is derived from disk and a cached copy is one more
                    // thing that can disagree with it.
                    apps = remember(destination, filesFor) {
                        dev.ely.warp.build.Projects.all(context)
                    },
                    folderFor = {
                        dev.ely.warp.build.Projects.forConversation(context, it.conversationId)
                    },
                    onOpenSettings = { destination = WarpDestination.SETTINGS },
                    initial = filesFor,
                )
            }
        }

        // Over everything, including the shell. The editor is not a place you
        // navigate to, so it does not sit inside the shell's content slot — it
        // covers it, and Back puts you back exactly where you were.
        editing?.let { file ->
            androidx.activity.compose.BackHandler { editing = null }
            EditorScreen(file = file, onClose = { editing = null })
        }
      }
    }
}

@Composable
private fun PreflightScreen() {
    val context = LocalContext.current
    var checks by remember { mutableStateOf<List<DeviceProbe.Check>?>(null) }

    LaunchedEffect(Unit) {
        checks = withContext(Dispatchers.IO) { DeviceProbe.runAll(context) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(
            text = "⚡ Warp",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "Step 0 · pre-flight checks",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.size(24.dp))

        when (val result = checks) {
            null -> Text(
                text = "Running checks…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                result.forEach { check ->
                    CheckRow(check)
                    Spacer(Modifier.size(12.dp))
                }

                Spacer(Modifier.size(12.dp))
                Verdict(result)
            }
        }
    }
}

@Composable
private fun CheckRow(check: DeviceProbe.Check) {
    val tint = when (check.status) {
        DeviceProbe.Status.PASS -> WarpSuccess
        DeviceProbe.Status.WARN -> WarpWarning
        DeviceProbe.Status.FAIL -> MaterialTheme.colorScheme.error
        DeviceProbe.Status.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val glyph = when (check.status) {
        DeviceProbe.Status.PASS -> "✓"
        DeviceProbe.Status.WARN -> "!"
        DeviceProbe.Status.FAIL -> "✕"
        DeviceProbe.Status.INFO -> "·"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(16.dp),
            )
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .size(20.dp)
                    .background(tint, CircleShape),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = glyph,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.size(12.dp))
            Text(
                text = check.label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.size(8.dp))
        Text(
            text = check.value,
            style = WarpMono,
            color = tint,
            fontWeight = FontWeight.Medium,
        )

        check.detail?.let {
            Spacer(Modifier.size(6.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Verdict(checks: List<DeviceProbe.Check>) {
    val execCheck = checks.firstOrNull { it.label == "Execute from app storage" }
    val canExec = execCheck?.status == DeviceProbe.Status.PASS
    val anyFail = checks.any { it.status == DeviceProbe.Status.FAIL }

    val (headline, body, tint) = when {
        canExec && !anyFail -> Triple(
            "Ready to build",
            "This device can run Warp's compiler toolchain. " +
                "The core assumption behind the whole project is confirmed.",
            WarpSuccess,
        )
        canExec -> Triple(
            "Mostly ready",
            "Execution works, but something else needs attention above.",
            WarpWarning,
        )
        else -> Triple(
            "Blocked",
            "This device refuses to execute binaries from app storage. " +
                "Warp needs the jniLibs fallback described in the plan.",
            MaterialTheme.colorScheme.error,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.titleMedium,
            color = tint,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
