// Service worker: caches every file the game needs on first visit, so MyPet starts and
// runs with no network at all afterwards. Cached files are refreshed in the background
// whenever the network is available, so updates arrive on the next launch.
const VERSION = "mypet-v1";
const ASSETS = [
  "./",
  "index.html",
  "styles.css",
  "manifest.webmanifest",
  "js/app.js",
  "js/data.js",
  "js/game.js",
  "js/minigame.js",
  "js/render.js",
  "js/storage.js",
  "icons/icon-192.png",
  "icons/icon-512.png",
  "icons/icon-maskable-512.png",
  "icons/apple-touch-icon.png",
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(VERSION)
      .then((cache) => cache.addAll(ASSETS.map((a) => new Request(a, { cache: "reload" }))))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET" || new URL(req.url).origin !== self.location.origin) return;

  event.respondWith((async () => {
    const cache = await caches.open(VERSION);
    // Pages always fall back to the cached app shell, whatever the URL.
    const key = req.mode === "navigate" ? "index.html" : req;
    const cached = await cache.match(key, { ignoreSearch: true });
    const refresh = fetch(req)
      .then((res) => {
        if (res && res.ok && res.type === "basic") cache.put(key, res.clone());
        return res;
      })
      .catch(() => null);
    if (cached) {
      event.waitUntil(refresh);
      return cached;
    }
    const res = await refresh;
    return res || new Response("Offline and not cached yet.", { status: 503, headers: { "Content-Type": "text/plain" } });
  })());
});
