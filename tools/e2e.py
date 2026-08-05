"""End-to-end check of Warp, driven through the debug surface.

Every assertion reads state back out of the app rather than trusting that a
request returned. That is the whole reason the surface answers in the first
place.
"""
import json
import sys
import threading
import time
import urllib.request
import subprocess

ADB = r"C:\Users\ely\Android\Sdk\platform-tools\adb.exe"

# 127.0.0.1, never "localhost".
#
# On Windows "localhost" resolves to ::1 first, and `adb forward` binds IPv4
# only — so every request waited about two seconds for the IPv6 attempt to fail
# before falling back. 2076 ms against 13 ms, on a route that returns {"ok":true}.
# It made the whole suite four minutes long and looked like a slow app.
BASE = "http://127.0.0.1:8099"
KEY = "test123"
passed, failed = [], []


def call(method, path, body=None, timeout=15, _retry=True):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("X-Warp-Key", KEY)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())
    except urllib.error.URLError:
        # The forward dies on its own. Seen after the system installer takes the
        # screen, after `adb install`, and after a replug — three causes, one
        # symptom, and the suite used to fall over with a stack trace blaming
        # whichever check happened to run next.
        if not _retry:
            raise
        subprocess.run([ADB, "forward", "tcp:8099", "tcp:8099"], capture_output=True)
        time.sleep(1)
        return call(method, path, body, timeout, _retry=False)


def settle(limit=25.0):
    """Wait until the turn is actually over, rather than guessing at it.

    Fixed sleeps were both slow and wrong: too long for the mock on a good run,
    too short whenever anything was slower than expected, and silent either way.
    A pending permission prompt counts as settled — the turn stays busy on
    purpose while it waits for a person.
    """
    deadline = time.time() + limit
    while time.time() < deadline:
        s, st = call("GET", "/state")
        asking_question = call("GET", "/question")[1].get("asking") is True
        if s == 200 and (st.get("busy") is False or st.get("askingTool") or asking_question):
            # One short beat so the last write lands before anything reads it.
            time.sleep(0.25)
            return True
        time.sleep(0.15)
    return False


def check(name, ok, detail=""):
    (passed if ok else failed).append(name)
    print(f"  {'PASS' if ok else 'FAIL'}  {name}{'  — ' + detail if detail else ''}")


# Wait for the screen to register itself before doing anything.
#
# The server answers as soon as the process is up, but the chat bridge is set
# from a composable — so for a moment after launch every route is live and the
# ones that need a screen return 503. A fixed sleep made this suite flaky: one
# run failed "the user message is stored verbatim" and the next passed, which is
# the worst kind of test, since it teaches you to re-run instead of to look.
# Bring Warp to the front first.
#
# MIUI freezes it in the background — the process survives, the socket stops
# answering — so a run started while the phone sat idle failed on its first
# request with a connection error. Waiting was not enough; it has to be woken.
subprocess.run([ADB, "shell", "am", "start", "-n",
                "dev.ely.warp/.MainActivity"], capture_output=True)
subprocess.run([ADB, "forward", "tcp:8099", "tcp:8099"], capture_output=True)

ready = False
for _ in range(60):
    # Anything can go wrong here, including the socket not existing yet, and
    # the point of a readiness loop is to survive exactly that. It used to call
    # through `call`, which re-raises, so the wait crashed instead of waiting.
    try:
        if call("GET", "/state", timeout=5)[0] == 200:
            ready = True
            break
    except Exception:
        pass
    time.sleep(0.5)
if not ready:
    print("  the app never became ready — is the phone connected and unlocked?")
    sys.exit(1)

# Force the mock provider before anything else.
#
# Every check here assumes scripted replies. Left on a real model the suite still
# runs, still mostly passes, and quietly spends the user's money on 120 requests
# — which is exactly what happened once: the model was switched by hand for a
# live test and the next suite run went to OpenRouter without saying so.
call("POST", "/model", {"provider": "mock", "model": "mock-fast"})

# An interrupted run can leave a question on screen and a turn waiting on it for
# ever. Clear it before anything else, or every later check inherits the mess.
call("POST", "/permission", {"decision": "DENY"})
call("POST", "/question/answer", {"text": "left over from an interrupted run"})
call("POST", "/chat/command", {"text": "/goal clear"})
# Rules are global and outlive a run. A rule left behind changes what the mock
# replies with, and unrelated sections start failing for a reason nothing names.
call("POST", "/chat/command", {"text": "/rules clear"})

# Everything that existed before this run.
#
# The suite creates a conversation for nearly every check and used to leave all
# of them behind: 240 chats and 19 apps had piled up, and the shelf was showing
# "Crashy" five times as though they were real work. Anything not in this set
# when the run ends was made by the run, and goes.
BEFORE = {c["id"] for c in call("GET", "/conversations")[1]["conversations"]}



# ── helpers, above every group ───────────────────────────────────────────
#
# Written where they were first needed, which was fine while the file ran
# top to bottom every time. With groups they have to sit above all of them,
# or asking for one group calls a function defined inside another.

seen_states = []

def project_path(rel=""):
    """Where the open chat's files live on the phone.

    There is no single "the project" any more — each conversation has its own
    folder, which is what makes the shelf possible. Every path in this suite
    goes through here so the layout is stated once.
    """
    cid = call("GET", "/state")[1].get("conversationId") or "_scratch"
    return f"files/projects/{cid}" + (f"/{rel}" if rel else "")

def on_device(*args):
    return subprocess.run([ADB, "shell", "run-as", "dev.ely.warp"] + list(args),
                          capture_output=True, text=True).stdout.strip()

def tool_cards():
    cid = call("GET", "/state")[1]["conversationId"]
    return [c for msg in call("GET", "/messages?id=" + cid)[1]["messages"]
            for c in msg.get("toolCalls", [])]

