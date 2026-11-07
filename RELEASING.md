<!-- Copy of Goaway/RELEASING.md (workspace root, shared by all repos). Keep the copies in sync. -->

# Releasing GoAwayGo

What happens after a merge to `develop`, and how to publish a new Android build. Applies to all three repos
(`goawaygo_backend`, `goawaygo_web_frontend`, `goawaygo_app_frontend`). Setup details: each repo's `docs/`.

## After every merge to `develop`

| Part        | You do                                | Happens automatically                                                                                          | Check                                                                  |
| ----------- | ------------------------------------- | -------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| Backend     | Nothing                               | CI tests → deploy to Google Cloud Run (`api.goawaygo.com`) → smoke test; Flyway applies DB migrations (~5 min) | GitHub → goawaygo_backend → Actions → **Deploy staging** ✅            |
| Website     | Nothing                               | Cloudflare Pages builds and publishes `staging.goawaygo.com` (~2 min)                                          | Cloudflare → Workers & Pages → **goawaygo-web-frontend** → Deployments |
| Android app | Release it (below) when worth testing | Nothing: it is signed with the upload key that exists only on the release PC                                   | `https://staging.goawaygo.com/download` shows the new version          |

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
- Ends with `Live: https://staging.goawaygo.com/download`. If it asks for Cloudflare, run
  `npx --yes wrangler@4.145.0 login` once and repeat.
- It refuses to run without the upload key (`android/key.properties`), so nothing debug-signed is ever published.
- Fails with `cannot find symbol ... Plugin` in `GeneratedPluginRegistrant.java` (usually after a dependency
  update): an old partial build of that plugin is left on this PC. Run `flutter clean` and run the command again.

Then send it to the beta testers:

1. Firebase console → project **goaway-483709** → **App Distribution**.
2. Drag in `app_frontend/build/release/goawaygo-<version>-<build>-universal.apk`, paste the same notes.
3. Testers → group **public-beta** → **Distribute**.

Check `https://staging.goawaygo.com/download`, then install it on a phone over the previous build.

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

Add a row for every release.

## Occasionally

| Situation                         | What to do                                                                                                                              |
| --------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| New backend secret (API key etc.) | Stored with a hidden-input script into Google Secret Manager (`backend/scripts/gcp-secrets.sh`)                                         |
| New website setting (`VITE_*`)    | Cloudflare → goawaygo-web-frontend → Settings → Variables, then redeploy                                                                |
| Database change                   | Nothing: Flyway migrates on deploy. Keep migrations additive so a rollback still works.                                                 |
| Backend broken on staging         | `gcloud run services update-traffic goawaygo-backend --region asia-southeast1 --to-revisions REVISION=100` (backend/docs/deployment.md) |
| Website broken on staging         | Cloudflare → goawaygo-web-frontend → Deployments → earlier deployment → **Rollback**                                                    |
| Bad app build                     | Release a fixed build with a higher build number; delete the bad files from R2 and purge their URLs in Cloudflare cache                 |

## Production (`main`)

Not set up yet: comes at launch (Phase 5) with production Google Cloud, Cloud SQL, `goawaygo.com` and the Play Store.
