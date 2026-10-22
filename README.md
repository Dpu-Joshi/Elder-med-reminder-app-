# Elder medicine reminder (working name: Dawa Saathi)

A free Android app that rings like an alarm when an older person's medicine is due, and
quietly alerts a family member if the dose is not confirmed within 30 minutes. A family member
sets everything up from their own phone; the elder only ever taps one big button.

This repository holds the week-1 alarm test and the first-release (MVP) code from the product
plan, in one Gradle project:

| Folder | What it is |
| --- | --- |
| `core-alarm/` | The code that decides whether a dose rings: exact alarms, the full-screen reminder, alarm sound on the alarm stream, speech, reboot handling, phone-settings checks, brand-specific battery screens. Plain Android framework, no AndroidX. Both apps use it. |
| `alarm-test/` | Throwaway test app for week 1. Rings test alarms, logs how late each one rang, and shares a report. No Firebase. |
| `app/` | The real app: Elder mode and Family mode, Room on the phone, Firebase for sync and alerts. |
| `firebase/` | Firestore and Storage security rules, indexes, and Cloud Functions (missed-dose check, escalation, "phone is silent", pairing, consent withdrawal). |
| `shared/dose-plan-vectors.json` | Dose-planning cases that the Kotlin and TypeScript planners must both pass, so the phone and the server agree on dose ids. |

## What has been checked, and what has not

The machine this was written on could not reach `dl.google.com`, which serves the Android SDK,
the Android Gradle Plugin, AndroidX and Firebase for Android. So:

| Part | Status |
| --- | --- |
| Cloud Functions (TypeScript) | Compiles. 12 unit tests and 44 Firestore-emulator tests pass: security rules from each role's side, dose planning, 30/60-minute escalation, quiet hours, pairing, invite codes, the wrong-code lockout, data deletion. The functions load in the Functions emulator. |
| `core-alarm`, `alarm-test` | Compiled against the Android 16 (API 36) framework classes (Robolectric `android-all`). Not built with the Android Gradle Plugin, not run on a phone. |
| `app` | Pure-Kotlin parts (dose planner, reminder text) pass their unit tests on the JVM, and match the server's planner on the shared vectors, including daylight-saving edge cases. All app code compiles against the real Compose and Material3 APIs (JetBrains desktop builds) plus hand-written stand-ins for Firebase, Room, WorkManager, Credential Manager, ML Kit and Coil. Not built with the Android Gradle Plugin; Room's annotation processor has not run. |
| Library versions | Google-hosted versions in `gradle/libs.versions.toml` (AGP, AndroidX, Compose BOM, Firebase BOM) could not be confirmed. If Android Studio flags one, take the version it suggests. |

The first real build is in Android Studio, or in CI: `.github/workflows/ci.yml` builds both
apps, runs the unit tests, uploads the alarm-test APK, and runs the backend tests against the
emulator on every push.

## Week 1: prove the alarm rings

1. Open the project in a current Android Studio (it must support AGP 8.11; JDK 17+). Pick the `alarm-test` run
   configuration, or run `./gradlew :alarm-test:assembleDebug` and install
   `alarm-test/build/outputs/apk/debug/alarm-test-debug.apk`. It needs no Firebase setup.
2. On each borrowed phone (Xiaomi or Redmi, Vivo, Oppo or Realme, Samsung):
   - Fix every red line under **Phone settings**, then open each `?` line and switch it on.
     These are the brand screens (autostart, battery saver, lock-screen pop-ups) that no app can
     read back.
   - Tap **Ring in 1 minute**, lock the phone, and check the full-screen alarm appears and speaks.
   - Tap **Ring every 60 minutes until 7 am**, swipe the app away from recent apps, leave the
     phone locked on battery overnight.
   - In the morning, tap **Share report** and send it to yourself.
3. Compare **Alarm clock** with **Exact while idle** on the phones that ring late. The real app
   uses alarm clock.
4. Hand the phone to an elder, ring a reminder, and watch without helping. Every hesitation is
   the screen's fault. The test alarm screen matches the real one.

Go only if families say they would use it and the alarm rang every time.

## Setting up the MVP

### Firebase project (one time)

1. Create a Firebase project. Switch it to the Blaze plan (Cloud Functions need it) and set a
   budget alert at a small amount, such as ₹200 a month.
2. **Firestore**: create the database in `asia-south1` (Mumbai). The functions use that region
   (`REGION` in `firebase/functions/src/model.ts` and `Paths.REGION` in the app); keep them together.