def new_project(name, package=None):
    args = {"name": name}
    if package is not None:
        args["package"] = package
    return call("POST", "/tool", {"name": "new_project", "args": args})[1]

def wipe_project():
    """Clear the open chat's project folder.

    Each conversation has its own folder now, so there is no single "the
    project" to delete any more. Wiping the old shared path silently did
    nothing, and the checks that followed then failed against whatever the
    previous section had built.
    """
    on_device("rm", "-rf", project_path())
    # Anything left from an older layout, so a phone that has been through the
    # migration does not keep failing on a folder nobody reads any more.
    on_device("rm", "-rf", "files/project")

def command(text):
    return call("POST", "/chat/command", {"text": text})[1].get("note")

def goal_state():
    return call("GET", "/goal")[1]

def idle(limit=90.0):
    deadline = time.time() + limit
    while time.time() < deadline:
        if not call("GET", "/state")[1]["busy"] and not goal_state()["running"]:
            time.sleep(0.3)
            return True
        time.sleep(0.2)
    return False

def watch_build(seconds=140):
    def run():
        for _ in range(seconds * 2):
            s = call("GET", "/build/status", timeout=10)[1].get("state")
            if s and (not seen_states or seen_states[-1] != s):
                seen_states.append(s)
            time.sleep(0.5)
    threading.Thread(target=run, daemon=True).start()

# Which groups to run: `py tools/e2e.py goal` runs only the goal checks.
#
# The whole suite is about seven minutes, because it compiles twice and walks a
# goal to its turn limit. Waiting seven minutes to check one thing is how you
# stop running it at all.
#
# Groups rather than single sections: sections 3 to 6 read a conversation that
# section 2 creates, so offering them apart would be offering a broken choice.
WANTED = {a.lower() for a in sys.argv[1:]}
GROUP_NAMES = ["surface", "chat", "tools", "permissions", "commands", "grill",
               "goal", "project", "build", "room", "subagent", "settings"]


def want(group):
    return not WANTED or group in WANTED


if WANTED - set(GROUP_NAMES):
    print("  unknown group(s):", ", ".join(sorted(WANTED - set(GROUP_NAMES))))
    print("  choose from:", ", ".join(GROUP_NAMES))
    sys.exit(2)
if WANTED:
    print("  running only:", ", ".join(sorted(WANTED)))


if want("surface"):
    print("\n1. THE SURFACE ITSELF")
    req = urllib.request.Request(BASE + "/state")
    try:
        urllib.request.urlopen(req, timeout=5)
        check("rejects a request with no key", False, "it answered")
    except urllib.error.HTTPError as e:
        check("rejects a request with no key", e.code == 401)

    s, _ = call("GET", "/health")
    check("answers with the right key", s == 200)

    s, b = call("GET", "/nowhere")
    check("unknown route is a 404, not a crash", s == 404 and "error" in b)


if want("chat"):
    print("\n2. STARTING A CONVERSATION")
    call("POST", "/nav", {"to": "CHAT"})
    call("POST", "/chat/new")
    s, st = call("GET", "/state")
    check("a new chat has no conversation yet", st["conversationId"] is None,
          "created by the first message, not by the button")

    s, sent = call("POST", "/chat/send", {"text": "end to end check please"})
    check("sending returns the stored message id", s == 200 and "messageId" in sent)
    settle()

    s, st = call("GET", "/state")
    cid = st["conversationId"]
    check("a conversation now exists", cid is not None)
    check("the reply arrived", st["messageCount"] == 2, f"messageCount={st['messageCount']}")
    check("it is no longer busy", st["busy"] is False)


    print("\n3. WHAT WAS STORED IS WHAT WAS SENT")
    s, msgs = call("GET", f"/messages?id={cid}")
    texts = [(m["role"], m["text"]) for m in msgs["messages"]]
    check("the user message is stored verbatim",
          any(r == "USER" and t == "end to end check please" for r, t in texts))
    check("the assistant reply is stored",
          any(r == "ASSISTANT" and t for r, t in texts))
    check("the question is stored before the answer",
          texts[0][0] == "USER", "the rowid tiebreak holding")


    print("\n4. IT NAMED ITSELF")
    s, convs = call("GET", "/conversations")
    mine = next((c for c in convs["conversations"] if c["id"] == cid), None)
    check("the new conversation is in the list", mine is not None)
    if mine:
        check("it was named from the first message, not left as 'New chat'",
              mine["title"] != "New chat", f"title={mine['title']!r}")


    print("\n5. RENAME, PIN, DELETE, UNDO")
    s, r = call("POST", "/conversation/rename", {"id": cid, "title": "renamed by the test"})
    check("rename is readable back from the database",
          r.get("title") == "renamed by the test", f"read back {r.get('title')!r}")

    s, r = call("POST", "/conversation/pin", {"id": cid, "pinned": True})
    check("pin is readable back", r.get("pinned") is True)

    s, r = call("POST", "/conversation/delete", {"id": cid})
    check("a deleted conversation leaves the list", r.get("stillListed") is False)

    s, r = call("POST", "/conversation/undelete", {"id": cid})
    check("undo brings it back", r.get("stillListed") is True)


    print("\n6. SEARCH")
    s, r = call("GET", "/search?q=renamed")
    check("finds a conversation by its name",
          any(c["id"] == cid for c in r["results"]), f"{len(r['results'])} result(s)")

    s, r = call("GET", "/search?q=r")
    check("a single letter works — the prefix bug stays fixed", len(r["results"]) > 0,
          f"{len(r['results'])} result(s)")

    s, r = call("GET", "/search?q=zzzznope")
    check("no matches returns nothing rather than everything", len(r["results"]) == 0)

    s, a = call("GET", "/search?q=e&order=alpha")
    s, b = call("GET", "/search?q=e&order=recent")
    check("the two orderings are honoured",
          a["order"] == "ALPHABETICAL" and b["order"] == "RECENT")
    if len(a["results"]) > 1:
        titles = [c["title"].lower() for c in a["results"]]
        check("A–Z really is sorted", titles == sorted(titles),
              f"{len(titles)} titles, {titles[0]!r} first")


