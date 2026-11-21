<script setup lang="ts">
import { computed, nextTick, ref } from "vue";
import { Activity, ArrowUp, ArrowUpRight, AudioLines, BookOpen, Bot, CalendarClock, Check, ChevronUp, CircleHelp, Clock3, CreditCard, FileText, FolderOpen, Layers3, LogOut, Moon, PanelLeft, Paperclip, Plus, Search, Settings, Sun, WandSparkles, X } from "lucide-vue-next";

import GatewayAccount from "./GatewayAccount.vue";
import { gatewayUser, signOutGateway } from "./gatewaySession";

const agents = {
  general: { label: "通用 Agent", description: "问答、分析与日常任务" },
  research: { label: "研究 Agent", description: "拆解问题并汇总资料" },
  build: { label: "构建 Agent", description: "生成并迭代应用" },
};
type Agent = keyof typeof agents;
type Message = { text: string; author: "你" | "Atoms Agent" };
type Session = { id: number; title: string; agent: Agent; messages: Message[]; lastUpdated: Date; usage?: { tokens: number; usd: number } };
const sessions = ref<Session[]>([
  { id: 1, lastUpdated: new Date("2026-11-21T10:24:00+08:00"), usage: { tokens: 4832, usd: 0.024 }, title: "整理产品发布清单", agent: "general", messages: [{ author: "你", text: "整理产品发布前需要核对的事项。" }, { author: "Atoms Agent", text: "可以从发布范围、回归验证和上线回滚三个方面整理。" }] },
  { id: 2, lastUpdated: new Date("2026-11-20T15:08:00+08:00"), usage: { tokens: 12680, usd: 0.061 }, title: "比较知识库检索方案", agent: "research", messages: [{ author: "你", text: "比较关键词检索与向量检索的适用场景。" }] },
  { id: 3, lastUpdated: new Date("2026-11-20T11:42:00+08:00"), usage: { tokens: 8940, usd: 0.042 }, title: "迭代工作室预约页", agent: "build", messages: [{ author: "你", text: "让预约页在手机上更容易填写。" }] },
]);
const nav = [
  { id: "new", label: "新任务", icon: Plus },
  { id: "agents", label: "Agent", icon: Bot },
  { id: "skills", label: "技能", icon: WandSparkles },
  { id: "schedules", label: "定时任务", icon: CalendarClock },
];
const area = ref("new");
const dark = ref(localStorage.getItem("atoms-task-prototype-theme") === "dark");
function toggleTheme() {
  dark.value = !dark.value;
  localStorage.setItem("atoms-task-prototype-theme", dark.value ? "dark" : "light");
}
const accountOpen = ref(false);
const showAccountLogin = ref(false);
const brokenAvatar = ref("");
const accountName = computed(() => gatewayUser.value?.googleProfile?.name || gatewayUser.value?.googleProfile?.email || "个人工作区");
const accountEmail = computed(() => gatewayUser.value?.googleProfile?.email);
const avatar = computed(() => {
  const picture = gatewayUser.value?.googleProfile?.picture;
  return picture === brokenAvatar.value ? undefined : picture;
});
const avatarInitial = computed(() => accountName.value === "个人工作区" ? "A" : Array.from(accountName.value)[0]);
function toggleAccount() {
  if (gatewayUser.value) accountOpen.value = !accountOpen.value;
  else showAccountLogin.value = true;
}
const accountTrigger = ref<HTMLButtonElement>();
const accountItems = [
  { id: "usage", label: "用量与费用", icon: CreditCard },
  { id: "settings", label: "账号设置", icon: Settings },
  { id: "help", label: "使用帮助", icon: BookOpen },
];
function closeAccount(restoreFocus = false) {
  if (!accountOpen.value) return;
  accountOpen.value = false;
  if (restoreFocus) accountTrigger.value?.focus();
}
function accountAction(id: string) {
  closeAccount();
  navigate(id);
}
const view = ref("对话");
const sidePanel = ref<"timeline" | "workspace" | null>(null);
const activeFile = ref("");
const formatEventTime = new Intl.DateTimeFormat("zh-CN", { timeStyle: "medium" });
const workspaceFiles = computed(() => {
  if (!session.value?.usage) return [];
  if (session.value.agent === "build") return [
    { name: "README.md", contents: "# 工作室预约页\n\n移动端预约流程已拆分为选择日期、填写信息和确认预约三个步骤。" },
    { name: "acceptance.md", contents: "# 验收记录\n\n- 手机宽度下表单不溢出\n- 必填项为空时显示提示\n- 提交后显示预约确认" },
  ];
  const reply = session.value.messages.find(message => message.author === "Atoms Agent");
  return reply ? [{ name: "notes.md", contents: "# 会话笔记\n\n" + reply.text }] : [];
});
const selectedFile = computed(() => workspaceFiles.value.find(item => item.name === activeFile.value));
const timeline = computed(() => {
  const run = session.value;
  if (!run) return [];
  const events = [
    { title: "接收任务", details: run.messages[0]?.text, done: true },
    { title: "准备会话上下文", details: "使用当前会话与 " + agents[run.agent].label + " 的配置。", done: true },
    { title: run.usage ? "产出任务结果" : "等待执行", details: run.usage ? "本次运行已记录 Token 用量。" : "演示任务已提交。", done: Boolean(run.usage) },
  ];
  return events.map((event, index) => ({ ...event, at: new Date(run.lastUpdated.getTime() - (events.length - 1 - index) * 1000) }));
});
function togglePanel(panel: "timeline" | "workspace") {
  sidePanel.value = sidePanel.value === panel ? null : panel;
  if (sidePanel.value === "workspace") activeFile.value = workspaceFiles.value[0]?.name ?? "";
}
const sidebar = ref(innerWidth > 740);
const agent = ref<Agent>("general");
const swarm = ref(false);
const draft = ref("");
const attachment = ref<File | null>(null);
const search = ref("");
const selected = ref<number | null>(null);
const composer = ref<HTMLTextAreaElement>();
const file = ref<HTMLInputElement>();
const messagesEnd = ref<HTMLElement>();
const session = computed(() => sessions.value.find(item => item.id === selected.value));
const visibleSessions = computed(() => {
  const query = search.value.trim().toLowerCase();
  return sessions.value.filter(item => [item.title, agents[item.agent].label].some(value => value.toLowerCase().includes(query)))
    .sort((a, b) => b.lastUpdated.getTime() - a.lastUpdated.getTime());
});
const recentSessions = computed(() => [...sessions.value].sort((a, b) => b.lastUpdated.getTime() - a.lastUpdated.getTime()).slice(0, 5));
const formatDate = new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" });
const formatTokens = new Intl.NumberFormat("zh-CN");
const formatCost = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD", minimumFractionDigits: 3 });

