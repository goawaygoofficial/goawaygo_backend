-- Notifications (4.5): the bell's list, the phones that get push messages, and which pushes each user wants.
-- Everything is deleted with the account (ON DELETE CASCADE); notifications themselves after 90 days.

-- One row per notification. The text is written when it is read (names can change), from the type and the
-- people, post or plan it points to; a deleted post or plan deletes its notifications.
CREATE TABLE notifications (
    id          bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     bigint       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type        varchar(20)  NOT NULL,
    -- Who did it (for grouped likes: the latest person to like).
    actor_id    bigint       REFERENCES users (id) ON DELETE CASCADE,
    post_id     bigint       REFERENCES wanderbook_posts (id) ON DELETE CASCADE,
    -- A deleted comment takes its notification with it.
    comment_id  bigint       REFERENCES wanderbook_comments (id) ON DELETE CASCADE,
    plan_id     bigint       REFERENCES trip_plans (id) ON DELETE CASCADE,
    -- Grouped likes: how many people liked the post since the owner last read the bell.
    count       integer      NOT NULL DEFAULT 1,
    -- An announcement's title and text, or a comment's text.
    title       varchar(120) NOT NULL DEFAULT '',
    text        varchar(500) NOT NULL DEFAULT '',
    read_at     timestamptz,
    -- When this notification last went out as a push (grouped likes push at most hourly).
    pushed_at   timestamptz,
    created_at  timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT notifications_type_check CHECK (type IN ('follow', 'follow_request', 'follow_accepted', 'like',
        'comment', 'trip_reminder', 'plan_opened', 'announcement'))
);

CREATE INDEX notifications_user_idx ON notifications (user_id, id DESC);
CREATE INDEX notifications_unread_idx ON notifications (user_id) WHERE read_at IS NULL;
CREATE INDEX notifications_created_idx ON notifications (created_at);
-- At most one unread "liked your post" per post: new likes add to it.
CREATE UNIQUE INDEX notifications_unread_like_key ON notifications (user_id, post_id)
    WHERE type = 'like' AND read_at IS NULL;

-- Firebase Cloud Messaging registration tokens: one per app install. A token moves to whoever signs in on that
-- phone; tokens Firebase reports as gone are deleted.
CREATE TABLE notification_devices (
    token       varchar(4096) PRIMARY KEY,
    user_id     bigint        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    platform    varchar(10)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT notification_devices_platform_check CHECK (platform IN ('android', 'ios'))
);

CREATE INDEX notification_devices_user_idx ON notification_devices (user_id);

-- Which pushes a user wants (no row: all of them). The bell lists everything either way.
CREATE TABLE notification_settings (
    user_id         bigint      PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    wanderbook      boolean     NOT NULL DEFAULT true,
    trip_reminders  boolean     NOT NULL DEFAULT true,
    shared_plans    boolean     NOT NULL DEFAULT true,
    announcements   boolean     NOT NULL DEFAULT true,
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- Trip reminders: the last one sent (3 = "in 3 days", 1 = "tomorrow"), so each goes out once.
ALTER TABLE trip_plans ADD COLUMN reminded_days smallint;
-- "Someone opened your shared plan": at most once a day per plan.
ALTER TABLE trip_plans ADD COLUMN opened_notified_at timestamptz;
