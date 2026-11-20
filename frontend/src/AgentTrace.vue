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
};
const nodeLabels: Record<string, string> = {
  "v1.requirements": "需求分析",
  "v1.architecture": "架构分析",
  "v1.synthesis": "汇总开发约束",
};
function executionLabel(outcome: string, failureText = "需调整") {
  if (outcome === "FAILED") return failureText;
  if (outcome === "SUCCEEDED") return "已完成";
  if (props.status === "PAUSED") return "等待恢复";
  if (props.status === "FAILED") return "已中断";
  return "执行中";
}
function dependencyLabels(ids: string[]) {
  return ids.map((id) => nodeLabels[id] || id).join("、");
}
const labels: Record<string, string> = {
  PLAN_DAG: "分解与汇总开发任务",
  LIST_FILES: "查看文件目录",
  READ_FILE: "读取源码",
  GENERATE: "编写或修复源码",
  VALIDATE: "静态校验",
  REVIEW: "复核需求与源码",
  FINISH: "准备保存版本",
  INVALID: "调整动作格式",
};
</script>

<template>
  <details v-if="steps.length" class="agent-trace" open>
    <summary>{{ patternLabels[pattern || 'react'] || pattern }} · {{ steps.length }} 步</summary>
    <details v-if="nodes?.length" class="dag-nodes">
      <summary>分析节点 · {{ nodes.length }}</summary>
      <div v-for="node in nodes" :key="node.id">
        <strong>{{ nodeLabels[node.id] || node.id }}</strong>
        <span> · {{ executionLabel(node.status, '失败') }}</span>
        <p v-if="node.dependencies.length">依赖 {{ dependencyLabels(node.dependencies) }}</p>
        <details v-if="node.output">
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
    <p class="agent-trace-note">校验步骤只检查文件与支持的语法，交互和业务功能仍需验收。</p>
    <p v-if="usage" class="agent-trace-note">模型调用 {{ usage.modelCalls }} 次；已记录 {{ usage.promptTokens + usage.completionTokens }} tokens（{{ usage.meteredCalls }} 次返回用量）。</p>
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
