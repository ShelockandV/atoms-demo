<script setup lang="ts">
import type { SwarmTeam } from "./api";

const props = defineProps<{ teams: SwarmTeam[]; runStatus: string }>();
const labels: Record<string, string> = {
  PENDING: "待执行", RUNNING: "执行中", SUCCEEDED: "已完成", FAILED: "失败",
};
function label(state: string) {
  if (state === "RUNNING") {
    if (props.runStatus === "PAUSED") return "等待恢复";
    if (props.runStatus === "CANCELLED") return "已取消";
    if (props.runStatus === "FAILED") return "已中断";
  }
  return labels[state] || state;
}
</script>

<template>
  <details class="swarm-board" open>
    <summary>Swarm 任务板</summary>
    <section v-for="team in teams" :key="team.id">
      <strong>{{ team.id }}</strong>
      <p>
        规划 {{ label(team.planning) }} · 复核 {{ label(team.review) }}
        <template v-if="team.replanning"> · 重规划 {{ label(team.replanning) }}</template>
        · 汇总 {{ label(team.synthesis) }}
      </p>
      <ul>
        <li v-for="(task, index) in team.tasks" :key="`${task.id}:${task.round ?? Number(Boolean(task.followup))}`">
          <strong>{{ task.role || task.id }}</strong>
          <span v-if="task.followup">{{ team.tasks.findIndex(previous => previous.id === task.id) < index ? "重派任务" : "补充任务" }}<template v-if="task.round"> · 第 {{ task.round }} 轮</template></span>
          <span>{{ label(task.status) }}</span>
          <p>{{ task.query }}</p>
          <p v-if="task.dependencies.length">依赖 {{ task.dependencies.join("、") }}</p>
          <p v-for="round in task.peerRounds" :key="round.round">
            第 {{ round.round }} 轮
            <span v-if="round.outboxStatus">发件 {{ label(round.outboxStatus) }}</span>
            <span v-if="round.inboxStatus">收件处理 {{ label(round.inboxStatus) }}</span>
          </p>
          <p v-if="!task.peerRounds?.length && (task.outboxStatus || task.inboxStatus)">
            <span v-if="task.outboxStatus">发件 {{ label(task.outboxStatus) }}</span>
            <span v-if="task.inboxStatus">收件处理 {{ label(task.inboxStatus) }}</span>
          </p>
        </li>
      </ul>
    </section>
  </details>
</template>

<style scoped>
.swarm-board { margin: 12px 0; padding: 10px; border: 1px solid var(--line); border-radius: 8px; }
summary { cursor: pointer; font-weight: 600; }
section { margin-top: 8px; }
ul { padding: 0; list-style: none; }
li { margin: 6px 0; padding: 6px 8px; border: 1px solid var(--line); border-radius: 6px; }
li span { margin-left: 7px; color: var(--muted); }
p { margin: 4px 0; overflow-wrap: anywhere; }
</style>
