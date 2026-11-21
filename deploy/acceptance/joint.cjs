const { chromium } = require("playwright");

const origin = "http://127.0.0.1:18080";
const timeout = 8000;

function scenario(raw) {
  const value = JSON.parse(raw);
  for (const name of ["page", "endpoint"]) {
    if (typeof value[name] !== "string" || !/^\/[A-Za-z0-9_/-]*$/.test(value[name]) || value[name].startsWith("//")) {
      throw new Error("联合场景必须使用回环服务的相对路径");
    }
  }
  for (const name of ["click", "responseText", "visibleText"]) {
    if (typeof value[name] !== "string" || !value[name].trim() || value[name].length > 500)
      throw new Error("联合场景缺少按钮、响应文本或页面断言");
  }
  if (!["GET", "POST", "PUT", "PATCH", "DELETE"].includes(value.method)) throw new Error("联合场景请求方法无效");
  return value;
}

async function main() {
  const check = scenario(process.argv[2]);
  const browser = await chromium.launch({ args: ["--disable-dev-shm-usage"], timeout: 15000 });
  try {
    const context = await browser.newContext({ serviceWorkers: "block", acceptDownloads: false });
    await context.route("**/*", route => {
      return new URL(route.request().url()).origin === origin ? route.continue() : route.abort();
    });
    const page = await context.newPage();
    const errors = [];
    page.on("pageerror", error => errors.push(error.message.slice(0, 400)));
    const loaded = await page.goto(origin + check.page, { waitUntil: "load", timeout });
    if (!loaded?.ok()) throw new Error("页面未返回成功状态");
    const expected = page.getByText(check.visibleText, { exact: true }).first();
    if (await expected.isVisible()) throw new Error("预期结果在点击前已经可见");
    const [response] = await Promise.all([
      page.waitForResponse(candidate => {
        const url = new URL(candidate.url());
        return url.origin === origin && url.pathname === check.endpoint && candidate.request().method() === check.method;
      }, { timeout }),
      page.getByRole("button", { name: check.click, exact: true }).click({ timeout }),
    ]);
    if (!response.ok()) throw new Error("后端响应失败，HTTP " + response.status());
    if (!(await response.text()).includes(check.responseText)) throw new Error("后端响应缺少预期内容");
    await expected.waitFor({ state: "visible", timeout });
    if (errors.length) throw new Error(errors[0]);
    console.log("联合验收通过");
  } finally {
    await browser.close();
  }
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
