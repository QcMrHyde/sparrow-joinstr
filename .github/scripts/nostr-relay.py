#!/usr/bin/env python3
"""A minimal nostr relay: enough for EVENT, REQ and CLOSE, in memory."""
import asyncio, json, sys
import websockets

EVENTS = []
SUBS = {}   # websocket -> {subid: [filters]}

def matches(filters, ev):
    for f in filters:
        if "kinds" in f and ev.get("kind") not in f["kinds"]:
            continue
        if "#p" in f:
            ptags = [t[1] for t in ev.get("tags", []) if len(t) >= 2 and t[0] == "p"]
            if not any(p in f["#p"] for p in ptags):
                continue
        if "authors" in f and ev.get("pubkey") not in f["authors"]:
            continue
        return True
    return False

async def handler(ws):
    SUBS[ws] = {}
    try:
        async for raw in ws:
            msg = json.loads(raw)
            kind = msg[0]
            if kind == "EVENT":
                ev = msg[1]
                EVENTS.append(ev)
                await ws.send(json.dumps(["OK", ev.get("id", ""), True, ""]))
                for peer, subs in list(SUBS.items()):
                    for subid, filters in subs.items():
                        if matches(filters, ev):
                            try:
                                await peer.send(json.dumps(["EVENT", subid, ev]))
                            except Exception:
                                pass
            elif kind == "REQ":
                subid = msg[1]
                filters = msg[2:]
                SUBS[ws][subid] = filters
                for ev in EVENTS:
                    if matches(filters, ev):
                        await ws.send(json.dumps(["EVENT", subid, ev]))
                await ws.send(json.dumps(["EOSE", subid]))
            elif kind == "CLOSE":
                SUBS[ws].pop(msg[1], None)
    except Exception:
        pass
    finally:
        SUBS.pop(ws, None)

async def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 7777
    async with websockets.serve(handler, "127.0.0.1", port):
        print(f"relay listening on ws://127.0.0.1:{port}", flush=True)
        await asyncio.Future()

asyncio.run(main())
