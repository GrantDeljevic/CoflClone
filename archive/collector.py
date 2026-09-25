"""Small, dependency-free local history of Hypixel's public ended auctions."""

from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import io
import json
import logging
from logging.handlers import RotatingFileHandler
from pathlib import Path
import sqlite3
import struct
import sys
import time
import urllib.error
import urllib.request


HERE = Path(__file__).resolve().parent
DATA = Path(r"M:\HypixelAuctionArchive")
DB = DATA / "market-history.sqlite3"
URL = "https://api.hypixel.net/v2/skyblock/auctions_ended"
IGNORE = {"id", "uuid", "timestamp", "petInfo", "enchantments",
          "originTag", "spawnedFor", "bossId"}


class NbtReader:
    def __init__(self, data: bytes):
        self.stream = io.BytesIO(data)

    def read(self, n: int) -> bytes:
        value = self.stream.read(n)
        if len(value) != n:
            raise ValueError("truncated NBT")
        return value

    def number(self, fmt: str):
        return struct.unpack(">" + fmt, self.read(struct.calcsize(fmt)))[0]

    def string(self) -> str:
        length = self.number("H")
        return self.read(length).decode("utf-8", "replace")

    def payload(self, tag: int, depth: int = 0):
        if depth > 32:
            raise ValueError("NBT nesting too deep")
        scalars = {1: "b", 2: "h", 3: "i", 4: "q", 5: "f", 6: "d"}
        if tag in scalars:
            return self.number(scalars[tag])
        if tag == 7:
            size = self.number("i")
            if not 0 <= size <= 4_000_000:
                raise ValueError("bad NBT array length")
            return self.read(size).hex()
        if tag == 8:
            return self.string()
        if tag == 9:
            child, size = self.number("b"), self.number("i")
            if not 0 <= size <= 100_000:
                raise ValueError("bad NBT list length")
            return [self.payload(child, depth + 1) for _ in range(size)]
        if tag == 10:
            result = {}
            while child := self.number("b"):
                name = self.string()
                result[name] = self.payload(child, depth + 1)
                if len(result) > 10_000:
                    raise ValueError("NBT compound too large")
            return result
        if tag in (11, 12):
            size = self.number("i")
            if not 0 <= size <= 100_000:
                raise ValueError("bad NBT array length")
            fmt = "i" if tag == 11 else "q"
            return [self.number(fmt) for _ in range(size)]
        raise ValueError(f"unknown NBT tag {tag}")

    def root(self):
        if self.number("b") != 10:
            raise ValueError("NBT root is not a compound")
        self.string()
        return self.payload(10)


