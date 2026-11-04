export function onComposerKeydown(event: KeyboardEvent, send: () => void) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  if (!event.repeat) send()
}
