# Personal Hypixel auction price archive

`collector.py` polls Hypixel's public `auctions_ended` feed every 30 seconds. It
uses conditional requests, so unchanged polls return HTTP 304 without an auction
body, and requests gzip for changed polls. A live check on 2026-09-24 measured
about 28 MB working memory and under 0.25 CPU seconds per minute. The 30-second
interval leaves room for a delayed request before the feed's 60-second window
expires.

It stores **each completed auction's final price as a separate record forever** in
`M:\HypixelAuctionArchive\market-history.sqlite3`. Records include the auction ID, UTC time, price,
BIN flag, seller, buyer, SkyBlock item ID, tier, stack count, and a key containing
item modifiers. Repeated item keys are shared in a separate table to save space.
There is no automatic rollup or deletion of sale prices.

The Windows Task Scheduler task **Local Hypixel Auction Archive** starts the
collector at sign-in with `C:\Python312\pythonw.exe`. Minecraft does not need to
be open. The task runs while this Windows user is signed in and the PC is awake.
The public endpoint contains only the last 60 seconds of ended auctions, so
offline periods cannot be backfilled. Detected gaps are saved in the `gaps`
table. The existing mod cache was imported once; those rows have a `MOD_CACHE`
key because that cache did not contain the original item bytes.

The archive deliberately does not keep the full original `item_bytes` NBT blob.
That blob averaged 914 compressed bytes per auction in one live sample, roughly
50 GB per year at 100 auctions per minute before SQLite overhead. The stored
modifier key includes the item's ExtraAttributes except item identity and
creation metadata (`uuid`, `timestamp`, `originTag`, `spawnedFor`, `bossId`).
Pet type, tier, item, skin, exact experience, candy count, and extra data are
included when present. Future analysis can use the individual prices and these
features, but cannot reconstruct discarded original NBT fields.

## Storage estimate

On 2026-09-24 the feed returned about 100–110 sales per minute. A local SQLite
benchmark using those real items measured 117 bytes per sale when modifiers
repeat, 210 bytes with half of sale variants new, and 301 bytes if every sale
has a distinct variant. At 100 sales/minute this is approximately:

| Time | Repeated variants | Every sale unique |
| --- | ---: | ---: |
| Day | 17 MB | 43 MB |
| Month | 0.5 GB | 1.3 GB |
| Year | 6 GB | 16 GB |
| Five years | 31 GB | 79 GB |

Actual growth depends on auction volume and modifier variety. This is a
projection, not a storage limit. A live collector log rotates at 2 MB with two
backups, so logs will not grow without bound.

## Check it

From `C:\Users\Curious Beats\AppData\Roaming\.minecraft\local-ah-flipper`:

```powershell
& 'C:\Python312\python.exe' archive\collector.py --status
Get-ScheduledTask -TaskName 'Local Hypixel Auction Archive'
Get-Content 'M:\HypixelAuctionArchive\collector.log' -Tail 10
```

The database is ordinary SQLite. For example:

```sql
SELECT s.ended_ms, v.item_id, s.price, s.bin, v.key
FROM recent_sales AS s JOIN variants AS v ON v.id = s.variant_id
WHERE v.item_id = 'JUJU_SHORTBOW'
ORDER BY s.ended_ms DESC LIMIT 20;
```

To stop automatic collection, disable the named task in Windows Task Scheduler.
The database remains available for later analysis.
