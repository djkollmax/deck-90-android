# Deck 90 for Android

The radio of Deck 90 (github.com/djkollmax/deck-90, an Electron hi-fi rack for the PC) on the phone and in Android
Auto. Damián (djkollmax) owns it and writes in Rioplatense Spanish: what the user reads (the app's texts, toasts,
README.md) is in that Spanish; code, comments, commits and CHANGELOG.md are in English.

## Layout

- `app/src/main/java/com/djkollmax/deck90/`: `RadioService.kt` (the Media3 library service: the browse tree of bands
  and memories for Android Auto and the phone, the player, `Resolver` finding a memory's stream when it plays,
  `Now` for the song and problems), `Stations.kt` (the bands and their first memories, as in deck-90's
  src/core/radio.js), `RadioBrowser.kt` (the lookup and the best-stream choice, as in deck-90's main.js and
  radio.js), `Store.kt` (what's kept: memories, band and slot, resolved addresses; media ids `st:FM2:3`),
  `Levels.kt` (the meters' tap), `MainActivity.kt` (the WebView with the face and its keys).
- `app/src/main/assets/face/`: the stereo's face. `stereo-face.css`, `dp940-display.js` and `img/front.jpg` are
  deck-90's own, copied by `scripts/sync-face.sh` (don't edit them here); `index.html`, `face.css`, `face.js` are
  this app's (the hotspots as in deck-90's src/remote/stereo.js).

## Checks

`./gradlew testDebugUnitTest assembleDebug` (needs an Android SDK: `local.properties` with `sdk.dir`). The face can
be looked at in a browser (face.js stands in for the app when `Deck90Android` isn't there).

## Releases

`version.txt` holds the version. Every push to main runs the tests and builds the APK; when the version has no
release yet, the APK is signed with the app's key (the secrets DECK90_KEYSTORE_B64, DECK90_KEYSTORE_PASSWORD,
DECK90_KEY_ALIAS, DECK90_KEY_PASSWORD) and published, where Obtainium finds it. Without the key nothing is
published: a release signed with another key couldn't update the installed app. Push only when Damián says so.
