package dev.ely.warp.debug

import android.content.Context
import android.util.Log
import dev.ely.warp.WarpApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * A control surface for the app, reachable over a USB cable.
 *
 * ```
 * adb forward tcp:8099 tcp:8099
 * curl -H "X-Warp-Key: <key>" localhost:8099/state
 * ```
 *
 * **This file exists only in debug builds.** It is in `src/debug`, so a release
 * build does not contain it — not disabled, not guarded, absent.
 *
 * It exists because half a feedback loop is not a feedback loop. MIUI refuses
 * adb's synthetic taps, so during the design pass every check was "please tap
 * this and tell me what you see" — screenshots came back over the cable and
 * nothing could go the other way. An app whose purpose is building and testing
 * software on the device it runs on should not be the one thing on that device
 * nobody can drive.
 *
 * HTTP rather than broadcasts, for one reason: **a request can answer.** A
 * broadcast can ask the app to do something and cannot report what happened, and
 * the point is checking the result rather than firing the trigger.
 *
 * Written against a raw socket rather than pulling in a server library. The
 * surface is one loopback port speaking a subset of HTTP/1.1 to one client; a
 * dependency shipped in every debug build to save eighty lines is a bad trade.
 */
object DebugServer {

    /** True where this file is compiled. The release stub answers false. */
    const val IS_SUPPORTED = true

    private const val TAG = "WarpDebug"
    const val PORT = 8099

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: ServerSocket? = null

    fun start(context: Context) {
        if (socket != null) return
        val app = context.applicationContext

        scope.launch {
            runCatching {
                // Loopback only. Not a configuration choice — the bind address is
                // the security boundary, and a port bound to 0.0.0.0 on a phone
                // is a port on whatever café network it is standing in.
                ServerSocket(PORT, 4, InetAddress.getLoopbackAddress()).also {
                    socket = it
                    Log.i(TAG, "listening on 127.0.0.1:$PORT")
                }
            }.onFailure {
                Log.w(TAG, "could not listen on $PORT", it)
                return@launch
            }

            while (!Thread.currentThread().isInterrupted) {
                val client = runCatching { socket?.accept() }.getOrNull() ?: break
                scope.launch {
                    // Logged, not swallowed. A route that threw used to close the
                    // socket with no response and no trace: the caller saw
                    // "remote end closed connection" and the phone said nothing
                    // at all, which is the least debuggable failure there is.
                    runCatching { serve(app, client) }.onFailure {
                        Log.w(TAG, "request failed", it)
                        runCatching { respond(client, 500, error("${it.javaClass.simpleName}: ${it.message}")) }
                    }
                }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        Log.i(TAG, "stopped")
    }

    // ── one request ──────────────────────────────────────────────────────

    private fun serve(context: Context, client: Socket) = client.use {
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))

        val requestLine = reader.readLine() ?: return@use
        val parts = requestLine.split(' ')
        if (parts.size < 2) return@use respond(client, 400, error("malformed request"))

        val method = parts[0]
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").toParams()

        var length = 0
        var key: String? = null
        while (true) {
            val header = reader.readLine()
            if (header.isNullOrEmpty()) break
            val name = header.substringBefore(':').trim().lowercase()
            val value = header.substringAfter(':').trim()
            when (name) {
                "content-length" -> length = value.toIntOrNull() ?: 0
                "x-warp-key" -> key = value
            }
        }

        val body = if (length > 0) CharArray(length).let {
            reader.read(it, 0, length)
            String(it)
        } else ""

        // Checked before anything is read out of the app and before anything is
        // done to it. An unauthenticated request learns only that something is
        // listening.
        val expected = DebugBridge.key.value
        if (expected == null) {
            return@use respond(client, 503, error("debug surface is closed — set a key in Settings"))
        }
        if (key != expected) {
            return@use respond(client, 401, error("bad or missing X-Warp-Key"))
        }

        // Caught **inside** the socket's lifetime. It used to be caught by the
        // caller, outside `client.use`, which meant the socket was already
        // closed and the 500 went into a closed pipe: every route failure
        // looked like the connection dropping for no reason.
        val (status, json) = try {
            route(context, method, path, query, body)
        } catch (e: Throwable) {
            Log.w(TAG, "route failed: $method $path", e)
            500 to error("${e.javaClass.simpleName}: ${e.message}")
        }
        respond(client, status, json)
    }

