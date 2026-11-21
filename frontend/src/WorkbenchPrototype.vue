<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { Activity, ArrowLeft, ArrowRight, ArrowUpRight, Check, ChevronDown, Command, Folder, GitBranch, Plus, Search, ShieldCheck, Workflow, X } from "lucide-vue-next";

const variants = ["A", "B", "C", "history"];
const names = ["Agent 会话", "边做边预览", "多 Agent 协作", "会话历史"];
const initial = variants.indexOf(new URLSearchParams(location.search).get("variant") ?? "history");
const variant = ref(Math.max(0, initial));
const tasks = ref([
  { id: "RUN-1842", title: "增加按月消费趋势", project: "个人记账", pattern: "Supervisor", state: "待审批", time: "2 分钟前", description: "研究角色请求读取项目目录，为消费趋势页面确认现有的组件和数据结构。" },
  { id: "RUN-1841", title: "生成交互式预约页面", project: "工作室预约", pattern: "DAG", state: "运行中", time: "8 分钟前", description: "页面结构已生成，正在执行浏览器交互验收。" },
  { id: "RUN-1840", title: "比较三种文档检索策略", project: "知识库研究", pattern: "Research", state: "已完成", time: "24 分钟前", description: "已汇总三种检索策略及对应使用场景。" },
  { id: "RUN-1839", title: "修正移动端导航布局", project: "工作室预约", pattern: "ReAct", state: "失败", time: "46 分钟前", description: "浏览器验收发现导航入口没有响应。" },
  { id: "RUN-1838", title: "归档上季度消费报告", project: "个人记账", pattern: "DAG", state: "已完成", time: "今天 09:12", description: "报告已生成并保存到项目。" },
]);
const selected = ref("RUN-1842");
const query = ref("");
const drawer = ref(false);
const sessionView = ref("对话");
const usage: Record<string, string> = {
  "RUN-1842": "$0.018", "RUN-1841": "$0.042", "RUN-1840": "$0.031",
  "RUN-1839": "$0.026", "RUN-1838": "$0.012",
};
const creating = ref(false);
const draft = ref("");
const notice = ref("");
const followups = ref<Record<string, string[]>>({});
const artifactTab = ref("页面预览");
const roles = [
  { name: "Supervisor", task: "拆解需求与分派", state: "已完成", note: "先检查工作区，再实现趋势图，最后做浏览器验收。" },
  { name: "研究 Agent", task: "检查项目与账单结构", state: "待审批", note: "需要列出项目文件，定位已有账单接口与前端组件。" },
  { name: "构建 Agent", task: "实现月度消费趋势", state: "等待依赖", note: "读取研究结论后实现页面和数据绑定。" },
  { name: "验收 Agent", task: "验证页面交互", state: "等待依赖", note: "构建完成后验证交互和结果展示。" },
];
const roleIndex = ref(1);
const roleRun = computed(() => tasks.value.find(task => task.id === "RUN-1842")!);
const activeRole = computed(() => roles[roleIndex.value]);

function selectRole(index: number) {
  roleIndex.value = index;
  select("RUN-1842");
}
const active = computed(() => tasks.value.find(task => task.id === selected.value));
const filtered = computed(() => tasks.value.filter(task =>
  `${task.title} ${task.project} ${task.id}`.toLowerCase().includes(query.value.trim().toLowerCase())));

function cycle(offset: number) {
  navigate((variant.value + offset + variants.length) % variants.length);
}

function navigate(index: number) {
  variant.value = index;
  query.value = "";
  if (variant.value !== 0) select("RUN-1842");
  updateUrl();
}

function updateUrl() {
  const url = new URL(location.href);
  url.searchParams.set("variant", variants[variant.value]);
  history.replaceState(null, "", url);
}

