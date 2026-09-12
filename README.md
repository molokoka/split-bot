# split

A Telegram bot that splits shared expenses within a group and tracks who owes whom.

Kotlin/Gradle multi-module project:

- `core` — domain logic (balances, settlements), no I/O.
- `storage` — SQLite persistence via Exposed + Flyway migrations.
- `telegram` — the bot adapter: command parsing, message formatting, the Telegram HTTP API client, and the composition root (`BotApplication.kt`).

## Prerequisites

- JDK 17
- A Telegram account, if you want to run the bot against real Telegram (see below)

No other setup is required — `./gradlew` (the wrapper) downloads Gradle and all dependencies on first run.

## Running locally (tests)

```bash
./gradlew test
```

Runs the full suite across `core`, `storage`, and `telegram`. This is the normal local dev loop — no Telegram token or network access needed.

Run a single module or test class when iterating:

```bash
./gradlew :telegram:test --tests "split.telegram.CommandRouterSpec"
```

## Running as a live/test bot

This runs the actual bot process, polling real Telegram for updates — useful for manually verifying a change end-to-end.

### 1. Create a bot with BotFather

In Telegram, message [@BotFather](https://t.me/BotFather), send `/newbot` (or reuse an existing test bot you already own), and copy the token it gives you. Keep it secret — anyone with the token can control the bot.

### 2. Set environment variables

The app reads two env vars directly (`System.getenv`) — nothing loads `.env` automatically, so export them in your shell first:

```bash
export TELEGRAM_BOT_TOKEN="<token from BotFather>"
export SPLIT_DB_PATH="./split-manual-test.db"
```

`SPLIT_DB_PATH` points at a local SQLite file; if you skip it, the bot falls back to `./split-dev.db`. Either way the file is created and migrated automatically on startup — you don't need to set it up yourself. Point it at a throwaway path so you don't pollute your real dev database.

A relative `SPLIT_DB_PATH` resolves against `telegram/` (Gradle's `application` plugin runs `:telegram:run` with that module's directory as the working directory), not the repo root you're running `./gradlew` from — look there if a relative path seems to "disappear."

If you keep these values in a `.env` file at the project root instead (gitignored, so it's safe to keep real values there), load it into your shell before running:

```bash
set -a; source .env; set +a
```

### 3. Run the bot

```bash
./gradlew :telegram:run
```

You should see `Bot started, polling for updates...`. Leave it running in the foreground.

### 4. Seed a test scenario (optional)

Most flows are only really testable with more than one member, and you're the only real Telegram
user in your own test chat. Rather than recruiting accounts, apply a named scenario:

```bash
./gradlew :telegram:seed --args="-1001234567890 members"
./gradlew :telegram:seed --args="list"     # what's available
```

The first argument is your chat's id — the bot logs it on every command (`command "/help" chat=…`),
so message it once and copy it from there.

| Scenario | What it sets up | Useful for |
| --- | --- | --- |
| `members` | `@alice`, `@bobby`, `@carol` | `/split 90 dinner @alice @bobby`, `/expenses pending` |
| `expenses` | members + one expense per split type | `/expenses`, `/history`, `/balance`, `/delete` |
| `unbalanced` | debts arranged in a chain | `/settle_suggest`, `/settle` |
| `big-group` | eight members | how tables and keyboards hold up on long lists |

Scenarios build their state through the same `IdentityResolver` and repositories the bot uses, so
what they leave behind is what the bot itself would have written — and re-running is safe, since
anyone already present is left alone. The command reads `SPLIT_DB_PATH` exactly like the bot does;
unlike `:telegram:run`, a relative path here resolves against the repo root.

In-flight splits aren't seedable, by design: a `split_flow_state` row is keyed by Telegram message
ids, so a fabricated one would point at messages that were never sent — a row with no table to look
at and no buttons to tap. Seed the members, then start the split yourself.

### 5. Stop and clean up

`Ctrl+C` to stop the poll loop, then delete the manual test database:

```bash
rm -f split-manual-test.db*
```

(Matches `SPLIT_DB_PATH` above — adjust if you used a different path. The default `*` glob also removes SQLite's `-wal`/`-shm` sidecar files.)

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE).
