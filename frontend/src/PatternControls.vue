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
    <label class="composer-option-row">
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
.pattern-controls { color: inherit; }
.pattern-controls > span { display: block; margin-top: 5px; font-size: 11px; color: var(--atoms-muted, #595959); }
select:disabled { opacity: .6; cursor: default; }
</style>
