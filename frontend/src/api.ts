export type Mode = "build" | "chat" | "image" | "research";
export interface SkillSummary {
  name: string;
  version: string;
  description: string;
  requires_tools: string[];
  requires_role: string;
}
export interface ModelConfig {
  skills?: SkillSummary[];
  learningRouting?: boolean;
  patterns?: { id: string; description: string }[];
  templates?: { id: string; name: string; description: string; version: string }[];
  demo: boolean;
  model: string;
  configured: boolean;
  models: Record<Mode, string[]>;
  defaults: Record<Mode, string>;
}
export type Files = Record<string, string>;
export interface Version {
  id: string;
  number: number;
  summary: string;
  files: Files;
  createdAt: string;
}
export interface SwarmTeam {
  id: string;
  planning: string;
  review: string;
  synthesis: string;
  replanning?: string;
  tasks: { id: string; role: string; query: string; dependencies: string[]; status: string;
    outboxStatus: string; inboxStatus: string; followup?: boolean; round?: number;
    peerRounds?: { round: number; outboxStatus: string; inboxStatus: string }[] }[];
}
export interface Run {
  approvalReason?: string;
  approvalId?: string;
  approvalTool?: string;
  approvalNode?: string;
  planReviewVersion?: number;
  supervisorReview?: boolean;
  swarmAcceptingInput?: boolean;
  swarmBoard?: SwarmTeam[];
  skill?: string;
  nodes?: {
    id: string;
    dependencies: string[];
    status: string;
    output: string;
    executionTimeMs: number;
  }[];
  pattern?: string;
  template?: string;
  templateVersion?: string;
  steps?: AgentStep[];
  usage?: {
    modelCalls: number;
    models?: { model: string; meteredCalls: number; promptTokens: number; completionTokens: number }[];
    meteredCalls: number;
    promptTokens: number;
    completionTokens: number;
  };
  mode: Mode;
  model: string;
  id: string;
  projectId: string;
  status: string;
  error: string | null;
  plan: string | null;
  baseVersion: string | null;
  createdAt: string;
}
export interface AgentStep {
  executionTimeMs?: number | null;
  step: number;
  action: string;
  summary: string;
  outcome: "RUNNING" | "SUCCEEDED" | "FAILED";
  observation: string;
  updatedAt: string;
}
export interface Project {
  latestMode?: Mode | null;
  latestModel?: string | null;
  latestPattern?: string | null;
  runCount?: number;
  costUsd?: number | null;
  totalTokens?: number | null;
  id: string;
  title: string;
  currentVersion: string | null;
  updatedAt: string;
}
export interface Message {
  mode?: Mode;
  model?: string;
  imageUrl?: string | null;
  id: string;
  role: string;
  content: string;
  createdAt: string;
}
export interface Detail extends Project {
  messages: Message[];
  versions: Version[];
  run: Run | null;
}
export async function api<T>(
  path: string,
  method = "GET",
  body?: unknown,
  options: Pick<RequestInit, "headers" | "signal"> = {},
): Promise<T> {
  const headers = new Headers(options.headers);
  if (body !== undefined) headers.set("Content-Type", "application/json");
  const response = await fetch("/api" + path, {
    method,
    credentials: "same-origin",
    headers,
    signal: options.signal,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    throw new Error(data.error || `请求失败 (${response.status})`);
  }
  return response.json();
}