if want("tools"):
    print("\n7. TOOLS REALLY RUN")
    # Nothing in this section is scripted. The mock asks for list_dir, the runner
    # walks the app's own project folder, and the result goes back into the next
    # request. Every earlier version of the tool cards passed by looking right.
    call("POST", "/chat/new")
    # A brand new chat has an empty folder, and list_dir returning nothing is then
    # correct rather than broken. Give it a project so the check is about the tool
    # round-trip and not about an empty directory.
    call("POST", "/chat/send", {"text": "hello"})
    settle()
    call("POST", "/tool", {"name": "new_project", "args": {"name": "Looker"}})
    call("POST", "/chat/send", {"text": "look at the project files"})
    settle()
    s, st = call("GET", "/state")
    s, m = call("GET", "/messages?id=" + st["conversationId"])
    msgs = m["messages"]
    calls = [c for msg in msgs for c in msg.get("toolCalls", [])]

    check("the model's tool call was executed",
          len(calls) == 1 and calls[0]["status"] == "DONE",
          f"{[(c['name'], c['status']) for c in calls]}")
    check("the result says what it did, not 'ok'",
          bool(calls) and "entries" in (calls[0]["result"] or ""),
          f"result={calls[0]['result'] if calls else None}")
    check("the full output came back with it",
          bool(calls) and bool(calls[0]["body"]),
          f"body={calls[0]['body'] if calls else None!r}")
    check("the tool output reached the model",
          len(msgs) >= 3 and bool(calls)
          and (calls[0]["body"] or "|").split("\n")[0] in msgs[-1]["text"],
          "the last reply quotes what the tool found")
    # Five: hello + reply, then the request + the tool turn + the turn that
    # reports what came back. The number matters only as "it did not balloon" —
    # the real assertion is the single tool call above.
    check("one round of tools, not eight — the budget is not the brake",
          len(msgs) == 5, f"messages={len(msgs)}")

    s, out = call("POST", "/tool",
                  {"name": "read_file", "args": {"path": "../databases/warp.db"}})
    check("a path outside the project is refused",
          "outside" in json.dumps(out), json.dumps(out)[:90])


if want("permissions"):
    print("\n8. NOTHING IS WRITTEN WITHOUT YOU")
    # The one path in Warp that can destroy work. Every check here reads the disk
    # through run-as, not the card — a card saying DENIED while the file changed is
    # precisely the failure worth catching.








    call("POST", "/permission/revoke")
    on_device("rm", "-f", project_path("src/Counter.kt"))

    call("POST", "/chat/new")
    call("POST", "/chat/send", {"text": "create a new file for me"})
    settle()
    s, st = call("GET", "/state")
    check("a write stops and asks", st.get("askingTool") == "write_file",
          f"asking={st.get('askingTool')}")
    check("it names the file, not the tool", "Counter.kt" in (st.get("askingAbout") or ""),
          f"{st.get('askingAbout')!r}")
    check("the turn is still open while it waits", st["busy"] is True)

    call("POST", "/permission", {"decision": "DENY"})
    time.sleep(3)
    check("no really means no — nothing on disk",
          "Counter.kt" not in on_device("ls", project_path("src")),
          on_device("ls", project_path("src")).replace("\n", " "))
    check("and the card says so",
          any(c["status"] == "DENIED" for c in tool_cards()))

    call("POST", "/chat/new")
    call("POST", "/chat/send", {"text": "create a new file for me"})
    settle()
    call("POST", "/permission", {"decision": "ONCE"})
    settle()
    check("Allow writes the file for real",
          "Counter.kt" in on_device("ls", project_path("src")))
    check("the file holds what was asked for",
          "fun main()" in on_device("cat", project_path("src/Counter.kt")))
    check("the card reports what happened, not 'ok'",
          any("created" in (c["result"] or "") for c in tool_cards()),
          f"{[c['result'] for c in tool_cards()]}")
    check("Allow once grants nothing standing",
          call("GET", "/permission")[1]["granted"] == [])

    call("POST", "/chat/new")
    call("POST", "/chat/send", {"text": "create a new file for me"})
    settle()
    call("POST", "/permission", {"decision": "ALWAYS"})
    time.sleep(3)
    trusting = call("GET", "/state")[1]["conversationId"]
    check("Always is remembered",
          call("GET", "/permission")[1]["granted"] == ["write_file"])

    # Same chat: it must not ask again.
    call("POST", "/chat/send", {"text": "create a new file for me"})
    settle()
    st = call("GET", "/state")[1]
    check("the same chat is not asked again",
          st.get("askingTool") is None and st["busy"] is False)
    check("and the write still happened",
          any(c["status"] == "DONE" for c in tool_cards()))

    # A different chat: it must ask. This is the whole point of per-chat scope —
    # a decision made in one piece of work must not silently apply to another.
    call("POST", "/chat/new")
    call("POST", "/chat/send", {"text": "create a new file for me"})
    settle()
    st = call("GET", "/state")[1]
    check("a DIFFERENT chat still asks", st.get("askingTool") == "write_file",
          f"asking={st.get('askingTool')}")
    check("and it starts with nothing granted",
          call("GET", "/permission")[1]["granted"] == [])
    call("POST", "/permission", {"decision": "DENY"})
    time.sleep(2)

    s, r = call("POST", "/permission/revoke", {"id": trusting})
    check("revoke takes an Always back, per chat", r.get("granted") == [],
          json.dumps(r)[:80])

    s, r = call("GET", "/permission?id=" + trusting)
    check("and it stays taken back", r["granted"] == [])

    # The /tool route works in whichever chat is open, and by now that is a
    # different one from the chat the earlier Allow wrote into. Put a file there
    # first, so this section tests edit_file rather than testing which chat is open.
    call("POST", "/tool", {"name": "write_file", "args": {
        "path": "src/Counter.kt",
        "content": "fun main() {\n    var count = 0\n    println(count)\n}\n"}})

    s, r = call("POST", "/tool", {"name": "edit_file", "args": {
        "path": "src/Counter.kt", "old": "var count = 0", "new": "var count = 100"}})
    check("edit_file replaces a unique piece of text", "+ var count = 100" in json.dumps(r),
          json.dumps(r)[:70])

    s, r = call("POST", "/tool", {"name": "edit_file", "args": {
        "path": "src/Counter.kt", "old": "count", "new": "tally"}})
    check("an ambiguous edit is refused rather than guessed",
          "appears" in (r.get("failed") or ""), json.dumps(r)[:70])

    s, r = call("POST", "/tool", {"name": "write_file", "args": {
        "path": "../escaped.txt", "content": "no"}})
    check("a write outside the project is refused", "outside" in (r.get("failed") or ""))


