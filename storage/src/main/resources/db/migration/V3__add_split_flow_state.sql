CREATE TABLE split_flow_state (
    chat_id                    INTEGER NOT NULL,
    prompt_message_id          INTEGER NOT NULL,
    group_id                   TEXT NOT NULL REFERENCES group_(id),
    state_type                 TEXT NOT NULL,
    invoker_id                 TEXT NOT NULL REFERENCES member(id),
    description                TEXT,
    amount_cents               INTEGER,
    currency                   TEXT,
    awaiting                   TEXT,
    split_type_hint            TEXT,
    mention_usernames          TEXT,
    exact_amounts              TEXT,
    stage                      TEXT,
    participant_ids            TEXT,
    amounts_entered            TEXT,
    actions_message_id         INTEGER,
    pending_participant_id     TEXT,
    pending_prompt_message_id  INTEGER,
    pending_is_auto_advance    INTEGER,
    PRIMARY KEY (chat_id, prompt_message_id)
);

CREATE INDEX split_flow_state_group_idx ON split_flow_state(group_id, state_type);
