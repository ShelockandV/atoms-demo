<script setup lang="ts">
import { ref } from "vue";
import { SlidersHorizontal } from "lucide-vue-next";
import PatternControls from "./PatternControls.vue";
import type { Mode, ModelConfig, SkillSummary } from "./api";

const props = defineProps<{
  mode: Mode;
  config: ModelConfig;
  model: string;
  pattern: string;
  skills: SkillSummary[];
  skill: string;
  disabled: boolean;
}>();
const emit = defineEmits<{ model: [value: string]; pattern: [value: string]; skill: [value: string] }>();
const disclosure = ref<HTMLDetailsElement>();
function close(event: KeyboardEvent) {
  if (!disclosure.value?.open) return;
  event.stopPropagation();
  disclosure.value.open = false;
  disclosure.value.querySelector("summary")?.focus();
}
</script>

<template>
  <details ref="disclosure" class="composer-options" @keydown.esc="close">
    <summary aria-label="任务选项" title="任务选项"><SlidersHorizontal :size="17" /></summary>
    <div class="composer-options-panel">
      <label v-if="config.models[mode]?.length" class="composer-option-row">
        模型
        <select aria-label="选择模型" :value="model" :disabled="disabled"
          @change="emit('model', ($event.target as HTMLSelectElement).value)">
          <option v-for="id in config.models[mode]" :key="id" :value="id">{{ id === "auto" ? "自动选择" : id }}</option>
        </select>
      </label>
      <PatternControls v-if="mode === 'build'" :value="pattern" :config="config" :disabled="disabled"
        @change="emit('pattern', $event)" />
      <label v-if="props.skills.length" class="composer-option-row">
        技能
        <select :value="skill" :disabled="disabled" @change="emit('skill', ($event.target as HTMLSelectElement).value)">
          <option value="">无</option>
          <option v-for="item in skills" :key="`${item.name}@${item.version}`" :value="`${item.name}@${item.version}`">
            {{ item.name }}@{{ item.version }}
          </option>
        </select>
      </label>
      <p v-if="!config.models[mode]?.length && mode !== 'build' && !skills.length">当前 Agent 没有可用选项。</p>
    </div>
  </details>
</template>
