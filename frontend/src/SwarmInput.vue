<script setup lang="ts">
import { ref } from "vue";
import { api } from "./api";

const props = defineProps<{ runId: string }>();
const message = ref("");
const pending = ref(false);
const feedback = ref("");

async function send() {
  if (pending.value || !message.value.trim()) return;
  pending.value = true;
  feedback.value = "";
  try {
    await api(`/runs/${encodeURIComponent(props.runId)}/swarm/message`, "POST", { message: message.value.trim() });
    message.value = "";
    feedback.value = "已发送给 Swarm Lead";
  } catch (error) {
    feedback.value = error instanceof Error ? error.message : "发送失败";
  } finally {
    pending.value = false;
  }
}
</script>

<template>
  <form class="swarm-input" @submit.prevent="send">
    <label>
      补充给 Swarm Lead
      <textarea v-model="message" maxlength="8000" rows="2" required :disabled="pending" />
    </label>
    <button class="button small" :disabled="pending || !message.trim()">发送补充</button>
    <p v-if="feedback" role="status">{{ feedback }}</p>
  </form>
</template>

<style scoped>
.swarm-input { display: grid; gap: 6px; margin-top: 12px; }
label { display: grid; gap: 4px; }
textarea { width: 100%; resize: vertical; border: 1px solid var(--line); border-radius: 6px; padding: 6px; }
button { justify-self: start; }
p { margin: 0; }
</style>