def item_variant(encoded: str) -> tuple[str, str, str, int]:
    compressed = base64.b64decode(encoded, validate=True)
    if len(compressed) > 1_000_000:
        raise ValueError("item_bytes too large")
    with gzip.GzipFile(fileobj=io.BytesIO(compressed)) as gz:
        unpacked = gz.read(4_000_001)
    if len(unpacked) > 4_000_000:
        raise ValueError("unpacked item_bytes too large")
    stack = NbtReader(unpacked).root()["i"][0]
    extra = stack["tag"]["ExtraAttributes"]
    item_id = extra["id"]
    style = stack.get("components", {}).get("minecraft:tooltip_style", "")
    tier = style.removeprefix("hypixel_skyblock:").upper() if style.startswith("hypixel_skyblock:") else "UNKNOWN"
    count = int(stack.get("Count", 1))
    mods = {key: value for key, value in extra.items() if key not in IGNORE}
    for name, level in extra.get("enchantments", {}).items():
        mods["enchant:" + name] = level
    if item_id == "PET" and extra.get("petInfo"):
        pet = json.loads(extra["petInfo"])
        mods["pet_type"] = pet.get("type")
        mods["pet_tier"] = pet.get("tier")
        for field in ("heldItem", "skin"):
            if field in pet:
                mods["pet_" + field] = pet[field]
        for field in ("exp", "candyUsed", "extraData"):
            if field in pet:
                mods["pet_" + field] = pet[field]
    key = json.dumps([item_id, tier, count, mods], sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return key, item_id, tier, count


def db_open(path: Path = DB) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(path, timeout=30)
    db.execute("PRAGMA auto_vacuum=INCREMENTAL")
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA synchronous=FULL")
    db.execute("PRAGMA busy_timeout=30000")
    db.executescript("""
        CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS variants (
          id INTEGER PRIMARY KEY, key TEXT NOT NULL UNIQUE,
          item_id TEXT NOT NULL, tier TEXT NOT NULL, stack_count INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS recent_sales (
          auction_id BLOB PRIMARY KEY, ended_ms INTEGER NOT NULL,
          variant_id INTEGER NOT NULL, price INTEGER NOT NULL, bin INTEGER NOT NULL,
          seller BLOB, buyer BLOB
        ) WITHOUT ROWID;
        CREATE INDEX IF NOT EXISTS sales_by_variant_time
          ON recent_sales(variant_id, ended_ms);
        CREATE TABLE IF NOT EXISTS gaps (
          from_update_ms INTEGER NOT NULL, to_update_ms INTEGER NOT NULL,
          PRIMARY KEY(from_update_ms, to_update_ms)
        ) WITHOUT ROWID;
    """)
    return db


def uuid_bytes(value: str | None) -> bytes | None:
    if not value:
        return None
    return bytes.fromhex(value.replace("-", ""))


def poll(db: sqlite3.Connection, payload: dict) -> tuple[int, int]:
    if payload.get("success") is not True:
        raise ValueError("Hypixel reported success=false")
    update = int(payload["lastUpdated"])
    previous_row = db.execute("SELECT value FROM meta WHERE key='last_update'").fetchone()
    previous = int(previous_row[0]) if previous_row else 0
    if update <= previous:
        return 0, 0
    inserted = unparsed = 0
    with db:
        if previous and update - previous > 90_000:
            db.execute("INSERT OR IGNORE INTO gaps VALUES (?, ?)", (previous, update))
        for row in payload["auctions"]:
            price = int(row["price"])
            if price <= 0:
                continue
            try:
                variant, item_id, tier, count = item_variant(row["item_bytes"])
            except (KeyError, TypeError, ValueError, OSError, struct.error, IndexError, json.JSONDecodeError):
                # Preserve the sale price even if a future Hypixel item format cannot be decoded yet.
                digest = hashlib.sha256(row["item_bytes"].encode("ascii")).hexdigest()
                variant, item_id, tier, count = json.dumps(["UNPARSED", digest]), "UNPARSED", "UNKNOWN", 1
                unparsed += 1
            db.execute("INSERT OR IGNORE INTO variants(key,item_id,tier,stack_count) VALUES (?,?,?,?)",
                       (variant, item_id, tier, count))
            variant_id = db.execute("SELECT id FROM variants WHERE key=?", (variant,)).fetchone()[0]
            result = db.execute("INSERT OR IGNORE INTO recent_sales VALUES (?,?,?,?,?,?,?)",
                                (uuid_bytes(row["auction_id"]), int(row["timestamp"]), variant_id,
                                 price, int(bool(row["bin"])), uuid_bytes(row.get("seller")),
                                 uuid_bytes(row.get("buyer"))))
            inserted += result.rowcount
        db.execute("INSERT INTO meta(key,value) VALUES ('last_update',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                   (str(update),))
        db.execute("INSERT INTO meta(key,value) VALUES ('last_poll',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                   (str(int(time.time())),))
    return inserted, unparsed


def status(db: sqlite3.Connection) -> None:
    for label, query in (
        ("Last API update (UTC ms)", "SELECT value FROM meta WHERE key='last_update'"),
        ("Individual sales retained forever", "SELECT count(*) FROM recent_sales"),
        ("Item variants", "SELECT count(*) FROM variants"),
        ("Collection gaps", "SELECT count(*) FROM gaps"),
    ):
        row = db.execute(query).fetchone()
        print(f"{label}: {row[0] if row else 'never'}")
    size = sum(path.stat().st_size for path in (DB, Path(str(DB) + "-wal")) if path.exists())
    print(f"Database and write-ahead log: {size / 1024 / 1024:.2f} MiB")


def import_mod_cache(db: sqlite3.Connection, directory: Path) -> int:
    imported = 0
    for file in sorted(directory.glob("sales-*.jsonl")):
        with file.open(encoding="utf-8") as source, db:
            for line in source:
                try:
                    sale = json.loads(line)
                    original_key = sale["key"]
                    item_id, _, tier_and_rest = original_key.partition("|")
                    tier = tier_and_rest.partition("|")[0] or "UNKNOWN"
                    key = json.dumps(["MOD_CACHE", original_key], separators=(",", ":"))
                    db.execute("INSERT OR IGNORE INTO variants(key,item_id,tier,stack_count) VALUES (?,?,?,1)",
                               (key, item_id, tier))
                    variant_id = db.execute("SELECT id FROM variants WHERE key=?", (key,)).fetchone()[0]
                    result = db.execute("INSERT OR IGNORE INTO recent_sales VALUES (?,?,?,?,?,?,NULL)",
                                        (uuid_bytes(sale["id"]), int(sale["time"]), variant_id,
                                         int(sale["price"]), 1, uuid_bytes(sale.get("seller"))))
                    imported += result.rowcount
                except (KeyError, TypeError, ValueError, json.JSONDecodeError, sqlite3.Error):
                    logging.warning("Skipped malformed cache record in %s", file)
    return imported


def fetch(last_modified: str | None = None) -> tuple[dict | None, str | None]:
    headers = {"User-Agent": "LocalAhArchive/0.1 personal", "Accept-Encoding": "gzip"}
    if last_modified:
        headers["If-Modified-Since"] = last_modified
    request = urllib.request.Request(URL, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=25) as response:
            data = response.read(4_000_001)
            if len(data) > 4_000_000:
                raise ValueError("Hypixel response too large")
            if response.headers.get("Content-Encoding") == "gzip":
                data = gzip.decompress(data)
            return json.loads(data), response.headers.get("Last-Modified")
    except urllib.error.HTTPError as error:
        if error.code == 304:
            return None, last_modified
        raise


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--once", action="store_true", help="Fetch one update and stop")
    group.add_argument("--status", action="store_true", help="Show archive counts")
    group.add_argument("--import-mod-cache", type=Path, metavar="DIR", help="Import existing mod sales JSONL once")
    args = parser.parse_args()
    DATA.mkdir(parents=True, exist_ok=True)
    handlers = [RotatingFileHandler(DATA / "collector.log", maxBytes=2_000_000, backupCount=2, encoding="utf-8")]
    if sys.stdout and sys.stdout.isatty():
        handlers.append(logging.StreamHandler())
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", handlers=handlers)
    db = db_open()
    if args.status:
        status(db)
        return
    if args.import_mod_cache:
        print(f"Imported {import_mod_cache(db, args.import_mod_cache)} earlier sales")
        status(db)
        return
    failures = 0
    last_modified = None
    while True:
        try:
            payload, updated_modified = fetch(last_modified)
            if payload is None:
                added = unparsed = 0
            else:
                added, unparsed = poll(db, payload)
                last_modified = updated_modified
            failures = 0
            if added or unparsed:
                logging.info("Archived %s sales, %s with unparsed item metadata", added, unparsed)
            if args.once:
                status(db)
                return
            time.sleep(30)
        except Exception:
            failures += 1
            logging.exception("Collector update failed")
            if args.once:
                raise
            time.sleep(min(300, 30 * failures))


if __name__ == "__main__":
    main()
