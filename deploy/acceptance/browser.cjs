const { chromium } = require("playwright");
const { stat } = require("node:fs/promises");
const { resolve, sep } = require("node:path");

// Serve only the submitted archive over an in-memory route. The container has no network.
async function main() {
  const root = "/work/project";
  const origin = "http://atoms-preview.invalid";
  const entry = "/" + process.argv[2].split("/").map(encodeURIComponent).join("/");
  const failures = [];
  const fail = (message) => {
    if (failures.length < 10) failures.push(message.slice(0, 500));
  };
  const browser = await chromium.launch({ args: ["--disable-dev-shm-usage"], timeout: 15000 });
  try {
    const context = await browser.newContext({ serviceWorkers: "block", acceptDownloads: false });
    const page = await context.newPage();
    page.on("pageerror", (error) => fail(error.message));
    await context.route("**/*", async (route) => {
      const url = new URL(route.request().url());
      if (url.origin !== origin) {
        await route.abort();
        return;
      }
      let pathname;
      try {
        pathname = decodeURIComponent(url.pathname);
      } catch {
        fail("本地资源路径编码无效");
        await route.fulfill({ status: 400, body: "Invalid path" });
        return;
      }
      const path = resolve(root, "." + pathname);
      if (path.startsWith(root + sep) && (await stat(path).catch(() => null))?.isFile()) {
        await route.fulfill({ path });
        return;
      }
      if (!["fetch", "xhr"].includes(route.request().resourceType()) && url.pathname !== "/favicon.ico") {
        fail("本地资源不存在：" + url.pathname);
      }
      await route.fulfill({ status: 404, body: "Not found" });
    });
    await page.goto(origin + entry, { waitUntil: "load", timeout: 15000 });
    await page.locator("body").waitFor({ state: "attached", timeout: 3000 });
    if (failures.length) throw new Error(failures.join("\n"));
  } finally {
    await browser.close();
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