if want("commands"):
    print("\n9. COMMANDS")
    # /chat/command runs the same function the send button runs. A second code path
    # for testing would eventually disagree with the one people actually use.




    command("/rules clear")
    check("a rule is added, and says which number it got",
          command("/rules add never touch the manifest") == "Rule 1 added.")
    check("the same rule twice is refused, not silently dropped",
          "already there" in (command("/rules add never touch the manifest") or ""))
    command("/rules add always run the tests")
    check("both are stored", call("GET", "/rules")[1]["rules"] ==
          ["never touch the manifest", "always run the tests"])

    # The rule has to reach the model. A rule that is stored and never sent is the
    # first failure §5d names, and it looks exactly like one that works.
    call("POST", "/chat/new")
    command("what were you told")
    settle()
    cid = call("GET", "/state")[1]["conversationId"]
    reply = call("GET", "/messages?id=" + cid)[1]["messages"][-1]["text"]
    check("the rules reach the model", "never touch the manifest" in reply,
          repr(reply[:70]))

    call("POST", "/chat/new")
    command("/plan build me a todo app")
    settle()
    msgs = call("GET", "/messages?id=" + call("GET", "/state")[1]["conversationId"])[1]["messages"]
    check("what you typed is what the transcript shows",
          msgs[0]["text"] == "/plan build me a todo app")
    check("/plan reaches the model as a plan turn",
          "Planning only" in msgs[-1]["text"], repr(msgs[-1]["text"][:60]))
    # The load-bearing one. Telling a model not to write is a request; not giving it
    # a write tool is a boundary.
    check("and the writing tools are not offered at all",
          "write_file" not in msgs[-1]["text"] and "read_file" in msgs[-1]["text"],
          repr(msgs[-1]["text"][-70:]))

    check("a slash inside a sentence is not a command",
          command("what does src/main mean") == "sent")
    settle()
    check("an unbuilt command says so rather than doing nothing",
          command("/build") == "/build is not built yet.")
    check("removing by number reports what went",
          command("/rules remove 1") == "Removed: never touch the manifest")
    check("and the list really shrank",
          call("GET", "/rules")[1]["rules"] == ["always run the tests"])
    command("/rules clear")


if want("grill"):
    print("\n10. /GRILL-ME ASKS, ONE AT A TIME")
    # The value of a grilling is the *second* question — the one shaped by your last
    # answer. A single question proves the tool works and proves nothing about the
    # loop, which is exactly the state this shipped in for ten minutes.
    call("POST", "/chat/new")
    call("POST", "/chat/command", {"text": "/grill-me the todo app"})
    settle()

    q = call("GET", "/question")[1]
    check("it asks a question and waits", q.get("asking") is True)
    check("with concrete options", 2 <= len(q.get("options") or []) <= 4, f"{q.get('options')}")
    check("and says which it recommends", q.get("recommended") is not None)
    check("and why", bool(q.get("because")))
    check("only one question is open at a time",
          isinstance(q.get("question"), str) and "?" in q["question"],
          repr(q.get("question")))

    first = q["options"][q["recommended"]]
    call("POST", "/question/answer", {"option": q["recommended"]})
    settle()

    q2 = call("GET", "/question")[1]
    check("a second question follows", q2.get("asking") is True, repr(q2.get("question")))
    check("it is a different question", q2.get("question") != q.get("question"))
    check("and it was shaped by the first answer",
          "chose" in (q2.get("because") or "").lower(), repr(q2.get("because")))

    # The path every real answer eventually takes.
    call("POST", "/question/answer", {"text": "export to a file every night"})
    settle()

    grilled = call("GET", "/state")[1]["conversationId"]
    msgs = call("GET", "/messages?id=" + grilled)[1]["messages"]
    answers = [c["result"] for m in msgs for c in m.get("toolCalls", []) if c["name"] == "ask"]
    check("both answers are recorded verbatim",
          answers == [first, "export to a file every night"], f"{answers}")
    check("a written answer is taken as written",
          "export to a file every night" in msgs[-1]["text"], repr(msgs[-1]["text"][:80]))
    check("nothing is still being asked at the end",
          call("GET", "/question")[1].get("asking") is False)

    # Resumable, in the only sense that matters: the questions and answers are in
    # the transcript, so reopening the chat finds them rather than starting over.
    call("POST", "/chat/new")
    call("POST", "/chat/open", {"id": grilled})
    time.sleep(1)
    reopened = call("GET", "/messages?id=" + grilled)[1]["messages"]
    kept = [c["result"] for m in reopened for c in m.get("toolCalls", []) if c["name"] == "ask"]
    check("the answers survive reopening the chat", kept == answers, f"{kept}")

    # A grilling is questions, and questions were counted against the tool-round
    # budget — so a real model asked eight and was cut off with "Stopped after 8
    # rounds of tool calls", which reads as a fault rather than a limit. The mock
    # could only ever ask two, so nothing here could have caught it.
    call("POST", "/chat/new")
    command("/grill-me grill me hard about a habit tracker")
    answered = 0
    for _ in range(80):
        time.sleep(0.4)
        if call("GET", "/question", timeout=10)[1].get("asking"):
            call("POST", "/question/answer", {"option": 0})
            answered += 1
            continue
        if not call("GET", "/state", timeout=10)[1].get("busy") and answered:
            break

    check("a long grilling is not cut off by the round budget", answered >= 10,
          f"{answered} questions answered")
    msgs = call("GET", "/messages?id=" + call("GET", "/state")[1]["conversationId"])[1]["messages"]
    check("and it ends without an error",
          not any(m["error"] for m in msgs),
          next((m["error"] for m in msgs if m["error"]), "")[:70])


