<script setup lang="ts">
import type { AgentStep, Run } from "./api";
const props = defineProps<{
  steps: AgentStep[];
  status: string;
  usage?: Run["usage"];
  pattern?: string;
  nodes?: Run["nodes"];
}>();
const patternLabels: Record<string, string> = {
  react: "自主执行",
  reflection: "复核修复",
  dag: "分解开发",
  research: "资料研究",
};
const nodeLabels: Record<string, string> = {
  "v1.requirements": "需求分析",
  "v1.architecture": "架构分析",
  "v1.synthesis": "汇总开发约束",
  "research.plan": "拆解研究问题",
  "research.report": "生成研究报告",
  "research.refine": "检查资料缺口",
};
function executionLabel(outcome: string, failureText = "需调整") {
  if (outcome === "FAILED") return failureText;
  if (outcome === "SUCCEEDED") return "已完成";
  if (props.status === "PAUSED") return "等待恢复";
  if (props.status === "FAILED") return "已中断";
  return "执行中";
}
function nodeLabel(id: string) {
  const evidence = /^research\.evidence\.(\d+)$/.exec(id);
  if (evidence) return `证据检索 ${evidence[1]}`;
  const followup = /^research\.followup\.(\d+)$/.exec(id);
  if (followup) return `补充检索 ${followup[1]}`;
  const action = /\.swarm\.(?:followup(?:\.round-\d+)?\.)?([^.]+)\.actions\.(\d+)(?:\.(inbox|observation))?$/.exec(id);
  if (action) {
    const label = action[3] === "inbox" ? "收件" : action[3] === "observation" ? "检索结果" : "动作";
    return `${action[1]} · 第 ${action[2]} 步${label}`;
  }
  const swarm = /\.swarm\.(outbox|inbox)\.([^.]*)$/.exec(id);
  if (swarm) return `${swarm[2]} · ${swarm[1] === "outbox" ? "发送消息" : "处理收件箱"}`;
  const reactivated = /\.swarm\.reactivated\.([^.]+)$/.exec(id);
  if (reactivated) return `未读消息复核 · ${reactivated[1]}`;
  const swarmFollowup = /\.swarm\.followup\.([^.]*)$/.exec(id);
  if (swarmFollowup) return `补充任务 · ${swarmFollowup[1]}`;
  if (id.endsWith(".swarm.replan")) return "Lead 重规划";
  return nodeLabels[id] || id;
}
function peerMessages(output: string): { from: string; to: string; message: string }[] {
  try {
    const parsed: unknown = JSON.parse(output);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((item) => item && typeof item.from === "string"
      && typeof item.to === "string" && typeof item.message === "string");
  } catch {
    return [];
  }
}
function dependencyLabels(ids: string[]) {
  return ids.map(nodeLabel).join("、");
}
const labels: Record<string, string> = {
  PLAN_DAG: "分解与汇总开发任务",
  LIST_FILES: "查看文件目录",
  READ_FILE: "读取源码",
  SEARCH_DOCS: "检索工作区文档",
  GENERATE: "编写或修复源码",
  VALIDATE: "静态校验",
  REVIEW: "复核需求与源码",
  FINISH: "准备保存版本",
  INVALID: "调整动作格式",
};
</script>

<template>
  <details v-if="steps.length || nodes?.length" class="agent-trace" open>
    <summary>{{ patternLabels[pattern || 'react'] || pattern }} · {{ pattern === 'research' ? (nodes?.length || 0) + ' 个研究节点' : steps.length + ' 步' }}</summary>
    <details v-if="nodes?.length" class="dag-nodes">
      <summary>分析节点 · {{ nodes.length }}</summary>
      <div v-for="node in nodes" :key="node.id">
        <strong>{{ nodeLabel(node.id) }}</strong>
        <span> · {{ executionLabel(node.status, '失败') }}</span>
        <p v-if="node.dependencies.length">依赖 {{ dependencyLabels(node.dependencies) }}</p>
        <ul v-if="node.id.includes('.swarm.outbox.') && node.status === 'SUCCEEDED'">
          <li v-for="(message, index) in peerMessages(node.output)" :key="index">
            {{ message.from }} → {{ message.to }} · {{ message.message }}
          </li>
          <li v-if="!peerMessages(node.output).length">未发送消息</li>
        </ul>
        <details v-else-if="node.output">
          <summary>查看分析结果</summary>
          <pre>{{ node.output }}</pre>
        </details>
      </div>
    </details>
    <ol>
      <li v-for="step in steps" :key="step.step" :data-outcome="step.outcome">
        <div class="agent-step-heading">
          <strong>{{ step.step }}. {{ labels[step.action] || step.action }}</strong>
          <span>{{ executionLabel(step.outcome) }}</span>
        </div>
        <p v-if="step.summary">{{ step.summary }}</p>
        <p v-if="step.executionTimeMs != null" class="agent-trace-note">耗时 {{ (step.executionTimeMs / 1000).toFixed(2) }} 秒</p>
        <details v-if="step.observation">
          <summary>查看结果</summary>
          <pre>{{ step.observation }}</pre>
        </details>
      </li>
    </ol>
    <p v-if="pattern !== 'research'" class="agent-trace-note">校验步骤只检查文件与支持的语法，交互和业务功能仍需验收。</p>
    <p v-if="usage" class="agent-trace-note">模型调用 {{ usage.modelCalls }} 次；已记录 {{ usage.promptTokens + usage.completionTokens }} tokens（{{ usage.meteredCalls }} 次返回用量）。</p>
    <p v-for="entry in usage?.models" :key="entry.model" class="agent-trace-note">
      {{ entry.model }} · 输入 {{ entry.promptTokens }} · 输出 {{ entry.completionTokens }}
    </p>
  </details>
</template>

<style scoped>
.agent-trace { margin: 14px 0; padding: 14px 16px; border: 1px solid #e5e5e5; border-radius: 14px; background: #fafafa; font-size: 12px; }
summary { cursor: pointer; color: #525252; }
ol { list-style: none; padding: 0; margin: 12px 0; }
li { border-left: 2px solid #d4d4d4; padding: 0 0 12px 12px; overflow-wrap: anywhere; }
li[data-outcome="FAILED"] { border-color: #d97706; }
.agent-step-heading { display: flex; gap: 10px; justify-content: space-between; }
.agent-step-heading span, .agent-trace-note { color: #737373; }
p { margin: 6px 0; }
pre { white-space: pre-wrap; font: inherit; max-height: 180px; overflow: auto; }
.dag-nodes { margin-top: 12px; }
.dag-nodes > div { padding: 10px 0; border-bottom: 1px solid #e5e5e5; }
</style>
