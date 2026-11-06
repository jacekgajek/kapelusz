// The browser tests of the app's screens (REFACTORING.md 4.6).
// 1. The app's Compose resources (strings, fonts): Karma serves them where the app fetches them, `./composeResources/…`
//    next to the test page. The directory comes from the test task (composeApp/build.gradle.kts).
const resources = process.env.COMPOSE_TEST_RESOURCES;
if (resources) {
    config.files.push({ pattern: resources + "/composeResources/**", included: false, served: true, watched: false });
    config.proxies = Object.assign({}, config.proxies, { "/composeResources/": "/absolute" + resources + "/composeResources/" });
}
// 2. A screen test waits for those resources and for the app's own work on the browser's loop: longer than Mocha's 2 s.
//    Up to six waits of 10 s in one test.
config.client = Object.assign({}, config.client, { mocha: Object.assign({}, (config.client || {}).mocha, { timeout: 60000 }) });
// 3. English strings whatever the machine's language: the tests assert on them.
Object.values(config.customLaunchers || {}).forEach(launcher => { launcher.flags = (launcher.flags || []).concat("--lang=en-US"); });
// 4. End-to-end specs (-Pe2e, LOCAL_DEVELOPMENT.md): the page reads the local server's address from Karma's client config
//    (window.__karma__.config). Without it the specs are skipped. A whole scenario needs more time than a smoke test.
const e2eApi = process.env.KAPELUSZ_E2E_API;
if (e2eApi) {
    config.client = Object.assign({}, config.client, { kapeluszE2eApi: e2eApi, mocha: Object.assign({}, config.client.mocha, { timeout: 300000 }) });
}