function newTask() {
  area.value = "new";
  selected.value = null;
  view.value = "对话";
  sidePanel.value = null;
  draft.value = "";
  clearAttachment();
  if (innerWidth <= 740) sidebar.value = false;
  void nextTick(() => composer.value?.focus());
}

function navigate(id: string) {
  if (id === "new") newTask();
  else {
    area.value = id;
    if (innerWidth <= 740) sidebar.value = false;
  }
}

function openSession(item: Session) {
  selected.value = item.id;
  agent.value = item.agent;
  area.value = "new";
  view.value = "对话";
  sidePanel.value = null;
  draft.value = "";
  clearAttachment();
  if (innerWidth <= 740) sidebar.value = false;
}

function clearAttachment() {
  attachment.value = null;
  if (file.value) file.value.value = "";
}

function send() {
  const text = draft.value.trim();
  if (!text) return;
  if (!session.value) {
    const id = Date.now();
    sessions.value.unshift({ id, title: text.slice(0, 26), agent: agent.value, messages: [], lastUpdated: new Date() });
    selected.value = id;
  }
  if (session.value) {
    session.value.lastUpdated = new Date();
    session.value.agent = agent.value;
  }
  session.value?.messages.push(
    { author: "你", text: text + (attachment.value ? "\n附件 · " + attachment.value.name : "") },
    { author: "Atoms Agent", text: swarm.value ? "已收到任务。Coordinator 会拆分步骤，再分派给对应的 Agent。" : "已收到任务。我会先确认目标，然后开始处理。" },
  );
  draft.value = "";
  clearAttachment();
  void nextTick(() => messagesEnd.value?.scrollIntoView({ block: "end" }));
}

function composerKeys(event: KeyboardEvent) {
  if (event.key !== "Enter" || event.shiftKey || event.isComposing || event.keyCode === 229) return;
  event.preventDefault();
  send();
}
</script>