if want("goal"):
    print("\n11. /GOAL WORKS ACROSS TURNS, AND STOPS")
    # The dangerous command. §5d's third failure is "frozen while claiming to work",
    # and /goal is the feature that causes it, because it removes the turn boundary
    # where you would otherwise notice. So all three exits are checked: reaching the
    # goal, running out of budget, and being stopped by hand.






    check("/goal with nothing running says so",
          command("/goal") == "No goal is running.")

    call("POST", "/chat/new")
    command("/goal the counter file exists")
    turns = set()
    steps_seen = False
    for _ in range(40):
        time.sleep(0.4)
        g = goal_state()
        if not g["running"]:
            break
        turns.add(g["turn"])
        if "step" in (g.get("label") or ""):
            steps_seen = True

    check("it ran more than one turn", len(turns) > 1, f"turns seen: {sorted(turns)}")
    check("the count climbs rather than sitting still", turns == set(range(1, max(turns) + 1)),
          f"{sorted(turns)}")

    # The bar led with "turn 1 of 10" and sat there through an entire successful
    # run, because a model that reads, writes, builds and fixes does all of it
    # inside one turn. A progress signal that does not move is the failure the
    # feature exists to prevent.
    check("the bar reports steps, not just turns",
          "step" in (call("GET", "/goal")[1].get("label") or "")
          or steps_seen, f"{sorted(turns)}")

    cid = call("GET", "/state")[1]["conversationId"]
    msgs = call("GET", "/messages?id=" + cid)[1]["messages"]
    done = [c for m in msgs for c in m.get("toolCalls", []) if c["name"] == "goal_done"]
    check("it ended by declaring the goal reached", len(done) == 1, f"{len(done)} goal_done calls")
    check("and had to say how it knows", bool(done and done[0]["body"]),
          repr(done[0]["body"]) if done else "")
    # The bug this section exists for: goal_done was not terminal inside a turn, so
    # the round loop kept calling it and paid for nine more turns after finishing.
    #
    # Asserted on the shape rather than on a message count. The count version broke
    # the moment the mock's goal turns started using a tool — more messages per turn,
    # same correct behaviour — which is a test measuring the wrong thing.
    done_index = next(i for i, m in enumerate(msgs)
                      if any(c["name"] == "goal_done" for c in m.get("toolCalls", [])))
    check("it stopped the moment it was done, not later",
          done_index == len(msgs) - 1,
          f"goal_done at {done_index} of {len(msgs) - 1}")
    check("nothing is left running", goal_state()["running"] is False)

    call("POST", "/chat/new")
    command("/goal it never finishes")
    check("the condition is the goal, not the keystrokes",
          goal_state()["condition"] == "it never finishes", repr(goal_state()["condition"]))
    idle(240)
    msgs = call("GET", "/messages?id=" + call("GET", "/state")[1]["conversationId"])[1]["messages"]
    # It used to end at the limit and throw the goal away. He finished the same work
    # by hand afterwards by saying "continue" — so the limit pauses now.
    check("the budget pauses it rather than ending it",
          "Paused after" in (msgs[-1]["error"] or ""), repr(msgs[-1]["error"])[:90])
    check("and says what it has not reached",
          "it never finishes" in (msgs[-1]["error"] or ""))
    check("the goal survives the pause", goal_state().get("running") is True,
          json.dumps(goal_state())[:90])
    check("and the bar offers Continue", "Continue" in (goal_state().get("label") or ""),
          goal_state().get("label") or "")

    s, r = call("POST", "/goal/continue", {})
    check("Continue picks it up again", r.get("resumed") is True, json.dumps(r)[:70])
    time.sleep(2)
    check("and it is working rather than paused",
          goal_state().get("running") is True and "Continue" not in (goal_state().get("label") or ""),
          goal_state().get("label") or "")
    call("POST", "/chat/command", {"text": "/goal clear"})

    call("POST", "/chat/new")
    command("/goal it never finishes")
    time.sleep(2)
    check("stopping names what was stopped",
          command("/goal clear") == "Stopped: it never finishes")
    time.sleep(1)
    check("nothing is running after a stop", goal_state()["running"] is False)
    check("and the turn really ended", call("GET", "/state")[1]["busy"] is False)
    # No corpse: §5f asks for the request, the process and the loop, all three.
    before = call("GET", "/state")[1]["messageCount"]
    time.sleep(4)
    check("no messages arrive after stopping",
          call("GET", "/state")[1]["messageCount"] == before,
          f"{before} then {call('GET', '/state')[1]['messageCount']}")


