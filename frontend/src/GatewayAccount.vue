<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { LogOut, X } from "lucide-vue-next";
import { api } from "./api";
import { loadGoogleSignIn } from "./googleGateway";
import { gatewayUser, signInGateway, signOutGateway } from "./gatewaySession";

const props = defineProps<{ dark?: boolean }>();
const emit = defineEmits<{ close: [] }>();
const dialog = ref<HTMLDialogElement>();
const googleButton = ref<HTMLElement>();
const credential = ref("");
const googleStatus = ref<"loading" | "disabled" | "available" | "unavailable">("loading");
const pending = ref(false);
const error = ref("");
const failedAvatar = ref("");
const profile = computed(() => gatewayUser.value?.googleProfile);
const profileName = computed(() => profile.value?.name || profile.value?.email || "Gateway 账号");
let closed = false;

async function signIn(token: string, provider = "bearer") {
  if (closed || pending.value || !token) return;
  pending.value = true;
  error.value = "";
  try {
    await signInGateway(token, provider);
    credential.value = "";
    dialog.value?.close();
  } catch (problem) {
    error.value = problem instanceof Error ? problem.message : "登录失败";
  } finally {
    pending.value = false;
  }
}

async function configureGoogle() {
  try {
    const options = await api<{ google_client_id: string }>("/v1/auth/options");
    if (closed) return;
    if (!options.google_client_id) {
      googleStatus.value = "disabled";
      return;
    }
    const signInClient = await loadGoogleSignIn();
    if (closed || !googleButton.value) return;
    signInClient.initialize({
      client_id: options.google_client_id,
      callback: ({ credential: token }) => void signIn(token, "google"),
    });
    signInClient.renderButton(googleButton.value, {
      theme: props.dark ? "filled_black" : "outline",
      size: "large",
      shape: "pill",
      width: Math.min(400, googleButton.value.clientWidth || 300).toString(),
    });
    googleStatus.value = "available";
  } catch {
    if (!closed) googleStatus.value = "unavailable";
  }
}

onMounted(() => {
  dialog.value?.showModal();
  void configureGoogle();
});
onUnmounted(() => { closed = true; });
</script>

<template>
  <dialog ref="dialog" class="modal gateway-account" :class="{ 'account-dark': dark }" aria-labelledby="gateway-account-title"
    @cancel.prevent="!pending && dialog?.close()" @close="emit('close')">
    <div class="modal-heading">
      <h2 id="gateway-account-title">{{ gatewayUser ? "个人账号" : "登录 / 注册" }}</h2>
      <button type="button" class="icon-button" aria-label="关闭账号窗口" :disabled="pending" @click="dialog?.close()">
        <X :size="18" />
      </button>
    </div>
    <div v-if="gatewayUser" class="account-signed-in">
      <div class="account-profile">
        <span class="account-avatar" aria-hidden="true">
          <img v-if="profile?.picture && profile.picture !== failedAvatar" :src="profile.picture" alt=""
            referrerpolicy="no-referrer" @error="failedAvatar = profile?.picture || ''" />
          <template v-else>{{ Array.from(profileName)[0] }}</template>
        </span>
        <span><strong>{{ profileName }}</strong><small v-if="profile?.email">{{ profile.email }}</small></span>
      </div>
      <div class="account-session-meta">
        <span>所属租户</span><strong>{{ gatewayUser.tenant_id }}</strong>
      </div>
      <button type="button" class="account-logout" @click="signOutGateway(); dialog?.close()"><LogOut :size="16" /> 退出登录</button>
    </div>
    <div v-show="!gatewayUser">
      <div ref="googleButton" class="google-signin"></div>
      <p v-if="googleStatus === 'available'">首次使用已开通的 Google 账号登录时会自动注册。</p>
      <p v-else-if="googleStatus === 'disabled'">管理员尚未配置 Google 登录和注册。已有 Gateway 凭据可以直接登录。</p>
      <p v-else-if="googleStatus === 'unavailable'">无法加载 Google 登录，请稍后重试。</p>
      <form class="credential-form" @submit.prevent="signIn(credential)">
        <label for="gateway-credential">Gateway 凭据</label>
        <input id="gateway-credential" v-model.trim="credential" type="password" maxlength="8192" autocomplete="off" required />
        <button class="account-submit" :disabled="pending || !credential">{{ pending ? "登录中…" : "登录" }}</button>
      </form>
    </div>
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
  </dialog>
</template>

<style scoped>
.gateway-account {
  --account-bg: #fff;
  --account-text: #151515;
  --account-muted: #575757;
  --account-border: #d8d8d8;
  --account-surface: #f4f4f4;
  color: var(--account-text);
  background: var(--account-bg);
  border: 1px solid var(--account-border);
  border-radius: 22px;
  width: min(420px, calc(100vw - 32px));
  padding: 24px;
  box-shadow: 0 18px 70px #0003;
  font-family: "Geist", "PingFang SC", system-ui, sans-serif;
}
.gateway-account.account-dark {
  color-scheme: dark;
  --account-bg: #191919;
  --account-text: #fafafa;
  --account-muted: #bcbcbc;
  --account-border: #454545;
  --account-surface: #282828;
}
.gateway-account::backdrop { background: #0006; backdrop-filter: blur(4px); }
.gateway-account .modal-heading { margin-bottom: 14px; }
.gateway-account .modal-heading h2 { color: var(--account-text); }
.gateway-account .icon-button { color: var(--account-text); }
.gateway-account .icon-button:hover { background: var(--account-surface); }
.gateway-account p { color: var(--account-muted); margin: 8px 0 0; }
.google-signin { max-width: 100%; overflow: hidden; }
.credential-form { display: grid; gap: 8px; margin-top: 20px; }
.credential-form input, .gateway-account .account-submit {
  box-sizing: border-box;
  width: 100%;
  min-height: 40px;
  border-radius: 22px;
  padding: 8px 14px;
}
.credential-form input {
  color: var(--account-text);
  background: var(--account-bg);
  border: 1px solid var(--account-border);
}
.gateway-account .account-submit { background: var(--account-text); color: var(--account-bg); border: 1px solid transparent; font-weight: 650; }
.gateway-account .account-submit:disabled {
  opacity: 1;
  color: var(--account-muted);
  background: var(--account-surface);
  border-color: var(--account-border);
}
.gateway-account :is(button, input):focus-visible { outline: 2px solid var(--account-text); outline-offset: 2px; }
.account-signed-in { display: grid; gap: 14px; }
.account-profile { display: flex; align-items: center; gap: 12px; padding: 12px; border-radius: 15px; background: var(--account-surface); }
.account-profile > span:last-child { min-width: 0; overflow-wrap: anywhere; }
.account-profile strong, .account-profile small { display: block; }
.account-profile small { color: var(--account-muted); margin-top: 3px; }
.account-avatar { display: grid; place-items: center; flex: 0 0 42px; height: 42px; border-radius: 50%; background: var(--account-bg); overflow: hidden; }
.account-avatar img { width: 100%; height: 100%; object-fit: cover; }
.account-session-meta { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; padding: 0 2px; color: var(--account-muted); font-size: 12px; }
.account-session-meta strong { font-weight: 550; overflow-wrap: anywhere; }
.gateway-account .account-logout { display: flex; align-items: center; justify-content: center; gap: 8px; border: 1px solid var(--account-border); border-radius: 999px; min-height: 38px; font-weight: 600; }
.gateway-account .account-logout:hover { background: var(--account-surface); }
</style>
