# TruckWeight Guard — Setup & Deployment Guide

## 1. Architecture Overview
- **Android Client (`app/`)**: Jetpack Compose + CameraX + Android Photo Picker + Firebase AI Logic (`gemini-3.8-flash` structured JSON weighbridge OCR) + Cloud Firestore (`truck_weight_records`, `app_settings`) + Google Sign-In via Credential Manager.
- **Security Rules (`/firestore.rules`)**: Enforces strict per-user ownership on `truck_weight_records` and restricts `app_settings` writes and global record audits to the verified application owner (`lofimusic492@gmail.com`).
- **Secure Backend (`/functions/index.js`)**: Firebase Cloud Function (`sendWeighbridgeToTelegram`) that authenticates the caller's Firebase ID token, reads `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` from Firebase Secret Manager, dispatches the original weighbridge slip image to Telegram `sendPhoto`, and writes `telegramMessageId` + `documentImageTelegramFileId` back to Firestore.

## 2. Backend Telegram Secret Configuration (Never in Frontend)
Never put your Telegram Bot API Token into Android source code, `strings.xml`, or `.env`.
Deploy the Cloud Function and store secrets server-side:

```bash
cd functions && npm install && cd ..

# Store secrets securely in Google Cloud Secret Manager via Firebase CLI
firebase functions:secrets:set TELEGRAM_BOT_TOKEN
firebase functions:secrets:set TELEGRAM_CHAT_ID

# Deploy the Cloud Function and Firestore rules
firebase deploy --only functions,firestore:rules
```

## 3. Configure Relay Endpoint in Owner-Only Settings
1. Sign in to **TruckWeight Guard** using the owner Google account (`lofimusic492@gmail.com`).
2. Open the **Settings** tab (Owner-Only Admin section).
3. Paste the deployed HTTPS Cloud Function URL (for example, `https://asia-southeast1-<project-id>.cloudfunctions.net/sendWeighbridgeToTelegram`) into **Backend Relay Endpoint URL**.
4. Optionally adjust alert thresholds (`Neutral: 0–4 KG`, `Green Signal: 5–15 KG`, `Orange Warning: 16–19 KG`, `Red Alert: 20+ KG`) and minimum OCR confidence, then tap **Save Owner Settings**.

## 4. Building & Testing
- Run unit tests: `gradle :app:testDebugUnitTest`
- Build debug APK: `gradle :app:assembleDebug` (output in `app/build/outputs/apk/debug/app-debug.apk`)
- Test Firestore rules locally: `FIRESTORE_EMULATOR_HOST="127.0.0.1:8085" node --test firestore.test.js`

## 5. Physical Device App Check Notes
- `AppCheckInit.kt` uses `DebugAppCheckProviderFactory` only when a launch `Intent` passes a pre-registered `FIREBASE_APPCHECK_DEBUG_TOKEN`.
- On physical devices installed directly via `APK_DOWNLOAD/app-debug.apk`, it automatically switches to `RecaptchaAppCheckProviderFactory` using the configured `recaptcha_site_key` so OCR calls validate properly.
- If a specific device or debug-signed build still fails App Check attestation on mobile networks, either register that device's App Check debug UUID from Logcat (`DebugAppCheckProvider`) in **Firebase Console -> App Check -> Apps**, build a release-signed APK, or use **Enter Weight Manually** on the OCR screen.

