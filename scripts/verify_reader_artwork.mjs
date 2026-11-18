import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { createRequire } from "node:module";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("..", import.meta.url));
const OUTPUT = resolve(process.argv[2] || join(ROOT, "artifacts/reader-artwork"));
const ASSETS = join(ROOT, "src/app/src/main/assets/reader-art");
const GOLDENS = join(ROOT, "src/shared/src/test/resources/reader");
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const read = (file) => readFile(file, "utf8");
const [renderer, manifestText, bootstrap, nightScroll] = await Promise.all([
  read(join(ASSETS, "renderer.js")),
  read(join(ASSETS, "lantern-v1/manifest.json")),
  read(join(GOLDENS, "default.js")),
  read(join(GOLDENS, "night-scroll.js")),
]);
const manifest = JSON.parse(manifestText);
const variants = Object.values(manifest).flatMap((mode) => [...mode.wide, ...mode.portrait]);
const assetFiles = new Map(variants.map((variant) => [`/art/${variant.file}`, variant]));

function extractBookFile(file) {
  return execFileSync("unzip", ["-p", join(ROOT, "测试电子书-TheLanternLibrary.epub"), file], {
    encoding: "utf8",
  });
}

const chapter = extractBookFile("OPS/one.xhtml").replace(
  "<head>", '<head><meta name="viewport" content="width=device-width, initial-scale=1">',
);
const bookCss = extractBookFile("OPS/book.css");
// Take theme colors from the checked-in ReaderScripts.bootstrap fixtures. Keep
// typography and pagination identical in both themes and both measurements.
function colors(source) {
  return Object.fromEntries(
    [...source.matchAll(/root\.style\.setProperty\('(--lr-(?:bg|fg|mark|link|due|selection|highlight))', ("[^"]+")\)/g)]
      .map(([, key, value]) => [key, JSON.parse(value)]),
  );
}

const palettes = { day: colors(bootstrap), night: colors(nightScroll) };
assert.equal(Object.keys(palettes.day).length, 7);
assert.equal(Object.keys(palettes.night).length, 7);

const server = createServer(async (request, response) => {
  const url = new URL(request.url, "http://localhost");
  function send(type, body) {
    response.writeHead(200, { "Content-Type": type, "Cache-Control": "no-store" });
    response.end(body);
  }
  try {
    if (url.pathname === "/chapter") {
      const palette = url.searchParams.get("palette") === "night" ? "night" : "day";
      const pilot = url.searchParams.get("pilot") === "1";
      const start = url.searchParams.get("start") === "1";
      const script = `
        window.readerEvents = [];
        window.ReaderBridge = new Proxy({}, {
          get: (_, name) => (...args) => readerEvents.push([name, ...args])
        });
        window.readerLcp = null;
        new PerformanceObserver(list => {
          readerLcp = list.getEntries().at(-1).startTime;
        }).observe({type: 'largest-contentful-paint', buffered: true});
        window.addEventListener("load", () => {
          ${bootstrap}
          const palettes = ${JSON.stringify(palettes)};
          window.selectReaderTheme = (palette, artwork) => {
            for (const [key, value] of Object.entries(palettes[palette])) {
              document.documentElement.style.setProperty(key, value);
            }
            if (window.lrSetArtwork) lrSetArtwork(artwork);
          };
          ${pilot ? `${renderer}\nwindow.lrInstallArtwork(${manifestText}, "/art/");` : ""}
          selectReaderTheme(${JSON.stringify(palette)}, ${JSON.stringify(start ? palette : null)});
        });
      `;
      send("text/html; charset=utf-8", chapter.replace("</body>", `<script>${script}</script></body>`));
    } else if (url.pathname === "/book.css") {
      send("text/css", bookCss);
    } else if (assetFiles.has(url.pathname)) {
      send("image/webp", await readFile(join(ASSETS, "lantern-v1", assetFiles.get(url.pathname).file)));
    } else {
      response.writeHead(404).end();
    }
  } catch {
    response.writeHead(500).end();
  }
});

await mkdir(OUTPUT, { recursive: true });
await new Promise((ready, reject) => {
  server.once("error", reject);
  server.listen(0, "127.0.0.1", ready);
});
const origin = `http://127.0.0.1:${server.address().port}`;
const timings = [];
const transitions = [];
let browser;

function openChapter(page, palette, pilot, start = pilot) {
  return page.goto(`${origin}/chapter?palette=${palette}&pilot=${Number(pilot)}&start=${Number(start)}`);
}

function artworkFile(page, file) {
  return page.waitForFunction((name) => {
    const image = document.getElementById("lr-artwork");
    return image?.naturalWidth > 0 && image.src.endsWith("/" + name);
  }, file);
}

function waitForReader(page) {
  return page.waitForFunction(() => window.readerEvents?.some(([name]) => name === "onReady"));
}

async function captureSample(name, viewport, dpr, palette, pilot, screenshot) {
  const context = await browser.newContext({ viewport, deviceScaleFactor: dpr });
  try {
    const page = await context.newPage();
    const client = await context.newCDPSession(page);
    await client.send("Network.enable");
    await client.send("Network.setCacheDisabled", { cacheDisabled: true });
    await client.send("Network.emulateNetworkConditions", {
      offline: false,
      latency: 40,
      downloadThroughput: 500_000,
      uploadThroughput: 500_000,
    });
    const errors = [];
    const artRequests = [];
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("requestfailed", (request) => errors.push(request.url()));
    page.on("response", (response) => {
      if (response.status() >= 400) errors.push(`${response.status()} ${response.url()}`);
    });
    page.on("request", (request) => {
      const path = new URL(request.url()).pathname;
      if (path.startsWith("/art/")) artRequests.push(path);
    });

    await openChapter(page, palette, pilot);
    await waitForReader(page);
    const family = viewport.width >= viewport.height * 1.08 ? "wide" : "portrait";
    const candidates = manifest[palette][family];
    const expected = candidates.find((entry) => (
      entry.width >= viewport.width * dpr && entry.height >= viewport.height * dpr
    )) || candidates.at(-1);
    if (pilot) await artworkFile(page, expected.file);
    await page.waitForFunction(() => readerLcp !== null);
    await page.evaluate(() => new Promise((ready) => requestAnimationFrame(() => requestAnimationFrame(ready))));

    const sample = await page.evaluate(() => {
      const root = document.documentElement;
      const image = document.getElementById("lr-artwork");
      const resources = [...performance.getEntriesByType("navigation"), ...performance.getEntriesByType("resource")];
      return {
        lcpMs: readerLcp,
        transferBytes: resources.reduce((sum, entry) => sum + entry.transferSize, 0),
        artworkMs: resources.find((entry) => entry.name.endsWith(".webp"))?.responseEnd || null,
        overflow: root.scrollWidth > root.clientWidth || document.body.scrollWidth > root.clientWidth,
        heading: document.querySelector("#lingua-reader-content h1").textContent,
        decoded: image ? [image.naturalWidth, image.naturalHeight] : null,
        artworkMask: image ? getComputedStyle(image).maskImage : null,
      };
    });
    assert.deepEqual(errors, []);
    assert.deepEqual(artRequests, pilot ? [`/art/${expected.file}`] : []);
    assert.equal(sample.overflow, false);
    assert.equal(sample.heading, "A Quiet Beginning");
    assert.deepEqual(sample.decoded, pilot ? [expected.width, expected.height] : null);
    if (pilot) assert.match(sample.artworkMask, /^radial-gradient\(/);
    if (screenshot) await page.screenshot({ path: join(OUTPUT, `${name}.png`) });
    timings.push({
      name, viewport, dpr, palette, pilot, ...sample,
      asset: pilot ? expected.file : null,
      coverScale: pilot ? Math.max(viewport.width * dpr / expected.width, viewport.height * dpr / expected.height) : null,
    });
  } finally {
    await context.close();
  }
}

try {
  browser = await chromium.launch({ executablePath: process.env.CHROME_EXECUTABLE });
  for (const [device, viewport, dpr] of [
    ["desktop", { width: 1366, height: 768 }, 1],
    ["phone", { width: 390, height: 844 }, 2],
  ]) {
    for (const palette of ["day", "night"]) {
      for (const pilot of [false, true]) {
        const name = `${device}-${palette}-${pilot ? "artwork" : "baseline"}`;
        for (let repetition = 0; repetition < 3; repetition++) {
          await captureSample(name, viewport, dpr, palette, pilot, repetition === 0);
        }
      }
    }
  }

  const context = await browser.newContext({ viewport: { width: 768, height: 512 }, deviceScaleFactor: 1 });
  try {
    const page = await context.newPage();
    await openChapter(page, "day", true);
    await waitForReader(page);
    await artworkFile(page, "day-wide-768.webp");
    await page.setViewportSize({ width: 1024, height: 640 });
    await artworkFile(page, "day-wide-1152.webp");
    transitions.push("desktop tier boundary");

    const client = await context.newCDPSession(page);
    for (const [dpr, file] of [[2, "day-wide-1536.webp"], [1, "day-wide-1152.webp"]]) {
      await client.send("Emulation.setDeviceMetricsOverride", { width: 1024, height: 640, deviceScaleFactor: dpr, mobile: false });
      await artworkFile(page, file);
    }
    transitions.push("DPR 1 → 2 → 1 with unchanged viewport");
    await client.send("Emulation.clearDeviceMetricsOverride");
    await page.setViewportSize({ width: 390, height: 844 });
    await artworkFile(page, "day-portrait-660.webp");
    transitions.push("landscape → portrait");

    let release;
    const gate = new Promise((ready) => { release = ready; });
    await page.evaluate(() => {
      const decode = HTMLImageElement.prototype.decode;
      HTMLImageElement.prototype.decode = function () {
        const result = decode.call(this);
        if (!this.src.endsWith("/night-portrait-660.webp")) return result;
        HTMLImageElement.prototype.decode = decode;
        return result.then(() => { window.delayedArtworkDecoded = true; });
      };
    });
    await page.route("**/night-portrait-660.webp", async (route) => {
      await gate;
      await route.continue();
    });
    const pending = page.waitForRequest("**/night-portrait-660.webp");
    await page.evaluate(() => selectReaderTheme("night", "night"));
    await pending;
    await artworkFile(page, "day-portrait-660.webp");
    transitions.push("slow replacement retains displayed artwork");
    await page.evaluate(() => selectReaderTheme("day", "day"));
    release();
    await page.waitForFunction(() => window.delayedArtworkDecoded);
    assert.match(await page.locator("#lr-artwork").getAttribute("src"), /day-portrait-660\.webp$/);
    transitions.push("stale decoded replacement never commits");

    await page.unroute("**/night-portrait-660.webp");
    await page.route("**/night-portrait-660.webp", (route) => route.abort());
    const failure = page.waitForEvent("requestfailed", (request) => request.url().endsWith("night-portrait-660.webp"));
    await page.evaluate(() => selectReaderTheme("night", "night"));
    await failure;
    await artworkFile(page, "day-portrait-660.webp");
    transitions.push("failed request retains displayed artwork");

    await page.unroute("**/night-portrait-660.webp");
    await page.evaluate(() => {
      const decode = HTMLImageElement.prototype.decode;
      HTMLImageElement.prototype.decode = function () {
        if (this.src.endsWith("/night-portrait-660.webp")) {
          HTMLImageElement.prototype.decode = decode;
          window.artworkDecodeFailed = true;
          return Promise.reject(new DOMException("Invalid artwork", "EncodingError"));
        }
        return decode.call(this);
      };
      selectReaderTheme("day", "day");
      selectReaderTheme("night", "night");
    });
    await page.waitForFunction(() => window.artworkDecodeFailed);
    await artworkFile(page, "day-portrait-660.webp");
    transitions.push("failed decode retains displayed artwork");

    await page.evaluate(() => {
      selectReaderTheme("day", "day");
      selectReaderTheme("night", "night");
    });
    await artworkFile(page, "night-portrait-660.webp");
    transitions.push("successful retry after a new theme selection");

    const locus = await page.evaluate(() => {
      lrTurn(1);
      return lrLocusHere();
    });
    assert.ok(JSON.parse(locus).blockIndex > 0);
    await page.evaluate(() => selectReaderTheme("day", null));
    assert.equal(await page.locator("#lr-artwork").count(), 0);
    assert.equal(await page.evaluate(() => lrLocusHere()), locus);
    transitions.push("plain theme removes artwork and retains reading position");

    await page.route("**/night-portrait-660.webp", (route) => route.abort());
    const firstFailure = page.waitForEvent("requestfailed", (request) => request.url().endsWith("night-portrait-660.webp"));
    await page.evaluate(() => selectReaderTheme("night", "night"));
    await firstFailure;
    assert.equal(await page.locator("#lr-artwork").count(), 0);
    assert.equal(await page.evaluate(() => getComputedStyle(document.body).backgroundColor), "rgb(23, 23, 23)");
    assert.equal(await page.evaluate(() => lrLocusHere()), locus);
    transitions.push("first artwork failure leaves the selected paper color and reading position");
  } finally {
    await context.close();
  }
  const report = { browser: browser.version(), timings, transitions };
  await writeFile(join(OUTPUT, "verification.json"), JSON.stringify(report, null, 2) + "\n");
  console.log(`Passed ${timings.length} cold-cache samples and ${transitions.length} transitions. ${OUTPUT}`);
} finally {
  await browser?.close();
  server.close();
}
