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

            // Does JGit actually run here — §5n, feasibility before work.
            //
            // The APK building proves only that it fits. This makes a repository,
            // commits to it, and reads the commit back out, which is the whole
            // question: JGit is pure Java but it looks for a filesystem, a user
            // identity and a `git` config that Android has none of.
            //
            // Throwaway directory, removed afterwards. Nothing here touches a
            // real project.
            "POST /git/probe" -> {
                val out = JSONObject()
                val dir = java.io.File(context.cacheDir, "git-probe-${System.currentTimeMillis()}")
                try {
                    dir.mkdirs()
                    org.eclipse.jgit.api.Git.init().setDirectory(dir).call().use { git ->
                        java.io.File(dir, "hello.txt").writeText("from the phone")
                        git.add().addFilepattern("hello.txt").call()
                        val commit = git.commit()
                            .setMessage("first commit on the phone")
                            // Set explicitly: JGit otherwise reads a global git
                            // config and a hostname, and Android has neither.
                            .setAuthor("Warp", "warp@localhost")
                            .call()
                        val log = git.log().call().toList()
                        out.put("ok", true)
                            .put("commit", commit.name.take(8))
                            .put("message", log.firstOrNull()?.fullMessage)
                            .put("commits", log.size)
                    }
                } catch (e: Throwable) {
                    // Throwable, not Exception: a missing class on Android
                    // arrives as an Error, and that is exactly the failure this
                    // probe exists to catch.
                    out.put("ok", false)
                        .put("failed", "${e.javaClass.name}: ${e.message}")
                } finally {
                    dir.deleteRecursively()
                }

                // Asked separately, because SSH is its own artifact and its own
                // risk. HTTPS with a token works without it.
                out.put(
                    "ssh",
                    runCatching {
                        Class.forName(
                            "org.eclipse.jgit.transport.sshd.SshdSessionFactory"
                        ).simpleName
                    }.getOrElse { "unavailable: ${it.javaClass.simpleName}" }
                )

                200 to out
            }

            /**
             * The icon path, without spending anything — §8.
             *
             * Everything after the picture arrives: five densities, the round
             * variants, the adaptive layers, the manifest, and the app's colour
             * being replaced by one taken from its own artwork. The drawing
             * itself is the one step not exercised, deliberately — a test that
             * costs four cents per run is a test nobody runs twice.
             *
             * Artwork comes from [Icons.drawDefault], which is the same local
             * generator every new project already gets, so the probe proves the
             * real write path rather than a copy of it.
             */
            /**
             * Is there a compiler on this phone, and if not, why not.
             *
             * Added because the answer was unobtainable. Somebody was given a
             * copy of Warp, asked it for an app, and the build failed with
             * nothing anywhere naming the cause — the toolchain had never been
             * unpacked, and neither they nor the person who gave it to them had
             * any way to find that out. One request now says it.
             */
            /**
             * What is Warp actually telling the model about this project?
             *
             * Added because the question was unanswerable from outside, and it
             * cost two wrong diagnoses. A chat asked for Compose and was told
             * "this toolchain has no Compose"; the summary was fixed, the same
             * thing happened again, and only then did it turn out the model had
             * read it from `android_docs("layout")` instead — a page making an
             * absolute claim about the whole toolchain.
             *
             * Both halves are returned, because the model reads both and either
             * can contradict the other.
             *
             * @param id conversation to answer for. Omitted means a new chat,
             *   which is its own state and the one that was wrong.
             */
            "GET /brain" -> {
                val id = query["id"]
                val project = dev.ely.warp.build.Projects.forConversation(context, id)
                val compose = if (id == null) null
                    else dev.ely.warp.build.NewProject.meta(project)?.compose

                val summary = dev.ely.warp.brain.AndroidBrain.summaryFor(compose)
                val topics = dev.ely.warp.brain.AndroidBrain.topics

                // The claim that caused it, wherever it is still made. An
                // absolute "Compose will not compile" is now only ever correct
                // inside the XML summary, which is given to XML projects alone.
                val lying = topics.filterValues { "Compose will not compile" in it }.keys

                200 to JSONObject()
                    .put("conversation", id ?: JSONObject.NULL)
                    .put(
                        "projectKind",
                        when (compose) {
                            null -> "none yet"
                            true -> "compose"
                            false -> "xml"
                        },
                    )
                    .put("summary", summary)
                    .put("saysComposeUnavailable", "NO Compose" in summary)
                    .put("topics", JSONArray(topics.keys.toList()))
                    .put("topicsClaimingNoCompose", JSONArray(lying.toList()))
            }

            "GET /toolchain" -> {
                val state = dev.ely.warp.build.ToolchainInstaller.state.value
                val out = JSONObject()
                    .put("state", state.javaClass.simpleName)
                    .put(
                        "bundled",
                        dev.ely.warp.build.ToolchainInstaller.isBundled(context),
                    )
                    .put(
                        "ready",
                        state is dev.ely.warp.build.ToolchainInstaller.State.Installed,
                    )
                when (state) {
                    is dev.ely.warp.build.ToolchainInstaller.State.Installed ->
                        out.put("version", state.version).put("megabytes", state.megabytes)
                    is dev.ely.warp.build.ToolchainInstaller.State.Installing ->
                        out.put("files", state.files).put("megabytes", state.megabytes)
                    is dev.ely.warp.build.ToolchainInstaller.State.NoRoom ->
                        out.put("neededMb", state.neededMb).put("freeMb", state.freeMb)
                    is dev.ely.warp.build.ToolchainInstaller.State.Failed ->
                        out.put("failed", state.message).put("detail", state.detail)
                    else -> Unit
                }
                200 to out
            }

            /**
             * Can this phone build Compose, and does the split hold — §8 item 11.
             *
             * Offline and free. It does not compile anything: compiling was
             * proved by hand on the device, and a suite that runs a real
             * Compose build costs half a minute every time it is run. What this
             * checks is the part that rots — that the kit is present, that a
             * Compose project is written as Compose and an XML one as XML, and
             * that the two never bleed into each other.
             */
            "GET /compose" -> {
                val toolchain = dev.ely.warp.build.Toolchain.forContext(context)
                val out = JSONObject()
                    .put("ready", toolchain.hasCompose)
                    .put("plugin", toolchain.composePlugin.isFile)
                    .put("libs", toolchain.composeLibs.size)
                    .put("flatRes", toolchain.composeFlatRes.size)
                    .put("dex", toolchain.composeDex.size)
                    .put("packages", toolchain.composePackages.size)
                    // The stdlib must be inside the kit's dex, because a
                    // Compose build uses it *instead of* the stdlib cache. A
                    // spike that got this wrong produced an APK that installed
                    // cleanly and died on launch.
                    .put(
                        "stdlibInKit",
                        toolchain.composeDex.sumOf { it.length() } > 15_000_000L,
                    )

                // Two throwaway projects, one of each kind, to check the split.
                val dir = java.io.File(context.cacheDir, "compose-probe-${System.currentTimeMillis()}")
                try {
                    val xmlDir = java.io.File(dir, "xml").apply { mkdirs() }
                    val cDir = java.io.File(dir, "compose").apply { mkdirs() }
                    dev.ely.warp.build.NewProject.create(xmlDir, "Plain", "dev.ely.plain")
                    dev.ely.warp.build.NewProject.create(
                        cDir, "Modern", "dev.ely.modern", compose = true,
                    )

                    val xmlMeta = dev.ely.warp.build.NewProject.meta(xmlDir)
                    val cMeta = dev.ely.warp.build.NewProject.meta(cDir)
                    val cMain = java.io.File(cDir, "src/MainActivity.kt").readText()
                    val xmlMain = java.io.File(xmlDir, "src/MainActivity.kt").readText()

                    out.put("xmlFlag", xmlMeta?.compose == false)
                        .put("composeFlag", cMeta?.compose == true)
                        // A Compose project must not ship a layout nobody reads.
                        .put("composeHasNoLayout", !java.io.File(cDir, "res/layout").exists())
                        .put("xmlHasLayout", java.io.File(xmlDir, "res/layout/activity_main.xml").isFile)
                        .put("composeUsesSetContent", "setContent {" in cMain)
                        .put("composeAvoidsSetContentView", "setContentView" !in cMain)
                        .put("xmlUsesSetContentView", "setContentView" in xmlMain)
                        // The template is the most-read example in a project.
                        // If it hard-codes sizes or skips the theme, so will
                        // everything written after it.
                        .put("templateUsesDp", ".dp" in cMain)
                        .put("templateUsesTheme", "MaterialTheme" in cMain)
                        .put("templateHasState", "remember {" in cMain)
                        // The summary the model is actually given, per project.
                        .put(
                            "brainSplit",
                            "JETPACK COMPOSE" in
                                dev.ely.warp.brain.AndroidBrain.summaryFor(true) &&
                                "NO Compose" in
                                dev.ely.warp.brain.AndroidBrain.summaryFor(false),
                        )
                        // The state that made the first version self-fulfilling:
                        // no project yet must NOT read as "no Compose", or the
                        // model says Compose will not compile and then makes an
                        // XML project, which makes it true.
                        .put(
                            "newChatOffersCompose",
                            "NO Compose" !in dev.ely.warp.brain.AndroidBrain.summaryFor(null) &&
                                "Compose IS available" in
                                dev.ely.warp.brain.AndroidBrain.summaryFor(null),
                        )
                        .put(
                            "composeTopicExists",
                            dev.ely.warp.brain.AndroidBrain.topics.containsKey("compose"),
                        )
                        .put("ok", true)
                } catch (e: Throwable) {
                    out.put("ok", false).put("failed", "${e.javaClass.name}: ${e.message}")
                } finally {
                    dir.deleteRecursively()
                }
                200 to out
            }

            "POST /icon/probe" -> {
                val out = JSONObject()
                val dir = java.io.File(context.cacheDir, "icon-probe-${System.currentTimeMillis()}")
                try {
                    // A whole project, because [IconStudio] refuses to put an
                    // icon where there is no app — and that refusal is part of
                    // what is being checked.
                    dir.mkdirs()
                    val made = dev.ely.warp.build.NewProject.create(
                        dir, "Probe", "dev.ely.probe",
                    )
                    val before = dev.ely.warp.build.NewProject.meta(dir)?.colour

                    val artwork = dev.ely.warp.build.Icons.drawDefault(
                        "Probe", android.graphics.Color.rgb(0x2E, 0x9E, 0x5B),
                    )
                    when (
                        val outcome = dev.ely.warp.build.IconStudio.install(dir, artwork)
                    ) {
                        is dev.ely.warp.build.IconStudio.Outcome.Failed ->
                            out.put("ok", false).put("failed", outcome.message)

                        is dev.ely.warp.build.IconStudio.Outcome.Drawn -> {
                            val manifest = java.io.File(dir, "AndroidManifest.xml").readText()
                            out.put("ok", true)
                                .put("created", made == null)
                                .put("groups", outcome.files.size)
                                // Counted from disk rather than from what the
                                // writer said it wrote. Those are two different
                                // claims, and this project has been caught three
                                // times by code that reported the first one.
                                .put(
                                    "pngs",
                                    java.io.File(dir, "res").walkTopDown()
                                        .count { it.isFile && it.extension == "png" },
                                )
                                .put(
                                    "adaptive",
                                    java.io.File(dir, "res/mipmap-anydpi-v26/ic_launcher.xml")
                                        .isFile,
                                )
                                .put("declared", "android:icon" in manifest)
                                .put("colourChanged", before != null && before != outcome.colour)
                                .put(
                                    "colourSaved",
                                    dev.ely.warp.build.NewProject.meta(dir)?.colour
                                        == outcome.colour,
                                )
                                .put("preview", dev.ely.warp.build.IconStudio.current(dir) != null)
                                .put(
                                    "previewRound",
                                    dev.ely.warp.build.IconStudio.currentRound(dir) != null,
                                )
                                // Decoded, not merely present. The Assets screen
                                // reads these files back with BitmapFactory, and
                                // "the file is on disk" and "the screen can draw
                                // it" are two different claims — the tiles fall
                                // back to a coloured letter that looks almost
                                // exactly like the real placeholder, so a broken
                                // decode would be invisible by eye.
                                .put(
                                    "previewPx",
                                    dev.ely.warp.build.IconStudio.current(dir)?.let {
                                        android.graphics.BitmapFactory.decodeFile(it.path)?.width
                                    } ?: 0,
                                )
                        }
                    }
                } catch (e: Throwable) {
                    out.put("ok", false).put("failed", "${e.javaClass.name}: ${e.message}")
                } finally {
                    dir.deleteRecursively()
                }

                // Not an icon check, but it needs a real compiler run to be
                // worth anything and this is the group that has one. jansi
                // fails to load on every single kotlinc run and puts an
                // UnsatisfiedLinkError at the top of the output the model reads
                // to find out what to fix — in a real session the actual error
                // sat underneath what looked like a crash in the compiler.
                out.put(
                    "noiseFiltered",
                    dev.ely.warp.build.BuildEngine.filtersNoise(
                        "Failed to load native library:jansi-2.4.0-x-libjansi.so\n" +
                            "java.lang.UnsatisfiedLinkError: /data/jansi.so: dlopen failed\n" +
                            "\tat org.fusesource.jansi.internal.JansiLoader.load\n" +
                            "src/MainActivity.kt:43:5: error: unresolved reference 'Bundle'."
                    ),
                )

                // The chosen model and its price, so the suite can check that the
                // picker and the card agree about what a tap costs.
                val model = dev.ely.warp.data.ImageModels.chosen(context)
                out.put("model", model.id)
                    .put("modelName", model.name)
                    .put("price", dev.ely.warp.ai.ImageGen.cents(model))
                    .put("options", dev.ely.warp.data.ImageModels.all.size)
                    // The ordering claim the Settings screen makes out loud. If
                    // this is ever false the screen is telling people something
                    // untrue about how to read the list.
                    .put(
                        "orderedByPrice",
                        dev.ely.warp.data.ImageModels.all
                            .zipWithNext().all { (a, b) -> a.centsEach > b.centsEach },
                    )
                    .put("tool", dev.ely.warp.tools.ALL_TOOLS["make_icon"]?.risk?.name)

                200 to out
            }

            /**
             * Does the image endpoint exist, and does the key open it — §8.
             *
             * Its own route rather than part of `/icon/probe`, because that one
             * is offline and must stay that way: a suite that quietly reaches
             * the network is a suite that fails on a train.
             *
             * **Asks for a model that does not exist.** A 404 is the answer that
             * proves the most for the least: the URL resolved, the key was
             * accepted, and the catalogue looked the name up and did not find
             * it — all without a picture being drawn or a cent being charged.
             * A 401 would mean the key; anything else is the endpoint itself.
             */
            "POST /icon/reach" -> {
                val out = JSONObject()
                val key = dev.ely.warp.ai.KeyVault.load(context, "openrouter")
                if (key.isNullOrBlank()) {
                    out.put("ok", false).put("failed", "no openrouter key on this phone")
                } else {
                    runCatching {
                        val c = java.net.URL(
                            "https://openrouter.ai/api/v1/images/generations"
                        ).openConnection() as java.net.HttpURLConnection
                        c.requestMethod = "POST"
                        c.connectTimeout = 30_000
                        c.readTimeout = 60_000
                        c.setRequestProperty("Content-Type", "application/json")
                        c.setRequestProperty("Authorization", "Bearer $key")
                        c.doOutput = true
                        c.outputStream.bufferedWriter().use {
                            it.write(
                                JSONObject()
                                    .put("model", "warp/definitely-not-a-model")
                                    .put("prompt", "probe")
                                    .put("aspect_ratio", "1:1")
                                    .toString()
                            )
                        }
                        val code = c.responseCode
                        val text = runCatching {
                            c.errorStream?.bufferedReader()?.readText().orEmpty()
                        }.getOrDefault("")
                        out.put("ok", true)
                            .put("code", code)
                            // 404 is the pass. Recorded as a number rather than
                            // as a verdict so a different answer is legible
                            // instead of merely "failed".
                            .put("keyAccepted", code != 401 && code != 403)
                            .put("body", text.take(200))
                    }.onFailure {
                        out.put("ok", false)
                            .put("failed", "${it.javaClass.simpleName}: ${it.message}")
                    }
                }
                200 to out
            }

            /**
             * Does installing refuse an APK older than the code — §8's real bug.
             *
             * Done with timestamps on a throwaway project rather than by
             * compiling, because the logic being checked *is* timestamps, and a
             * real build would put thirty seconds between the suite and an
             * answer it could have had instantly.
             *
             * Both directions matter. A guard that always refuses would pass a
             * one-sided test and make installing impossible.
             */
            "POST /install/stale" -> {
                val out = JSONObject()
                val dir = java.io.File(context.cacheDir, "stale-${System.currentTimeMillis()}")
                try {
                    dir.mkdirs()
                    dev.ely.warp.build.NewProject.create(dir, "Stale", "dev.ely.stale")

                    val apk = java.io.File(dir, "app.apk").apply { writeText("not really an apk") }
                    dev.ely.warp.build.NewProject.recordBuild(dir, apk)

                    // Built after everything: nothing to complain about.
                    apk.setLastModified(System.currentTimeMillis() + 5_000)
                    out.put("freshIsFine", dev.ely.warp.build.NewProject.staleReason(dir) == null)

                    // An icon drawn after the build — the exact case he hit.
                    val icon = java.io.File(dir, "res/mipmap-xxxhdpi/ic_launcher.png")
                    icon.setLastModified(System.currentTimeMillis() + 10_000)
                    val reason = dev.ely.warp.build.NewProject.staleReason(dir)
                    out.put("staleIsCaught", reason != null)
                        // The message must name the file. "Out of date" leaves
                        // you guessing which change is missing, and guessing is
                        // what sent him to reinstalling in the first place.
                        .put("namesTheFile", reason?.contains("ic_launcher.png") == true)
                        .put("reason", reason)

                    // warp.json is rewritten *after* the APK by recordBuild, so
                    // it is newer than every build that ever happens. If it were
                    // not excluded, nothing could ever be installed again.
                    java.io.File(dir, "warp.json")
                        .setLastModified(System.currentTimeMillis() + 20_000)
                    icon.setLastModified(System.currentTimeMillis())
                    out.put(
                        "metaIgnored",
                        dev.ely.warp.build.NewProject.staleReason(dir) == null,
                    )
                    out.put("ok", true)
                } catch (e: Throwable) {
                    out.put("ok", false).put("failed", "${e.javaClass.name}: ${e.message}")
                } finally {
                    dir.deleteRecursively()
                }
                200 to out
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
