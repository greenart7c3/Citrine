#!/usr/bin/env python3
"""Citrine relay benchmark with stall diagnostics.

Mirrors the go-nostr benchmark (publish N signed events, then run M queries
cycling through 5 filter shapes with QuerySync's 7s timeout), but adds the
diagnostics needed to localize slow queries: per-query first-event latency,
EOSE latency, largest inter-frame gap, and a forensic report for every query
that times out.

Requirements:
    pip install websockets coincurve

Usage:
    # Against the phone over USB:
    #   adb forward tcp:4869 tcp:4869
    python3 relay_bench.py
    # Against the phone over LAN:
    python3 relay_bench.py --url ws://192.168.1.50:4869
    # A/B the permessage-deflate theory:
    python3 relay_bench.py --compression gorilla   # go-nostr-like (default)
    python3 relay_bench.py --compression off       # no compression offered

Interpreting output:
    - "timeout" queries with events_received=0 and no first-event latency mean
      the whole response stalled in transit (server enqueued it; it never
      arrived) or the relay never answered.
    - A large "max gap" inside an otherwise successful query means delivery
      froze mid-stream and recovered.
    - Compare `--compression off` vs `gorilla`: if timeouts disappear with
      compression off, the stall is in the client/server deflate interaction.
"""
import argparse
import asyncio
import hashlib
import json
import secrets
import statistics
import time

import websockets
from coincurve import PrivateKey
from websockets.extensions import permessage_deflate


def now() -> int:
    return int(time.time())


class Signer:
    def __init__(self):
        self.sk = PrivateKey(secrets.token_bytes(32))
        self.pubkey = self.sk.public_key.format(compressed=True)[1:].hex()

    def sign_event(self, kind: int, content: str, tags=None) -> dict:
        tags = tags or []
        created_at = now()
        serialized = json.dumps(
            [0, self.pubkey, created_at, kind, tags, content],
            separators=(",", ":"),
            ensure_ascii=False,
        ).encode()
        event_id = hashlib.sha256(serialized).digest()
        sig = self.sk.sign_schnorr(event_id, secrets.token_bytes(32))
        return {
            "id": event_id.hex(),
            "pubkey": self.pubkey,
            "created_at": created_at,
            "kind": kind,
            "tags": tags,
            "content": content,
            "sig": sig.hex(),
        }


def dumps(obj) -> str:
    return json.dumps(obj, separators=(",", ":"), ensure_ascii=False)


def make_connect_kwargs(compression: str) -> dict:
    if compression == "off":
        return {"compression": None, "max_size": None}
    if compression == "gorilla":
        # go-nostr/gorilla negotiates permessage-deflate without context takeover
        return {
            "extensions": [
                permessage_deflate.ClientPerMessageDeflateFactory(
                    client_no_context_takeover=True,
                    server_no_context_takeover=True,
                )
            ],
            "max_size": None,
        }
    return {"max_size": None}  # "default": library default (context takeover)


async def publisher(pid: int, url: str, count: int, content_size: int, kwargs: dict, stats: dict):
    signer = Signer()
    content = "x" * content_size
    async with websockets.connect(url, **kwargs) as ws:
        for _ in range(count):
            ev = signer.sign_event(1, content)
            await ws.send(dumps(["EVENT", ev]))
            while True:  # wait for OK, like go-nostr Publish
                msg = json.loads(await ws.recv())
                if msg[0] == "OK" and msg[1] == ev["id"]:
                    if msg[2] is not True:
                        stats["rejected"] += 1
                        if stats["rejected"] <= 3:
                            print(f"  REJECTED: {msg[3] if len(msg) > 3 else ''}")
                    else:
                        stats["published"] += 1
                    break


async def publish_phase(url, events, concurrency, content_size, kwargs):
    stats = {"published": 0, "rejected": 0}
    per = events // concurrency
    extra = events % concurrency
    start = time.monotonic()
    await asyncio.gather(
        *(publisher(i, url, per + (1 if i < extra else 0), content_size, kwargs, stats)
          for i in range(concurrency))
    )
    dur = time.monotonic() - start
    print(f"  Published: {stats['published']}")
    if stats["rejected"]:
        print(f"  Rejected: {stats['rejected']}  <-- relay refused events, results are not comparable")
    print(f"  Duration: {dur:.2f}s")
    print(f"  Rate: {stats['published'] / dur:.2f} events/s")


