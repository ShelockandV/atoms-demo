<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue";
import { X } from "lucide-vue-next";
import { api } from "./api";

interface Approval {
  request_id: string;
  agent: string;
  tool: string;
  args: string;
  thread_id: string;
  created_at: string;
}

const emit = defineEmits<{ close: [] }>();
const dialog = ref<HTMLDialogElement>();
const token = ref("");
const connected = ref(false);
const pending = ref(false);
const items = ref<Approval[]>([]);
const error = ref("");
const notice = ref("");
const controller = new AbortController();
const endpoint = "/v1/daemon/approvals";

async function refresh() {
  if (pending.value || !token.value) return;
  pending.value = true;
  error.value = "";
  try {
    const result = await api<{ approvals: Approval[] }>(endpoint, "GET", undefined, options());
    items.value = result.approvals;
    connected.value = true;
  } catch (problem) {
    if (!controller.signal.aborted) error.value = message(problem);
  } finally {
    pending.value = false;
  }
}

async function decide(approval: Approval, decision: "allow" | "deny") {
  if (pending.value) return;
  pending.value = true;
  error.value = "";
  notice.value = "";
  try {
    await api(`${endpoint}/${encodeURIComponent(approval.request_id)}/decision`, "POST", { decision }, options());
    items.value = items.value.filter((item) => item.request_id !== approval.request_id);
    notice.value = decision === "allow" ? "已批准请求" : "已拒绝请求";
  } catch (problem) {
    if (!controller.signal.aborted) error.value = message(problem);
  } finally {
    pending.value = false;
  }
}

function options() {
  return { headers: { Authorization: `Bearer ${token.value}` }, signal: controller.signal };
}

function message(problem: unknown) {
  return problem instanceof Error ? problem.message : "审批请求失败";
}

function disconnect() {
  connected.value = false;
  token.value = "";
  items.value = [];
  error.value = "";
  notice.value = "";
}

onMounted(() => dialog.value?.showModal());
onUnmounted(() => {
  controller.abort();
  disconnect();
});
</script>

<template>
  <dialog ref="dialog" class="modal daemon-approval-panel" aria-labelledby="daemon-approval-title" @close="emit('close')">
    <div class="modal-heading">
      <h2 id="daemon-approval-title">Daemon 审批</h2>
      <button class="icon-button" aria-label="关闭 daemon 审批" @click="dialog?.close()"><X :size="18" /></button>
    </div>
    <form v-if="!connected" class="approval-form" @submit.prevent="refresh">
      <label for="daemon-approval-token">租户配置令牌</label>
      <input id="daemon-approval-token" v-model.trim="token" type="password" autocomplete="off" required />
      <button class="button primary" :disabled="pending">查看待审批</button>
    </form>
    <div v-else class="approval-actions">
      <button class="button small" :disabled="pending" @click="refresh">刷新待审批</button>
      <button class="button small" :disabled="pending" @click="disconnect">退出审批</button>
    </div>
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="connected && !items.length && !error" role="status">暂无待审批请求</p>
    <article v-for="item in items" :key="item.request_id" class="approval-request">
      <strong>{{ item.tool }}</strong>
      <p>{{ item.agent || "未命名 Agent" }} · {{ new Date(item.created_at).toLocaleString() }}</p>
      <p v-if="item.thread_id">会话 {{ item.thread_id }}</p>
      <details v-if="item.args">
        <summary>查看请求参数</summary>
        <pre>{{ item.args }}</pre>
      </details>
      <div class="approval-actions">
        <button class="button primary" :disabled="pending" @click="decide(item, 'allow')">批准</button>
        <button class="button" :disabled="pending" @click="decide(item, 'deny')">拒绝</button>
      </div>
    </article>
  </dialog>
</template>

<style scoped>
.daemon-approval-panel::backdrop { background: #27213460; backdrop-filter: blur(4px); }
.approval-form { display: grid; gap: 10px; margin: 14px 0; }
.approval-form input { width: 100%; padding: 9px; border: 1px solid var(--line); border-radius: 6px; }
.approval-form button { justify-self: start; }
.approval-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.approval-request { border-top: 1px solid var(--line); padding: 12px 0; overflow-wrap: anywhere; }
.approval-request p { margin: 5px 0; }
.approval-request pre { max-height: 180px; overflow: auto; white-space: pre-wrap; }
</style>
