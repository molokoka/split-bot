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

The app reads these env vars directly (`System.getenv`) — nothing loads `.env` automatically, so export them in your shell first:

```bash
export TELEGRAM_BOT_TOKEN="<token from BotFather>"
export TELEGRAM_BOT_USERNAME="<bot's @username, without the @>"
export SPLIT_DB_PATH="./split-manual-test.db"
```

`TELEGRAM_BOT_USERNAME` is used to build the "Add me to a group" deep link shown when someone messages the bot directly instead of adding it to a group.

`SPLIT_DB_PATH` points at a local SQLite file; if you skip it, the bot falls back to `./split-dev.db`. Either way the file is created and migrated automatically on startup — you don't need to set it up yourself. Point it at a throwaway path so you don't pollute your real dev database.

If you keep these values in a `.env` file at the project root instead (gitignored, so it's safe to keep real values there), load it into your shell before running:

```bash
set -a; source .env; set +a
```

### 3. Run the bot

```bash
./gradlew :telegram:run
```

You should see `Bot started, polling for updates...`. Leave it running in the foreground.

### 4. Stop and clean up

`Ctrl+C` to stop the poll loop, then delete the manual test database:

```bash
rm -f split-manual-test.db*
```

(Matches `SPLIT_DB_PATH` above — adjust if you used a different path. The default `*` glob also removes SQLite's `-wal`/`-shm` sidecar files.)

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE).
