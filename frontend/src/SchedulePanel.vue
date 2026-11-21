<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue";
import { X } from "lucide-vue-next";
import { gatewayApi, gatewayUser } from "./gatewaySession";

interface ScheduleInput {
  name: string;
  cron_expression: string;
  timezone: string;
  task_query: string;
  timeout_seconds: number;
  max_budget_per_run_usd: number;
  execution_target: "cloud" | "daemon";
  agent_name: string;
}

interface Schedule extends ScheduleInput {
  id: string;
  status: "ACTIVE" | "PAUSED";
  next_run_at: string | null;
  sync_pending?: boolean;
}

interface ScheduledRun {
  workflow_id: string;
  status: string;
  model_used?: string;
  triggered_at: string;
  result?: string;
  error_message?: string;
  approval_reason?: string;
  total_cost_usd?: number;
  max_budget_per_run_usd?: number;
}

interface TaskEvent {
  seq: number;
  type: string;
  payload?: { message?: string };
}

const emit = defineEmits<{ close: []; login: [] }>();
const dialog = ref<HTMLDialogElement>();
const schedules = ref<Schedule[]>([]);
const history = ref<{ id: string; runs: ScheduledRun[] } | null>(null);
const executionEvents = ref<{ id: string; entries: TaskEvent[]; offset: number } | null>(null);
const eventsPageSize = 50;
const editing = ref("");
const pending = ref(false);
const error = ref("");
const page = ref(1);
const pageSize = 20;
const total = ref(0);
const endpoint = "/v1/schedules";
const emptyForm = (): ScheduleInput => ({
  name: "",
  cron_expression: "0 9 * * *",
  timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC",
  task_query: "",
  timeout_seconds: 0,
  max_budget_per_run_usd: 0,
  execution_target: "cloud",
  agent_name: "",
});
const form = ref(emptyForm());

const statusLabels: Record<string, string> = {
  ACTIVE: "启用", PAUSED: "已暂停", COMPLETED: "已完成", FAILED: "失败",
  CANCELLED: "已取消", TIMEOUT: "已超时", QUEUED: "排队中", ANSWERING: "执行中", AWAITING_APPROVAL: "待审批",
};

function formatTime(value: string) {
  return new Date(value).toLocaleString();
}

const usd = new Intl.NumberFormat(undefined, {
  style: "currency", currency: "USD", maximumFractionDigits: 12,
});

async function load(target = page.value) {
  const result = await gatewayApi<{ schedules: Schedule[]; total_count: number }>(
    `${endpoint}?page=${target}&page_size=${pageSize}`,
  );
  schedules.value = result.schedules;
  total.value = result.total_count;
  page.value = target;
}

async function perform(action: () => Promise<void>) {
  pending.value = true;
  error.value = "";
  try {
    await action();
  } catch (problem) {
    error.value = problem instanceof Error ? problem.message : "计划任务请求失败";
  } finally {
    pending.value = false;
  }
}

function startEdit(schedule: Schedule) {
  editing.value = schedule.id;
  const { name, cron_expression, timezone, task_query, timeout_seconds, max_budget_per_run_usd } = schedule;
  form.value = {
    name, cron_expression, timezone, task_query, timeout_seconds, max_budget_per_run_usd,
    execution_target: schedule.execution_target, agent_name: schedule.agent_name ?? "",
  };
}

function clearForm() {
  editing.value = "";
  form.value = emptyForm();
}

function save() {
  return perform(async () => {
    const id = editing.value;
    const payload = { ...form.value };
    if (payload.execution_target === "daemon") payload.max_budget_per_run_usd = 0;
    await gatewayApi(id ? `${endpoint}/${id}` : endpoint, id ? "PUT" : "POST", payload);
    clearForm();
    await load(id ? page.value : 1);
  });
}

