#!/usr/bin/env bash
set -euo pipefail

DB_PATH="${1:-split-manual-test.db}"

if [ ! -f "$DB_PATH" ]; then
    echo "No database at $DB_PATH — run the bot once first so Flyway creates and migrates it." >&2
    exit 1
fi

GROUP_COUNT=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM group_;")
if [ "$GROUP_COUNT" -eq 0 ]; then
    echo "No group found in $DB_PATH yet — message the bot at least once (e.g. /start) first." >&2
    exit 1
fi
if [ "$GROUP_COUNT" -gt 1 ]; then
    echo "Multiple groups found in $DB_PATH — this script only supports a single-group test database. Groups:" >&2
    sqlite3 -header -column "$DB_PATH" "SELECT id, default_currency FROM group_;" >&2
    exit 1
fi
GROUP_ID=$(sqlite3 "$DB_PATH" "SELECT id FROM group_;")

for name in alice bobby carol; do
    EXISTING=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM platform_identity WHERE platform = 'telegram' AND external_user_id = 'test-$name';")
    if [ "$EXISTING" -gt 0 ]; then
        echo "test-$name already seeded, skipping."
        continue
    fi
    MEMBER_ID=$(uuidgen | tr 'A-Z' 'a-z')
    DISPLAY_NAME="$(tr '[:lower:]' '[:upper:]' <<< "${name:0:1}")${name:1}"
    sqlite3 "$DB_PATH" "
        INSERT INTO member (id, display_name) VALUES ('$MEMBER_ID', '$DISPLAY_NAME');
        INSERT INTO group_member (group_id, member_id) VALUES ('$GROUP_ID', '$MEMBER_ID');
        INSERT INTO platform_identity (platform, external_user_id, member_id, username)
            VALUES ('telegram', 'test-$name', '$MEMBER_ID', '$name');
    "
    echo "Seeded $DISPLAY_NAME (@$name)."
done
