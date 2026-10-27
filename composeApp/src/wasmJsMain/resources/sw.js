// Keeps the web app's own files on the device so it starts offline (ITERATION_3 §2.3). Data lives in IndexedDB and
// syncs once online, as on Android; this worker only serves the app's files.
//
// - The page and composeApp.js: network first, so a new deploy is picked up on the next start; the cached copy when
//   offline (or the network is very slow).
// - The content-hashed .wasm files: cache first; their names change with their content.
// - composeApp.js names the .wasm files it loads, so a copy of it is cached only once those are, and .wasm files no
//   longer named are dropped: the cached app always starts, also when the deploy has deleted the old files.
// - Anything else of this site (resources, icons, manifest): network first, cached for offline.
// - Not touched: other origins (the API, Google sign-in, photos), non-GET requests, /download/ (version.json),
//   /.well-known/ and the privacy policy (its own pages, not the app: never kept as the app's page).
const CACHE = "kapelusz-v1";
const SCOPE = new URL("./", self.location).href;
const PAGE = SCOPE; // Every page (e.g. /join/ABC123) is index.html, the single-page app.
const APP_SCRIPT = new URL("composeApp.js", SCOPE).href;
const HASHED_WASM = /(?:^|\/)[0-9a-f]{16,}\.wasm$/;
const NETWORK_TIMEOUT_MS = 5000;

self.addEventListener("install", (event) => {
    event.waitUntil(self.skipWaiting());
});

self.addEventListener("activate", (event) => {
    event.waitUntil((async () => {
        for (const key of await caches.keys()) {
            if (key.startsWith("kapelusz-") && key !== CACHE) await caches.delete(key);
        }
        await self.clients.claim();
    })());
});

// The page lists the files it loaded before this worker took control (the first visit), to keep them as well.
self.addEventListener("message", (event) => {
    const data = event.data;
    if (!data || data.type !== "cache" || !Array.isArray(data.urls)) return;
    event.waitUntil(keepLoaded(data.urls));
});

self.addEventListener("fetch", (event) => {
    const request = event.request;
    if (request.method !== "GET") return;
    const url = new URL(request.url);
    if (!handled(url)) return;
    if (request.mode === "navigate") {
        event.respondWith(networkFirst(event, request, PAGE));
    } else if (HASHED_WASM.test(url.pathname)) {
        event.respondWith(cacheFirst(url.href));
    } else {
        const key = url.href === new URL("index.html", SCOPE).href ? PAGE : stripSearch(url);
        event.respondWith(networkFirst(event, request, key));
    }
});

function handled(url) {
    if (url.origin !== self.location.origin || !url.href.startsWith(SCOPE)) return false;
    const path = url.pathname;
    return !path.startsWith("/download/") && !path.startsWith("/.well-known/") && !path.endsWith("/sw.js") &&
        path !== "/privacy-policy" && !path.startsWith("/privacy-policy/");
}

function stripSearch(url) {
    return url.origin + url.pathname;
}

async function networkFirst(event, request, key) {
    const cache = await caches.open(CACHE);
    const network = fetch(request).then(async (response) => {
        if (cacheable(response, key)) {
            const copy = response.clone();
            event.waitUntil(key === APP_SCRIPT ? keepAppScript(copy) : cache.put(key, copy));
        }
        return response;
    });
    const cached = await cache.match(key);
    if (!cached) return network;
    // With a copy at hand, a hanging network (a weak signal) does not keep the app from starting.
    const timeout = new Promise((resolve) => setTimeout(() => resolve(null), NETWORK_TIMEOUT_MS));
    try {
        // An error answer (a server or CDN hiccup, a captive portal) loses to the good copy too (V5-M1 D7).
        const answer = await Promise.race([network, timeout]);
        return answer && answer.ok ? answer : cached;
    } catch (e) {
        return cached;
    }
}

// The single-page fallback answers a missing file with index.html: never keep that under another name.
function cacheable(response, key) {
    if (!response.ok || response.type === "opaque") return false;
    const html = (response.headers.get("content-type") || "").includes("text/html");
    return key === PAGE ? html : !html;
}

// One download per .wasm file, even when the page and keepAppScript ask at the same time.
const inFlight = new Map();

async function cacheFirst(url) {
    const cache = await caches.open(CACHE);
    const hit = await cache.match(url);
    if (hit) return hit;
    let pending = inFlight.get(url);
    const first = !pending;
    if (first) {
        // Resolves to null once cached, or to the response that could not be cached (e.g. a 404).
        pending = fetch(url).then(async (response) => {
            const wasm = (response.headers.get("content-type") || "").includes("application/wasm");
            if (!response.ok || !wasm) return response;
            await cache.put(url, response);
            return null;
        }).finally(() => inFlight.delete(url));
        inFlight.set(url, pending);
    }
    let failed;
    try {
        failed = await pending;
    } catch (e) {
        return Response.error();
    }
    return (await cache.match(url)) || (first ? failed : fetch(url));
}

// Caches composeApp.js only once all .wasm files it names are cached, then drops the .wasm files it no longer names.
async function keepAppScript(response) {
    const text = await response.clone().text();
    const names = [...new Set(text.match(/[0-9a-f]{16,}\.wasm/g) || [])];
    const urls = names.map((name) => new URL(name, SCOPE).href);
    const cache = await caches.open(CACHE);
    for (const url of urls) {
        await cacheFirst(url);
        if (!(await cache.match(url))) return; // Keep the previous, complete copy.
    }
    await cache.put(APP_SCRIPT, response);
    for (const request of await cache.keys()) {
        if (HASHED_WASM.test(new URL(request.url).pathname) && !urls.includes(request.url)) await cache.delete(request);
    }
}

async function keepLoaded(urls) {
    const cache = await caches.open(CACHE);
    const wanted = [PAGE, ...urls.map((u) => new URL(u, SCOPE)).filter(handled).map(stripSearch)];
    for (const url of new Set(wanted)) {
        if (await cache.match(url)) continue;
        try {
            if (HASHED_WASM.test(new URL(url).pathname)) {
                await cacheFirst(url);
                continue;
            }
            const response = await fetch(url);
            if (!cacheable(response, url)) continue;
            if (url === APP_SCRIPT) await keepAppScript(response);
            else await cache.put(url, response);
        } catch (e) {
            // Offline or gone: the next start tries again.
        }
    }
}
