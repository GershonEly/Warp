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

s, sent = call("POST", "/chat/send", {"text": "end to end weather app"})
check("sending returns the stored message id", s == 200 and "messageId" in sent)
time.sleep(4)

s, st = call("GET", "/state")
cid = st["conversationId"]
check("a conversation now exists", cid is not None)
check("the reply arrived", st["messageCount"] == 2, f"messageCount={st['messageCount']}")
check("it is no longer busy", st["busy"] is False)


print("\n3. WHAT WAS STORED IS WHAT WAS SENT")
s, msgs = call("GET", f"/messages?id={cid}")
texts = [(m["role"], m["text"]) for m in msgs["messages"]]
check("the user message is stored verbatim",
      any(r == "USER" and t == "end to end weather app" for r, t in texts))
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


print("\n7. SETTINGS ROUND-TRIP")
s, r = call("POST", "/settings", {"name": "ambient", "value": "false"})
check("turning the atmosphere off reads back as off", r.get("value") == "false")
s, r = call("POST", "/settings", {"name": "ambient", "value": "true"})
check("and back on", r.get("value") == "true")

s, r = call("POST", "/settings", {"name": "nonsense", "value": "x"})
check("an unknown setting is refused rather than silently ignored", s == 400)


print("\n8. NAVIGATION")
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
