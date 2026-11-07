-- AI trip planner (4.4): day-by-day plans a signed-in user made and saved (My Trips). The plan is the user's own
-- content once saved, so it is stored (unlike Discover's cards, which are asked fresh on every page open).

CREATE TABLE trip_plans (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Random id in links (/plans/<public_id>), so plans can't be found by counting.
    public_id     varchar(12)      NOT NULL DEFAULT substr(md5(gen_random_uuid()::text), 1, 12),
    user_id       bigint           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    place_id      varchar(255),
    place_name    varchar(200)     NOT NULL,
    place_area    varchar(200)     NOT NULL DEFAULT '',
    country       varchar(100)     NOT NULL,
    lat           double precision NOT NULL,
    lon           double precision NOT NULL,
    -- The destination's city airport code, for the Flights shortcut (null when none was found).
    airport       varchar(3),
    start_date    date             NOT NULL,
    days          smallint         NOT NULL,
    travellers    varchar(10)      NOT NULL,
    pace          varchar(10)      NOT NULL,
    budget        varchar(10)      NOT NULL,
    interests     varchar(200)     NOT NULL DEFAULT '',
    currency      varchar(3)       NOT NULL,
    -- The itinerary as JSON (title, summary, days, tips, stay estimate); see plans/Itinerary.java.
    itinerary     jsonb            NOT NULL,
    model         varchar(60)      NOT NULL,
    -- Shared plans can be opened by anyone with the link (read-only).
    shared        boolean          NOT NULL DEFAULT false,
    created_at    timestamptz      NOT NULL DEFAULT now(),
    updated_at    timestamptz      NOT NULL DEFAULT now(),

    CONSTRAINT trip_plans_public_id_key UNIQUE (public_id),
    CONSTRAINT trip_plans_days_check CHECK (days BETWEEN 1 AND 10),
    CONSTRAINT trip_plans_travellers_check CHECK (travellers IN ('solo', 'couple', 'family', 'friends')),
    CONSTRAINT trip_plans_pace_check CHECK (pace IN ('relaxed', 'balanced', 'packed')),
    CONSTRAINT trip_plans_budget_check CHECK (budget IN ('budget', 'mid', 'luxury'))
);

CREATE INDEX trip_plans_user_created_idx ON trip_plans (user_id, created_at DESC);