function keys(event: KeyboardEvent) {
  if ((event.target as HTMLElement | null)?.closest("input, textarea, [contenteditable]")) return;
  if (event.key === "ArrowLeft") cycle(-1);
  if (event.key === "ArrowRight") cycle(1);
  if (event.key === "Escape") { creating.value = false; drawer.value = false; }
}
onMounted(() => window.addEventListener("keydown", keys));
onUnmounted(() => window.removeEventListener("keydown", keys));

function select(id: string) {
  selected.value = id;
  drawer.value = false;
  sessionView.value = "对话";
}

function openSession(id: string) {
  navigate(0);
  select(id);
}

function decide(approved: boolean) {
  if (active.value?.state !== "待审批") return;
  active.value.state = approved ? "运行中" : "已拒绝";
  notice.value = approved ? "已批准 · 运行已恢复" : "已拒绝 · 工具未执行";
}

function followUp() {
  const message = draft.value.trim();
  if (!message || !active.value) return;
  (followups.value[active.value.id] ??= []).push(message);
  draft.value = "";
}

function createRun() {
  if (!draft.value.trim()) return;
  const id = `RUN-${1843 + tasks.value.length - 5}`;
  tasks.value.unshift({ id, title: draft.value.trim(), project: "个人记账", pattern: "DAG", state: "运行中", time: "刚刚", description: "任务已提交，正在生成执行计划。" });
  draft.value = "";
  creating.value = false;
  query.value = "";
  if (variant.value >= 2) navigate(0);
  select(id);
  notice.value = "已创建演示任务";
}

function tone(state: string) {
  return ({ 待审批: "amber", 运行中: "blue", 已完成: "green", 失败: "red", 已拒绝: "red" } as Record<string,string>)[state] ?? "";
}
</script>

