-- Wanderbook (4.2), part 1: profiles, follows (with requests for private profiles) and blocks.
-- Everything is deleted with the account (ON DELETE CASCADE).

-- Random id used in profile links (/wanderbook/u/<public_id>), so profiles can't be listed by counting user ids.
CREATE FUNCTION wanderbook_new_public_id() RETURNS varchar(12)
    LANGUAGE sql VOLATILE
    AS $$ SELECT substr(md5(gen_random_uuid()::text), 1, 12) $$;

-- Every account has a Wanderbook profile (created with the account, see the trigger below). The name and account
-- photo come from users.
CREATE TABLE wanderbook_profiles (
    user_id     bigint       PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    public_id   varchar(12)  NOT NULL DEFAULT wanderbook_new_public_id(),
    bio         varchar(150) NOT NULL DEFAULT '',
    home_town   varchar(100) NOT NULL DEFAULT '',
    -- Private: only approved followers see posts and follower lists.
    is_private  boolean      NOT NULL DEFAULT false,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT wanderbook_profiles_public_id_key UNIQUE (public_id)
);

CREATE FUNCTION wanderbook_create_profile() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    INSERT INTO wanderbook_profiles (user_id) VALUES (NEW.id);
    RETURN NEW;
END
$$;

CREATE TRIGGER users_create_wanderbook_profile AFTER INSERT ON users
    FOR EACH ROW EXECUTE FUNCTION wanderbook_create_profile();

INSERT INTO wanderbook_profiles (user_id) SELECT id FROM users;

-- Who follows whom. A follow of a private profile starts as a request (pending) until the owner approves it.
CREATE TABLE wanderbook_follows (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    follower_id  bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    followee_id  bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status       varchar(8)  NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT wanderbook_follows_pair_key UNIQUE (follower_id, followee_id),
    CONSTRAINT wanderbook_follows_not_self CHECK (follower_id <> followee_id),
    CONSTRAINT wanderbook_follows_status_check CHECK (status IN ('accepted', 'pending'))
);

CREATE INDEX wanderbook_follows_followee_idx ON wanderbook_follows (followee_id, status, id DESC);
CREATE INDEX wanderbook_follows_follower_idx ON wanderbook_follows (follower_id, status, id DESC);

-- Blocks: the two users can't see or follow each other (blocking also removes follows both ways).
CREATE TABLE wanderbook_blocks (
    blocker_id  bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    blocked_id  bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT wanderbook_blocks_not_self CHECK (blocker_id <> blocked_id)
);

CREATE INDEX wanderbook_blocks_blocked_idx ON wanderbook_blocks (blocked_id);

-- People search by name (case-insensitive "contains").
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX users_name_trgm_idx ON users USING gin (lower(name) gin_trgm_ops);
