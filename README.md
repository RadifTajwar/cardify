# Cardify

Keep the visiting cards from your wallet on your phone. Scan a card and Cardify reads it (name, title, company, phones,
email, website, address); check the details and save. Search them all, and call, email, open the map, share, or save to
contacts from any card.

- `server/` — Go API, standard library only. Cards live in `data/cards.json`, photos in `data/images/`. Back up by copying `data/`.
- `android/` — Kotlin + Jetpack Compose (Material 3) app, Android 8.0+. Scanning uses Google's ML Kit document scanner
  and text recognition, so the phone needs Google Play services.

## Run the server

```sh
openssl rand -hex 24                       # make a token once; the app must send the same one
cd server && CARDIFY_TOKEN=<token> go run .
```

Optional env: `CARDIFY_ADDR` (default `:8080`), `CARDIFY_DATA` (default `./data`).

## Run the app

1. Set the server address and token in `android/gradle.properties` (or in `~/.gradle/gradle.properties`, which keeps the
   token out of git):
   - emulator: `cardify.apiUrl=http://10.0.2.2:8080`
   - phone on the same Wi-Fi: `cardify.apiUrl=http://<your computer's LAN IP>:8080`
   - `cardify.token=<token>`
2. Open `android/` in Android Studio and press Run (or `cd android && ./gradlew installDebug`).

Debug builds may talk plain `http://`. Release builds only allow `https://`, so put the server behind a TLS proxy
(e.g. Caddy) before reaching it over the internet.

## Test

```sh
cd server && go test ./...
cd android && ./gradlew testDebugUnitTest
```