<template>
  <div class="workbench-prototype" :class="'view-' + variants[variant]">
    <header class="masthead">
      <a href="/?prototype=workbench" class="brand"><span><Command :size="19" /></span>Atoms</a>
      <div class="breadcrumb"><ChevronDown :size="12" /> 个人工作区</div>
      <div class="mast-right"><span class="demo"><i /> 原型 · 演示数据</span><a href="/" aria-label="返回现有工作台"><ArrowUpRight :size="17" /></a><span class="avatar">A</span></div>
    </header>
    <div class="shell">
      <aside v-if="variant === 0 || variant === 3" class="sidebar">
        <button class="new-session" @click="creating = true"><Plus :size="16" /> 新建会话</button>
        <button :class="{current: variant === 3}" :aria-current="variant === 3 ? 'page' : undefined" @click="navigate(3)"><Activity :size="16" /> 会话历史</button>
        <hr />
        <small>最近会话</small>
        <label class="search sidebar-search"><Search :size="15" /><input v-model="query" placeholder="搜索会话" aria-label="搜索会话" /></label>
        <button v-for="task in filtered" :key="task.id" :class="{current: variant === 0 && selected === task.id}" @click="openSession(task.id)"><Folder :size="15" /> <span>{{ task.title }}<small>{{ task.project }}</small></span></button>
        <a class="back" href="/"><ArrowLeft :size="15" /> 返回当前工作台</a>
      </aside>
      <main>
        <nav v-if="variant === 1 || variant === 2" class="prototype-nav"><button @click="navigate(3)"><ArrowLeft :size="15" /> 会话历史</button></nav>
        <div class="heading">
          <div><div v-if="variant !== 3" class="section-label"><span /> {{ variant === 2 ? 'SUPERVISOR' : 'WORKSPACE' }}</div><h1>{{ names[variant] }}</h1><p>{{ ['在会话中发起任务，查看 Agent 的执行过程和交付结果。','在同一屏里和 Agent 迭代，查看上一版页面与最新运行。','查看任务如何分派、哪些角色正在执行、哪些步骤需要你介入。','查找以前的会话，并回到原来的任务继续。'][variant] }}</p></div>
          <button class="primary" @click="creating = true"><Plus :size="17" /> {{ variant === 3 ? "新建会话" : "新建任务" }}</button>
        </div>
        <section v-if="variant === 3" class="history-panel" aria-label="会话列表">
          <div class="history-search">
            <label class="search"><Search :size="17" /><input v-model="query" type="search" aria-label="搜索会话历史" placeholder="搜索标题、项目或编号" /></label>
            <span>{{ filtered.length }} 个会话</span>
          </div>
          <div class="table-scroll">
            <table>
              <thead><tr><th scope="col">会话</th><th scope="col">Agent</th><th scope="col">任务数</th><th scope="col">费用</th><th scope="col">操作</th></tr></thead>
              <tbody>
                <tr v-for="task in filtered" :key="task.id">
                  <td><button class="task-title" @click="openSession(task.id)">{{ task.title }}<small>{{ task.project }} · {{ task.time }} · {{ task.state }}</small></button></td>
                  <td>{{ task.pattern }}</td>
                  <td>{{ 1 + (followups[task.id]?.length ?? 0) }}</td>
                  <td>{{ usage[task.id] ?? "—" }}</td>
                  <td><button class="history-open" :aria-label="'打开会话：' + task.title" @click="openSession(task.id)">打开 <ArrowUpRight :size="15" /></button></td>
                </tr>
                <tr v-if="!filtered.length"><td colspan="5" class="empty">没有符合条件的会话</td></tr>
              </tbody>
            </table>
          </div>
        </section>
        <section v-else-if="variant !== 2 && active" class="conversation" aria-label="Agent 会话">
          <header class="conversation-header">
            <div><strong>{{ active.title }}</strong><small>{{ active.project }} · {{ active.pattern }}</small></div>
            <span class="status" :class="tone(active.state)">{{ active.state }}</span>
          </header>
          <div v-if="variant === 0" class="session-controls" aria-label="会话视图">
            <div><button v-for="view in ['对话','摘要']" :key="view" :aria-pressed="sessionView === view" @click="sessionView = view">{{ view }}</button></div>
            <div><button :aria-pressed="drawer" @click="drawer = !drawer"><Activity :size="15" /> 执行时间线</button><button @click="variant = 1; artifactTab = '页面预览'; updateUrl()"><Folder :size="15" /> 工作区</button></div>
          </div>
          <section v-if="variant === 0 && sessionView === '摘要'" class="session-summary">
            <h2>任务摘要</h2><p>{{ active.description }}</p>
            <dl><dt>工作流</dt><dd>{{ active.pattern }}</dd><dt>当前状态</dt><dd>{{ active.state }}</dd><dt>累计费用</dt><dd>{{ usage[active.id] ?? "—" }}</dd></dl>
          </section>
          <div v-else class="transcript" aria-live="polite">
            <div class="user-message"><small>你</small><p>{{ active.id === 'RUN-1842' ? '帮我给记账应用加一个月度消费趋势。沿用现有的数据结构，完成后给我看页面效果。' : active.title }}</p></div>
            <div class="agent-message">
              <span class="agent-mark"><Command :size="17" /></span>
              <div>
                <strong>Atoms Agent</strong>
                <p>{{ active.id === 'RUN-1842' ? '我会先检查现有页面与账单数据，再实现趋势图并运行浏览器验收。' : active.description }}</p>
                <details v-if="active.id === 'RUN-1842'" class="plan" open>
                  <summary>执行计划</summary>
                  <ol><li><Check :size="15" /> 已拆解需求</li><li><Search :size="15" /> 检查现有项目</li><li><GitBranch :size="15" /> 实现月度趋势并验收</li></ol>
                </details>
                <section v-if="active.state === '待审批'" class="inline-tool">
                  <div><ShieldCheck :size="17" /><strong>读取项目文件列表</strong><code>LIST_FILES</code></div>
                  <p>我需要查看项目目录，确认可以复用哪些页面与组件。此操作只读。</p>
                  <div class="review-buttons"><button @click="decide(false)">拒绝</button><button class="primary" @click="decide(true)">批准并继续</button></div>
                </section>
                <p v-else-if="active.id === 'RUN-1842'" class="agent-result">{{ active.state === '已拒绝' ? '你拒绝了目录读取。工具未执行。' : '审批已通过，我正在读取项目目录。' }}</p>
                <p v-else-if="active.state === '已完成'" class="agent-result"><Check :size="15" /> 结果已保存到 {{ active.project }}</p>
              </div>
            </div>
            <template v-for="(message, i) in followups[active.id]" :key="i">
              <div class="user-message"><small>你</small><p>{{ message }}</p></div>
              <div class="agent-message"><span class="agent-mark"><Command :size="17" /></span><div><strong>Atoms Agent</strong><p>{{ active.state === '待审批' ? '已收到补充要求。当前工具仍在等待审批。' : '已收到补充要求，并记录到当前会话。' }}</p></div></div>
            </template>
          </div>
          <form class="composer-prototype" @submit.prevent="followUp">
            <textarea v-model="draft" aria-label="给 Agent 发消息" placeholder="给 Agent 发消息，补充要求或发起下一步…" rows="2" />
            <div><span><Workflow :size="15" /> {{ active.pattern }}</span><button class="primary" type="submit" :disabled="!draft.trim()">发送 <ArrowRight :size="16" /></button></div>
          </form>
        </section>
        <section v-else-if="variant === 2" class="mission" aria-label="Agent 协作流程">
          <div class="mission-request"><span class="agent-mark"><Command :size="17" /></span><div><small>你的任务</small><h2>给记账应用增加月度消费趋势</h2><p>沿用现有账单结构，完成后验收页面交互。</p></div></div>
          <div class="role-flow">
            <button v-for="(role, index) in roles" :key="role.name" class="role-step" :class="{selected: index === roleIndex}" :aria-pressed="index === roleIndex" @click="selectRole(index)">
              <span class="role-icon"><component :is="[Workflow, Search, GitBranch, Check][index]" :size="19" /></span>
              <span><strong>{{ role.name }}</strong><small>{{ role.task }}</small></span>
              <span class="status" :class="tone(index === 1 ? roleRun.state : role.state)">{{ index === 1 ? roleRun.state : role.state }}</span>
            </button>
          </div>
          <section class="role-detail" aria-live="polite">
            <h3>{{ activeRole.name }} · {{ activeRole.task }}</h3>
            <p>{{ activeRole.note }}</p>
            <div v-if="roleIndex === 1 && roleRun.state === '待审批'" class="inline-tool">
              <div><ShieldCheck :size="17" /><strong>读取当前项目目录</strong><code>LIST_FILES</code></div>
              <p>只读操作。构建 Agent 将等待此节点的输出。</p>
              <div class="review-buttons"><button @click="decide(false)">拒绝</button><button class="primary" @click="decide(true)">批准并继续</button></div>
            </div>
            <p v-else-if="roleIndex === 1">{{ roleRun.state === '已拒绝' ? '目录读取已拒绝，构建仍在等待依赖。' : '已批准，研究 Agent 正在读取目录。' }}</p>
          </section>
        </section>
      </main>
      <aside v-if="variant === 1" class="artifact" aria-label="项目产物">
        <p v-if="active?.id !== 'RUN-1842'" class="empty">本次任务还没有生成产物</p>
        <template v-else>
        <header><div><Folder :size="16" /> 项目产物</div><span class="status green">上一版 · v12</span></header>
        <div class="artifact-tabs" aria-label="产物视图"><button v-for="tab in ['页面预览', '变更记录']" :key="tab" :class="{selected: tab === artifactTab}" :aria-pressed="tab === artifactTab" @click="artifactTab = tab">{{ tab }}</button></div>
        <div v-if="artifactTab === '页面预览'" class="artifact-window">
          <div class="artifact-url">个人记账 / 账单</div>
          <div class="ledger">
            <small>个人记账</small><h2>本月支出</h2><strong>¥ 3,680.50</strong>
            <div class="ledger-row"><span>餐饮</span><b>¥ 1,220.50</b></div>
            <div class="ledger-row"><span>交通</span><b>¥ 460.00</b></div>
            <div class="ledger-row"><span>日常购物</span><b>¥ 2,000.00</b></div>
          </div>
        </div>
        <ul v-else class="artifact-history"><li><strong>v12 · 已交付</strong><p>按类别展示本月账单</p></li><li><strong>当前任务</strong><p>{{ roleRun.state === '待审批' ? '等待读取项目目录' : roleRun.state === '已拒绝' ? '目录读取已拒绝' : '正在检查现有文件' }}</p></li></ul>
        </template>
      </aside>
      <aside v-if="variant === 0 && drawer && active" class="detail" aria-label="运行详情">
        <div class="detail-top"><span><Workflow :size="16" /> 运行详情</span><button class="icon" aria-label="关闭详情" @click="drawer = false"><X :size="19" /></button></div>
        <div class="run-id">{{ active.id }} <span class="status" :class="tone(active.state)">{{ active.state }}</span></div>
        <h2>{{ active.title }}</h2><p>{{ active.project }} <span>·</span> {{ active.pattern }}</p>
        <section v-if="active.state === '待审批' && variant !== 0" class="review">
          <div><ShieldCheck :size="18" /> 工具调用审批</div><p>{{ active.description }}</p>
          <dl><dt>节点</dt><dd>research.inspect</dd><dt>工具</dt><dd><code>LIST_FILES</code></dd><dt>权限</dt><dd>只读 · 当前项目</dd></dl>
          <div class="review-buttons"><button @click="decide(false)">拒绝</button><button class="primary" @click="decide(true)"><Check :size="15" /> 批准并继续</button></div>
        </section>
        <section v-else-if="variant !== 0" class="summary">{{ active.state === '已拒绝' ? '工具调用已拒绝。' : active.description }}</section>
        <section class="timeline"><h3>执行记录</h3><div><Check :size="15" /><span>任务已提交<small>{{ active.time }}</small></span></div><div><GitBranch :size="15" /><span>工作流已加载<small>{{ active.pattern }}</small></span></div><div v-if="active.id === 'RUN-1842'"><ShieldCheck :size="15" /><span>{{ active.state === '待审批' ? '等待工具审批' : active.state === '已拒绝' ? '工具调用已拒绝' : '工具调用已批准' }}<small>research.inspect</small></span></div></section>
      </aside>
    </div>
    <div v-if="notice" class="toast" role="status"><Check :size="17" /> {{ notice }} <button class="icon" aria-label="关闭通知" @click="notice = ''"><X :size="15" /></button></div>
    <div class="switcher" aria-label="切换设计"><button aria-label="上一个方案" @click="cycle(-1)"><ArrowLeft :size="17" /></button><span>{{ variant === 3 ? names[variant] : variants[variant] + " · " + names[variant] }}</span><button aria-label="下一个方案" @click="cycle(1)"><ArrowRight :size="17" /></button></div>
    <div v-if="creating" class="modal-backdrop" @click.self="creating = false"><form class="modal" @submit.prevent="createRun"><div><h2>{{ variant === 3 ? "新建会话" : "新建任务" }}</h2><button type="button" class="icon" aria-label="取消" @click="creating = false"><X :size="19" /></button></div><label>任务描述<textarea v-model="draft" placeholder="例如：为记账应用加入月度预算" required autofocus /></label><button class="primary" :disabled="!draft.trim()" type="submit">提交任务 <ArrowRight :size="16" /></button></form></div>
  </div>
</template>

<style scoped src="./workbench-prototype.css"></style>
