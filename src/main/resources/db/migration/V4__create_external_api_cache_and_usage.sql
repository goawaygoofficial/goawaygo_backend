-- Outside APIs (src/main/java/com/goaway/backend/external): a response cache and per-provider usage counters.

-- Responses from outside APIs, so repeated requests don't use up free quotas. Rows past expires_at are still
-- served if the provider is down ("stale"), then deleted a week after they expire.
CREATE TABLE external_api_cache (
    provider    varchar(64)  NOT NULL,
    cache_key   varchar(255) NOT NULL,
    body        text         NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    expires_at  timestamptz  NOT NULL,

    CONSTRAINT external_api_cache_pkey PRIMARY KEY (provider, cache_key)
);

CREATE INDEX external_api_cache_expires_at_idx ON external_api_cache (expires_at);

-- Calls made to each provider per UTC day and per UTC month. A call is counted before it is made, and refused once
-- the configured limit (set below the provider's free limit) is reached.
CREATE TABLE external_api_usage (
    provider      varchar(64) NOT NULL,
    period        varchar(5)  NOT NULL,
    period_start  date        NOT NULL,
    calls         integer     NOT NULL,

    CONSTRAINT external_api_usage_pkey PRIMARY KEY (provider, period, period_start),
    CONSTRAINT external_api_usage_period_check CHECK (period IN ('DAY', 'MONTH')),
    CONSTRAINT external_api_usage_calls_check CHECK (calls >= 0)
);
