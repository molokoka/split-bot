# SQLite persistence layer — design

## Purpose

Give the core domain module (`split-core`) durable storage, backed by
SQLite as already committed to in the
[Telegram bill-splitting bot design](2026-08-26-telegram-split-bot-design.md).
This spec covers the storage architecture, schema, and library choices —
not the Telegram adapter, which is a separate piece of work.

## Architecture

A new Gradle module, `storage`, sits alongside `core`:

```
core     — pure domain (entities, balances, debt simplification, settling)
           + repository interfaces (ports)
storage  — Exposed/SQLite-backed repository implementations (adapters)
           + Flyway migrations
```

`storage` depends on `core`. Nothing in `core` depends on `storage`,
Exposed, or JDBC — this preserves the "core has no I/O coupling" property
the bot design already establishes, and keeps the same core reusable if
storage is ever swapped or a second platform adapter is added.

Repository interfaces live in `core` as ports:

- `MemberRepository`
- `GroupRepository`
- `ExpenseRepository`
- `SettlementRepository`
- `PlatformDirectory` — resolves `PlatformIdentity` / `PlatformGroupLink`
  rows; used by adapter code (e.g. the future Telegram adapter) to map an
  external platform ID to a core `MemberId`/`GroupId`. Not used by any
  balance/settlement logic.

Each method is `suspend fun`. JDBC itself is blocking — there's no async
SQLite driver — but the bot's long-polling loop is coroutine-based, and a
blocking repository call would stall it (and any concurrent update
processing) for the duration of every DB write.

`storage` implements each method using Exposed 1.0's `suspendTransaction`
(exposed-jdbc), wrapped in `withContext(Dispatchers.IO)` to move the
blocking JDBC work off the calling coroutine's dispatcher:

```kotlin
suspend fun create(expense: Expense) = withContext(Dispatchers.IO) {
    suspendTransaction(db) {
        // insert into expense + expense_share
    }
}
```

Note for implementation: Exposed had an older, since-removed
`newSuspendedTransaction(context: CoroutineContext)` API (pre-1.0,
package `transactions.experimental`) that took a dispatcher argument
directly. Exposed 1.0 (stable, released January 2026) replaced it with
`suspendTransaction()`, which has no dispatcher parameter — the
`withContext(Dispatchers.IO)` wrapper above is what the current docs
recommend for controlling where the blocking work runs. Pin the exact
`exposed-core`/`exposed-jdbc` versions during implementation and use
this pattern, not the older API.

This needs `kotlinx-coroutines-core` as a dependency of `storage` only —
`suspend` is a language keyword backed by `kotlin-stdlib`, not the
coroutines library, so `core` compiles the `suspend fun` port interfaces
with no extra dependency (verified: `core` builds clean with zero
coroutines dependency once the interfaces are added).

## Core prerequisite

