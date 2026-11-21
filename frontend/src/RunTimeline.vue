<script setup lang="ts">
import { computed } from "vue";
import { Check, Circle, LoaderCircle, Pause, X } from "lucide-vue-next";
import type { Run } from "./api";

const props = defineProps<{ run: Run }>();
const labels: Record<string, string> = {
  WORKFLOW_STARTED: "开始执行",
  ANSWERING: "生成回答",
  PLANNING: "制定计划",
  RESEARCHING: "开始研究",
  WORKFLOW_PAUSED: "已暂停",
  WORKFLOW_RESUMED: "已继续",
};
const endLabels: Record<string, string> = {
  SUCCEEDED: "结果已就绪",
  FAILED: "执行失败",
  CANCELLED: "已终止",
  TIMEOUT: "执行超时",
  PAUSED: "等待继续",
  AWAITING_APPROVAL: "等待审批",
};
const events = computed(() => (props.run.timeline || []).filter(event => labels[event.type]));
const completedAt = computed(() => props.run.timeline?.slice().reverse().find(event =>
  ["WORKFLOW_COMPLETED", "WORKFLOW_CANCELLED", "WORKFLOW_FAILED"].includes(event.type),
)?.timestamp);
const statusText = computed(() => endLabels[props.run.status]);
const active = computed(() => !statusText.value);
const failed = computed(() => ["FAILED", "CANCELLED", "TIMEOUT"].includes(props.run.status));
function clock(timestamp?: string) {
  if (!timestamp) return "";
  const date = new Date(timestamp);
  return Number.isNaN(date.getTime()) ? "" : date.toLocaleTimeString([], { hour12: false });
}
</script>

<template>
  <ol class="run-timeline" aria-live="polite">
    <li>
      <span class="timeline-marker" :class="{ settled: events.length || !active }">
        <Check v-if="events.length || !active" :size="13" /><Circle v-else :size="8" />
      </span>
      <div><strong>任务已提交</strong><time :datetime="run.createdAt">{{ clock(run.createdAt) }}</time></div>
    </li>
    <li v-for="(event, index) in events" :key="event.seq">
      <span class="timeline-marker" :class="{ settled: !active || index !== events.length - 1 }">
        <LoaderCircle v-if="active && index === events.length - 1" class="spinning" :size="14" />
        <Pause v-else-if="event.type === 'WORKFLOW_PAUSED'" :size="13" />
        <Check v-else :size="13" />
      </span>
      <div><strong>{{ labels[event.type] }}</strong><time :datetime="event.timestamp">{{ clock(event.timestamp) }}</time></div>
    </li>
    <li v-if="$slots.steps" class="timeline-steps"><div><slot name="steps" /></div></li>
    <li v-if="run.usage?.modelCalls">
      <span class="timeline-marker settled"><Check :size="13" /></span>
      <div>
        <strong>模型用量</strong>
        <details>
          <summary>{{ run.usage.modelCalls }} 次调用 · {{ run.usage.promptTokens + run.usage.completionTokens }} tokens 已记录</summary>
          <p v-for="model in run.usage.models" :key="model.model">
            {{ model.model }} · 输入 {{ model.promptTokens }} · 输出 {{ model.completionTokens }}
          </p>
        </details>
      </div>
    </li>
    <li v-if="statusText">
      <span class="timeline-marker" :class="{ settled: run.status === 'SUCCEEDED', failed }">
        <X v-if="failed" :size="13" />
        <Check v-else-if="run.status === 'SUCCEEDED'" :size="13" />
        <Pause v-else :size="13" />
      </span>
      <div>
        <strong>{{ statusText }}</strong><time v-if="completedAt" :datetime="completedAt">{{ clock(completedAt) }}</time>
        <details v-if="run.outputPreview">
          <summary>查看结果预览</summary>
          <pre>{{ run.outputPreview }}</pre>
        </details>
        <p v-else-if="failed && run.error">{{ run.error }}</p>
      </div>
    </li>
  </ol>
</template>

<style scoped>
.run-timeline { list-style: none; margin: 0; padding: 16px 0 8px; }
.run-timeline li { display: grid; grid-template-columns: 24px minmax(0, 1fr); gap: 10px; position: relative; padding-bottom: 19px; }
.run-timeline li:not(:last-child)::before { content: ""; position: absolute; width: 1px; top: 24px; bottom: 0; left: 11px; background: var(--atoms-line); }
.run-timeline strong { font-size: 13px; font-weight: 600; }
.timeline-marker { display: grid; place-items: center; width: 24px; height: 24px; border: 1px solid var(--atoms-line); border-radius: 50%; }
.timeline-marker.settled { color: #15945d; border-color: currentColor; }
.timeline-marker.failed { color: #d34a4a; border-color: currentColor; }
.timeline-steps > div { grid-column: 2; }
time, summary, p { font-size: 12px; color: var(--atoms-muted); }
time { margin-inline-start: 8px; white-space: nowrap; }
details, p { margin: 5px 0 0; }
summary { cursor: pointer; }
pre { font: inherit; font-size: 12px; white-space: pre-wrap; overflow-wrap: anywhere; max-height: 200px; overflow: auto; }
.spinning { animation: timeline-spin 1s linear infinite; }
@keyframes timeline-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .spinning { animation: none; } }
</style>