3. **Authentication**: enable **Google** and **Anonymous**.
4. **Storage**: create the default bucket (medicine photos only; health records will go to the
   family's own Drive in the second release).
5. **Android app**: register `com.medreminder` (pick the final package name first; it cannot change
   after the first Play upload) with your debug and release SHA-1 fingerprints, which Google
   sign-in needs. Download `google-services.json` into `app/`. It is git-ignored.
6. Deploy the backend:
   ```sh
   npm install -g firebase-tools
   cd firebase
   firebase use --add            # pick your project
   (cd functions && npm ci)
   firebase deploy --only firestore,storage,functions
   ```

### Run it

1. Build and install `app` on two phones.
2. **Elder's phone**: choose *My parent's phone*. The setup walks through language, phone
   settings, a QR pairing code, a family PIN, the elder's own consent (read aloud), and a test
   alarm.
3. **Your phone**: choose *My phone (family)*, sign in with Google, *Pair a parent's phone*, scan
   the code, then *Add a medicine*.

### Backend tests

```sh
cd firebase/functions
npm test                 # planner, timing and message unit tests
npm run test:emulator    # rules and server logic against the Firestore emulator (needs Java and firebase-tools)
```

## How it works

```
Elder's phone (Elder mode)            Firebase                          Family phone (Family mode)
  Room: medicines, doses   ── Taken ─▶  Firestore  ◀── edits ───────────  Medicines, photos
  AlarmManager alarms      ◀─ sync ───  Functions: missed-dose check ──▶  FCM push: Call / I checked
  rings with no internet               every 5 min, phone-silent check
```

- **Reminders work offline.** The elder's phone plans the next two days of doses from the
  medicines in Room (`ReminderScheduler`) and registers one `setAlarmClock` alarm per dose.
  Planning runs again after every alarm, sync, reboot, clock change and permission change, so the
  phone keeps ringing for days without internet.
- **At dose time** the alarm posts a full-screen notification; `DoseAlarmActivity` shows the
  photo, the dose in large type, before or after food, and speaks the reminder in Hindi or
  English. It rings again at 5 and 10 minutes if nobody taps.
- **Taken** is saved in Room first, then written to Firestore (queued while offline) under a dose
  id both sides compute the same way: `medicineId_yyyyMMdd_HHmm`.
- **The missed-dose check runs on the server** (`checkDoses`, every 5 minutes), so the family is
  alerted even when the elder's phone is off. Each dose document carries `alertAt`. The first
  family contact is alerted at 30 minutes and the second at 60, unless someone confirms, taps
  *She took it, I checked*, or taps *Call*. Alerts say "not confirmed", never "missed".
- **Phone is silent**: the elder's phone checks in about hourly. If nothing is heard for 8 hours
  outside quiet hours (22:00 to 07:00), the first contact is told.
- **Pairing** has no password or OTP for the elder. Their phone signs in anonymously and shows a
  QR code. The family member claims it, and the server gives the phone custom claims
  (`fid`, `eid`), which the security rules check.
- **Consent** comes from the elder, read aloud, with an equally easy *No*. *Privacy* on the
  elder's Today screen turns everything off and deletes the elder's data (`deleteElderData`).

### Data model

`users/{uid}`, `families/{familyId}` (members, alert order),
`families/{f}/elders/{e}` (language, timezone, settings, check-in),
`.../medicines/{m}` (photo, dose, food rule, times, dates, tablets left, who added it),
`.../doses/{medicineId_yyyyMMdd_HHmm}` (status, confirmed at/by, alert level, `alertAt`),
`pairingCodes/{code}`, `familyInvites/{code}`. Field names are listed in
`firebase/functions/src/model.ts` and mirrored in `app/.../data/remote/FirestoreModels.kt`.

## Cost

Push notifications, sign-in and the functions' invocations fit in Firebase's free quotas. The
limit to watch is Firestore writes (20,000 a day free). Each elder costs roughly 20 writes a day:
about 8 check-ins, one per confirmed dose, and the server's pending-dose documents. That is about
1,000 active elders on the free quota. Above that, published US rates put 10,000 elders at
roughly $10 to $20 a month (approximate; check the current Firestore pricing for `asia-south1`).
No SMS, no WhatsApp Business API, no paid voice.

## Before Google Play

- Complete the **full-screen intent** declaration and the **Health apps** declaration in Play
  Console. Record a short video showing why the alarm must ring at an exact time.
- `SCHEDULE_EXACT_ALARM` is user-granted. The setup wizard sends the family member to the
  setting. Do not switch to `USE_EXACT_ALARM`, which Play reserves for alarm-clock and calendar apps.
- Publish a privacy policy (have a lawyer review it). The DPDP Rules' consent and notice duties
  apply from 14 May 2027.
- A new personal developer account must run a closed test with at least 12 testers for 14 days
  in a row. Recruit 20.

## Not built yet

Planned for the second release: family-recorded voice, refill alerts (`tabletsLeft` is stored
but not yet decremented), weekly summary and doctor report, health records in the family's
Drive, more languages, home-screen widget.

Gaps in this MVP code to close first:
- Undoing a Taken tapped by mistake (family side).
- Removing an elder from the family side (the `deleteElderData` function already allows it).
- Storage rules have no emulator tests yet.
- English-only Family mode text, written inline in the screens rather than in string resources.
- No instrumented tests. The alarm path needs testing on real phones from each brand.
