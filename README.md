# Cross Companion

An unofficial Android companion for [Cross with Friends](https://crosswithfriends.com): the
collaborative crossword site in a full-screen native shell, plus native solving help and a
personal vocabulary journal.

## Features

- **Game shell**: edge-to-edge WebView, resumes your last room, smart back button (room → lobby,
  double-press to exit), pinch-to-zoom, `.puz`/JSON uploads, pull-to-refresh in the lobby and an
  offline screen with retry.
- **Google sign-in notice**: Google blocks OAuth in embedded WebViews, so the app catches those
  attempts and explains how to use email and password instead.
- **Help carousel** (bottom-left button, shown on first launch): six cards covering the app.
- **Hints & journal** (bottom-right button):
  - *Word or term*: Wikipedia, Free Dictionary and Wiktionary lookup with category, trivia, origin
    and usage; save to the journal, sync to a Google Doc, or search on Google.
  - *Clue*: loads Google results for the clue and crops the page down to the AI Overview.
  - *Journal*: offline JSON journal; copy as Markdown, delete, sync one entry or export them all.
- **Google Doc sync** through a self-hosted Apps Script web app
  ([`app/src/main/assets/Code.gs`](app/src/main/assets/Code.gs)), with an in-app setup guide.

## Building

Requires JDK 17 and the Android SDK (platform 35).

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Install it with
`adb install -r app/build/outputs/apk/debug/app-debug.apk`.

CI (`.github/workflows/android.yml`) builds debug and release APKs on every push to `main` and
attaches them as workflow artifacts. Release builds use the debug key unless the
`RELEASE_KEYSTORE_*` secrets are set.

## Known limitations

- The Clue tab depends on Google's results markup and on AI Overviews being shown for your
  region and query. When no overview appears, it falls back to the cleaned-up results page.
- Pull-to-refresh is disabled inside puzzle rooms so a downward swipe on the grid never reloads
  mid-solve.