The bot design's data model lists `created_by` / `created_at` on
`Expense`, `created_at` on `Group`, and `created_by` / `created_at` /
`deleted_at` on `Settlement`. Checked against the actual
`core/src/main/kotlin/split/core/Entities.kt`: **none of these exist
yet** — `Expense` only has `deletedAt`, and `Group` / `Settlement` have
neither audit nor soft-delete fields at all. (An earlier draft of this
spec assumed `Expense` already had `createdBy`/`createdAt`; it doesn't.)
Before the storage layer is built, `core` needs:

- `Expense.createdBy: MemberId`
- `Expense.createdAt: Instant`
- `Group.createdAt: Instant`
- `Settlement.createdBy: MemberId`
- `Settlement.createdAt: Instant`
- `Settlement.deletedAt: Instant? = null`

`computeBalances` should exclude soft-deleted settlements the same way it
already excludes soft-deleted expenses (`it.deletedAt == null`), and an
authorization rule for deleting a settlement (payer or group admin)
should mirror the existing `canDeleteExpense`. `createSettlement` and any
expense-creation helper need `createdBy`/`createdAt` parameters. These
are small, isolated additions to already-tested code — same shape as the
existing `deletedAt`/`canDeleteExpense` pattern, done via TDD like the
rest of `core`.

Two new small `core` types support the platform-mapping layer:

```kotlin
data class PlatformIdentity(
    val platform: String,
    val externalUserId: String,
    val memberId: MemberId,
)

data class PlatformGroupLink(
    val platform: String,
    val externalChatId: String,
    val groupId: GroupId,
)
```

`platform` is a plain string (not an enum tied to Telegram) so this stays
usable by any future adapter.

## Repository interfaces

Sketch of the port surface (exact signatures finalized during
implementation):

```kotlin
interface MemberRepository {
    suspend fun create(member: Member)
    suspend fun find(id: MemberId): Member?
    suspend fun findByGroup(groupId: GroupId): List<Member>
}

interface GroupRepository {
    suspend fun create(group: Group)
    suspend fun find(id: GroupId): Group?
    suspend fun addMember(groupId: GroupId, memberId: MemberId)
}

interface ExpenseRepository {
    suspend fun create(expense: Expense)          // writes expense + expense_share rows in one transaction
    suspend fun find(id: ExpenseId): Expense?
    suspend fun listActive(groupId: GroupId, currency: String): List<Expense>  // deletedAt IS NULL
    suspend fun softDelete(id: ExpenseId, deletedAt: Instant)
}

interface SettlementRepository {
    suspend fun create(settlement: Settlement)
    suspend fun listActive(groupId: GroupId, currency: String): List<Settlement>
    suspend fun softDelete(id: SettlementId, deletedAt: Instant)
}

interface PlatformDirectory {
    suspend fun findMember(platform: String, externalUserId: String): MemberId?
    suspend fun linkMember(platform: String, externalUserId: String, memberId: MemberId)
    suspend fun findGroup(platform: String, externalChatId: String): GroupId?
    suspend fun linkGroup(platform: String, externalChatId: String, groupId: GroupId)
}
```

`ExpenseRepository.create` is the one place that translates between the
core's aggregate shape (`Expense` holding `shares: List<ExpenseShare>`)
and the two normalized tables — exactly the translation the bot design
already calls out as the repository's job.

## Schema

Flyway migration `storage/src/main/resources/db/migration/V1__init.sql`.
SQLite types: `TEXT` for UUIDs and ISO-8601 timestamps, `INTEGER` for
money in minor units (cents).

SQLite is a "community" (non-core) database for Flyway, which as of
Flyway 13.x needs its own module — `flyway-database-nc-sqlite` —
alongside `flyway-core` on the classpath; `flyway-core` alone does not
migrate SQLite. Pinned versions used in `storage/build.gradle.kts`
(checked against Maven Central at implementation time): Exposed
`1.5.0` (`exposed-core`, `exposed-jdbc`), Flyway `13.4.0`
(`flyway-core`, `flyway-database-nc-sqlite`), `org.xerial:sqlite-jdbc`
`3.53.4.0`, `kotlinx-coroutines-core` `1.11.0` (also pulled in
transitively by `exposed-core`, but declared explicitly since `storage`
calls `Dispatchers`/`withContext` directly).

```sql
CREATE TABLE group_ (
    id TEXT PRIMARY KEY,
    default_currency TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE member (
    id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL
);

CREATE TABLE group_member (
    group_id TEXT NOT NULL REFERENCES group_(id),
    member_id TEXT NOT NULL REFERENCES member(id),
    PRIMARY KEY (group_id, member_id)
);

CREATE TABLE expense (
    id TEXT PRIMARY KEY,
    group_id TEXT NOT NULL REFERENCES group_(id),
    currency TEXT NOT NULL,
    description TEXT NOT NULL,
    amount_cents INTEGER NOT NULL,
    payer_id TEXT NOT NULL REFERENCES member(id),
    split_type TEXT NOT NULL,
    created_by TEXT NOT NULL REFERENCES member(id),
    created_at TEXT NOT NULL,
    deleted_at TEXT
);

CREATE INDEX idx_expense_group_currency_active
    ON expense(group_id, currency) WHERE deleted_at IS NULL;

CREATE TABLE expense_share (
    expense_id TEXT NOT NULL REFERENCES expense(id),
    member_id TEXT NOT NULL REFERENCES member(id),
    share_amount_cents INTEGER NOT NULL,
    PRIMARY KEY (expense_id, member_id)
);

CREATE TABLE settlement (
    id TEXT PRIMARY KEY,
    group_id TEXT NOT NULL REFERENCES group_(id),
    currency TEXT NOT NULL,
    from_member_id TEXT NOT NULL REFERENCES member(id),
    to_member_id TEXT NOT NULL REFERENCES member(id),
    amount_cents INTEGER NOT NULL,
    created_by TEXT NOT NULL REFERENCES member(id),
    created_at TEXT NOT NULL,
    deleted_at TEXT
);

CREATE INDEX idx_settlement_group_currency_active
    ON settlement(group_id, currency) WHERE deleted_at IS NULL;

CREATE TABLE platform_identity (
    platform TEXT NOT NULL,
    external_user_id TEXT NOT NULL,
    member_id TEXT NOT NULL REFERENCES member(id),
    PRIMARY KEY (platform, external_user_id)
);

CREATE TABLE platform_group_link (
    platform TEXT NOT NULL,
    external_chat_id TEXT NOT NULL,
    group_id TEXT NOT NULL REFERENCES group_(id),
    PRIMARY KEY (platform, external_chat_id)
);
```

The table is named `group_` (trailing underscore) because `GROUP` is a
SQL reserved word; the Kotlin type stays `Group`.

## Money encoding

`core` keeps `BigDecimal` for `Expense.amount` / `ExpenseShare.shareAmount`
/ `Settlement.amount` — the rounding rules for equal splits are already
written and tested against it, and there's no domain reason to change
that. The `storage` module converts at the repository boundary only:

- Write: `amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()`
- Read: `BigDecimal(cents).movePointLeft(2)`

`RoundingMode.UNNECESSARY` is deliberate: if a `BigDecimal` ever has more
than 2 decimal places by the time it reaches storage, that's a bug
upstream (e.g. in split resolution), and this makes it fail loudly at
the boundary instead of silently truncating money.

## Connection management

- Driver: `org.xerial:sqlite-jdbc`.
- `Database.connect` (Exposed) against a JDBC URL built from an
  `SPLIT_DB_PATH` env var (defaults to a local dev path, e.g.
  `./split-dev.db`), so tests and prod never share a file.
- On every new connection, set:
  ```sql
  PRAGMA journal_mode=WAL;
  PRAGMA synchronous=NORMAL;
  PRAGMA foreign_keys=ON;
  PRAGMA busy_timeout=5000;
  ```
  SQLite serializes writes regardless of pool size, so a small connection
  pool (or a single connection) is sufficient at this traffic level; WAL
  lets reads proceed without blocking on an in-flight write.
- Flyway runs its migrations against the same JDBC URL at startup,
  before the (future) Telegram adapter begins polling.

## Transactions

Every multi-row write goes through exactly one `suspendTransaction { }`
block (wrapped in `withContext(Dispatchers.IO)`, per above) inside the
repository method that performs it — e.g. `ExpenseRepository.create`
inserts the `expense` row and all `expense_share` rows in a single
transaction, so a partial write (which would corrupt balance
calculations) can't happen. Adapter code never issues raw SQL and never
spans a transaction across two repository calls.

## Testing

Repository tests use a real temp-file SQLite database per test (created
via `Files.createTempFile`, deleted on teardown), migrated through the
actual Flyway path — not SQLite's `:memory:` mode. Each JDBC connection
to `:memory:` is its own separate database unless a shared-cache URI is
forced, which is an easy way to write tests that silently pass against
an empty DB; a temp file sidesteps that entirely and exercises the exact
migration path production uses.

Kotest (already used in `core`) is reused for `storage` tests, following
the same spec style as the existing `core` test suite. Kotest's coroutine
test support (`kotest-runner-junit5` runs suspend test bodies directly)
covers calling `suspend fun` repository methods without extra setup.

## Deployment

Matches the bot design's EC2 + systemd plan: back up the SQLite file
with `sqlite3 <path> ".backup <dest>"`, not a raw file copy, so a backup
never captures a mid-write state. The DB path being env-configurable
(`SPLIT_DB_PATH`) is what keeps this decoupled from any specific deploy
script.

## Out of scope for this spec

- The Telegram adapter module itself (command parsing, keyboards) — a
  separate piece of work that will consume these repository interfaces
  and `PlatformDirectory`.
- Connection pooling tuning / HikariCP — a single Exposed `Database`
  connection is sufficient at current expected load; revisit only if
  contention is observed.
- Multi-currency conversion, WhatsApp adapter — already out of scope per
  the bot design.
