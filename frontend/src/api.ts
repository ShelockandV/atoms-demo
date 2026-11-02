export type Mode = 'build' | 'chat' | 'image'
export interface ModelConfig { demo:boolean; model:string; configured:boolean; models:Record<Mode,string[]>; defaults:Record<Mode,string> }
export type Files = Record<string, string>
export interface Version { id: string; number: number; summary: string; files: Files; createdAt: string }
export interface Run { mode:Mode; model:string; id: string; projectId: string; status: string; error: string | null; plan: string | null; baseVersion: string | null; createdAt: string }
export interface Project { id: string; title: string; currentVersion: string | null; updatedAt: string }
export interface Message { mode?:Mode; model?:string; imageUrl?:string | null; id: string; role: string; content: string; createdAt: string }
export interface Detail extends Project { messages: Message[]; versions: Version[]; run: Run | null }
export async function api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await fetch('/api' + path, { method, credentials: 'same-origin', headers: body === undefined ? {} : { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
  if (!response.ok) { const data = await response.json().catch(() => ({})); throw new Error(data.error || `请求失败 (${response.status})`) }
  return response.json()
}
