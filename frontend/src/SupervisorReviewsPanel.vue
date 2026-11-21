<script setup lang="ts">
import { onMounted, ref } from "vue";
import { X } from "lucide-vue-next";
import { api } from "./api";

interface Review {
  run_id: string;
  prompt: string;
  plan: string;
  version: number;
  approval_id: string;
}

defineEmits<{ close: [] }>();
const dialog = ref<HTMLDialogElement>();
const credential = ref("");
const reviews = ref<Review[]>();
const pending = ref(false);
const error = ref("");

async function perform(review?: Review, approved?: boolean) {
  if (!credential.value || pending.value) return;
  pending.value = true;
  error.value = "";
  const options = { headers: { Authorization: `Bearer ${credential.value}` } };
  try {
    if (review) await api(`/supervisor-reviews/${review.run_id}`, "POST", {
      approvalId: review.approval_id, approved,
    }, options);
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : "无法提交审批";
  }
  try {
    reviews.value = (await api<{ reviews: Review[] }>("/supervisor-reviews", "GET", undefined, options)).reviews;
  } catch (failure) {
    reviews.value = undefined;
    if (!error.value) error.value = failure instanceof Error ? failure.message : "无法读取审核";
  } finally {
    pending.value = false;
  }
}

onMounted(() => dialog.value?.showModal());
</script>

<template>
  <dialog ref="dialog" class="modal" aria-labelledby="supervisor-review-heading" @close="$emit('close')">
    <div class="modal-heading">
      <h2 id="supervisor-review-heading">Supervisor 审核</h2>
      <button class="icon-button" aria-label="关闭审核" @click="dialog?.close()"><X :size="18" /></button>
    </div>
    <form class="run-controls" @submit.prevent="perform()">
      <label>审批人凭据 <input v-model.trim="credential" type="password" autocomplete="off"
        :disabled="pending" @input="reviews = undefined" /></label>
      <button class="button small" :disabled="pending || !credential">读取待审计划</button>
    </form>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="reviews?.length === 0">暂无待审计划</p>
    <article v-for="review in reviews" :key="review.approval_id">
      <h3>{{ review.prompt }} · 第 {{ review.version }} 版</h3>
      <p style="white-space: pre-wrap">{{ review.plan }}</p>
      <div class="run-controls">
        <button class="button small primary" :disabled="pending" @click="perform(review, true)">批准该版本</button>
        <button class="button small" :disabled="pending" @click="perform(review, false)">拒绝该版本</button>
      </div>
    </article>
  </dialog>
</template>
