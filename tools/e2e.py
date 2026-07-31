"""End-to-end check of Warp, driven through the debug surface.

Every assertion reads state back out of the app rather than trusting that a
request returned. That is the whole reason the surface answers in the first
place.
"""
import json
import sys
import time
import urllib.request

BASE = "http://localhost:8099"
KEY = "test123"
passed, failed = [], []


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("X-Warp-Key", KEY)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def check(name, ok, detail=""):
    (passed if ok else failed).append(name)
    print(f"  {'PASS' if ok else 'FAIL'}  {name}{'  — ' + detail if detail else ''}")


# A run that was interrupted can leave a question on screen and a turn waiting
# on it for ever. Clear it before anything else, or every later check inherits
# the last run's mess.
call("POST", "/permission", {"decision": "DENY"})
# Rules are global and outlive a run. A rule left behind changes what the mock
# replies with, and unrelated sections start failing for a reason nothing names.
call("POST", "/chat/command", {"text": "/rules clear"})

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


print("\n2. STARTING A CONVERSATION")
call("POST", "/nav", {"to": "CHAT"})
call("POST", "/chat/new")
s, st = call("GET", "/state")
check("a new chat has no conversation yet", st["conversationId"] is None,
      "created by the first message, not by the button")

s, sent = call("POST", "/chat/send", {"text": "end to end check please"})
check("sending returns the stored message id", s == 200 and "messageId" in sent)
time.sleep(6)

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
    check("A–Z really is sorted", titles == sorted(titles), f"{titles}")


print("\n7. TOOLS REALLY RUN")
# Nothing in this section is scripted. The mock asks for list_dir, the runner
# walks the app's own project folder, and the result goes back into the next
# request. Every earlier version of the tool cards passed by looking right.
call("POST", "/chat/new")
call("POST", "/chat/send", {"text": "look at the project files"})
time.sleep(10)
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
check("one round of tools, not eight — the budget is not the brake",
      len(msgs) == 3, f"messages={len(msgs)}")

s, out = call("POST", "/tool",
              {"name": "read_file", "args": {"path": "../databases/warp.db"}})
check("a path outside the project is refused",
      "outside" in json.dumps(out), json.dumps(out)[:90])


print("\n8. NOTHING IS WRITTEN WITHOUT YOU")
# The one path in Warp that can destroy work. Every check here reads the disk
# through run-as, not the card — a card saying DENIED while the file changed is
# precisely the failure worth catching.
import subprocess
ADB = r"C:\Users\ely\Android\Sdk\platform-tools\adb.exe"


def on_device(*args):
    return subprocess.run([ADB, "shell", "run-as", "dev.ely.warp"] + list(args),
                          capture_output=True, text=True).stdout.strip()


def tool_cards():
    cid = call("GET", "/state")[1]["conversationId"]
    return [c for msg in call("GET", "/messages?id=" + cid)[1]["messages"]
            for c in msg.get("toolCalls", [])]


call("POST", "/permission/revoke")
on_device("rm", "-f", "files/project/src/Counter.kt")

call("POST", "/chat/new")
call("POST", "/chat/send", {"text": "create a new file for me"})
time.sleep(6)
s, st = call("GET", "/state")
check("a write stops and asks", st.get("askingTool") == "write_file",
      f"asking={st.get('askingTool')}")
check("it names the file, not the tool", "Counter.kt" in (st.get("askingAbout") or ""),
      f"{st.get('askingAbout')!r}")
check("the turn is still open while it waits", st["busy"] is True)

call("POST", "/permission", {"decision": "DENY"})
time.sleep(3)
check("no really means no — nothing on disk",
      "Counter.kt" not in on_device("ls", "files/project/src"),
      on_device("ls", "files/project/src").replace("\n", " "))
check("and the card says so",
      any(c["status"] == "DENIED" for c in tool_cards()))

call("POST", "/chat/new")
call("POST", "/chat/send", {"text": "create a new file for me"})
time.sleep(6)
call("POST", "/permission", {"decision": "ONCE"})
time.sleep(4)
check("Allow writes the file for real",
      "Counter.kt" in on_device("ls", "files/project/src"))
check("the file holds what was asked for",
      "fun main()" in on_device("cat", "files/project/src/Counter.kt"))
check("the card reports what happened, not 'ok'",
      any("created" in (c["result"] or "") for c in tool_cards()),
      f"{[c['result'] for c in tool_cards()]}")
check("Allow once grants nothing standing",
      call("GET", "/permission")[1]["granted"] == [])

call("POST", "/chat/new")
call("POST", "/chat/send", {"text": "create a new file for me"})
time.sleep(6)
call("POST", "/permission", {"decision": "ALWAYS"})
time.sleep(3)
trusting = call("GET", "/state")[1]["conversationId"]
check("Always is remembered",
      call("GET", "/permission")[1]["granted"] == ["write_file"])

# Same chat: it must not ask again.
call("POST", "/chat/send", {"text": "create a new file for me"})
time.sleep(7)
st = call("GET", "/state")[1]
check("the same chat is not asked again",
      st.get("askingTool") is None and st["busy"] is False)
check("and the write still happened",
      any(c["status"] == "DONE" for c in tool_cards()))

# A different chat: it must ask. This is the whole point of per-chat scope —
# a decision made in one piece of work must not silently apply to another.
call("POST", "/chat/new")
call("POST", "/chat/send", {"text": "create a new file for me"})
time.sleep(6)
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


print("\n9. COMMANDS")
# /chat/command runs the same function the send button runs. A second code path
# for testing would eventually disagree with the one people actually use.


def command(text):
    return call("POST", "/chat/command", {"text": text})[1].get("note")


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
time.sleep(6)
cid = call("GET", "/state")[1]["conversationId"]
reply = call("GET", "/messages?id=" + cid)[1]["messages"][-1]["text"]
check("the rules reach the model", "never touch the manifest" in reply,
      repr(reply[:70]))

call("POST", "/chat/new")
command("/plan build me a todo app")
time.sleep(6)
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
check("an unbuilt command says so rather than doing nothing",
      command("/goal ship it") == "/goal is not built yet.")
check("removing by number reports what went",
      command("/rules remove 1") == "Removed: never touch the manifest")
check("and the list really shrank",
      call("GET", "/rules")[1]["rules"] == ["always run the tests"])
command("/rules clear")


print("\n10. SETTINGS ROUND-TRIP")
s, r = call("POST", "/settings", {"name": "ambient", "value": "false"})
check("turning the atmosphere off reads back as off", r.get("value") == "false")
s, r = call("POST", "/settings", {"name": "ambient", "value": "true"})
check("and back on", r.get("value") == "true")

s, r = call("POST", "/settings", {"name": "nonsense", "value": "x"})
check("an unknown setting is refused rather than silently ignored", s == 400)


print("\n11. NAVIGATION")
for dest in ("SETTINGS", "BUILD", "CHAT"):
    call("POST", "/nav", {"to": dest})
    s, st = call("GET", "/state")
    check(f"can go to {dest}", st["destination"] == dest)

s, r = call("POST", "/nav", {"to": "ATLANTIS"})
check("a destination that does not exist is refused", s == 400)


print("\n" + "=" * 52)
print(f"  {len(passed)} passed, {len(failed)} failed")
if failed:
    print("\n  failures:")
    for f in failed:
        print("   -", f)
sys.exit(1 if failed else 0)
