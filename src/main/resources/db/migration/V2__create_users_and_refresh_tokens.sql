-- Users and refresh tokens for email/password and (later) Google sign-in.

CREATE TABLE users (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Stored lower-cased and trimmed by the application; the check keeps it that way.
    email               varchar(320) NOT NULL,
    name                varchar(100) NOT NULL,
    -- BCrypt hash. NULL for accounts that only sign in with Google.
    password_hash       varchar(100),
    -- Google account id ("sub" claim). NULL until the user signs in with Google.
    google_subject      varchar(255),
    email_verified      boolean      NOT NULL DEFAULT false,
    profile_picture_url varchar(2048),
    phone               varchar(32),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT users_email_key UNIQUE (email),
    CONSTRAINT users_google_subject_key UNIQUE (google_subject),
    CONSTRAINT users_email_normalized CHECK (email = lower(btrim(email))),
    CONSTRAINT users_has_sign_in_method CHECK (password_hash IS NOT NULL OR google_subject IS NOT NULL)
);

-- One row per issued refresh token. Tokens are rotated on every use: the old row is revoked and
-- points to its replacement. All tokens descending from one sign-in share a family_id, so reuse of
-- an already-rotated token (a sign of theft) can revoke the whole family.
CREATE TABLE refresh_tokens (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      bigint       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- SHA-256 of the token, hex-encoded. The token itself is never stored.
    token_hash   varchar(64)  NOT NULL,
    family_id    uuid         NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    expires_at   timestamptz  NOT NULL,
    revoked_at   timestamptz,
    replaced_by  bigint       REFERENCES refresh_tokens (id),
    user_agent   varchar(255),

    CONSTRAINT refresh_tokens_token_hash_key UNIQUE (token_hash)
);

CREATE INDEX refresh_tokens_user_id_idx ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_family_id_idx ON refresh_tokens (family_id);
