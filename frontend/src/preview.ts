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
  doc.querySelectorAll('*').forEach(e => { for (const a of Array.from(e.attributes)) if (/^on/i.test(a.name) || a.name === 'srcdoc') e.removeAttribute(a.name) })
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
  const bridge = doc.createElement('script'); bridge.textContent = `window.addEventListener('error', e => parent.postMessage({type:'preview-error',token:${JSON.stringify(token)},message:e.message},'*'));window.addEventListener('unhandledrejection',e=>parent.postMessage({type:'preview-error',token:${JSON.stringify(token)},message:String(e.reason)},'*'));`
  doc.head.append(bridge)
  for (const script of scripts) doc.body.append(script)
  for (const style of Array.from(doc.querySelectorAll('style'))) style.textContent = (style.textContent || '').replace(/<\/style/gi, '<\\/style')
  for (const script of Array.from(doc.scripts)) script.textContent = (script.textContent || '').replace(/<\/script/gi, '<\\/script')
  return '<!doctype html>' + doc.documentElement.outerHTML
}