if want("project"):
    print("\n12. A NEW PROJECT IS ONE A COMPILER ACCEPTS")
    # The point of this section is the last check, and only the last check. A
    # scaffold that writes plausible files but does not compile is worse than none:
    # every build after it fails for a reason that looks like your code.






    wipe_project()
    check("an empty folder is not a project",
          call("GET", "/project")[1]["exists"] is False)

    r = new_project("Notes")
    check("the tool creates one", "com.example.notes" in (r.get("summary") or ""), json.dumps(r)[:80])

    p = call("GET", "/project")[1]
    check("it knows what it made", p["name"] == "Notes" and p["applicationId"] == "com.example.notes")
    check("and wrote what a build needs",
          {"AndroidManifest.xml", "res/values/strings.xml", "src/MainActivity.kt"}
          <= set(p["files"]), f"{p['files']}")

    # It never deletes. SampleProject.write starts with deleteRecursively, which is
    # right for a throwaway and catastrophic for somebody's work.
    check("it refuses to overwrite an existing project",
          "already a project" in (new_project("Something Else").get("failed") or ""))
    check("and the first project is untouched",
          call("GET", "/project")[1]["name"] == "Notes")

    for bad, expect in [("notes", "at least one dot"),
                        ("com.example.class", "reserved word"),
                        ("com.example.9lives", "cannot start with"),
                        ("com..x", "empty part"),
                        ("com.exa-mple.x", "letters, digits")]:
        wipe_project()
        check(f"refuses the package {bad!r}",
              expect in (new_project("X", bad).get("failed") or ""),
              new_project("X", bad).get("failed") or "")

    wipe_project()
    check("a name starting with a digit still makes a legal package",
          "com.example.app3dpaint" in (new_project("3D Paint").get("summary") or ""))
    wipe_project()
    check("a name with no letters at all is refused",
          "needs a name" in (new_project("   ").get("failed") or ""))

    wipe_project()
    new_project("Notes & Co")
    strings = on_device("cat", project_path("res/values/strings.xml"))
    check("the app name is escaped for XML", "Notes &amp; Co" in strings,
          strings.splitlines()[-2].strip() if strings else "")

    # The only check that matters. Slow on purpose: it really compiles, on the
    # phone, with the real toolchain.
    s, built = call("POST", "/project/build", {}, timeout=600)
    check("the project it made actually compiles", built.get("ok") is True,
          json.dumps(built)[:200])
    if built.get("ok"):
        check("into a signed APK", built.get("signed") is True and built.get("bytes", 0) > 100_000,
              f"{built.get('apk')} {built.get('bytes')} bytes in {built.get('ms')} ms")


if want("build"):
    print("\n13. BUILD, LAUNCH, AND READING A CRASH")
    # The loop this whole project exists for: write, build, read the error, fix,
    # build again. Slow on purpose — it really compiles, twice.
    #
    # `install` is not driven here and cannot be: Android's installer is a separate
    # screen a person has to agree to. What IS checked is that install refuses
    # honestly when there is nothing to install.

    # A previous run may have left the app installed, which would make "launch
    # refuses when it is not installed" pass or fail depending on history.
    subprocess.run([ADB, "uninstall", "com.example.crashy"], capture_output=True)

    wipe_project()
    check("build refuses with no project, and names the fix",
          "new_project" in (call("POST", "/tool", {"name": "build", "args": {}},
                                 timeout=600)[1].get("failed") or ""))
    check("install refuses with no project",
          bool(call("POST", "/tool", {"name": "install", "args": {}}, timeout=60)[1].get("failed")))

    new_project("Crashy")
    check("install refuses before anything is built",
          "nothing built yet" in
          (call("POST", "/tool", {"name": "install", "args": {}}, timeout=60)[1].get("failed") or ""))
    check("launch refuses when it is not installed",
          "not installed" in
          (call("POST", "/tool", {"name": "launch", "args": {}}, timeout=60)[1].get("failed") or ""))

    broken = 'package com.example.crashy\n\nclass MainActivity {\n    val x: Int = "no"\n}\n'
    call("POST", "/tool", {"name": "write_file",
                           "args": {"path": "src/MainActivity.kt", "content": broken}})
    s, r = call("POST", "/tool", {"name": "build", "args": {}}, timeout=600)
    fail = r.get("failed") or ""
    check("a broken project fails to build", bool(fail), json.dumps(r)[:70])
    # The whole reason build returns the raw output: the model has to read this and
    # fix it, and a summary would throw away the line number.
    check("and the failure carries the compiler's own words",
          "MainActivity.kt:4" in fail and "expected 'Int'" in fail,
          next((l for l in fail.splitlines() if "MainActivity.kt" in l), fail[:90]))

    fixed = ('package com.example.crashy\n\nimport android.app.Activity\n'
             'import android.os.Bundle\n\nclass MainActivity : Activity() {\n'
             '    override fun onCreate(savedInstanceState: Bundle?) {\n'
             '        super.onCreate(savedInstanceState)\n'
             '    }\n}\n')
    call("POST", "/tool", {"name": "write_file",
                           "args": {"path": "src/MainActivity.kt", "content": fixed}})
    s, r = call("POST", "/tool", {"name": "build", "args": {}}, timeout=600)
    check("fixing it makes the build pass", "built" in (r.get("summary") or ""),
          r.get("summary") or json.dumps(r)[:90])

    # Crash reporting, without needing anyone to tap Install. Every app Warp builds
    # posts its own death to this provider; here we post one by hand and check the
    # tool reads it. Logcat could not do this at all — Android hides another app's
    # log unless READ_LOGS is granted, and on Xiaomi that grant is one-time.
    # No colons and no newlines in the value: `adb shell content` parses bindings
    # as key:type:value and splits the command on spaces, so a real stack trace
    # cannot survive the trip. That is a limitation of this injection route, not of
    # the provider — a real crash arrives from the app itself, formatted freely.
    trace = ("java.lang.RuntimeException Unable to start activity "
             "Caused by java.lang.ArrayIndexOutOfBoundsException length=2 index=7")
    subprocess.run(
        [ADB, "shell",
         "content insert --uri content://dev.ely.warp.crashes "
         "--bind package:s:com.example.crashy "
         f"--bind trace:s:'{trace}'"],
        capture_output=True, text=True)

    s, r = call("POST", "/tool", {"name": "logcat", "args": {}}, timeout=120)
    check("a delivered crash is found", "CRASHED" in (r.get("summary") or ""),
          (r.get("summary") or json.dumps(r))[:100])
    # "CRASHED - Thread: main" was the first version of this, which is true and
    # says nothing. The headline has to name the actual fault.
    check("and the headline names the real fault, not the wrapper",
          "ArrayIndexOutOfBounds" in (r.get("summary") or ""), r.get("summary") or "")
    check("the whole trace comes back with it",
          "Unable to start activity" in (r.get("body") or ""))

    # Install the APK Warp just built, over adb. The `install` tool cannot be driven
    # from here — Android's installer is a screen a person agrees to — but the APK
    # itself can still be proven installable, which is the part that could break.
    import tempfile, os
    apk = os.path.join(tempfile.gettempdir(), "e2e-built.apk")
    with open(apk, "wb") as f:
        f.write(subprocess.run(
            [ADB, "exec-out", "run-as", "dev.ely.warp",
             "cat", "files/work/build/com.example.crashy.apk"],
            capture_output=True).stdout)
    installed = subprocess.run([ADB, "install", "-r", apk], capture_output=True, text=True)
    check("the APK Warp built really installs", "Success" in installed.stdout,
          installed.stdout.strip()[:80] or installed.stderr.strip()[:80])

    s, r = call("POST", "/tool", {"name": "launch", "args": {}}, timeout=60)
    check("and then launch reaches it", bool(r.get("summary")), json.dumps(r)[:80])
    time.sleep(3)
    s, r = call("POST", "/tool", {"name": "logcat", "args": {}}, timeout=120)
    # A stale crash sends you to fix something already fixed, which is worse than no
    # crash at all. A launch that never started anything must NOT clear it, though —
    # that would erase the evidence of the failure you are looking at.
    check("launching forgets the previous crash",
          "CRASHED" not in (r.get("summary") or ""), (r.get("summary") or "")[:90])


