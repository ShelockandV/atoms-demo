<script setup lang="ts">
import { onMounted, ref } from "vue";
import { X } from "lucide-vue-next";
import { gatewayApi } from "./gatewaySession";

interface Entry {
  name: string;
  path: string;
  is_dir: boolean;
  size_bytes: number;
}

interface MemoryFile {
  content: string;
  content_type: string;
  size_bytes: number;
}

const emit = defineEmits<{ close: [] }>();
const dialog = ref<HTMLDialogElement>();
const path = ref("");
const entries = ref<Entry[]>([]);
const selected = ref<{ entry: Entry; file: MemoryFile }>();
const pending = ref(false);
const error = ref("");

async function perform(action: () => Promise<void>) {
  if (pending.value) return;
  pending.value = true;
  error.value = "";
  try {
    await action();
  } catch (problem) {
    error.value = problem instanceof Error ? problem.message : "无法读取记忆文件";
  } finally {
    pending.value = false;
  }
}

function browse(directory: string) {
  return perform(async () => {
    const query = directory ? `?path=${encodeURIComponent(directory)}` : "";
    const result = await gatewayApi<{ files: Entry[] }>(`/v1/memory/files${query}`);
    path.value = directory;
    entries.value = result.files;
    selected.value = undefined;
  });
}

function open(entry: Entry) {
  if (entry.is_dir) return browse(entry.path);
  return perform(async () => {
    const encoded = entry.path.split("/").map(encodeURIComponent).join("/");
    const file = await gatewayApi<MemoryFile>(`/v1/memory/files/${encoded}`);
    selected.value = { entry, file };
  });
}

function isText(file: MemoryFile) {
  return file.content_type.startsWith("text/") || ["application/json", "application/xml"].includes(file.content_type);
}

function download() {
  if (!selected.value) return;
  const { entry, file } = selected.value;
  const content = isText(file) ? file.content : Uint8Array.from(atob(file.content), (byte) => byte.charCodeAt(0));
  const url = URL.createObjectURL(new Blob([content], { type: "application/octet-stream" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = entry.name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

onMounted(() => {
  dialog.value?.showModal();
  void browse("");
});
</script>

<template>
  <dialog ref="dialog" class="modal memory-panel" aria-labelledby="gateway-memory-title" @close="emit('close')">
    <div class="modal-heading">
      <h2 id="gateway-memory-title">记忆文件</h2>
      <button type="button" class="icon-button" aria-label="关闭记忆文件" @click="dialog?.close()"><X :size="18" /></button>
    </div>
    <button v-if="path" class="button small" :disabled="pending" @click="browse(path.split('/').slice(0, -1).join('/'))">
      上一级
    </button>
    <p v-if="path">{{ path }}</p>
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    <p v-else-if="!entries.length">{{ pending ? "正在加载…" : "暂无记忆文件" }}</p>
    <ul v-if="entries.length" class="memory-entries">
      <li v-for="entry in entries" :key="entry.path">
        <button class="button small" :disabled="pending" @click="open(entry)">
          {{ entry.name }}{{ entry.is_dir ? "/" : "" }}
        </button>
      </li>
    </ul>
    <section v-if="selected" class="memory-detail">
      <div class="modal-heading">
        <strong>{{ selected.entry.name }}</strong>
        <button class="button small" @click="download">下载</button>
      </div>
      <pre v-if="isText(selected.file)">{{ selected.file.content }}</pre>
    </section>
  </dialog>
</template>

<style scoped>
.memory-panel { width: min(640px, calc(100vw - 32px)); }
.memory-panel::backdrop { background: #27213460; backdrop-filter: blur(4px); }
.memory-entries { display: flex; flex-wrap: wrap; gap: 6px; list-style: none; padding: 0; }
.memory-detail pre { max-height: 50vh; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
