<!-- Copy of Goaway/RELEASING.md (workspace root, shared by all repos). Keep the copies in sync. -->

# Releasing GoAwayGo

What happens after a merge to `develop`, and how to publish a new Android build. Applies to all three repos
(`goawaygo_backend`, `goawaygo_web_frontend`, `goawaygo_app_frontend`). Setup details: each repo's `docs/`.

## After every merge to `develop`

Every merge whose CI passes becomes a **version** (MVP 2, 5.2): a `vMAJOR.MINOR.PATCH` tag and a GitHub Release
(a pre-release until it reaches production) listing the merged PRs. The PR title decides the number: `feat:` → minor,
`fix:` and everything else → patch, `feat!:` or `BREAKING CHANGE` → major (`.github/scripts/next-version.sh`). Each
repo has its own versions; all three started at `v1.5.0` (MVP 1).

| Part        | You do                                | Happens automatically                                                                                                                                 | Check                                                     |
| ----------- | ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| Backend     | Nothing                               | CI tests → version → image built once (tagged with the version) → deployed to **dev** (`api-dev.goawaygo.com`) → smoke test; Flyway migrates (~6 min) | GitHub → goawaygo_backend → Actions → **Release** ✅      |
| Website     | Nothing                               | CI → version → website built once, attached to the release, deployed to **dev** (`dev.goawaygo.com`) (**Release**)                                    | GitHub → goawaygo_web_frontend → Actions → **Release** ✅ |
| Android app | Release it (below) when worth testing | CI → version (**Release**). The APK is signed with the upload key that exists only on the release PC                                                  | `https://goawaygo.com/download` shows the new version     |

The app doesn't need a release after every merge: release at the end of a feature or when testers should try
something.

## Release a new Android build (~10 min)

Git Bash, one command per line:

```bash
cd /c/Users/priyesh/OneDrive/Desktop/Dev/Goaway/app_frontend
git switch develop
git pull
export PATH="$PATH:/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin"
bash scripts/release-android.sh 1.0.0 <next-build> "What changed in this version" --upload
```

- `<next-build>`: the last build number + 1. **It must always go up**, or phones refuse the update.
- Ends with `Live: https://goawaygo.com/download`. If it asks for Cloudflare, run
  `npx --yes wrangler@4.145.0 login` once and repeat.
- It refuses to run without the upload key (`android/key.properties`), so nothing debug-signed is ever published.
- Push notifications need the Firebase key on this PC: run `bash scripts/firebase-key.sh` once (gcloud signed in).
  Without it the script warns and that build has no push.
- Fails with `cannot find symbol ... Plugin` in `GeneratedPluginRegistrant.java` (usually after a dependency
  update): an old partial build of that plugin is left on this PC. Run `flutter clean` and run the command again.

Then send it to the beta testers:

1. Firebase console → project **goaway-483709** → **App Distribution**.
2. Drag in `app_frontend/build/release/goawaygo-<version>-<build>-universal.apk`, paste the same notes.
3. Testers → group **public-beta** → **Distribute**.

Check `https://goawaygo.com/download`, then install it on a phone over the previous build.

### Version numbers

| Change          | Example                    |
| --------------- | -------------------------- |
| Every release   | build number +1, always    |
| Fixes           | keep the version (`1.0.0`) |
| New feature set | `1.1.0`                    |
| Big milestone   | `2.0.0`                    |

### Release history

| Date       | Version | Build | Notes                                                                                                                                                                                                                                                                  |
| ---------- | ------- | ----- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 2026-10-17 | 1.0.0   | 4     | First public beta (build 3 withdrawn: version-code bug)                                                                                                                                                                                                                |
| 2026-10-24 | 1.0.0   | 6     | First GoAwayGo build: new name and app ID `com.goawaygo.app` (testers install it, then remove the old Goaway app); legal links, optional usage statistics, Sentry 10, Android 8.0+. Build 5 skipped (an untracked upload with placeholder notes, 2026-10-17, replaced) |
| 2026-10-31 | 1.1.0   | 7     | Discover (search any place: photos and nine travel info cards) and Wanderbook (share trip photos, follow travellers, like and comment); smoother navigation and photo animations                                                                                       |
| 2026-11-07 | 1.2.0   | 8     | Book: flight search with recent prices (Aviasales), hotel and car rental partners opened with your dates, airport and city suggestions while typing                                                                                                                    |
| 2026-11-08 | 1.3.0   | 9     | AI trip planner: day-by-day plans from any destination or saved place, cost estimate, booking shortcuts, change a day, share by link                                                                                                                                   |
| 2026-11-09 | 1.4.0   | 10    | Notifications: a bell on Home with an unread count; push for new followers, likes, comments, trip reminders and opened shared plans; notification settings with a switch per type                                                                                      |
| 2026-11-10 | 1.5.0   | 11    | Me: edit name, photo, password, email, country and currency; recently viewed; delete account. 7 languages (en, hi, es, fr, de, ar, ja) and prices in your currency; accessibility (contrast, 48dp buttons, labels) and faster start                                    |
| 2026-11-10 | 1.5.0   | 12    | Updated crash reporting (Sentry) and usage statistics (PostHog) libraries                                                                                                                                                                                              |

