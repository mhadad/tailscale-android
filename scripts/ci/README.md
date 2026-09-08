# CI: build validation + delivery to your phone (Firebase App Distribution)

Mirrors [Hogozat's own pipeline](https://github.com/mhadad/Hogozat/blob/main/scripts/ci/README.md)
one-for-one, adapted for this fork's layout (Gradle project root is `android/`, not
`app/`, and the debug APK needs the embedded Tailscale Go core built first via `make`).

## The loop

```
you prompt Claude here  ->  Claude edits + commits + pushes to nena-watch-bridge
        push to github  ->  GitHub Actions starts a cloud runner
      cloud runner       ->  make tailscale-debug.apk   (Go core + gradlew test assembleDebug)
      fastlane           ->  uploads android-debug.apk to Firebase App Distribution
   Firebase              ->  notifies your phone (App Tester app / email)
   your phone            ->  you tap to install and try the build
```

- **Automated gate:** the Go core + Gradle build compiles and its tests pass. If
  either fails, the run goes red and nothing is distributed.
- **On-device check:** manual — and for this fork specifically, more than usual.
  App Distribution *delivers* the build to your phone; it does not verify that
  watch discovery/pairing/connect/log-pull actually works end to end (that needs a
  real nearby watch and was the whole point of this session's testing).

The Firebase app id is read automatically from `android/google-services.json`, so
it never has to be hardcoded.

## Important: the bundled native binaries are prebuilt, not built by CI

`android/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/{libnenaadb.so,libnenamdnsd.so}`
are committed directly to this repo (overriding the upstream `.gitignore` for just
these four files) — they're custom `adb`/`mdnsd` binaries built from a private AOSP
source checkout with local patches that only exists on the machine that built them,
and rebuilding them takes a full AOSP unbundled build (large checkout, long build
time) far beyond what a standard GitHub Actions runner can reasonably do.

**If you change anything under that private AOSP checkout** (interface handling,
the mDNS socket-path fix, the `connect_device()` restoration, etc.), you need to
rebuild those four `.so` files locally and commit the new versions yourself — CI
will keep shipping whatever is currently checked in, silently, with no warning if
it's stale.

## What runs each push

- [`.github/workflows/distribute.yml`](../../.github/workflows/distribute.yml) —
  cloud runner: Go, JDK 17, Gradle cache, Ruby + fastlane, then `fastlane distribute`.
- [`fastlane/Fastfile`](../../fastlane/Fastfile) — the `distribute` lane:
  `make tailscale-debug.apk` (Go core + `gradlew test assembleDebug`) →
  `firebase_app_distribution(...)` to the tester group.
- Triggers only on pushes to the `nena-watch-bridge` branch (or manual
  `workflow_dispatch`) — not on `main`, which stays the plain upstream Tailscale
  code with its own separate `android.yml` CI.

## One-time setup

### 1. Firebase project

This fork uses its own Firebase project (`nena-watch-bridge-49743`, under the
`hogozat755-org` organization) rather than reusing Hogozat's project directly —
Hogozat's project was found to be in `DELETE_REQUESTED` state when this was set up.
The Android app entry:

- App ID: `1:1061294042473:android:0c0fca3248782f66b6e1f8`
- Package name: `com.tailscale.ipn`
- Console: https://console.firebase.google.com/project/nena-watch-bridge-49743/overview

### 2. Firebase CLI token for CI uploads

Same policy as Hogozat: downloadable service-account keys are blocked
(`iam.disableServiceAccountKeyCreation`), so CI authenticates with a Firebase CLI
token instead. Generate one (interactive Google login in a browser, must be an
account with access to the `nena-watch-bridge-49743` project — `hogozat755@gmail.com`
already has Owner access via the org):

```bash
npx firebase-tools login:ci
```

Copy the printed token.

### 3. Add your phone as a tester

Pick one:

- **Simplest (just your email, no group):** set the repo variable
  `FIREBASE_TESTER_EMAILS` to your Google account email (step 4). The lane invites
  it directly on the first upload.
- **Group:** Firebase console → **App Distribution → Testers & groups** → create a
  group with alias **`testers`** and add your email.

Then on the phone install **"App Tester"** (from the invite email link) and sign in
with that email. New builds arrive as notifications.

### 4. Repo secrets/variables

**GitHub → `mhadad/tailscale-android` → Settings → Secrets and variables → Actions:**

| Kind     | Name                       | Value                                                       |
| -------- | -------------------------- | ----------------------------------------------------------- |
| Secret   | `FIREBASE_TOKEN`           | the token from `firebase login:ci` (step 2)                 |
| Variable | `FIREBASE_TESTER_EMAILS`   | your email, e.g. `you@gmail.com` (no group needed)          |
| Variable | `FIREBASE_TESTER_GROUPS`   | *or* a group alias like `testers` (used if no emails set)   |

Set **either** `FIREBASE_TESTER_EMAILS` **or** `FIREBASE_TESTER_GROUPS` — emails win
if both are present; the lane defaults to the `testers` group if neither is set.

### 5. Verify

Push any commit to `nena-watch-bridge`, or trigger **Distribute to phone** from the
**Actions** tab (`workflow_dispatch`). The run builds, tests, uploads, and your
phone gets the new build in App Tester.

## Running the distribution by hand

```bash
gem install bundler && bundle install
export FIREBASE_TOKEN="$(npx firebase-tools login:ci)"   # or paste an existing token
export FIREBASE_TESTER_EMAILS="you@gmail.com"
bundle exec fastlane distribute
```
