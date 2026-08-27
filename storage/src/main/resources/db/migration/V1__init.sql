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