if want("room"):
    print("\n14. THE ROOM CARRIES THE BUILD")
    # The wash behind the composer is what tells you the phone is compiling. Section
    # 9h says the room IS the progress, which is why there is no bar anywhere. A slow
    # glow is not something a test can look at, but the state machine driving it is.
    import threading





    check("the room is still when nothing is building",
          call("GET", "/build/status")[1]["state"] == "IDLE")

    call("POST", "/chat/new")
    call("POST", "/chat/send", {"text": "hello"})
    settle()
    new_project("Roomy")
    call("POST", "/tool", {"name": "write_file", "args": {
        "path": "src/MainActivity.kt",
        "content": "package com.example.roomy\n\nclass MainActivity { val x: Int = 1 + \"no\" }\n"}})

    seen_states.clear()
    watch_build()
    call("POST", "/tool", {"name": "build", "args": {}}, timeout=600)
    time.sleep(2)
    # The leading IDLE is incidental: the sampler sometimes starts after the build
    # has already begun, and asserting on it made a passing behaviour look broken.
    # What matters is that it moved and then went red.
    check("it moves while compiling, then goes red",
          "RUNNING" in seen_states and seen_states[-1] == "FAILED",
          " -> ".join(seen_states))
    time.sleep(3)
    # 9h: "pulls red, and stays until it has been looked at". A failure that fades on
    # a timer is one you can miss by looking away.
    check("and the red stays rather than fading",
          call("GET", "/build/status")[1]["state"] == "FAILED")

    call("POST", "/tool", {"name": "write_file", "args": {
        "path": "src/MainActivity.kt",
        "content": ("package com.example.roomy\n\nimport android.app.Activity"
                    "\n\nclass MainActivity : Activity()\n")}})
    seen_states.clear()
    watch_build()
    call("POST", "/tool", {"name": "build", "args": {}}, timeout=600)
    time.sleep(3)
    check("a good build settles instead of staying lit",
          "SUCCEEDED" in seen_states and seen_states[-1] == "IDLE",
          " -> ".join(seen_states))

    # The APK has to survive the next app being built. It did not: one shared work
    # folder meant compiling B deleted A's APK, and the shelf then said "not built"
    # for something you had watched build.
    roomy = [a for a in call("GET", "/apps")[1]["apps"] if a["name"] == "Roomy"]
    check("the app keeps its own APK", bool(roomy) and roomy[0]["built"] is True,
          f"{roomy[:1]}")


