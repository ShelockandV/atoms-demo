import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import ts from 'typescript'
const source = readFileSync(new URL('../src/preview.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ES2022, target: ts.ScriptTarget.ES2022 } }).outputText.replace("from 'acorn'", `from '${new URL('../node_modules/acorn/dist/acorn.mjs', import.meta.url).href}'`)
const { canPreview } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
test('previews static frontend with custom file names and supporting files', () => {
  assert.equal(canPreview({'index.html':'<body><script src="app.js"></script></body>', 'app.js':'', 'styles.css':'', 'package.json':'{}'}), true)
})
test('previews self-contained HTML and nested static entry', () => {
  assert.equal(canPreview({'index.html':'<body>Hello</body>'}), true)
  assert.equal(canPreview({'web/index.html':'<body>Hello</body>', 'web/main.js':''}), true)
})
test('backend source alone has no browser preview', () => {
  assert.equal(canPreview({'pom.xml':'', 'src/main/java/App.java':''}), false)
})

test('assembles local resources and executes generated interaction', async () => {
  const { JSDOM } = await import('jsdom')
  globalThis.DOMParser = new JSDOM('').window.DOMParser
  const { previewDocument } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
  const html = previewDocument({
    'web/index.html': '<html><head><link rel="stylesheet" href="./assets/main.css"></head><body><button id="count" onclick="throw 1">0</button><script src="assets/main.js"></script><script>document.body.dataset.ready="yes"</script></body></html>',
    'web/assets/main.css': 'button { color: red; }',
    'web/assets/main.js': 'document.querySelector("button").addEventListener("click",e=>e.target.textContent="1")',
    'README.md': 'Example'
  }, 'test-token')
  const runtime = new JSDOM(html, { runScripts: 'dangerously' })
  const button = runtime.window.document.querySelector('button')
  assert.equal(button.hasAttribute('onclick'), false)
  button.click()
  assert.equal(button.textContent, '1')
  assert.equal(runtime.window.document.body.dataset.ready, 'yes')
  assert.match(runtime.window.document.querySelector('style').textContent, /color: red/)
  assert.match(runtime.window.document.querySelector('meta').content, /connect-src 'none'/)
  runtime.window.close()
})

test('inline HTML works and unsupported resources report actionable errors', async () => {
  const { JSDOM } = await import('jsdom')
  globalThis.DOMParser = new JSDOM('').window.DOMParser
  const { previewDocument } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
  const html = previewDocument({'index.html':'<body><style>body{color:red}</style><script>document.body.dataset.ok="yes"</script></body>'}, 'token')
  const runtime = new JSDOM(html, {runScripts:'dangerously'})
  assert.equal(runtime.window.document.body.dataset.ok, 'yes')
  runtime.window.close()
  assert.throws(()=>previewDocument({'index.html':'<script src="missing.js"></script>'}, 't'), /缺少资源文件/)
  assert.throws(()=>previewDocument({'index.html':'<script type="module" src="src/main.ts"></script>'}, 't'), /需要构建/)
  assert.throws(()=>previewDocument({'index.html':'<script src="https://example.com/app.js"></script>'}, 't'), /不支持外部资源/)
})
