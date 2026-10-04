# Cardify

Keep the visiting cards from your wallet on your phone. Sign up, scan a card and Cardify reads it (name, title, company,
phones, email, website, address); check the details and save. Cards are private unless you make them public; public
cards show up in every user's search. Call, email, open the map, share, or save to contacts from any card.

- `server/` — Go API, deployed on Vercel. Accounts and cards live in MongoDB (Atlas). Card photos live on Cloudinary;
  the server does the uploading, so the Cloudinary secret never leaves it.
- `android/` — Kotlin + Jetpack Compose (Material 3) app, Android 8.0+. Scanning uses Google's ML Kit document scanner
  and text recognition, so the phone needs Google Play services.

## Deploy the server

The server runs on Vercel (project Root Directory: `server`). **Every push to `main` deploys it.** It needs:

- `MONGODB_URI`: connect MongoDB Atlas to the Vercel project (Vercel Marketplace → MongoDB Atlas), which sets it.
- `CLOUDINARY_URL`: `cloudinary://<api_key>:<api_secret>@<cloud_name>` from the Cloudinary dashboard, added as a
  Sensitive variable for Production and Preview. Without it the server still runs, but photo uploads are off.
- `GOOGLE_CLIENT_ID`: the Web application client ID from Google Cloud (Google Auth Platform → Clients), for
  "Continue with Google". The app's `cardify.googleClientId` (in `android/gradle.properties`) must be the same ID, and
  the project needs an Android client for `com.cardify` with the signing key's SHA-1.
- `GMAIL_SENDER` and `GMAIL_APP_PASSWORD`: the Gmail address that emails password reset codes, and an app password for
  it (Google Account → Security → 2-Step Verification → App passwords). Without them, "Forgot password" says it isn't
  set up.

Run it locally with MongoDB in Docker:

```sh
docker run -d --name cardify-mongo -p 27017:27017 mongo:8
cd server && MONGODB_URI=mongodb://localhost:27017 go run .   # add CLOUDINARY_URL=... for photos
```

## Run the app

1. Set the server address in `android/gradle.properties`: `cardify.apiUrl=https://<your-project>.vercel.app`.
2. Open `android/` in Android Studio and press Run (or `cd android && ./gradlew installDebug`).

For a server on your own Mac, build with `-Pcardify.apiUrl=http://10.0.2.2:8080` from the emulator, or with your Mac's
LAN IP from a phone on the same Wi-Fi. Debug builds may talk plain `http://`; release builds only allow `https://`.

## Test

```sh
cd server && CARDIFY_TEST_MONGODB_URI=mongodb://localhost:27017 go test ./...   # throwaway database, fake Cloudinary
cd server && CLOUDINARY_URL=cloudinary://... go test -run Live ./...          # checks the real Cloudinary account
cd android && ./gradlew testDebugUnitTest
```