function change(schedule: Schedule, action: "pause" | "resume" | "delete") {
  return perform(async () => {
    const path = `${endpoint}/${schedule.id}`;
    if (action === "delete") {
      await gatewayApi(path, "DELETE");
      if (editing.value === schedule.id) clearForm();
      if (history.value?.id === schedule.id) {
        history.value = null;
        executionEvents.value = null;
      }
    } else {
      await gatewayApi(`${path}/${action}`, "POST", {});
    }
    await load(action === "delete" && schedules.value.length === 1 ? Math.max(1, page.value - 1) : page.value);
  });
}

async function loadHistory(id: string) {
  const result = await gatewayApi<{ runs: ScheduledRun[] }>(`${endpoint}/${id}/runs`);
  history.value = { id, runs: result.runs };
}

function openHistory(id: string) {
  return perform(async () => {
    await loadHistory(id);
    executionEvents.value = null;
  });
}

function showExecutionEvents(id: string, offset = 0) {
  return perform(async () => {
    const result = await gatewayApi<{ events: TaskEvent[] }>(
      `/v1/tasks/${id}/events?limit=${eventsPageSize}&offset=${offset}`,
    );
    executionEvents.value = { id, entries: result.events, offset };
  });
}

function decide(scheduleId: string, runId: string, approved: boolean) {
  return perform(async () => {
    await gatewayApi("/v1/approvals/decision", "POST", { workflow_id: runId, approval_id: runId, approved });
    await loadHistory(scheduleId);
  });
}

let syncTimer: ReturnType<typeof setInterval> | undefined;
onMounted(() => {
  dialog.value?.showModal();
  void perform(() => load());
  syncTimer = setInterval(() => {
    if (!pending.value && !error.value && schedules.value.some((schedule) => schedule.sync_pending))
      void perform(() => load());
  }, 3000);
});
onUnmounted(() => clearInterval(syncTimer));
</script>

