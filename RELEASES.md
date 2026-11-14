# GoAwayGo Backend — releases

Every merge into `develop` whose CI passes becomes a version (Release workflow) and goes to **dev**; a version
moves to **test** and **prod** only through the Controller, after it passed below and with the owner's
approval. Newest first. Each entry: the changes (pull requests) and where it ran. Update this file in the PR
that records a release reaching prod (README → Releasing).

## v1.14.7 — 2026-11-13

Ran in: dev ✅ 2026-11-13

- CLEANUP.md: accounts done ([#92](https://github.com/goawaygoofficial/goawaygo_backend/pull/92))

## v1.14.6 — 2026-11-13

Ran in: dev ✅ 2026-11-13

- Clean-up record: only dev, test and prod remain ([#91](https://github.com/goawaygoofficial/goawaygo_backend/pull/91))

## v1.14.5 — 2026-11-13

Ran in: dev ✅ 2026-11-13

- Clean-up: complete per-environment variables ([#90](https://github.com/goawaygoofficial/goawaygo_backend/pull/90))

## v1.14.4 — 2026-11-13

Ran in: dev ✅ 2026-11-13

- LAUNCH.md: part B done (goawaygo.com is live) ([#89](https://github.com/goawaygoofficial/goawaygo_backend/pull/89))

## v1.14.3 — 2026-11-13

Ran in: dev ✅ 2026-11-13

- LAUNCH.md: part A done ([#88](https://github.com/goawaygoofficial/goawaygo_backend/pull/88))

## v1.14.2 — 2026-11-13

Ran in: dev ✅ 2026-11-13 · test ✅ 2026-11-13 · prod ✅ 2026-11-13

- fix: backup dump owned by the runner so it can be shredded ([#85](https://github.com/goawaygoofficial/goawaygo_backend/pull/85))

## v1.14.1 — 2026-11-13

Ran in: dev ✅ 2026-11-13

- docs: launch checklist for goawaygo.com ([#84](https://github.com/goawaygoofficial/goawaygo_backend/pull/84))

## v1.14.0 — 2026-11-13

Ran in: dev ✅ 2026-11-13

- feat: nightly encrypted prod database backups ([#83](https://github.com/goawaygoofficial/goawaygo_backend/pull/83))

## v1.13.1 — 2026-11-12

Ran in: dev ✅ 2026-11-12

- docs: 5.5 and 5.6 done ([#82](https://github.com/goawaygoofficial/goawaygo_backend/pull/82))

## v1.13.0 — 2026-11-12

Ran in: dev ✅ 2026-11-12 · test ✅ 2026-11-12 · prod ✅ 2026-11-12

- feat: Server-Timing on test runs' answers ([#79](https://github.com/goawaygoofficial/goawaygo_backend/pull/79))

## v1.12.1 — 2026-11-12

Ran in: dev ✅ 2026-11-12

- fix: no secrets in test reports ([#78](https://github.com/goawaygoofficial/goawaygo_backend/pull/78))

## v1.12.0 — 2026-11-12

Ran in: dev ✅ 2026-11-12

- feat: automated and performance tests after every deployment, required to move a version up ([#77](https://github.com/goawaygoofficial/goawaygo_backend/pull/77))

## v1.11.0 — 2026-11-12

Ran in: dev ✅ 2026-11-12

- feat: automated test runs skip rate limits in dev and test ([#76](https://github.com/goawaygoofficial/goawaygo_backend/pull/76))

## v1.10.0 — 2026-11-12

Ran in: dev ✅ 2026-11-12

- feat: fake outside services for automated test runs in dev and test ([#75](https://github.com/goawaygoofficial/goawaygo_backend/pull/75))

## v1.9.1 — 2026-11-12

Ran in: dev ✅ 2026-11-12

- docs: production runs in goawaygo-prod; 5.4 done ([#74](https://github.com/goawaygoofficial/goawaygo_backend/pull/74))

## v1.9.0 — 2026-11-11

Ran in: dev ✅ 2026-11-11 · test ✅ 2026-11-12 · prod ✅ 2026-11-12

- feat: production in its own Google Cloud project ([#71](https://github.com/goawaygoofficial/goawaygo_backend/pull/71))

## v1.8.0 — 2026-11-11

Ran in: dev ✅ 2026-11-11 · test ✅ 2026-11-11

- feat: controller.yaml moves a version to test and prod ([#69](https://github.com/goawaygoofficial/goawaygo_backend/pull/69))

## v1.7.0 — 2026-11-11

- feat: dev, test and prod environments ([#68](https://github.com/goawaygoofficial/goawaygo_backend/pull/68))

## v1.6.0 — 2026-11-11

- feat: a version and GitHub Release for every merge into develop ([#67](https://github.com/goawaygoofficial/goawaygo_backend/pull/67))

## Before versioned releases (MVP 1, up to v1.5.0)

Versions started with MVP 2 (v1.6.0, Release workflow). What shipped before, all three parts together:

- **Phase 1 — Foundation:** repos, branching, CI, secret scanning, Dependabot.
- **Phase 2 — Backend and login:** PostgreSQL, JWT + rotating refresh tokens, Google Sign-In, rate limits, email
  verification and password reset, website and app connected, UI matched across web and app.
- **Phase 3 — First hosted version:** API on Cloud Run + Neon, the website on Cloudflare Pages, Resend email, Android
  builds for download and Firebase beta (app 1.0.0 build 4).
- **Phase 4 — Features:** 4.0 foundations (monitoring, analytics with consent, legal pages, the outside-API toolkit),
  4.1 Discover, 4.2 Wanderbook, 4.3 Book, 4.4 AI trip planner, 4.5 notifications, 4.6 Me, 4.7 global readiness
  (7 languages, currencies, accessibility). Details per feature: README → Roadmap.
