<script setup lang="ts">
import { ref } from "vue";
import { api } from "./api";

const props = defineProps<{ projectId: string; versionId: string }>();
const emit = defineEmits<{ open: [path: string] }>();
interface Hit {
  path: string;
  chunk: number;
  start: number;
  end: number;
  text: string;
}
const query = ref("");
const hits = ref<Hit[] | null>(null);
const pending = ref(false);
const failure = ref("");

async function search() {
  if (!query.value.trim() || pending.value) return;
  pending.value = true;
  hits.value = null;
  failure.value = "";
  try {
    const params = new URLSearchParams({ query: query.value.trim(), versionId: props.versionId });
    hits.value = (await api<{ hits: Hit[] }>(
      `/projects/${encodeURIComponent(props.projectId)}/documents/search?${params}`,
    )).hits;
  } catch (error) {
    failure.value = error instanceof Error ? error.message : "文档检索失败";
  } finally {
    pending.value = false;
  }
}
</script>

<template>
  <section class="document-search" aria-label="版本文档搜索">
    <form @submit.prevent="search">
      <input v-model="query" aria-label="文档关键词" placeholder="搜索当前查看的已保存版本" maxlength="200" required />
      <button class="button small" :disabled="pending">{{ pending ? "搜索中" : "搜索" }}</button>
    </form>
    <p v-if="failure" role="alert">{{ failure }}</p>
    <p v-else-if="hits?.length === 0">没有找到相关文档片段</p>
    <article v-for="hit in hits" :key="`${hit.path}:${hit.chunk}`">
      <button type="button" class="button small" @click="emit('open', hit.path)">{{ hit.path }}</button>
      <span>字符 {{ hit.start }}–{{ hit.end }}</span>
      <pre>{{ hit.text }}</pre>
    </article>
  </section>
</template>

<style scoped>
.document-search { max-height: 42%; overflow: auto; padding: 10px 14px; border-bottom: 1px solid var(--line); }
form { display: flex; gap: 8px; }
input { flex: 1; min-width: 80px; border: 1px solid var(--line); border-radius: 6px; padding: 5px 8px; }
article { margin-top: 10px; }
article span { margin-left: 8px; color: var(--muted); }
pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: 6px 0; }
</style>
