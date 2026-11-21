<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from "vue";
import { ArrowLeft, Download, FileText, Folder, RefreshCw, X } from "lucide-vue-next";
import { api } from "./api";

const props = defineProps<{
  projectId: string;
  active: boolean;
  revision: string;
  images: { id: string; imageUrl?: string | null }[];
}>();
defineEmits<{ close: [] }>();
const files = ref<Record<string, string>>({});
const directory = ref("");
const selected = ref("");
const busy = ref(false);
const error = ref("");
let request: AbortController | undefined;
let reloadAfterRequest = false;

const entries = computed(() => {
  const prefix = directory.value ? directory.value + "/" : "";
  const folders = new Set<string>();
  const leafFiles: string[] = [];
  for (const path of Object.keys(files.value)) {
    if (!path.startsWith(prefix)) continue;
    const relative = path.slice(prefix.length);
    const slash = relative.indexOf("/");
    if (slash === -1) leafFiles.push(relative);
    else folders.add(relative.slice(0, slash));
  }
  return [
    ...[...folders].sort().map(name => ({ name, folder: true, path: prefix + name })),
    ...leafFiles.sort().map(name => ({ name, folder: false, path: prefix + name })),
  ];
});
async function load() {
  if (busy.value) {
    reloadAfterRequest = true;
    return;
  }
  const current = new AbortController();
  request = current;
  busy.value = true;
  error.value = "";
  try {
    const value = await api<Record<string, string>>(
      `/projects/${encodeURIComponent(props.projectId)}/workspace`, "GET", undefined, { signal: current.signal });
    if (current.signal.aborted) return;
    files.value = value;
    if (!Object.hasOwn(value, selected.value)) selected.value = "";
    if (directory.value && !Object.keys(value).some(path => path.startsWith(directory.value + "/"))) directory.value = "";
  } catch (e) {
    if (!current.signal.aborted) error.value = e instanceof Error ? e.message : "无法加载会话文件";
  } finally {
    if (request === current) {
      busy.value = false;
      if (reloadAfterRequest && !current.signal.aborted) {
        reloadAfterRequest = false;
        void load();
      }
    }
  }
}
function download() {
  const url = URL.createObjectURL(new Blob([files.value[selected.value]], { type: "text/plain;charset=utf-8" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = selected.value.split("/").at(-1) || "file.txt";
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
watch(() => props.revision, load, { immediate: true });
let timer: ReturnType<typeof setInterval> | undefined;
watch(() => props.active, active => {
  clearInterval(timer);
  if (active) timer = setInterval(load, 2500);
}, { immediate: true });
onUnmounted(() => {
  request?.abort();
  clearInterval(timer);
});
</script>

<template>
  <aside class="session-timeline session-workspace" aria-labelledby="session-workspace-title">
    <header>
      <h2 id="session-workspace-title">工作空间</h2>
      <div>
        <button type="button" class="icon-button" aria-label="刷新文件" :disabled="busy" @click="load"><RefreshCw :size="15" /></button>
        <button type="button" class="icon-button" aria-label="关闭工作空间" @click="$emit('close')"><X :size="16" /></button>
      </div>
    </header>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-else-if="busy && !entries.length" role="status">正在加载会话文件…</p>
    <p v-else-if="!entries.length && !images.length">当前会话暂无文件。</p>
    <nav class="session-file-list" aria-label="会话文件">
      <button v-if="directory" type="button" @click="directory = directory.split('/').slice(0, -1).join('/')">
        <ArrowLeft :size="15" /> 返回上一级
      </button>
      <button v-for="entry in entries" :key="entry.path" type="button" :aria-pressed="!entry.folder && entry.path === selected"
        @click="entry.folder ? directory = entry.path : selected = entry.path">
        <Folder v-if="entry.folder" :size="16" /><FileText v-else :size="16" /> {{ entry.name }}
      </button>
    </nav>
    <section v-if="selected" class="session-file-preview" aria-label="文件预览">
      <header><span>{{ selected }}</span><button type="button" class="icon-button" aria-label="下载文件" @click="download"><Download :size="16" /></button></header>
      <pre>{{ files[selected] }}</pre>
    </section>
    <section v-if="!directory && images.length" class="session-image-files" aria-label="生成的图片">
      <a v-for="(image, index) in images" :key="image.id" :href="image.imageUrl || undefined" download>
        <img :src="image.imageUrl || undefined" :alt="'生成图片 ' + (index + 1)" loading="lazy" />
      </a>
    </section>
  </aside>
</template>