Add a row for every release.

## Occasionally

| Situation                         | What to do                                                                                                                                                                        |
| --------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| New backend secret (API key etc.) | Stored with a hidden-input script into Google Secret Manager (`backend/scripts/gcp-secrets.sh`)                                                                                   |
| New website setting               | A change to `src/lib/env.ts` (one build serves every environment), merged like any other change                                                                                   |
| Database change                   | Nothing: Flyway migrates on deploy. Keep migrations additive so a rollback still works.                                                                                           |
| Broken in test or prod            | Roll back with **Controller** → Run workflow (above). Instant backend switch: `gcloud run services update-traffic <service> --region asia-southeast1 --to-revisions REVISION=100` |
| Website broken in prod (urgent)   | Cloudflare → goawaygo-web-prod → Deployments → earlier deployment → **Rollback**; then the Controller as above                                                                    |
| Bad app build                     | Release a fixed build with a higher build number; delete the bad files from R2 and purge their URLs in Cloudflare cache                                                           |

## Moving a version up: test, then prod (`controller.yaml`, MVP 2, 5.4)

dev gets every version automatically. test and prod get a version only through **Controller**
(`.github/workflows/controller.yaml` in each repo), with your approval twice: once for the merge, once for the deploy.

1. **To test:** a PR from `develop` into `test` (opened by the bot). You approve it; it's merged with a merge commit.
2. Controller starts on `test`. It picks the version whose files are exactly what `test` now holds, checks that the
   version passed dev (its GitHub Release shows **✅** for the dev deployment, **AT dev** and **PT dev**), then waits
   for you: Actions → **Controller** → the
   run → **Review deployments** → tick **test** → **Approve and deploy**.
3. The backend and website deploy that version's image and build to test (`api-test.goawaygo.com`,
   `test.goawaygo.com`) and check them. The app has nothing to deploy: the version is recorded as promoted.
4. The automation (AT) and performance (PT) tests run on test right after the deploy and write their result into the
   release; then test it yourself on test.
5. **To prod:** a PR from `test` into `main`, approved and merged the same way; Controller checks the version passed
   test (deployment, AT and PT), waits for your approval (**prod**), deploys, and makes the version the **latest full release** (no longer a
   pre-release). For the app, build the APK from that version (below).

Each step is written into the version's GitHub Release under **Deployments**, e.g.
`- **test**: ✅ deployed 2026-11-12 09:30 UTC · run`, so every release shows where it has run. If a version isn't
ready (Release on `develop` still running, or it failed below), Controller stops with the reason; fix it and re-run.

## Automated and performance tests (AT, PT; MVP 2, 5.5 and 5.6)

The tests live in their own repos, with the same branches and rules: **goawaygo_automation_tests** (API and website
with Playwright, the Android app with Maestro on an emulator) and **goawaygo_performance_tests** (API and website with
k6, the app's start-up time and scrolling). Each environment runs the tests from its own branch (dev: `develop`,
test: `test`, prod: `main`), so a test change is promoted like code.

| When                          | What runs                                                                    | Evidence                                    |
| ----------------------------- | ---------------------------------------------------------------------------- | ------------------------------------------- |
| A pull request into `develop` | **Tests before merge**: the smoke tests against dev (app: built from the PR) | the report on the PR (not a required check) |
| After every deploy to dev     | **Release → Tests on dev**: all AT, and PT with limits that fail the run     | `AT dev` / `PT dev` ✅ or ❌ in the release |
| After a deploy to test        | **Controller → Tests on test**: the same                                     | `AT test` / `PT test` in the release        |
| After a deploy to prod        | **Controller → Tests on prod**: read-only smoke tests, never PT              | `AT prod` in the release                    |

A version goes to test only with ✅ for AT and PT in dev, and to prod only with ✅ for both in test. Every run keeps
its reports for 90 days: the run's page → **Artifacts** (Playwright HTML report, k6 HTML report, app screenshots and
timings). In dev and test the tests use the backend's fake outside services (no AI or partner quota is used) and skip
its rate limits; prod has neither. A failed run: open the report, fix the code or the test, and re-run (Actions →
the run → **Re-run failed jobs**); the release then shows the new result.

**Roll back or deploy again:** Actions → **Controller** → **Run workflow** → "Use workflow from" **main** (prod) or
**test** (test) → version, e.g. `v1.7.0` (empty = the branch's own version) → approve. Only a version that is merged
into that branch and passed the environment below can be deployed. Keep database migrations additive so an older
version still runs.
