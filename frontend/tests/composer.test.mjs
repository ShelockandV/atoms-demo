import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import ts from 'typescript'
import { JSDOM } from 'jsdom'
const compiled = ts.transpileModule(readFileSync(new URL('../src/composer.ts', import.meta.url),'utf8'), {compilerOptions:{module:ts.ModuleKind.ES2022}}).outputText
const { onComposerKeydown } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)

for (const [name, options, sends, prevented] of [
  ['Enter sends', {key:'Enter'}, 1, true],
  ['Shift+Enter keeps newline', {key:'Enter',shiftKey:true}, 0, false],
  ['Chinese IME confirmation does not send', {key:'Enter',isComposing:true}, 0, false],
  ['IME legacy keyCode 229 does not send', {key:'Enter',keyCode:229}, 0, false],
  ['held Enter cannot repeatedly submit', {key:'Enter',repeat:true}, 0, true],
  ['other keys keep normal typing', {key:'a'}, 0, false],
]) {
  test(name, () => {
    const dom=new JSDOM('<textarea></textarea>')
    const input=dom.window.document.querySelector('textarea')
    let submitted=0
    input.addEventListener('keydown', event=>onComposerKeydown(event,()=>submitted++))
    const event=new dom.window.KeyboardEvent('keydown',{...options,cancelable:true,bubbles:true})
    input.dispatchEvent(event)
    assert.equal(submitted,sends)
    assert.equal(event.defaultPrevented,prevented)
    dom.window.close()
  })
}
