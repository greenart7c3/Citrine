#!/usr/bin/env python3
"""Citrine relay benchmark with stall diagnostics.

Mirrors the go-nostr benchmark (publish N signed events, then run M queries
cycling through 5 filter shapes with QuerySync's 7s timeout), but adds the
diagnostics needed to localize slow queries: per-query first-event latency,
EOSE latency, largest inter-frame gap, and a forensic report for every query
that times out.

Requirements:
    pip install websockets
    # optional, for much faster signing: pip install coincurve
    # (events are pre-signed before the publish timer starts, so the pure-Python
    #  fallback does not skew the measured publish rate)

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
from websockets.extensions import permessage_deflate

try:
    from coincurve import PrivateKey as _CoincurvePrivateKey
except ImportError:
    _CoincurvePrivateKey = None


def now() -> int:
    return int(time.time())


# ---------------------------------------------------------------------------
# BIP-340 Schnorr signing. Uses coincurve when available; otherwise a
# pure-Python implementation (a few ms per signature — fine for a benchmark,
# and events are pre-signed outside the timed publish window anyway).
# ---------------------------------------------------------------------------

_P = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F
_N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
_GX = 0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798
_GY = 0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8


# Point arithmetic in Jacobian coordinates (single modular inversion per
# multiplication instead of one per addition).

def _jac_double(p):
    x1, y1, z1 = p
    if y1 == 0:
        return (0, 0, 0)
    a = x1 * x1 % _P
    b = y1 * y1 % _P
    c = b * b % _P
    d = 2 * ((x1 + b) * (x1 + b) - a - c) % _P
    e = 3 * a % _P
    f = e * e % _P
    x3 = (f - 2 * d) % _P
    y3 = (e * (d - x3) - 8 * c) % _P
    z3 = 2 * y1 * z1 % _P
    return (x3, y3, z3)


def _jac_add(p, q):
    if p[2] == 0:
        return q
    if q[2] == 0:
        return p
    x1, y1, z1 = p
    x2, y2, z2 = q
    z1z1 = z1 * z1 % _P
    z2z2 = z2 * z2 % _P
    u1 = x1 * z2z2 % _P
    u2 = x2 * z1z1 % _P
    s1 = y1 * z2 * z2z2 % _P
    s2 = y2 * z1 * z1z1 % _P
    if u1 == u2:
        if s1 != s2:
            return (0, 0, 0)
        return _jac_double(p)
    h = (u2 - u1) % _P
    r = (s2 - s1) % _P
    h2 = h * h % _P
    h3 = h * h2 % _P
    u1h2 = u1 * h2 % _P
    x3 = (r * r - h3 - 2 * u1h2) % _P
    y3 = (r * (u1h2 - x3) - s1 * h3) % _P
    z3 = h * z1 * z2 % _P
    return (x3, y3, z3)


def _point_mul(point, k):
    result = (0, 0, 0)
    acc = (point[0], point[1], 1)
    while k:
        if k & 1:
            result = _jac_add(result, acc)
        acc = _jac_double(acc)
        k >>= 1
    if result[2] == 0:
        return None
    zinv = pow(result[2], _P - 2, _P)
    zinv2 = zinv * zinv % _P
    return (result[0] * zinv2 % _P, result[1] * zinv2 * zinv % _P)


def _tagged_hash(tag: str, data: bytes) -> bytes:
    tag_hash = hashlib.sha256(tag.encode()).digest()
    return hashlib.sha256(tag_hash + tag_hash + data).digest()


class _PurePySigner:
    def __init__(self):
        self.d0 = int.from_bytes(secrets.token_bytes(32), "big") % (_N - 1) + 1
        pub = _point_mul((_GX, _GY), self.d0)
        self.point = pub
        self.pubkey_bytes = pub[0].to_bytes(32, "big")
        self.pubkey = self.pubkey_bytes.hex()

    def sign(self, msg32: bytes) -> bytes:
        d = self.d0 if self.point[1] % 2 == 0 else _N - self.d0
        aux = secrets.token_bytes(32)
        t = (d ^ int.from_bytes(_tagged_hash("BIP0340/aux", aux), "big")).to_bytes(32, "big")
        k0 = int.from_bytes(
            _tagged_hash("BIP0340/nonce", t + self.pubkey_bytes + msg32), "big"
        ) % _N
        if k0 == 0:
            raise RuntimeError("nonce is zero")
        r_point = _point_mul((_GX, _GY), k0)
        k = k0 if r_point[1] % 2 == 0 else _N - k0
        rx = r_point[0].to_bytes(32, "big")
        e = int.from_bytes(_tagged_hash("BIP0340/challenge", rx + self.pubkey_bytes + msg32), "big") % _N
        return rx + ((k + e * d) % _N).to_bytes(32, "big")


class _CoincurveSigner:
    def __init__(self):
        self.sk = _CoincurvePrivateKey(secrets.token_bytes(32))
        self.pubkey = self.sk.public_key.format(compressed=True)[1:].hex()

    def sign(self, msg32: bytes) -> bytes:
        return self.sk.sign_schnorr(msg32, secrets.token_bytes(32))


class Signer:
    def __init__(self):
        self._impl = _CoincurveSigner() if _CoincurvePrivateKey else _PurePySigner()
        self.pubkey = self._impl.pubkey

    def sign_event(self, kind: int, content: str, tags=None) -> dict:
        tags = tags or []
        created_at = now()
        serialized = json.dumps(
            [0, self.pubkey, created_at, kind, tags, content],
            separators=(",", ":"),
            ensure_ascii=False,
        ).encode()
        event_id = hashlib.sha256(serialized).digest()
        sig = self._impl.sign(event_id)
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


async def publisher(url: str, events: list, kwargs: dict, stats: dict):
    async with websockets.connect(url, **kwargs) as ws:
        for ev in events:
            await ws.send(dumps(["EVENT", ev]))
            while True:  # wait for OK, like go-nostr Publish
                msg = json.loads(await ws.recv())
                if msg[0] == "OK" and msg[1] == ev["id"]:
                    if msg[2] is not True:
                        stats["rejected"] += 1
                        if stats["rejected"] <= 3:
                            print(f"  REJECTED: {msg[3] if len(msg) > 3 else ''}")
                    elif len(msg) > 3 and str(msg[3]).startswith("duplicate"):
                        stats["duplicate"] += 1
                    else:
                        stats["published"] += 1
                    break


async def publish_phase(url, events, concurrency, content_size, kwargs):
    # Pre-sign everything so signing speed (pure-Python fallback is slow)
    # cannot skew the measured relay publish rate.
    signer_kind = "coincurve" if _CoincurvePrivateKey else "pure-python"
    print(f"  Signing {events} events ({signer_kind})...")
    run_id = secrets.token_hex(4)
    batches = []
    per = events // concurrency
    extra = events % concurrency
    for i in range(concurrency):
        signer = Signer()
        batch = []
        for j in range(per + (1 if i < extra else 0)):
            # Unique content per event — same-second events with identical
            # content/kind/pubkey would share an id and be deduplicated by the
            # relay, silently shrinking the query workload.
            prefix = f"{run_id}:{i}:{j}:"
            content = prefix + "x" * max(0, content_size - len(prefix))
            batch.append(signer.sign_event(1, content))
        batches.append(batch)

    stats = {"published": 0, "rejected": 0, "duplicate": 0}
    start = time.monotonic()
    await asyncio.gather(*(publisher(url, batch, kwargs, stats) for batch in batches))
    dur = time.monotonic() - start
    print(f"  Published: {stats['published']}")
    if stats["rejected"]:
        print(f"  Rejected: {stats['rejected']}  <-- relay refused events, results are not comparable")
    if stats["duplicate"]:
        print(f"  Duplicates: {stats['duplicate']}  <-- already in DB, not newly stored")
    print(f"  Duration: {dur:.2f}s")
    print(f"  Rate: {(stats['published'] + stats['duplicate']) / dur:.2f} events/s")


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
