# Cardify

Keep the visiting cards from your wallet on your phone. Scan a card and Cardify reads it (name, title, company, phones,
email, website, address); check the details and save. Search them all, and call, email, open the map, share, or save to
contacts from any card.

- `server/` — Go API, deployed on Vercel. Cards and their photos live in a Postgres database (Neon).
- `android/` — Kotlin + Jetpack Compose (Material 3) app, Android 8.0+. Scanning uses Google's ML Kit document scanner
  and text recognition, so the phone needs Google Play services.

## Deploy the server

The server runs on Vercel (project Root Directory: `server`) with a Neon Postgres database connected to the project,
which sets `DATABASE_URL`. **Every push to `main` deploys it.**

The app's token is a Vercel secret, set once: `openssl rand -hex 24 | vercel env add CARDIFY_TOKEN production --sensitive`
(the app must send the same token).

Run it locally against the same database: `cd server && vercel env pull .env.local && set -a && . ./.env.local && set +a && go run .`

## Run the app

1. Set the server address and token in `android/gradle.properties` (or in `~/.gradle/gradle.properties`, which keeps the
   token out of git):
   - `cardify.apiUrl=https://<your-project>.vercel.app`
   - `cardify.token=<token>`
2. Open `android/` in Android Studio and press Run (or `cd android && ./gradlew installDebug`).

Debug builds may also talk plain `http://` (handy for a server on your own Wi-Fi); release builds only allow `https://`.

## Test

```sh
cd server && CARDIFY_TEST_DATABASE_URL=<direct Postgres URL> go test ./...   # uses a throwaway schema
cd android && ./gradlew testDebugUnitTest
```