def query_filter(i: int) -> dict:
    limit = 100
    t = now()
    case = i % 5
    if case == 0:
        return {"kinds": [1], "limit": limit}
    if case == 1:
        return {"since": t - 3600, "until": t, "limit": limit}
    if case == 2:
        return {"limit": limit}
    if case == 3:
        return {"kinds": [1, 6, 7], "limit": limit}
    return {"since": t - 7200, "until": t - 1800, "limit": limit}


async def query_phase(url, queries, timeout, kwargs):
    total_events = 0
    timeouts = []
    latencies = []
    worst_gap = (0.0, -1)  # (gap seconds, query index)

    async with websockets.connect(url, **kwargs) as ws:
        for i in range(queries):
            sub = f"bench{i}"
            f = query_filter(i)
            q0 = time.monotonic()
            deadline = q0 + timeout
            await ws.send(dumps(["REQ", sub, f]))

            got = 0
            eose = False
            first_event_at = None
            last_frame_at = q0
            max_gap = 0.0

            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                try:
                    raw = await asyncio.wait_for(ws.recv(), timeout=remaining)
                except asyncio.TimeoutError:
                    break
                t = time.monotonic()
                max_gap = max(max_gap, t - last_frame_at)
                last_frame_at = t
                msg = json.loads(raw)
                if msg[0] == "EVENT" and msg[1] == sub:
                    got += 1
                    if first_event_at is None:
                        first_event_at = t
                elif msg[0] == "EOSE" and msg[1] == sub:
                    eose = True
                    break
                # frames for other (closed) subs are ignored, like go-nostr

            qdur = time.monotonic() - q0
            await ws.send(dumps(["CLOSE", sub]))

            if eose:
                total_events += got
                latencies.append(qdur)
                if max_gap > worst_gap[0]:
                    worst_gap = (max_gap, i)
                if max_gap > 1.0:
                    print(f"  MID-STREAM FREEZE query {i}: gap={max_gap:.2f}s dur={qdur:.2f}s events={got} filter={dumps(f)}")
            else:
                timeouts.append((i, qdur, got, first_event_at and (first_event_at - q0), f))
                print(
                    f"  TIMEOUT query {i}: waited {qdur:.2f}s, events_received={got}, "
                    f"first_event={'%.3fs' % (first_event_at - q0) if first_event_at else 'never'}, "
                    f"filter={dumps(f)}"
                )

    print(f"  Executed: {queries}")
    if latencies:
        total = sum(latencies) + sum(t[1] for t in timeouts)
        print(f"  Duration: {total:.2f}s")
        print(f"  Rate: {queries / total:.2f} queries/s")
        print(f"  Events returned: {total_events}")
        lat_sorted = sorted(latencies)
        print(
            f"  Successful query latency: p50={statistics.median(lat_sorted) * 1000:.0f}ms "
            f"p95={lat_sorted[int(len(lat_sorted) * 0.95) - 1] * 1000:.0f}ms "
            f"max={lat_sorted[-1] * 1000:.0f}ms"
        )
        print(f"  Largest mid-stream gap: {worst_gap[0] * 1000:.0f}ms (query {worst_gap[1]})")
    print(f"  Timeouts: {len(timeouts)}")
    if timeouts:
        lost = sum(t[2] for t in timeouts)
        print(f"  Events discarded by timed-out queries: {lost}")
        print("  --> Each timeout is a query whose response stalled in transit for the")
        print("      full timeout window. Check the relay log: if it shows 'sent N events'")
        print("      quickly for that subscription, the stall is between the server's")
        print("      write pipeline and this client (transport/deflate/client reader).")


async def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--url", default="ws://127.0.0.1:4869")
    p.add_argument("--events", type=int, default=1000, help="events to publish (0 to skip)")
    p.add_argument("--queries", type=int, default=100, help="queries to run (0 to skip)")
    p.add_argument("--concurrency", type=int, default=10, help="concurrent publishers")
    p.add_argument("--content-size", type=int, default=1024, help="event content size in bytes")
    p.add_argument("--timeout", type=float, default=7.0, help="per-query timeout (go-nostr QuerySync default: 7)")
    p.add_argument("--compression", choices=["gorilla", "default", "off"], default="gorilla",
                   help="permessage-deflate mode: gorilla=go-nostr-like, off=none")
    args = p.parse_args()

    kwargs = make_connect_kwargs(args.compression)
    print(f"Relay: {args.url}  compression={args.compression}")

    if args.events > 0:
        print(f"Publishing {args.events} events...")
        await publish_phase(args.url, args.events, args.concurrency, args.content_size, kwargs)

    if args.queries > 0:
        print(f"Executing {args.queries} queries...")
        await query_phase(args.url, args.queries, args.timeout, kwargs)


if __name__ == "__main__":
    asyncio.run(main())