if want("subagent"):
    print("\n14b. A HELPER, AND THE FENCE IT WORKS INSIDE")
    # §5c. Every check here is about a boundary, because a boundary written into
    # a prompt is a suggestion — the helper is held by what it is *given*.
    #
    # The mock plays the helper as well as the agent, so this costs nothing. It
    # reads when the job says `read <path>`, and never stops when the job says
    # `keep reading` — which is the only way the window and the budget can be
    # fired on purpose rather than hoped about. Without that the whole loop
    # returned a greeting after zero reads and every check below passed untried.

    call("POST", "/chat/new")
    call("POST", "/chat/send", {"text": "hello"})
    settle()
    new_project("Helper")
    call("POST", "/tool", {"name": "write_file", "args": {
        "path": "src/Thing.kt",
        "content": 'package app\n\nfun broken(): Int {\n    return "not an int"\n}\n'}})
    call("POST", "/tool", {"name": "write_file", "args": {
        "path": "secret.txt", "content": "a helper must never read this\n"}})

    # Somebody has to be there to answer "which model should the helper use?",
    # and it cannot be this thread: the /tool request blocks until the tool
    # returns, and the tool is waiting for the answer. So a watcher answers from
    # the side, which is also the only way to check it is asked exactly once.
    asked = []

    def answerer():
        end = time.time() + 240
        while time.time() < end:
            q = call("GET", "/question")[1]
            if q.get("asking"):
                asked.append(q.get("question") or "")
                options = q.get("options") or []
                call("POST", "/question/answer",
                     {"text": options[0] if options else "ok"})
            time.sleep(0.3)

    threading.Thread(target=answerer, daemon=True).start()

    def errand(args, timeout=180):
        return call("POST", "/tool", {"name": "delegate", "args": args}, timeout=timeout)[1]

    # Refused before a single token is spent. These are the cheap structural
    # faults, and they are checked ahead of whether a helper even exists — the
    # first version asked for the helper first, which made every one of them
    # unreachable and green.
    check("a helper with no declared output shape is refused",
          "return" in (errand({"job": "why", "files": ["src/Thing.kt"]}).get("failed") or ""))
    check("a helper with no window is refused",
          "no files" in (errand({"job": "why", "files": [], "returns": "the cause"})
                         .get("failed") or ""))
    check("a window wider than the cap is refused",
          "at most" in (errand({"job": "why", "returns": "the cause",
                                "files": [f"src/f{i}.kt" for i in range(6)]})
                        .get("failed") or ""))
    check("a window reaching outside the project is refused",
          "outside" in (errand({"job": "why", "returns": "the cause",
                                "files": ["../../etc/hosts"]}).get("failed") or ""))
    check("a window naming a file that is not there is refused",
          "not a file" in (errand({"job": "why", "returns": "the cause",
                                   "files": ["src/Nope.kt"]}).get("failed") or ""))

    r = errand({"job": "read src/Thing.kt and say what is wrong",
                "files": ["src/Thing.kt"], "returns": "the cause"})
    check("a read inside the window happens for real",
          "1 step" in (r.get("summary") or ""), f"summary={r.get('summary')!r}")
    check("and what it read reached it", "not an int" in (r.get("body") or ""),
          f"{(r.get('body') or '')[:90]!r}")

    # Asked once, then remembered for the chat — the same shape as Always on a
    # write. Skipped rather than failed when this phone has only one model
    # available, because then there is nothing to choose between and asking
    # anyway is the behaviour worth *not* having.
    if asked:
        check("it asked which model the helper should use",
              any("model" in q.lower() for q in asked), f"{asked}")
        before = len(asked)
        errand({"job": "read src/Thing.kt again", "files": ["src/Thing.kt"],
                "returns": "the cause"})
        check("and it does not ask a second time in the same chat",
              len(asked) == before, f"asked {len(asked) - before} more time(s)")
    else:
        print("  ....  only one model available — nothing to ask about, skipped")

    # The one that matters most. A helper is handed a segment, not the rules —
    # so the only thing stopping it reading your secrets is that the path is
    # checked against the window before anything is opened.
    r = errand({"job": "read secret.txt and say what is wrong",
                "files": ["src/Thing.kt"], "returns": "the cause"})
    check("a read OUTSIDE the window is refused",
          "not in your window" in (r.get("body") or ""), f"{(r.get('body') or '')[:110]!r}")
    check("and the secret never reached it",
          "never read this" not in (r.get("body") or ""), f"{(r.get('body') or '')[:110]!r}")

    r = errand({"job": "keep reading. read src/Thing.kt again and again",
                "files": ["src/Thing.kt"], "returns": "the cause", "max_steps": 2})
    check("the step budget stops it", "2 steps" in (r.get("summary") or ""),
          f"summary={r.get('summary')!r}")
    # Said on the card, not buried. A partial answer read as a whole one is
    # worse than no answer, and the agent reading this is the one that must know.
    check("and the card says it was cut off, not that it finished",
          "stopped at its budget" in (r.get("summary") or ""),
          f"summary={r.get('summary')!r}")

    call("POST", "/chat/new")
    call("POST", "/chat/command", {"text": "/plan build me a thing"})
    settle()
    plan = call("GET", "/messages?id=" + call("GET", "/state")[1]["conversationId"]
                )[1]["messages"][-1]["text"] or ""
    check("a plan turn is not offered a helper — it spends money",
          "delegate" not in plan, repr(plan[-140:]))


if want("settings"):
    print("\n15. SETTINGS ROUND-TRIP")
    s, r = call("POST", "/settings", {"name": "ambient", "value": "false"})
    check("turning the atmosphere off reads back as off", r.get("value") == "false")
    s, r = call("POST", "/settings", {"name": "ambient", "value": "true"})
    check("and back on", r.get("value") == "true")

    s, r = call("POST", "/settings", {"name": "nonsense", "value": "x"})
    check("an unknown setting is refused rather than silently ignored", s == 400)


    print("\n16. NAVIGATION")
    for dest in ("SETTINGS", "BUILD", "CHAT"):
        call("POST", "/nav", {"to": dest})
        s, st = call("GET", "/state")
        check(f"can go to {dest}", st["destination"] == dest)

    s, r = call("POST", "/nav", {"to": "ATLANTIS"})
    check("a destination that does not exist is refused", s == 400)


    # Put the phone back as it was found.
    #
    # Before the summary rather than after, so a run that is read and forgotten
    # still leaves nothing behind. Deleting a conversation takes its project folder
    # with it on the next start, so the shelf is cleaned by the same act.
    made = [c for c in call("GET", "/conversations")[1]["conversations"]
            if c["id"] not in BEFORE]
    for c in made:
        call("POST", "/conversation/delete", {"id": c["id"]})
    if made:
        print(f"\n  tidied up {len(made)} conversations this run created")

    # Back to the mock, since a real model may have been selected by hand before
    # this ran and leaving it selected is how the next run spends money.
    call("POST", "/model", {"provider": "mock", "model": "mock-fast"})

print("\n" + "=" * 52)
print(f"  {len(passed)} passed, {len(failed)} failed")
if failed:
    print("\n  failures:")
    for f in failed:
        print("   -", f)
sys.exit(1 if failed else 0)
