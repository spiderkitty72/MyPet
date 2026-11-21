# MyPet for the web (installable, works offline)

The HTML5 version of MyPet. It is a Progressive Web App: open it once in any modern browser
and it can be installed like an app (Android, iPhone/iPad, Windows, macOS, ChromeOS, Linux),
then it runs with **no internet connection**.

- **Same game as Android.** `js/game.js` is a line-by-line port of `core/.../PetGame.kt`, and
  `js/data.js` (editions, rules, sprites, icons, pixel font) is generated from the Kotlin sources.
  The LCD renderer in `js/render.js` ports `LcdScene.kt`, so the screen looks the same.
- **Offline.** `sw.js` caches every file on the first visit. After that the app starts from the
  cache, and quietly refreshes the cache whenever a connection is available.
- **Saved on the device.** The save lives in `localStorage`. Nothing is sent anywhere.
- **Time keeps passing while it's closed.** Nothing ticks in the background; instead, each time
  the app opens or comes back into view it replays every game minute since the last saved
  timestamp (up to 45 days), exactly as the Android app does.

Not in the web version yet: system notifications when the pet calls while the app is closed
(browsers can't schedule them offline). While the app is open the tab title shows `(!)`, the
installed app icon gets a badge where supported, and phones vibrate.

## Run it locally

No build step. Serve this folder over HTTP (service workers need `http://localhost` or HTTPS):

```sh
cd web
python3 -m http.server 8000   # then open http://localhost:8000
```

## Install on a device

The app must be served over HTTPS. The `Web app` GitHub Actions workflow publishes `web/` to
GitHub Pages on every push to `main` (one-time setup: repository **Settings > Pages > Source:
GitHub Actions**). Then open the Pages URL and:

- **Android / Chrome / Edge:** tap **Install** in the app's top bar, or browser menu > *Install app*.
- **iPhone / iPad (Safari):** Share > *Add to Home Screen*.

## Tests

```sh
node --test web/test/*.test.js
```

## Keeping it in sync with Android

After changing editions, sprites or icons in Kotlin, regenerate the data (and icons if needed):

```sh
python3 web/tools/sync_from_kotlin.py
python3 web/tools/make_icons.py      # needs Pillow
```

When you add a new file to the web app, list it in `ASSETS` in `sw.js` and bump `VERSION`.
