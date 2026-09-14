# SQLite persistence layer — design

## Purpose

Give the core domain module (`split-core`) durable storage, backed by
SQLite as already committed to in the
[Telegram bill-splitting bot design](telegram-split-bot-design.md).
This spec covers the storage architecture and the reasoning behind its
library/schema choices — not the Telegram adapter, which is a separate
piece of work.

## Architecture

A Gradle module, `storage`, sits alongside `core`:

```
core     — pure domain (entities, balances, debt simplification, settling)
           + repository interfaces (ports), see Repositories.kt
storage  — Exposed/SQLite-backed repository implementations (adapters)
           + Flyway migrations, see storage/src/main/resources/db/migration
```

`storage` depends on `core`. Nothing in `core` depends on `storage`,
Exposed, or JDBC — this preserves the "core has no I/O coupling" property
the bot design already establishes, and keeps the same core reusable if
storage is ever swapped or a second platform adapter is added. `core`
compiles its `suspend fun` port interfaces with zero coroutines
dependency (`suspend` is a language keyword, backed by `kotlin-stdlib`) —
`kotlinx-coroutines-core` is only a dependency of `storage`.

`PlatformDirectory` (one of the ports) resolves `PlatformIdentity` /
`PlatformGroupLink` rows — used by adapter code to map an external
platform ID to a core `MemberId`/`GroupId`. Not used by any
balance/settlement logic.

Each repository method is `suspend fun`. JDBC itself is blocking — there's
no async SQLite driver — but the bot's long-polling loop is
coroutine-based, and a blocking repository call would stall it (and any
concurrent update processing) for the duration of every DB write. `storage`
wraps each Exposed `suspendTransaction` call in `withContext(Dispatchers.IO)`
to move that blocking work off the calling coroutine's dispatcher.

Note for future upgrades: Exposed had an older, since-removed
`newSuspendedTransaction(context: CoroutineContext)` API (pre-1.0,
package `transactions.experimental`) that took a dispatcher argument
directly. Exposed 1.0+ replaced it with `suspendTransaction()`, which has
no dispatcher parameter — the `withContext(Dispatchers.IO)` wrapper above
is what's needed to get the same effect.

`ExpenseRepository.create` is the one place that translates between the
core's aggregate shape (`Expense` holding `shares: List<ExpenseShare>`)
and the two normalized tables it's split across.

## Schema

Flyway migrations in `storage/src/main/resources/db/migration` — that
directory is the source of truth, not this doc. Notable choices that
aren't obvious from reading the SQL:

- The `group_` table is named with a trailing underscore because `GROUP`
  is a SQL reserved word; the Kotlin type stays `Group`.
- Money is stored as `INTEGER` cents (minor units), not `REAL` or `TEXT` —
  see "Money encoding" below.
- The active-rows indexes (e.g. on `expense`, `settlement`) are partial,
  filtered to `WHERE deleted_at IS NULL` — every hot-path query excludes
  soft-deleted rows, so there's no reason to index the deleted ones too.
- SQLite is a "community" (non-core) database for Flyway, which needs its
  own module — `flyway-database-nc-sqlite` — alongside `flyway-core` on
  the classpath; `flyway-core` alone does not migrate SQLite. See
  `storage/build.gradle.kts` for the pinned versions in use.

## Money encoding

`core` keeps `BigDecimal` for `Expense.amount` / `ExpenseShare.shareAmount`
/ `Settlement.amount` — the rounding rules for equal splits are already
written and tested against it, and there's no domain reason to change
that. The `storage` module converts to/from integer cents at the
repository boundary only, using `RoundingMode.UNNECESSARY` deliberately:
if a `BigDecimal` ever has more than 2 decimal places by the time it
reaches storage, that's a bug upstream (e.g. in split resolution), and
this makes it fail loudly at the boundary instead of silently truncating
money.

## Connection management

`SplitDatabase.kt` builds the JDBC data source (WAL journal mode,
`synchronous=NORMAL`, foreign keys enforced, a busy timeout) and runs
Flyway migrations before returning the connected `Database` — read that
file rather than this doc for the exact settings. The path comes from
`SPLIT_DB_PATH` (env var), so tests and prod never share a file. SQLite
serializes writes regardless of connection-pool size, so a single
connection is sufficient at this traffic level; WAL is what lets reads
proceed without blocking on an in-flight write.

## Transactions

Every multi-row write goes through exactly one transaction inside the
repository method that performs it — e.g. `ExpenseRepository.create`
inserts the `expense` row and all `expense_share` rows together, so a
partial write (which would corrupt balance calculations) can't happen.
Adapter code never issues raw SQL and never spans a transaction across
two repository calls.

## Testing

Repository tests use a real temp-file SQLite database per test (see
`withTestDatabase` in the telegram module's test sources), migrated
through the actual Flyway path — not SQLite's `:memory:` mode. Each JDBC
connection to `:memory:` is its own separate database unless a
shared-cache URI is forced, which is an easy way to write tests that
silently pass against an empty DB; a temp file sidesteps that entirely
and exercises the exact migration path production uses. See
`test-fixture-conventions.md` for how fixtures are built on top of this.

## Deployment

See `scaleway-kubernetes-deployment-design.md` for the current target —
back up the SQLite file with `sqlite3 <path> ".backup <dest>"`, not a raw
file copy, so a backup never captures a mid-write state.

## Out of scope for this spec

- The Telegram adapter module itself (command parsing, keyboards) — a
  separate piece of work that consumes these repository interfaces and
  `PlatformDirectory`.
- Connection pooling tuning / HikariCP — a single Exposed `Database`
  connection is sufficient at current expected load; revisit only if
  contention is observed.
- Multi-currency conversion, WhatsApp adapter — already out of scope per
  the bot design.
