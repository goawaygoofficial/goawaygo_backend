-- Single-use tokens sent by email: password reset and email verification.
-- Only the SHA-256 hash of a token is stored, like refresh tokens, so a database leak can't be used to reset
-- passwords or verify emails.

CREATE TABLE user_action_tokens (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     bigint       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose     varchar(32)  NOT NULL,
    token_hash  varchar(64)  NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    expires_at  timestamptz  NOT NULL,
    -- Set when the token is redeemed, or when a newer token of the same purpose replaces it.
    used_at     timestamptz,

    CONSTRAINT user_action_tokens_token_hash_key UNIQUE (token_hash),
    CONSTRAINT user_action_tokens_purpose_check CHECK (purpose IN ('PASSWORD_RESET', 'EMAIL_VERIFICATION'))
);

CREATE INDEX user_action_tokens_user_purpose_idx ON user_action_tokens (user_id, purpose);