    private fun route(
        context: Context,
        method: String,
        path: String,
        query: Map<String, String>,
        body: String,
    ): Pair<Int, JSONObject> {
        val json = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val store = (context as WarpApplication).conversations

        return when ("$method $path") {
            "GET /health" -> 200 to JSONObject().put("ok", true)

            "GET /state" -> {
                val supplier = DebugBridge.state
                    ?: return 503 to error("no screen is registered yet")
                200 to JSONObject(supplier().mapValues { it.value ?: JSONObject.NULL })
            }

            "POST /chat/send" -> {
                // Files first: a message that is only a screenshot is legal, so
                // the text guard below must not fire on one.
                val files = json.optJSONArray("attach")?.let { array ->
                    (0 until array.length()).mapNotNull {
                        array.optString(it).takeIf(String::isNotBlank)
                    }
                }.orEmpty()

                if (files.isNotEmpty()) {
                    val withFiles = DebugBridge.sendWithFiles
                        ?: return 503 to error("no chat on screen")
                    val id = withFiles(json.optString("text"), files)
                        ?: return 500 to error("the message was not accepted")
                    return 200 to JSONObject().put("messageId", id)
                }

                val text = json.optString("text").takeIf { it.isNotBlank() }
                    ?: return 400 to error("expected {\"text\": \"...\"}")
                val send = DebugBridge.send ?: return 503 to error("no chat on screen")

                // What actually happened, not that the request was accepted. The
                // id is what lets a caller then read the message back and check
                // it is the message it meant to send.
                val id = send(text) ?: return 500 to error("the message was not accepted")
                200 to JSONObject().put("messageId", id)
            }

            "POST /nav" -> {
                val to = json.optString("to").takeIf { it.isNotBlank() }
                    ?: return 400 to error("expected {\"to\": \"CHAT|FILES|BUILD|ASSETS|SETTINGS\"}")
                val go = DebugBridge.navigate ?: return 503 to error("no shell on screen")
                if (!go(to.uppercase())) return 400 to error("no destination called $to")
                200 to JSONObject().put("destination", to.uppercase())
            }

            "POST /chat/new" -> {
                val fresh = DebugBridge.newChat ?: return 503 to error("no chat on screen")
                fresh()
                200 to JSONObject().put("ok", true)
            }

            "POST /chat/open" -> {
                val id = json.optString("id").takeIf { it.isNotBlank() }
                    ?: return 400 to error("expected {\"id\": \"...\"}")
                val open = DebugBridge.open ?: return 503 to error("no chat on screen")
                if (!open(id)) return 404 to error("no conversation $id")
                200 to JSONObject().put("conversationId", id)
            }

            "GET /conversations" -> runBlocking {
                val rows = store.observeDrawer().first()
                val array = JSONArray()
                rows.conversations.forEach {
                    array.put(
                        JSONObject()
                            .put("id", it.id)
                            .put("title", it.title)
                            .put("pinned", it.pinned)
                            .put("folderId", it.folderId ?: JSONObject.NULL)
                            .put("updatedAt", it.updatedAt)
                    )
                }
                200 to JSONObject().put("conversations", array)
            }

            "GET /messages" -> runBlocking {
                val id = query["id"] ?: return@runBlocking 400 to error("expected ?id=")
                val array = JSONArray()
                store.loadMessages(id).forEach { message ->
                    // Tool calls are reported too. Without them the one thing
                    // this surface exists to check — that a tool really ran and
                    // really returned something — is the one thing it cannot
                    // see, and "the card looked right on my phone" is back to
                    // being the test.
                    val calls = JSONArray()
                    message.toolCalls.forEach { call ->
                        calls.put(
                            JSONObject()
                                .put("name", call.name)
                                .put("arguments", call.argumentsJson)
                                .put("status", call.status.name)
                                .put("result", call.result ?: JSONObject.NULL)
                                .put("body", call.body ?: JSONObject.NULL)
                        )
                    }
                    array.put(
                        JSONObject()
                            .put("id", message.id)
                            .put("role", message.role.name)
                            .put("text", message.text)
                            // Reported so "did any reasoning actually arrive"
                            // is a check rather than a squint at the screen.
                            .put("thinking", message.thinking)
                            .put("toolCalls", calls)
                            // Same reasoning as tool calls: a message that says
                            // it carried a screenshot and did not would look
                            // identical from outside without this.
                            .put(
                                "attachments",
                                JSONArray().apply {
                                    message.attachments.forEach { put(it.toJson()) }
                                },
                            )
                            .put("error", message.error?.message ?: JSONObject.NULL)
                            .put("createdAt", message.createdAt)
                    )
                }
                200 to JSONObject().put("messages", array)
            }

            "POST /settings" -> {
                val name = json.optString("name").takeIf { it.isNotBlank() }
                    ?: return 400 to error("expected {\"name\": \"...\", \"value\": \"...\"}")
                val apply = DebugBridge.setting ?: return 503 to error("settings unavailable")

                // The value as read back, not the value that was sent. A setter
                // that echoes its input proves nothing; this is the difference
                // between automating the app and testing it.
                val stored = apply(name, json.optString("value"))
                    ?: return 400 to error("no setting called $name")
                200 to JSONObject().put("name", name).put("value", stored)
            }

            // These go straight to the store rather than through the screen.
            // Renaming a conversation is a fact about the database, and routing
            // it through the UI would test the button rather than the behaviour.

            "GET /search" -> runBlocking {
                val q = query["q"] ?: return@runBlocking 400 to error("expected ?q=")
                val order = if (query["order"].equals("alpha", true)) {
                    dev.ely.warp.data.ConversationOrder.ALPHABETICAL
                } else {
                    dev.ely.warp.data.ConversationOrder.RECENT
                }
                val array = JSONArray()
                store.searchByName(q, order).forEach {
                    array.put(JSONObject().put("id", it.id).put("title", it.title))
                }
                200 to JSONObject().put("order", order.name).put("results", array)
            }

            "POST /conversation/rename" -> runBlocking {
                val id = json.optString("id"); val title = json.optString("title")
                if (id.isBlank() || title.isBlank()) {
                    return@runBlocking 400 to error("expected {id, title}")
                }
                store.rename(id, title, System.currentTimeMillis())
                200 to JSONObject().put("title", store.titleOf(id) ?: JSONObject.NULL)
            }

            "POST /conversation/pin" -> runBlocking {
                val id = json.optString("id")
                if (id.isBlank()) return@runBlocking 400 to error("expected {id, pinned}")
                store.setPinned(id, json.optBoolean("pinned", true), System.currentTimeMillis())
                200 to JSONObject().put("pinned", store.isPinned(id) ?: JSONObject.NULL)
            }

            "POST /conversation/delete" -> runBlocking {
                val id = json.optString("id")
                if (id.isBlank()) return@runBlocking 400 to error("expected {id}")
                store.softDelete(id, System.currentTimeMillis())
                200 to JSONObject().put("stillListed", store.titleOf(id) != null)
            }

            "POST /conversation/undelete" -> runBlocking {
                val id = json.optString("id")
                if (id.isBlank()) return@runBlocking 400 to error("expected {id}")
                store.undoDelete(id)
                200 to JSONObject().put("stillListed", store.titleOf(id) != null)
            }

            // Runs a tool directly, against the project folder, with no model
            // involved. That separation is the point: it answers "does the tool
            // work" without also asking "did the model choose to call it", which
            // are two failures that look identical from the chat.
            // Answer a permission prompt from the machine.
            //
            // Without it the Allow / Always path could only ever be checked by a
            // person holding the phone, which is the exact situation the debug
            // surface exists to end — and it guards the one thing here that can
            // destroy your work.
            "POST /permission" -> {
                val desk = DebugBridge.permission
                    ?: return 503 to error("no permission desk")
                val request = desk.pending.value
                    ?: return 409 to error("nothing is being asked")

                val decision = runCatching {
                    dev.ely.warp.tools.Decision.valueOf(json.optString("decision").uppercase())
                }.getOrElse { return 400 to error("expected ONCE, ALWAYS or DENY") }

                desk.answer(decision)
                200 to JSONObject()
                    .put("answered", decision.name)
                    .put("tool", request.toolName)
                    .put("summary", request.summary)
            }

            // Grants belong to a conversation now, so both of these take one.
            // There is deliberately no "revoke everywhere" — a route that
            // ignores the scope is the first step back to the global grant this
            // replaced.
            "GET /permission" -> runBlocking {
                val desk = DebugBridge.permission ?: return@runBlocking 503 to error("no desk")
                val id = query["id"] ?: DebugBridge.conversation?.invoke()
                200 to JSONObject()
                    .put("askingTool", desk.pending.value?.toolName ?: JSONObject.NULL)
                    .put("askingAbout", desk.pending.value?.summary ?: JSONObject.NULL)
                    .put("conversationId", id ?: JSONObject.NULL)
                    .put(
                        "granted",
                        JSONArray(id?.let { store.grantsFor(it).sorted() } ?: emptyList<String>())
                    )
            }

            "POST /permission/revoke" -> runBlocking {
                val id = json.optString("id").takeIf { it.isNotBlank() }
                    ?: DebugBridge.conversation?.invoke()
                    ?: return@runBlocking 400 to error("no chat is open — pass an id")
                val tool = json.optString("tool").takeIf { it.isNotBlank() }
                if (tool != null) store.revoke(id, tool) else store.revokeAllGrants(id)
                200 to JSONObject()
                    .put("conversationId", id)
                    .put("granted", JSONArray(store.grantsFor(id).sorted()))
            }

            // Types into the composer and presses send, command or not.
            "POST /chat/command" -> {
                val text = json.optString("text").takeIf { it.isNotBlank() }
                    ?: return 400 to error("expected a text field")
                val run = DebugBridge.command ?: return 503 to error("no chat on screen")
                200 to JSONObject().put("note", run(text))
            }

            "GET /question" -> {
                val desk = DebugBridge.questions ?: return 503 to error("no question desk")
                val q = desk.pending.value
                200 to JSONObject()
                    .put("asking", q != null)
                    .put("question", q?.text ?: JSONObject.NULL)
                    .put("options", JSONArray(q?.options ?: emptyList<String>()))
                    .put("recommended", q?.recommended ?: JSONObject.NULL)
                    .put("because", q?.because ?: JSONObject.NULL)
            }

            "POST /question/answer" -> {
                val desk = DebugBridge.questions ?: return 503 to error("no question desk")
                val q = desk.pending.value ?: return 409 to error("nothing is being asked")

                // Either tap an option by index or write your own, the same two
                // ways the card offers. A route that only accepted an index
                // would leave the "something else" path untested, and that is
                // the one every real answer eventually takes.
                val text = json.optString("text").takeIf { it.isNotBlank() }
                    ?: json.optInt("option", -1).takeIf { it in q.options.indices }
                        ?.let { q.options[it] }
                    ?: return 400 to error("pass text, or option as an index")

                desk.answer(text)
                200 to JSONObject().put("answered", text)
            }

            // Reads the project the model works in, and can build it.
            //
            // The build route is here rather than as a tool because the tools
            // that run things are item 5 and must ask permission every time.
            // This is how the scaffold gets *proven* to compile in the meantime
            // — a starting project that does not build is worse than none.
            "GET /project" -> {
                val dir = dev.ely.warp.build.Projects.forConversation(
                    context, DebugBridge.conversation?.invoke()
                )
                val meta = dev.ely.warp.build.NewProject.meta(dir)
                200 to JSONObject()
                    .put("exists", dev.ely.warp.build.NewProject.exists(dir))
                    .put("name", meta?.name ?: JSONObject.NULL)
                    .put("applicationId", meta?.applicationId ?: JSONObject.NULL)
                    .put(
                        "files",
                        JSONArray(
                            dir.walkTopDown().filter { it.isFile }
                                .map { it.relativeTo(dir).path.replace('\\', '/') }
                                .sorted().toList()
                        )
                    )
            }

            "POST /project/build" -> runBlocking {
                val dir = dev.ely.warp.build.Projects.forConversation(
                    context, DebugBridge.conversation?.invoke()
                )
                val meta = dev.ely.warp.build.NewProject.meta(dir)
                    ?: return@runBlocking 400 to error("no project here")

                // Assembled exactly as the Build screen assembles it, so this
                // route is testing the real pipeline rather than a second one
                // that happens to live next to it.
                val toolchain = dev.ely.warp.build.Toolchain.forContext(context)
                val workRoot = java.io.File(context.filesDir, "work").apply { mkdirs() }
                val signer = dev.ely.warp.build.ApkSigner(
                    toolchain = toolchain,
                    keystoreFile = java.io.File(context.filesDir, "keys/warp-debug.p12"),
                )
                val engine = dev.ely.warp.build.BuildEngine(toolchain, workRoot, signer)
                val log = StringBuilder()
                val outcome = engine.build(
                    dev.ely.warp.build.BuildEngine.Request(
                        projectDir = dir,
                        applicationId = meta.applicationId,
                    ),
                    onLine = { log.appendLine(it.text) },
                )

                when (outcome) {
                    is dev.ely.warp.build.BuildEngine.Outcome.Success -> 200 to JSONObject()
                        .put("ok", true)
                        .put("apk", outcome.apk.name)
                        .put("bytes", outcome.apk.length())
                        .put("signed", outcome.signed)
                        .put("ms", outcome.totalMs)

                    is dev.ely.warp.build.BuildEngine.Outcome.Failure -> 200 to JSONObject()
                        .put("ok", false)
                        .put("stage", outcome.stage.name)
                        .put("message", outcome.message)
                        .put("output", outcome.output.takeLast(1500))
                }
            }

            "POST /model" -> {
                val provider = json.optString("provider").takeIf { it.isNotBlank() }
                    ?: return 400 to error("expected a provider")
                val model = json.optString("model").takeIf { it.isNotBlank() }
                    ?: return 400 to error("expected a model")
                val choose = DebugBridge.chooseModel ?: return 503 to error("no chat on screen")
                val label = choose(provider, model)
                    ?: return 404 to error("no model $model on $provider")
                200 to JSONObject().put("model", label)
            }

            // The shelf, as data. §9h: a conversation becomes an app when
            // the AI writes its first file, so this lists folders rather than
            // any flag somebody has to remember to set.
            // The room's state, as a word.
            //
            // Worth a route because the thing it drives is a slow glow behind
            // the composer: "does it look like it is working" is not a check,
            // and a state machine nobody can assert on is one that quietly
            // stops working the first time something else changes.
            "GET /build/status" -> 200 to JSONObject()
                .put("state", dev.ely.warp.build.BuildStatus.state.value.name)

            "GET /apps" -> {
                val apps = dev.ely.warp.build.Projects.all(context)
                200 to JSONObject().put("apps", JSONArray(apps.map {
                    JSONObject()
                        .put("conversationId", it.conversationId)
                        .put("name", it.name)
                        .put("applicationId", it.applicationId)
                        .put("files", it.fileCount)
                        .put("built", it.built)
                        .put("apkBytes", it.apkBytes)
                }))
            }

            "POST /goal/continue" -> {
                val go = DebugBridge.continueGoal ?: return 503 to error("no chat on screen")
                if (!go()) return 409 to error("no goal is paused")
                200 to JSONObject().put("resumed", true)
            }

            "GET /goal" -> {
                val state = DebugBridge.goal?.invoke()
                200 to JSONObject()
                    .put("running", state != null)
                    .put("condition", state?.condition ?: JSONObject.NULL)
                    .put("turn", state?.turn ?: JSONObject.NULL)
                    .put("limit", state?.limit ?: JSONObject.NULL)
                    // The number the bar actually leads with.
                    .put("steps", state?.steps ?: JSONObject.NULL)
                    .put("label", state?.label ?: JSONObject.NULL)
            }

            "GET /rules" -> {
                val rules = dev.ely.warp.data.Rules.get(context).rules.value
                200 to JSONObject()
                    .put("rules", JSONArray(rules))
                    .put("prompt", dev.ely.warp.data.Rules.get(context).asPrompt() ?: JSONObject.NULL)
            }

            // The checklist, as data — §5j.
            //
            // Written at the same time as the tools rather than after them. The
            // rewind in §9f shipped with no route, so proving it needed a person
            // holding the phone and reading a dialog out loud.
            //
            // Reports each step's own state rather than a count, because "two
            // done" cannot tell you *which* two — and out-of-order ticking is a
            // thing this feature deliberately allows and has to be checked for.
            "GET /tasks" -> {
                val board = DebugBridge.tasks
                    ?: return 503 to error("no task board")
                val steps = board.state()
                200 to JSONObject()
                    .put(
                        "tasks",
                        JSONArray().also { array ->
                            steps.forEach { task ->
                                array.put(
                                    JSONObject()
                                        .put("text", task.text)
                                        .put("done", task.done)
                                        .put("note", task.note ?: JSONObject.NULL)
                                )
                            }
                        }
                    )
                    .put("count", steps.size)
                    .put("done", steps.count { it.done })
                    .put("open", steps.count { !it.done })
            }

            // The diff, as data — §5k.
            //
            // A pure function of two strings, which is exactly the part a
            // screenshot cannot check: whether one changed line in a block reads
            // as one line or as the whole block. Needs no screen, so it does not
            // go through the bridge.
            "POST /diff" -> {
                val lines = dev.ely.warp.ui.diffLines(
                    json.optString("old"),
                    json.optString("new"),
                )
                200 to JSONObject()
                    .put(
                        "lines",
                        JSONArray().also { array ->
                            lines.forEach {
                                array.put(
                                    JSONObject()
                                        .put("kind", it.kind.name)
                                        .put("text", it.text)
                                )
                            }
                        }
                    )
                    .put("same", lines.count { it.kind == dev.ely.warp.ui.DiffLine.Kind.SAME })
                    .put("added", lines.count { it.kind == dev.ely.warp.ui.DiffLine.Kind.ADDED })
                    .put(
                        "removed",
                        lines.count { it.kind == dev.ely.warp.ui.DiffLine.Kind.REMOVED },
                    )
            }

            "POST /tool" -> runBlocking {
                val name = json.optString("name")
                val tool = dev.ely.warp.tools.ALL_TOOLS[name]
                    ?: return@runBlocking 400 to error("no tool called $name")

                val args = json.optJSONObject("args") ?: JSONObject()
                // Reaches straight past the desk on purpose: this route is for
                // testing a tool's own behaviour, and it is only ever reachable
                // in a debug build. The chat path is the one that must ask.

                val project = dev.ely.warp.build.Projects.forConversation(
                    context, DebugBridge.conversation?.invoke()
                )

                // The board goes in too, or `set_tasks` driven through this route
                // writes to a list nothing draws and the check passes for the
                // wrong reason — the failure this whole surface exists to stop.
                val env = dev.ely.warp.tools.ToolEnv(
                    project, context, DebugBridge.subagents, DebugBridge.tasks,
                )
                when (val r = tool.run(env, args)) {
                    is dev.ely.warp.tools.ToolResult.Ok -> 200 to JSONObject()
                        .put("summary", r.summary)
                        .put("body", r.body ?: JSONObject.NULL)
                    is dev.ely.warp.tools.ToolResult.Failed -> 200 to JSONObject()
                        .put("failed", r.reason)
                }
            }

            else -> 404 to error("no route for $method $path")
        }
    }

    // ── plumbing ─────────────────────────────────────────────────────────

    private fun error(message: String) = JSONObject().put("error", message)

    private fun respond(client: Socket, status: Int, json: JSONObject) {
        val body = json.toString().toByteArray()
        client.getOutputStream().apply {
            write(
                buildString {
                    append("HTTP/1.1 $status ${reason(status)}\r\n")
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ${body.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray()
            )
            write(body)
            flush()
        }
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Unknown"
    }

    private fun String.toParams(): Map<String, String> =
        split('&').filter { it.isNotBlank() }.associate {
            val name = it.substringBefore('=')
            val value = URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            name to value
        }
}
