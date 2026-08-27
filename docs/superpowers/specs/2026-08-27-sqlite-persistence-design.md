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

Each is a plain Kotlin interface with suspend-free, synchronous methods
(no reactive/coroutine wrapping needed at this scale — the bot processes
one Telegram update at a time via long-polling).

## Core prerequisite

The bot design's data model lists `created_at` on `Group` and
`created_by` / `created_at` / `deleted_at` on `Settlement`, matching the
audit/soft-delete fields `Expense` already has. The current
`core/src/main/kotlin/split/core/Entities.kt` only has these fields on
`Expense`. Before the storage layer is built, `core` needs:

- `Group.createdAt: Instant`
- `Settlement.createdBy: MemberId`
- `Settlement.createdAt: Instant`
- `Settlement.deletedAt: Instant? = null`

`computeBalances` should exclude soft-deleted settlements the same way it
already excludes soft-deleted expenses (`it.deletedAt == null`), and an
authorization rule for deleting a settlement (payer or group admin)
should mirror the existing `canDeleteExpense`. These are small, isolated
additions to already-tested code — same shape as the existing
`Expense`/`canDeleteExpense` pattern, done via TDD like the rest of
`core`.

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
    fun create(member: Member)
    fun find(id: MemberId): Member?
    fun findByGroup(groupId: GroupId): List<Member>
}

interface GroupRepository {
    fun create(group: Group)
    fun find(id: GroupId): Group?
    fun addMember(groupId: GroupId, memberId: MemberId)
}

interface ExpenseRepository {
    fun create(expense: Expense)          // writes expense + expense_share rows in one transaction
    fun find(id: ExpenseId): Expense?
    fun listActive(groupId: GroupId, currency: String): List<Expense>  // deletedAt IS NULL
    fun softDelete(id: ExpenseId, deletedAt: Instant)
}

interface SettlementRepository {
    fun create(settlement: Settlement)
    fun listActive(groupId: GroupId, currency: String): List<Settlement>
    fun softDelete(id: SettlementId, deletedAt: Instant)
}

interface PlatformDirectory {
    fun findMember(platform: String, externalUserId: String): MemberId?
    fun linkMember(platform: String, externalUserId: String, memberId: MemberId)
    fun findGroup(platform: String, externalChatId: String): GroupId?
    fun linkGroup(platform: String, externalChatId: String, groupId: GroupId)
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

Every multi-row write goes through exactly one Exposed `transaction { }`
block inside the repository method that performs it — e.g.
`ExpenseRepository.create` inserts the `expense` row and all
`expense_share` rows in a single transaction, so a partial write (which
would corrupt balance calculations) can't happen. Adapter code never
issues raw SQL and never spans a transaction across two repository
calls.

## Testing

Repository tests use a real temp-file SQLite database per test (created
via `Files.createTempFile`, deleted on teardown), migrated through the
actual Flyway path — not SQLite's `:memory:` mode. Each JDBC connection
to `:memory:` is its own separate database unless a shared-cache URI is
forced, which is an easy way to write tests that silently pass against
an empty DB; a temp file sidesteps that entirely and exercises the exact
migration path production uses.

Kotest (already used in `core`) is reused for `storage` tests, following
the same spec style as the existing `core` test suite.

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
