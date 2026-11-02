import type { Files } from './api'
import { parse } from 'acorn'
export function canPreview(files: Files) {
  return ['index.html','style.css','script.js'].every(name=>typeof files[name]==='string')
    && Object.keys(files).every(name=>['index.html','style.css','script.js'].includes(name)||name.toLowerCase().endsWith('.md'))
}
export function previewDocument(files: Files, token: string) {
  if(!canPreview(files))throw new Error('该工程需要下载源码后在本地运行')
  parse(files['script.js'], { ecmaVersion: 'latest', sourceType: 'script' })
  const doc = new DOMParser().parseFromString(files['index.html'], 'text/html')
  doc.querySelectorAll('script,base,meta,link,iframe,object,embed').forEach(e => e.remove())
  doc.querySelectorAll('*').forEach(e => { for (const a of Array.from(e.attributes)) if (/^on/i.test(a.name) || a.name === 'srcdoc') e.removeAttribute(a.name) })
  const csp = doc.createElement('meta'); csp.httpEquiv = 'Content-Security-Policy'
  csp.content = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; font-src data:; connect-src 'none'; form-action 'none'; base-uri 'none'; object-src 'none'"
  doc.head.prepend(csp)
  const style = doc.createElement('style'); style.textContent = files['style.css']; doc.head.append(style)
  const bridge = doc.createElement('script'); bridge.textContent = `window.addEventListener('error', e => parent.postMessage({type:'preview-error',token:${JSON.stringify(token)},message:e.message},'*'));window.addEventListener('unhandledrejection',e=>parent.postMessage({type:'preview-error',token:${JSON.stringify(token)},message:String(e.reason)},'*'));`
  doc.head.append(bridge)
  const script = doc.createElement('script'); script.textContent = files['script.js']; doc.body.append(script)
  // HTML serialization needs escaped raw-text closing tags.
  style.textContent = style.textContent.replace(/<\/style/gi, '<\\/style')
  for (const s of Array.from(doc.scripts)) s.textContent = (s.textContent || '').replace(/<\/script/gi, '<\\/script')
  return '<!doctype html>' + doc.documentElement.outerHTML
}
