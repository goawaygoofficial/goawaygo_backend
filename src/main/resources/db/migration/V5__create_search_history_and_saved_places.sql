-- Discover (4.1): a signed-in user's recent searches (home banner) and saved places (My Trips).
-- Only places are stored: never the AI answers or photos, which are fetched fresh every time.

-- The places a user opened from search, newest first; the backend keeps only the latest 5 per user.
CREATE TABLE search_history (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       bigint           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    place_id      varchar(255)     NOT NULL,
    name          varchar(200)     NOT NULL,
    type          varchar(10)      NOT NULL,
    city          varchar(200)     NOT NULL DEFAULT '',
    region        varchar(200)     NOT NULL DEFAULT '',
    country       varchar(100)     NOT NULL,
    country_code  varchar(2)       NOT NULL,
    lat           double precision NOT NULL,
    lon           double precision NOT NULL,
    top_spot      varchar(200),
    searched_at   timestamptz      NOT NULL DEFAULT now(),

    CONSTRAINT search_history_user_place_key UNIQUE (user_id, place_id),
    CONSTRAINT search_history_type_check CHECK (type IN ('country', 'region', 'city', 'place'))
);

CREATE INDEX search_history_user_searched_idx ON search_history (user_id, searched_at DESC);

-- Places a user saved to My Trips (from a destination page or an idea).
CREATE TABLE saved_places (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     bigint           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    place_key   varchar(300)     NOT NULL,
    name        varchar(200)     NOT NULL,
    area        varchar(200)     NOT NULL DEFAULT '',
    country     varchar(100)     NOT NULL,
    lat         double precision NOT NULL,
    lon         double precision NOT NULL,
    category    varchar(30),
    created_at  timestamptz      NOT NULL DEFAULT now(),

    CONSTRAINT saved_places_user_place_key UNIQUE (user_id, place_key)
);

CREATE INDEX saved_places_user_created_idx ON saved_places (user_id, created_at DESC);
