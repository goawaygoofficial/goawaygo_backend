-- Me (4.6): account settings, deleting an account (30 days to change your mind) and recently viewed trip plans.

ALTER TABLE users
    -- ISO 3166 country (e.g. IN) and ISO 4217 currency (e.g. INR) the user picked; null until they do.
    ADD COLUMN country               varchar(2),
    ADD COLUMN currency              varchar(3),
    -- A new email waiting for its confirmation link; the account keeps the old one until then.
    ADD COLUMN pending_email         varchar(320),
    -- Set when the user asks to delete the account. Signed out everywhere and hidden from others at once; signing
    -- in again within 30 days clears it; after that the daily job deletes the account and everything it owns.
    ADD COLUMN deletion_requested_at timestamptz;

CREATE INDEX users_deletion_requested_idx ON users (deletion_requested_at) WHERE deletion_requested_at IS NOT NULL;

ALTER TABLE user_action_tokens
    DROP CONSTRAINT user_action_tokens_purpose_check,
    ADD CONSTRAINT user_action_tokens_purpose_check
        CHECK (purpose IN ('PASSWORD_RESET', 'EMAIL_VERIFICATION', 'EMAIL_CHANGE'));

-- Trip plans a signed-in user opened (their own and shared ones), newest first; the backend keeps the latest 30.
-- Recently viewed places are the existing search_history.
CREATE TABLE plan_views (
    user_id    bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    plan_id    bigint      NOT NULL REFERENCES trip_plans (id) ON DELETE CASCADE,
    viewed_at  timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (user_id, plan_id)
);

CREATE INDEX plan_views_user_viewed_idx ON plan_views (user_id, viewed_at DESC);
