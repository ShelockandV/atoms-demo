<script setup lang="ts">
import { computed } from 'vue'
import { Code2, MessageSquare, Image as ImageIcon } from 'lucide-vue-next'
import type { Mode, ModelConfig } from './api'
const props=defineProps<{mode:Mode; model:string; config:ModelConfig; disabled?:boolean}>()
const emit=defineEmits<{mode:[value:Mode]; model:[value:string]}>()
const options=computed(()=>props.config.models[props.mode]||[])
</script>
<template>
<div class="mode-controls">
 <div class="mode-select"><Code2 v-if="mode==='build'" :size="13"/><MessageSquare v-else-if="mode==='chat'" :size="13"/><ImageIcon v-else :size="13"/><select aria-label="输入模式" :value="mode" :disabled="disabled" @change="emit('mode',($event.target as HTMLSelectElement).value as Mode)"><option value="build">构建应用</option><option value="chat">对话问答</option><option value="image">图像生成</option></select></div>
 <span class="mode-separator"></span>
 <select aria-label="选择模型" class="model-select" :value="model" :disabled="disabled" @change="emit('model',($event.target as HTMLSelectElement).value)"><option v-for="id in options" :key="id" :value="id">{{id}}</option></select>
 <span v-if="config.demo" class="demo-label">演示</span>
</div>
</template>
