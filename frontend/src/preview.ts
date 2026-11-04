import type { Files } from './api'
import { parse } from 'acorn'

function entryFile(files: Files) {
  if (typeof files['index.html'] === 'string') return 'index.html'
  const entries = Object.keys(files).filter(name => name.endsWith('/index.html'))
  return entries.length === 1 ? entries[0] : undefined
}
export function canPreview(files: Files) { return !!entryFile(files) }

export function previewDocument(files: Files, token: string) {
  const entry = entryFile(files)
  if (!entry) throw new Error('未找到唯一的 index.html 入口，请导出源码后在本地运行')
  const resolve = (reference: string) => {
    if (/^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(reference)) throw new Error('预览不支持外部资源：' + reference)
    const parts = reference.startsWith('/') ? [] : entry.split('/').slice(0, -1)
    for (const part of reference.split(/[?#]/)[0].split('/')) {
      if (!part || part === '.') continue
      if (part === '..') { if (!parts.length) throw new Error('资源路径超出工程目录'); parts.pop() }
      else parts.push(part)
    }
    const path = parts.join('/')
    if (typeof files[path] !== 'string') throw new Error('预览缺少资源文件：' + path)
    return path
  }
  const doc = new DOMParser().parseFromString(files[entry], 'text/html')
  const used = new Set<string>()
  doc.querySelectorAll('base,meta,iframe,object,embed').forEach(e => e.remove())
  // Saved generations may use inline handlers despite the generation prompt.
  // Compile them into ordinary listeners inside the existing isolated sandbox.
  // This uses static script text, never eval or Function construction.
  const handlers: string[] = []
  const elements = Array.from(doc.querySelectorAll('*'))
  elements.forEach(element => element.removeAttribute('data-atoms-event-target'))
  elements.forEach((element, index) => {
    for (const attribute of Array.from(element.attributes)) {
      if (/^on/i.test(attribute.name)) {
        const handler = `function(event){${attribute.value}\n}`
        parse(`(${handler})`, { ecmaVersion: 'latest', sourceType: 'script' })
        element.setAttribute('data-atoms-event-target', String(index))
        handlers.push(`elements.get(${index})?.addEventListener(${JSON.stringify(attribute.name.slice(2))}, function(event){ if ((${handler}).call(this,event) === false) event.preventDefault(); });`)
        element.removeAttribute(attribute.name)
      } else if (attribute.name === 'srcdoc') element.removeAttribute(attribute.name)
    }
  })
  for (const link of Array.from(doc.querySelectorAll('link'))) {
    if (link.rel === 'stylesheet') {
      const path = resolve(link.getAttribute('href') || '')
      const style = doc.createElement('style'); style.textContent = files[path]
      if (link.media) style.media = link.media
      link.replaceWith(style); used.add(path)
    } else link.remove()
  }
  const scripts: HTMLScriptElement[] = []
  for (const original of Array.from(doc.scripts)) {
    const type = original.type.trim().toLowerCase()
    if (type === 'module') throw new Error('此工程使用模块脚本，需要构建后预览。请生成无外部依赖的 HTML/CSS/JavaScript 页面，或导出后在本地启动。')
    if (type && !['text/javascript', 'application/javascript'].includes(type)) continue
    const path = original.hasAttribute('src') ? resolve(original.getAttribute('src') || '') : undefined
    const script = doc.createElement('script'); script.textContent = path ? files[path] : original.textContent
    parse(script.textContent || '', { ecmaVersion: 'latest', sourceType: 'script' })
    if (path) used.add(path)
    scripts.push(script); original.remove()
  }
  // Keep compatibility with saved three-file versions whose HTML omits resource tags.
  const directory = entry.slice(0, entry.lastIndexOf('/') + 1)
  const css = directory + 'style.css', js = directory + 'script.js'
  if (typeof files[css] === 'string' && !used.has(css)) {
    const style = doc.createElement('style'); style.textContent = files[css]; doc.head.append(style)
  }
  if (typeof files[js] === 'string' && !used.has(js)) {
    parse(files[js], { ecmaVersion: 'latest', sourceType: 'script' })
    const script = doc.createElement('script'); script.textContent = files[js]; scripts.push(script)
  }
  const csp = doc.createElement('meta'); csp.httpEquiv = 'Content-Security-Policy'
  csp.content = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; font-src data:; connect-src 'none'; form-action 'none'; base-uri 'none'; object-src 'none'"
  doc.head.prepend(csp)
  // Opaque sandbox origins cannot access native Web Storage. Keep generated apps
  // compatible without granting same-origin access or persisting data across runs.
  const storageBridge = `(() => {
    for (const name of ['localStorage', 'sessionStorage']) {
      const data = Object.create(null);
      const methods = {
        getItem(key) { key = String(key); return Object.hasOwn(data, key) ? data[key] : null; },
        setItem(key, value) { data[String(key)] = String(value); },
        removeItem(key) { delete data[String(key)]; },
        clear() { for (const key of Object.keys(data)) delete data[key]; },
        key(index) { return Object.keys(data)[Number(index) >>> 0] ?? null; }
      };
      const storage = new Proxy(data, {
        get(target, key) { return key === 'length' ? Object.keys(data).length : Object.hasOwn(methods, key) ? methods[key] : target[key]; },
        set(target, key, value) { target[key] = String(value); return true; }
      });
      Object.defineProperty(window, name, { value: storage, configurable: true });
    }
  })();`
  const bridge = doc.createElement('script'); bridge.textContent = storageBridge + `window.addEventListener('error', e => parent.postMessage({type:'preview-error',token:${JSON.stringify(token)},message:e.message},'*'));window.addEventListener('unhandledrejection',e=>parent.postMessage({type:'preview-error',token:${JSON.stringify(token)},message:String(e.reason)},'*'));`
  doc.head.append(bridge)
  if (handlers.length) {
    const listeners = doc.createElement('script')
    listeners.textContent = `(() => {const elements=new Map(Array.from(document.querySelectorAll('[data-atoms-event-target]'),element=>{const index=Number(element.getAttribute('data-atoms-event-target'));element.removeAttribute('data-atoms-event-target');return [index,element];}));${handlers.join('\n')}\n})();`
    doc.body.append(listeners)
  }
  for (const script of scripts) doc.body.append(script)
  for (const style of Array.from(doc.querySelectorAll('style'))) style.textContent = (style.textContent || '').replace(/<\/style/gi, '<\\/style')
  for (const script of Array.from(doc.scripts)) script.textContent = (script.textContent || '').replace(/<\/script/gi, '<\\/script')
  return '<!doctype html>' + doc.documentElement.outerHTML
}
