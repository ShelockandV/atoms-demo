<script setup lang="ts">
import { computed } from "vue";
import type { ModelConfig } from "./api";
const props = defineProps<{ value: string; config: ModelConfig; disabled: boolean }>();
const emit = defineEmits<{ change: [value: string] }>();
const labels: Record<string, string> = { react: "自主执行", reflection: "复核修复", dag: "分解开发" };
const template = computed(() => props.config.templates?.find((template) => `template:${template.id}` === props.value));
</script>

<template>
  <div class="pattern-controls">
    <label>
      执行方式
      <select
        aria-label="执行方式"
        :value="value"
        :disabled="disabled"
        @change="emit('change', ($event.target as HTMLSelectElement).value)"
      >
        <option v-if="config.learningRouting" value="auto">自动选择</option>
        <option
          v-for="pattern in config.patterns || [{ id: 'react', description: '自主选择工具' }]"
          :key="pattern.id"
          :value="pattern.id"
          :title="pattern.description"
        >{{ labels[pattern.id] || pattern.id }}</option>
        <optgroup v-if="config.templates?.length" label="工作流模板">
          <option v-for="entry in config.templates" :key="entry.id" :value="`template:${entry.id}`">
            {{ entry.id }}
          </option>
        </optgroup>
      </select>
    </label>
    <span>{{ template ? template.description : value === 'auto' ? '参考本项目相似任务的执行结果' : value === 'dag' ? '并行分析后汇总开发' : value === 'reflection' ? '生成后逐项检查，按反馈修复' : '根据执行结果选择下一步' }}</span>
  </div>
</template>

<style scoped>
.pattern-controls { display: flex; align-items: center; flex-wrap: wrap; gap: 4px 10px; margin: 6px 0 10px; color: #737373; font-size: 11px; }
label { display: inline-flex; align-items: center; gap: 6px; }
select { border: 1px solid #e5e5e5; border-radius: 6px; padding: 3px 5px; background: white; color: #525252; font: inherit; cursor: pointer; }
select:disabled { opacity: 0.6; cursor: default; }
</style>