<template>
  <div class="task-home" :class="{ 'with-sidebar': sidebar, 'task-dark': dark }" @click="closeAccount()" @keydown.esc="closeAccount(true)">
    <aside v-if="sidebar" class="task-nav">
      <div class="task-brand"><span><AudioLines :size="21" /></span><strong>Atoms</strong></div>
      <nav aria-label="工作台">
        <button v-for="item in nav" :key="item.id" :aria-current="area === item.id && !session ? 'page' : undefined" @click="navigate(item.id)">
          <component :is="item.icon" :size="17" /> {{ item.label }}
        </button>
      </nav>
      <div class="recents"><div><span>最近会话</span><button aria-label="会话历史" @click="navigate('history')"><Clock3 :size="17" /></button></div>
        <button v-for="item in recentSessions" :key="item.id" :aria-current="area === 'new' && selected === item.id ? 'page' : undefined" @click="openSession(item)">
          <span class="recent-dot" :data-agent="item.agent" /> <span>{{ item.title }}</span>
        </button>
      </div>
      <footer class="task-account" @click.stop>
        <div v-if="accountOpen && gatewayUser" id="task-account-panel" class="task-account-panel" aria-label="个人账号">
          <div class="task-account-identity"><span class="task-avatar" aria-hidden="true"><img v-if="avatar" :src="avatar" alt="" referrerpolicy="no-referrer" @error="brokenAvatar = avatar || ''" /><template v-else>{{ avatarInitial }}</template></span><span><strong>{{ accountName }}</strong><small v-if="accountEmail">{{ accountEmail }}</small></span></div>
          <div class="task-account-actions">
            <button v-for="item in accountItems" :key="item.id" @click="accountAction(item.id)"><component :is="item.icon" :size="16" /> {{ item.label }}</button>
          </div>
          <button class="task-account-logout" @click="signOutGateway(); closeAccount(true)"><LogOut :size="16" /> 退出登录</button>
        </div>
        <button ref="accountTrigger" class="task-account-trigger" :aria-expanded="gatewayUser ? accountOpen : undefined" :aria-controls="accountOpen ? 'task-account-panel' : undefined" @click="toggleAccount">
          <span class="task-avatar" aria-hidden="true"><img v-if="avatar" :src="avatar" alt="" referrerpolicy="no-referrer" @error="brokenAvatar = avatar || ''" /><template v-else>{{ avatarInitial }}</template></span>
          <span><strong>{{ gatewayUser ? accountName : '登录 / 注册' }}</strong><small>{{ accountEmail || '个人工作区' }}</small></span>
          <ChevronUp v-if="gatewayUser" :size="15" />
        </button>
        <button class="task-theme-toggle" :aria-label="dark ? '切换到浅色模式' : '切换到深色模式'" @click="toggleTheme">
          <Sun v-if="dark" :size="18" /><Moon v-else :size="18" />
        </button>
      </footer>
    </aside>
    <main class="task-main">
      <header class="task-top">
        <button :aria-label="sidebar ? '收起侧栏' : '展开侧栏'" @click="sidebar = !sidebar"><PanelLeft :size="19" /></button>
        <strong>{{ area === 'new' ? session?.title || '新任务' : area === 'history' ? '会话历史' : (nav.find(item => item.id === area) || accountItems.find(item => item.id === area))?.label }}</strong>
        <label v-if="area === 'new'" class="agent-select"><Bot :size="16" /><select v-model="agent" aria-label="选择 Agent"><option v-for="(item, key) in agents" :key="key" :value="key">{{ item.label }}</option></select></label>
        <a v-else href="/" class="back-atoms">返回正式工作台</a>
      </header>
      <template v-if="area === 'new'">
        <div class="task-session-layout" :class="{ 'has-side-panel': sidePanel }">
          <div class="task-conversation">
        <div class="task-views" aria-label="会话视图">
          <button v-for="item in ['对话','摘要']" :key="item" :aria-pressed="view === item" @click="view = item">{{ item }}</button>
          <div v-if="session" class="task-panel-controls">
            <button :aria-pressed="sidePanel === 'timeline'" @click="togglePanel('timeline')"><Activity :size="16" /> 时间线</button>
            <button :aria-pressed="sidePanel === 'workspace'" @click="togglePanel('workspace')"><FolderOpen :size="16" /> 工作空间</button>
          </div>
        </div>
        <div v-if="view === '摘要'" class="task-utility"><h1>任务摘要</h1><p>{{ session ? session.title + ' · ' + agents[agent].label : '发送任务后，此处将显示摘要。' }}</p></div>
        <div v-else class="task-stage" :class="{ 'has-thread': session }">
          <div v-if="session" class="task-thread" aria-live="polite">
            <article v-for="(message, index) in session.messages" :key="index" :class="{ 'task-user': message.author === '你' }"><small>{{ message.author }}</small><p>{{ message.text }}</p></article>
            <div ref="messagesEnd" />
          </div>
          <div v-else class="task-welcome"><span class="task-emblem"><AudioLines :size="30" /></span><h1>你想完成什么？</h1><p>提出问题、研究资料，或让 Agent 帮你构建应用。</p></div>
          <form class="task-composer" @submit.prevent="send">
            <textarea ref="composer" v-model="draft" maxlength="10000" aria-label="输入任务" placeholder="描述你想让 Agent 做的事…" rows="3" @keydown="composerKeys" />
            <div v-if="attachment" class="file-chip">{{ attachment.name }}<button type="button" aria-label="移除附件" @click="clearAttachment"><X :size="14" /></button></div>
            <div class="composer-actions">
              <input ref="file" type="file" hidden @change="attachment = ($event.target as HTMLInputElement).files?.[0] ?? null" />
              <button type="button" aria-label="添加附件" @click="file?.click()"><Paperclip :size="17" /></button>
              <button type="button" :aria-pressed="swarm" @click="swarm = !swarm"><Layers3 :size="17" /> 多 Agent 协作</button>
              <button type="submit" class="task-send" aria-label="发送任务" :disabled="!draft.trim()"><ArrowUp :size="20" /></button>
            </div>
          </form>
        </div>
          </div>
          <aside v-if="session && sidePanel" class="task-side-panel" :aria-label="sidePanel === 'timeline' ? '执行时间线' : '工作空间'">
            <header><h2>{{ sidePanel === 'timeline' ? '执行时间线' : '工作空间' }}</h2><button aria-label="关闭面板" @click="sidePanel = null"><X :size="17" /></button></header>
            <ol v-if="sidePanel === 'timeline'" class="task-events">
              <li v-for="event in timeline" :key="event.title">
                <span class="task-event-icon" :class="{complete: event.done}"><Check v-if="event.done" :size="14" /><Clock3 v-else :size="14" /></span>
                <div><strong>{{ event.title }}</strong> <time :datetime="event.at.toISOString()">{{ formatEventTime.format(event.at) }}</time>
                  <details v-if="event.details"><summary>查看详情</summary><p>{{ event.details }}</p></details>
                </div>
              </li>
            </ol>
            <div v-else class="task-files">
              <p v-if="!workspaceFiles.length">当前会话暂无产物。</p>
              <nav v-else aria-label="会话文件">
                <button v-for="item in workspaceFiles" :key="item.name" :aria-pressed="activeFile === item.name" @click="activeFile = item.name"><FileText :size="16" /> {{ item.name }}</button>
              </nav>
              <pre v-if="selectedFile" aria-label="文件预览">{{ selectedFile.contents }}</pre>
            </div>
          </aside>
        </div>
      </template>
      <section v-else class="task-utility" :class="{ 'task-history': area === 'history' }">
        <template v-if="area === 'history'">
          <h1>会话历史</h1>
          <label class="task-search"><Search :size="16" /><input v-model="search" type="search" aria-label="搜索会话历史" placeholder="搜索会话或 Agent" /></label>
          <div class="task-history-scroll" role="region" aria-label="会话用量列表" tabindex="0">
            <table>
              <thead><tr><th scope="col">会话</th><th scope="col">Agent</th><th scope="col">任务数</th><th scope="col">用量与费用</th><th scope="col">操作</th></tr></thead>
              <tbody>
                <tr v-for="item in visibleSessions" :key="item.id">
                  <td><button class="session-name" @click="openSession(item)">{{ item.title }}</button><time :datetime="item.lastUpdated.toISOString()">{{ formatDate.format(item.lastUpdated) }}</time></td>
                  <td><span class="history-agent"><span class="recent-dot" :data-agent="item.agent" />{{ agents[item.agent].label }}</span></td>
                  <td>{{ item.messages.filter(message => message.author === "你").length }}</td>
                  <td v-if="item.usage"><strong>{{ formatCost.format(item.usage.usd) }}</strong><small>{{ formatTokens.format(item.usage.tokens) }} tokens</small></td>
                  <td v-else>暂无计量</td>
                  <td><button class="history-open" :aria-label="'打开会话：' + item.title" @click="openSession(item)">打开 <ArrowUpRight :size="14" /></button></td>
                </tr>
                <tr v-if="!visibleSessions.length"><td colspan="5">没有符合条件的会话。</td></tr>
              </tbody>
            </table>
            <div class="history-count">显示 {{ visibleSessions.length }} / {{ sessions.length }} 个会话 · 演示数据</div>
          </div>
        </template>
        <template v-else-if="area === 'agents'"><h1>选择 Agent</h1><button v-for="(item, key) in agents" :key="key" class="task-record" @click="agent = key; newTask()">{{ item.label }}<small>{{ item.description }}</small></button></template>
        <template v-else-if="area === 'usage'"><h1>用量与费用</h1><p>演示账号暂无计费记录。</p></template>
        <template v-else-if="area === 'settings'"><h1>账号设置</h1><p>{{ accountEmail ? "当前登录邮箱 " + accountEmail : "当前会话无已验证邮箱。" }}</p></template>
        <template v-else-if="area === 'help'"><h1>使用帮助</h1><p>从新任务发起会话，也可以从最近会话继续。</p></template>
        <template v-else><h1>{{ area === 'skills' ? '技能' : '定时任务' }}</h1><p><CircleHelp :size="16" /> 此入口在原型中演示导航。</p></template>
      </section>
    </main>
    <GatewayAccount v-if="showAccountLogin" :dark="dark" @close="showAccountLogin = false" />
  </div>
</template>

<style scoped src="./task-home-prototype.css"></style>
