-- Wanderbook (4.2), part 2: photos, posts, likes, comments, reports, and admins (who review reports).
-- Everything is deleted with the account (ON DELETE CASCADE); the photo files in R2 are deleted by the backend.

-- Reviews reports on the admin page. Set by hand: UPDATE users SET is_admin = true WHERE email = '...';
ALTER TABLE users ADD COLUMN is_admin boolean NOT NULL DEFAULT false;

-- Posts: a caption and a place tag (names only: exact coordinates are never stored).
CREATE TABLE wanderbook_posts (
    id             bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id      varchar(12)  NOT NULL DEFAULT wanderbook_new_public_id(),
    author_id      bigint       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    caption        varchar(2200) NOT NULL DEFAULT '',
    place_name     varchar(200),
    place_region   varchar(200),
    place_country  varchar(100),
    created_at     timestamptz  NOT NULL DEFAULT now(),
    edited_at      timestamptz,

    CONSTRAINT wanderbook_posts_public_id_key UNIQUE (public_id)
);

CREATE INDEX wanderbook_posts_author_idx ON wanderbook_posts (author_id, id DESC);

-- Uploaded photos (the original files, in R2 under object_key). A photo belongs to a post, or is someone's profile
-- photo or cover, or is waiting to be used (deleted after a day if it never is).
CREATE TABLE wanderbook_photos (
    id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Random id the apps use to refer to an upload (and the file name in R2).
    public_id     varchar(32)  NOT NULL,
    owner_id      bigint       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    post_id       bigint       REFERENCES wanderbook_posts (id) ON DELETE CASCADE,
    position      smallint,
    object_key    varchar(100) NOT NULL,
    content_type  varchar(20)  NOT NULL,
    bytes         bigint       NOT NULL,
    width         integer      NOT NULL,
    height        integer      NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT wanderbook_photos_public_id_key UNIQUE (public_id),
    CONSTRAINT wanderbook_photos_object_key_key UNIQUE (object_key)
);

CREATE INDEX wanderbook_photos_post_idx ON wanderbook_photos (post_id, position);
CREATE INDEX wanderbook_photos_unused_idx ON wanderbook_photos (created_at) WHERE post_id IS NULL;

ALTER TABLE wanderbook_profiles
    ADD COLUMN avatar_photo_id bigint REFERENCES wanderbook_photos (id) ON DELETE SET NULL,
    ADD COLUMN cover_photo_id  bigint REFERENCES wanderbook_photos (id) ON DELETE SET NULL;

CREATE TABLE wanderbook_likes (
    post_id     bigint      NOT NULL REFERENCES wanderbook_posts (id) ON DELETE CASCADE,
    user_id     bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (post_id, user_id)
);

CREATE INDEX wanderbook_likes_user_idx ON wanderbook_likes (user_id);

CREATE TABLE wanderbook_comments (
    id          bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    post_id     bigint       NOT NULL REFERENCES wanderbook_posts (id) ON DELETE CASCADE,
    author_id   bigint       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    text        varchar(500) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX wanderbook_comments_post_idx ON wanderbook_comments (post_id, id);

-- Reports of posts, comments and people, reviewed by admins. Deleting the reported content keeps the report
-- (target_id then points nowhere); deleting either account deletes it.
CREATE TABLE wanderbook_reports (
    id              bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reporter_id     bigint       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    target_type     varchar(10)  NOT NULL,
    target_id       bigint       NOT NULL,
    -- Who posted the reported content (or the reported person).
    target_user_id  bigint       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reason          varchar(20)  NOT NULL,
    details         varchar(500) NOT NULL DEFAULT '',
    status          varchar(10)  NOT NULL DEFAULT 'open',
    created_at      timestamptz  NOT NULL DEFAULT now(),
    resolved_at     timestamptz,
    resolution      varchar(20),

    CONSTRAINT wanderbook_reports_target_check CHECK (target_type IN ('post', 'comment', 'user')),
    CONSTRAINT wanderbook_reports_status_check CHECK (status IN ('open', 'resolved')),
    CONSTRAINT wanderbook_reports_once UNIQUE (reporter_id, target_type, target_id)
);

CREATE INDEX wanderbook_reports_open_idx ON wanderbook_reports (status, id DESC);