<template>
  <dialog ref="dialog" class="modal schedule-panel" aria-labelledby="schedule-title" @close="emit('close')">
    <div class="modal-heading">
      <h2 id="schedule-title">定时任务</h2>
      <button class="icon-button" aria-label="关闭定时任务" @click="dialog?.close()"><X :size="18" /></button>
    </div>
    <p v-if="gatewayUser">Gateway 租户 {{ gatewayUser.tenant_id }}</p>
    <button v-else class="button small" type="button" @click="emit('login')">登录 Gateway</button>
    <form class="schedule-form" @submit.prevent="save">
      <label>名称 <input v-model.trim="form.name" required maxlength="200" /></label>
      <label>Cron <input v-model.trim="form.cron_expression" required maxlength="128" /></label>
      <label>时区 <input v-model.trim="form.timezone" required maxlength="128" placeholder="Asia/Shanghai" /></label>
      <label>运行时限（秒，0 为不限） <input v-model.number="form.timeout_seconds" type="number" min="0" max="86400" required /></label>
      <label v-if="gatewayUser">执行位置
        <select v-model="form.execution_target">
          <option value="cloud">Atoms</option><option value="daemon">Daemon</option>
        </select>
      </label>
      <label v-if="form.execution_target === 'daemon'">Agent 名称 <input v-model.trim="form.agent_name" maxlength="100" /></label>
      <label v-else>单次预算（美元，0 为不限） <input v-model.number="form.max_budget_per_run_usd" type="number" min="0" max="1000000" step="any" required /></label>
      <label class="query-field">任务内容 <textarea v-model.trim="form.task_query" required maxlength="10000" rows="3" /></label>
      <div class="schedule-actions">
        <button class="button primary" :disabled="pending">{{ editing ? "保存修改" : "创建计划" }}</button>
        <button v-if="editing" type="button" class="button" :disabled="pending" @click="clearForm">取消编辑</button>
      </div>
    </form>
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    <p v-if="!schedules.length && !pending && !error">暂无定时任务</p>
    <article v-for="schedule in schedules" :key="schedule.id" class="schedule-row">
      <strong>{{ schedule.name }}</strong>
      <p>{{ statusLabels[schedule.status] }} · {{ schedule.cron_expression }} · {{ schedule.timezone }}</p>
      <p v-if="schedule.execution_target === 'daemon'">Daemon <span v-if="schedule.agent_name">· {{ schedule.agent_name }}</span></p>
      <p v-if="schedule.sync_pending">等待同步到 Temporal</p>
      <p v-else-if="schedule.next_run_at">下次执行 {{ formatTime(schedule.next_run_at) }}</p>
      <div class="schedule-actions">
        <button class="button small" :disabled="pending" @click="startEdit(schedule)">编辑</button>
        <button class="button small" :disabled="pending" @click="change(schedule, schedule.status === 'ACTIVE' ? 'pause' : 'resume')">
          {{ schedule.status === "ACTIVE" ? "暂停" : "恢复" }}
        </button>
        <button class="button small" :disabled="pending" @click="openHistory(schedule.id)">运行历史</button>
        <button class="button small" :disabled="pending" @click="change(schedule, 'delete')">删除</button>
      </div>
      <ul v-if="history?.id === schedule.id && history.runs.length">
        <li v-for="run in history.runs" :key="run.workflow_id">
          {{ formatTime(run.triggered_at) }} · {{ statusLabels[run.status] || run.status }}
          <p v-if="run.max_budget_per_run_usd !== undefined">
            估算费用 {{ usd.format(run.total_cost_usd ?? 0) }} · 预算 {{ usd.format(run.max_budget_per_run_usd) }}
          </p>
          <div v-if="run.status === 'AWAITING_APPROVAL'">
            <p v-if="run.approval_reason">{{ run.approval_reason }}</p>
            <div class="schedule-actions">
              <button class="button small" :disabled="pending" @click="decide(schedule.id, run.workflow_id, true)">批准运行</button>
              <button class="button small" :disabled="pending" @click="decide(schedule.id, run.workflow_id, false)">拒绝运行</button>
            </div>
          </div>
          <button v-if="run.model_used === 'daemon'" class="button small" :disabled="pending"
            @click="showExecutionEvents(run.workflow_id)">执行事件</button>
          <template v-if="executionEvents?.id === run.workflow_id">
            <ul v-if="executionEvents.entries.length">
              <li v-for="event in executionEvents.entries" :key="event.seq">
                {{ event.type }}<span v-if="event.payload?.message"> · {{ event.payload.message }}</span>
              </li>
            </ul>
            <p v-else>暂无执行事件</p>
            <div class="schedule-actions">
              <button v-if="executionEvents.offset > 0" class="button small" :disabled="pending"
                @click="showExecutionEvents(run.workflow_id, executionEvents.offset - eventsPageSize)">上一页</button>
              <button v-if="executionEvents.entries.length === eventsPageSize" class="button small" :disabled="pending"
                @click="showExecutionEvents(run.workflow_id, executionEvents.offset + eventsPageSize)">下一页</button>
            </div>
          </template>
          <p v-if="run.result || run.error_message">{{ run.result || run.error_message }}</p>
        </li>
      </ul>
      <p v-else-if="history?.id === schedule.id">暂无运行记录</p>
    </article>
    <div v-if="total > pageSize" class="schedule-actions">
      <button class="button small" :disabled="pending || page === 1" @click="perform(() => load(page - 1))">上一页</button>
      <button class="button small" :disabled="pending || page * pageSize >= total" @click="perform(() => load(page + 1))">下一页</button>
    </div>
  </dialog>
</template>

<style scoped>
.schedule-panel::backdrop { background: #27213460; backdrop-filter: blur(4px); }
.schedule-form { display: grid; grid-template-columns: repeat(auto-fit, minmax(125px, 1fr)); gap: 10px; margin: 14px 0 20px; }
.schedule-form label { display: grid; gap: 5px; }
.schedule-form input, .schedule-form textarea { width: 100%; border: 1px solid var(--line); border-radius: 6px; padding: 8px; }
.query-field, .schedule-form .schedule-actions { grid-column: 1 / -1; }
.schedule-actions { display: flex; flex-wrap: wrap; gap: 6px; }
.schedule-row { border-top: 1px solid var(--line); padding: 13px 0; overflow-wrap: anywhere; }
.schedule-row p { margin: 5px 0 9px; white-space: pre-wrap; }
.schedule-row ul { padding-left: 18px; }
</style>
