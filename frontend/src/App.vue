<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import {
  Activity,
  AudioLines,
  Bot,
  BookOpen,
  ArrowUp,
  ArrowUpRight,
  CalendarClock,
  LogOut,
  Moon,
  Sun,
  WandSparkles,
  ClipboardCheck,
  CreditCard,
  Files as FilesIcon,
  ShieldCheck,
  Plus,
  Search,
  PanelLeftClose,
  PanelLeftOpen,
  Sparkles,
  ChevronDown,
  Code2,
  Eye,
  Monitor,
  Smartphone,
  RotateCcw,
  Pause,
  Download,
  History,
  X,
  Check,
  LoaderCircle,
  CircleAlert,
  Play,
  Square,
  FolderOpen,
  ArrowLeft,
  Copy,
  Globe,
  Settings2,
  Command,
  MessageSquare,
  FileCode2,
  CheckCheck,
  Image as ImageIcon,
} from "lucide-vue-next";
import {
  api,
  type Project,
  type Detail,
  type Files,
  type Run,
  type Version,
  type Mode,
  type ModelConfig,
} from "./api";
import { canPreview, previewDocument } from "./preview";
import ComposerOptions from "./ComposerOptions.vue";
import SessionWorkspace from "./SessionWorkspace.vue";
import AgentTrace from "./AgentTrace.vue";
import RunTimeline from "./RunTimeline.vue";
import SchedulePanel from "./SchedulePanel.vue";
import GatewayAccount from "./GatewayAccount.vue";
import GatewayMemoryPanel from "./GatewayMemoryPanel.vue";
import { gatewayUser, signOutGateway } from "./gatewaySession";
import SupervisorReviewsPanel from "./SupervisorReviewsPanel.vue";
import DaemonApprovalsPanel from "./DaemonApprovalsPanel.vue";
import DocumentSearch from "./DocumentSearch.vue";
import SwarmInput from "./SwarmInput.vue";
import SwarmBoard from "./SwarmBoard.vue";
import { randomId } from "./randomId";
import { onComposerKeydown } from "./composer";
const showGatewayAccount = ref(false);
const accountOpen = ref(false);
const accountAnchor = ref<HTMLButtonElement>();
const showAccountUsage = ref(false);
const showAccountSettings = ref(false);
const showSkills = ref(false);
const darkTheme = ref(localStorage.getItem("atoms-theme") === "dark");
const avatarFailed = ref(false);
const accountName = computed(() => gatewayUser.value?.googleProfile?.name
  || gatewayUser.value?.googleProfile?.email || (gatewayUser.value ? "Gateway 账号" : "登录 / 注册"));
const accountEmail = computed(() => gatewayUser.value?.googleProfile?.email);
const tokenFormat = new Intl.NumberFormat("zh-CN");
const accountUsage = computed(() => projects.value.reduce((sum, item) => sum + (item.totalTokens ?? 0), 0));
const accountCost = computed(() => {
  const costs = projects.value.flatMap((item) => item.costUsd == null ? [] : [Number(item.costUsd)]);
  return costs.length ? costs.reduce((sum, item) => sum + item, 0) : null;
});
watch(gatewayUser, (user) => {
  avatarFailed.value = false;
  if (!user) accountOpen.value = false;
});
function toggleTheme() {
  darkTheme.value = !darkTheme.value;
  localStorage.setItem("atoms-theme", darkTheme.value ? "dark" : "light");
}
function closeAccount(restoreFocus = false) {
  if (!accountOpen.value) return;
  accountOpen.value = false;
  if (restoreFocus) accountAnchor.value?.focus();
}
const showGatewayMemory = ref(false);
const projects = ref<Project[]>([]),
  project = ref<Detail | null>(null);
const config = ref<ModelConfig>({
    model: "",
    configured: false,
    models: { build: [], chat: [], image: [], research: [] },
    defaults: { build: "", chat: "", image: "", research: "" },
  }),
  connected = ref(false);
const mode = ref<Mode>("build"),
  selectedModel = ref("");
const selectedPattern = ref("react");
const showSchedules = ref(false);
const showDaemonApprovals = ref(false);
const showSupervisorReviews = ref(false);
const showDocumentSearch = ref(false);
const selectedSkill = ref("");
const availableSkills = computed(() => {
  const pattern = mode.value === "build" ? selectedPattern.value : "react";
  if (mode.value === "image" || mode.value === "research" || pattern.startsWith("template:")) return [];
  return (config.value.skills ?? []).filter((skill) =>
    !(pattern === "dag" && skill.requires_role)
    && (!skill.requires_tools.length || (mode.value === "build" && ["react", "auto"].includes(pattern))),
  );
});
const activeSkill = computed(() =>
  availableSkills.value.find((skill) => `${skill.name}@${skill.version}` === selectedSkill.value),
);
function selectPattern(value: string, persist = true) {
  const known = (config.value.patterns || [{ id: "react" }]).some((p) => p.id === value)
    || (value === "auto" && config.value.learningRouting)
    || config.value.templates?.some((template) => `template:${template.id}` === value);
  if (!known) return;
  selectedPattern.value = value;
  if (persist) localStorage.setItem("atoms-pattern-override", value);
}
const modeLabels: Record<Mode, string> = {
  build: "构建应用",
  chat: "对话问答",
  research: "资料研究",
  image: "图像生成",
};
const placeholder = computed(() =>
  mode.value === "research"
    ? "输入要研究的问题，例如查找项目文档中的接口约束…"
    : mode.value === "chat"
    ? "直接提问，或一起讨论你的需求…"
    : mode.value === "image"
      ? "描述画面内容、风格、构图和色彩…"
      : project.value?.currentVersion
        ? "描述哪里有问题、报错或想修改什么，我会结合当前代码和项目历史继续修改…"
        : "告诉 Atoms 团队你的想法，剩下的交给我们…",
);
const generatedImages = computed(
  () => project.value?.messages.filter((m) => m.imageUrl) || [],
);
function selectModel(value: string) {
  selectedModel.value = value;
  localStorage.setItem("atoms-model-" + mode.value, value);
}
function selectMode(value: Mode) {
  mode.value = value;
  localStorage.setItem("atoms-mode", value);
  const saved = localStorage.getItem("atoms-model-" + value);
  selectedModel.value =
    saved && config.value.models[value].includes(saved)
      ? saved
      : config.value.models[value].includes("auto") ? "auto" : config.value.defaults[value];
  if (value === "image" && project.value) view.value = "images";
  else if (view.value === "images") view.value = "preview";
}
const prompt = ref(""),
  sidebar = ref(window.innerWidth > 850),
  view = ref<"preview" | "code" | "images">("preview");
const browsingSessions = ref(false);
const sessionsLoading = ref(false);
const sessionsError = ref("");
const historyQuery = ref("");
const sessionView = ref<"conversation" | "summary">("conversation");
const showTimeline = ref(false);
const showWorkspace = ref(false);
const showVersionEditor = ref(false);
const currency = new Intl.NumberFormat("zh-CN", { style: "currency", currency: "USD", maximumFractionDigits: 6 });
const dateTime = new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" });
const mobile = ref(false),
  history = ref(false),
  help = ref(false),
  error = ref(""),
  toast = ref(""),
  creating = ref(false);
const selectedFile = ref<string>("index.html"),
  files = ref<Files>({});
const preview = ref(""),
  token = ref(""),
  frame = ref<HTMLIFrameElement | null>(null),
  runtimeError = ref(""),
  stopped = ref(false);
const selectedVersion = ref<string | null>(null),
  saving = ref(false),
  controlling = ref(false);
const chatMessages = ref<HTMLElement | null>(null);
const agentDialog = ref<HTMLDialogElement>();
const agentDescriptions: Record<Mode, string> = {
  build: "构建应用、修改代码并查看预览",
  chat: "提问、讨论和整理思路",
  research: "查找资料并生成带引用的报告",
  image: "根据描述生成图片",
};
watch(
  () => [project.value?.id, project.value?.messages.length, browsingSessions.value, sessionView.value] as const,
  async () => {
    await nextTick();
    if (chatMessages.value)
      chatMessages.value.scrollTop = chatMessages.value.scrollHeight;
  },
);
let events: EventSource | null = null,
  poll: ReturnType<typeof setInterval> | null = null,
  toastTimer: ReturnType<typeof setTimeout> | null = null;
let loadSequence = 0,
  watchSequence = 0,
  disposed = false;
const stages = [
  { key: "QUEUED", label: "准备任务" },
  { key: "PLANNING", label: "整理需求与计划" },
  { key: "DECOMPOSING", label: "分解需求与架构任务" },
  { key: "REASONING", label: "选择下一步动作" },
  { key: "INSPECTING", label: "查看项目源码" },
  { key: "GENERATING", label: "编写应用代码" },
  { key: "REPAIRING", label: "根据反馈修复源码" },
  { key: "VALIDATING", label: "校验源码与隔离验收" },
  { key: "REVIEWING", label: "复核需求是否落实" },
];
const active = computed(
  () =>
    !!project.value?.run &&
    !["SUCCEEDED", "FAILED", "CANCELLED", "TIMEOUT", "PAUSED", "AWAITING_APPROVAL"].includes(project.value.run.status),
);
const paused = computed(() => project.value?.run?.status === "PAUSED");
const awaitingApproval = computed(() => project.value?.run?.status === "AWAITING_APPROVAL");
const busy = computed(
  () =>
    active.value ||
    paused.value ||
    awaitingApproval.value ||
    creating.value ||
    saving.value ||
    controlling.value,
);
const dirty = computed(() => {
  const v = project.value?.versions.find((v) => v.id === selectedVersion.value);
  return !!v && JSON.stringify(v.files) !== JSON.stringify(files.value);
});
const sourceNames = computed(() => Object.keys(files.value).sort());
const browserPreview = computed(() => canPreview(files.value));
const fileLanguage = computed(
  () =>
    ({
      java: "Java",
      xml: "XML",
      yml: "YAML",
      yaml: "YAML",
      js: "JavaScript",
      ts: "TypeScript",
      py: "Python",
      go: "Go",
      md: "Markdown",
      json: "JSON",
      sql: "SQL",
      css: "CSS",
      html: "HTML",
      properties: "Properties",
    })[selectedFile.value.split(".").pop() || ""] || "Text",
);
function loadFiles(version: Version) {
  files.value = { ...version.files };
  if (!(selectedFile.value in files.value))
    selectedFile.value =
      sourceNames.value.find((n) => n.endsWith(".java")) ||
      sourceNames.value[0] ||
      "";
  runPreview();
}
function beforeExport(e: MouseEvent) {
  if (dirty.value) {
    e.preventDefault();
    error.value = "当前编辑尚未保存，请先保存，再导出源码。";
  }
}
const recentProjects = computed(() => projects.value.slice(0, 5));
const historySessions = computed(() => {
  const query = historyQuery.value.trim().toLowerCase();
  return projects.value.filter((p) => [p.title, p.latestModel, p.latestPattern].some((value) => value?.toLowerCase().includes(query)) || !query);
});
async function showSessions() {
  browsingSessions.value = true;
  if (window.innerWidth <= 850) sidebar.value = false;
  await loadSessions();
}
async function loadSessions() {
  if (sessionsLoading.value) return;
  sessionsLoading.value = true;
  sessionsError.value = "";
  try {
    await refreshList();
  } catch (e) {
    sessionsError.value = e instanceof Error ? e.message : "会话加载失败";
  } finally {
    sessionsLoading.value = false;
  }
}
function reloadWorkbench() { window.location.reload(); }
function notify(text: string) {
  toast.value = text;
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (toast.value = ""), 3500);
}
function report(e: unknown) {
  error.value = e instanceof Error ? e.message : "操作失败，请重试";
}
function disconnect() {
  watchSequence++;
  events?.close();
  events = null;
  if (poll) clearInterval(poll);
  poll = null;
}
async function refreshList() {
  projects.value = await api<Project[]>("/projects");
}
function runPreview() {
  runtimeError.value = "";
  if (!browserPreview.value) {
    preview.value = "";
    stopped.value = false;
    if (selectedVersion.value && view.value === "preview") view.value = "code";
    return;
  }
  preview.value = "";
  try {
    token.value = randomId();
    preview.value = previewDocument(files.value, token.value);
    stopped.value = false;
  } catch (e) {
    runtimeError.value = e instanceof Error ? e.message : "预览失败";
  }
}
function chooseVersion(v: Version) {
  if (
    dirty.value &&
    !window.confirm("当前代码尚未保存，切换版本会放弃修改。继续吗？")
  )
    return;
  selectedVersion.value = v.id;
  loadFiles(v);
}
async function openProject(id: string, force = false) {
  if (!force && project.value?.id === id && browsingSessions.value) {
    browsingSessions.value = false;
    error.value = "";
    if (window.innerWidth <= 850) sidebar.value = false;
    return;
  }
  if (
    !force &&
    dirty.value &&
    !window.confirm("当前代码尚未保存，切换项目会放弃修改。继续吗？")
  )
    return;
  const seq = ++loadSequence;
  disconnect();
  error.value = "";
  try {
    const detail = await api<Detail>("/projects/" + id);
    if (seq !== loadSequence || disposed) return;
    if (!force || project.value?.id !== id) {
      sessionView.value = "conversation";
      showTimeline.value = false;
      showWorkspace.value = false;
      showVersionEditor.value = false;
    }
    if (!force || !browsingSessions.value) browsingSessions.value = false;
    if (!force && window.innerWidth <= 850) sidebar.value = false;
    project.value = detail;
    if (mode.value === "image") view.value = "images";
    localStorage.setItem("atoms-current", id);
    const v = detail.versions.find((v) => v.id === detail.currentVersion);
    if (v) {
      selectedVersion.value = v.id;
      loadFiles(v);
    } else {
      selectedVersion.value = null;
      preview.value = "";
      files.value = {};
    }
    if (active.value && detail.run) watchRun(detail.run.id, id);
  } catch (e) {
    report(e);
  }
}
function home() {
  if (
    dirty.value &&
    !window.confirm("代码尚未保存，返回首页会放弃修改。继续吗？")
  )
    return;
  ++loadSequence;
  disconnect();
  project.value = null;
  files.value = {};
  selectedVersion.value = null;
  browsingSessions.value = false;
  if (window.innerWidth <= 850) sidebar.value = false;
  localStorage.removeItem("atoms-current");
  prompt.value = "";
  error.value = "";
}
async function submit() {
  if (!prompt.value.trim() || busy.value) return;
  if (!connected.value) {
    error.value = "后端尚未连接，请先启动 Java 服务";
    return;
  }
  if (dirty.value) {
    error.value = "请先保存代码修改，再发起生成";
    return;
  }
  creating.value = true;
  error.value = "";
  try {
    let id = project.value?.id;
    if (!id) {
      const p = await api<Project>("/projects", "POST", {
        title: prompt.value.trim().slice(0, 28),
      });
      id = p.id;
      await openProject(id, true);
      if (project.value?.id !== id) return;
      await refreshList();
    }
    const text = prompt.value.trim();
    const template = mode.value === "build" && selectedPattern.value.startsWith("template:")
      ? selectedPattern.value.slice("template:".length)
      : undefined;
    const run = await api<Run>(`/projects/${id}/runs`, "POST", {
      prompt: text,
      baseVersion: project.value?.currentVersion ?? null,
      mode: mode.value,
      model: selectedModel.value,
      pattern: mode.value === "build" && !template ? selectedPattern.value : undefined,
      template,
      requireTemplate: Boolean(template),
      skill: activeSkill.value ? selectedSkill.value : undefined,
    });
    prompt.value = "";
    if (project.value?.id === id) {
      project.value.messages.push({
        id: randomId(),
        role: "user",
        content: text,
        mode: mode.value,
        model: selectedModel.value,
        createdAt: new Date().toISOString(),
      });
      project.value.run = run;
      if (!showWorkspace.value && !showVersionEditor.value && window.matchMedia("(min-width: 961px)").matches) {
        showTimeline.value = true;
      }
      if (run.status !== "AWAITING_APPROVAL") watchRun(run.id, id);
    }
  } catch (e) {
    report(e);
  } finally {
    creating.value = false;
  }
}
function watchRun(rid: string, pid: string) {
  disconnect();
  const seq = watchSequence;
  let finishing = false;
  async function receive(run: Run) {
    if (seq !== watchSequence || project.value?.id !== pid || disposed) return;
    project.value.run = run;
    if (["SUCCEEDED", "FAILED", "CANCELLED", "TIMEOUT", "PAUSED", "AWAITING_APPROVAL"].includes(run.status) && !finishing) {
      finishing = true;
      disconnect();
      await openProject(pid, true);
      if (run.status === "FAILED") error.value = run.error || "任务失败";
      if (run.status === "SUCCEEDED") {
        if (run.mode === "image") view.value = "images";
        else if (run.mode === "build")
          view.value = browserPreview.value ? "preview" : "code";
      }
      await refreshList();
    }
  }
  events = new EventSource(`/api/runs/${rid}/events`);
  events.addEventListener("status", (event) => {
    void receive(JSON.parse((event as MessageEvent).data));
  });
  // Polling is also the recovery path when a proxy buffers SSE or the connection drops.
  poll = setInterval(async () => {
    try {
      await receive(await api<Run>("/runs/" + rid));
    } catch (e) {
      report(e);
    }
  }, 2500);
}
interface PlanReview {
  version: number;
  approvalId: string;
  decision: string;
  revisions: { version: number; plan_text: string; feedback: string; created_at: string }[];
}
const planFeedback = ref("");
const planReview = ref<PlanReview>();
const showPlanHistory = ref(false);

watch(() => [project.value?.run?.id, project.value?.run?.planReviewVersion] as const,
  async ([id, version], [previousId]) => {
    if (id !== previousId) {
      planReview.value = undefined;
      planFeedback.value = "";
      showPlanHistory.value = false;
    }
    if (!id || !version) return;
    try {
      const review = await api<PlanReview>(`/runs/${id}/plan-review`);
      if (project.value?.run?.id === id && project.value.run.planReviewVersion === version)
        planReview.value = review;
    } catch (e) {
      if (project.value?.run?.id === id) report(e);
    }
  });

watch(() => project.value?.run?.supervisorReview ? project.value.run.id : undefined, (id, _, cleanup) => {
  if (!id) return;
  const timer = window.setInterval(async () => {
    try {
      const updated = await api<Run>(`/runs/${id}`);
      if (project.value?.run?.id !== id) return;
      project.value.run = updated;
      if (!updated.supervisorReview) {
        window.clearInterval(timer);
        if (active.value) watchRun(id, project.value.id);
        else await openProject(project.value.id, true);
      }
    } catch (e) {
      if (project.value?.run?.id === id) report(e);
    }
  }, 2500);
  cleanup(() => window.clearInterval(timer));
});

async function revisePlan() {
  const run = project.value?.run;
  const feedback = planFeedback.value.trim();
  if (!run?.planReviewVersion || !feedback || controlling.value) return;
  controlling.value = true;
  error.value = "";
  try {
    const review = await api<PlanReview>(`/runs/${run.id}/plan-review`, "POST", {
      version: run.planReviewVersion, feedback,
    });
    if (project.value?.run?.id !== run.id) return;
    const updated = await api<Run>(`/runs/${run.id}`);
    if (project.value?.run?.id !== run.id) return;
    planReview.value = review;
    project.value.run = updated;
    planFeedback.value = "";
  } catch (e) {
    report(e);
    if (project.value?.run?.id === run.id) {
      try {
        const updated = await api<Run>(`/runs/${run.id}`);
        if (project.value?.run?.id === run.id) project.value.run = updated;
      } catch {
        // Keep the original feedback error visible.
      }
    }
  } finally {
    controlling.value = false;
  }
}

async function controlRun(action: "pause" | "resume" | "cancel" | "approve" | "reject") {
  const run = project.value?.run,
    pid = project.value?.id;
  if (!run || !pid || controlling.value) return;
  controlling.value = true;
  error.value = "";
  try {
    const approvalId = project.value?.run?.approvalId;
    const approvalQuery = approvalId ? `&approvalId=${encodeURIComponent(approvalId)}` : "";
    const endpoint = action === "approve" || action === "reject"
      ? `approval?approved=${action === "approve"}${approvalQuery}` : action;
    const updated = await api<Run>(`/runs/${run.id}/${endpoint}`, "POST");
    if (project.value?.id !== pid || project.value.run?.id !== run.id) return;
    project.value.run = updated;
    if (["FAILED", "CANCELLED", "TIMEOUT", "SUCCEEDED"].includes(updated.status)) {
      disconnect();
      await openProject(pid, true);
      if (updated.status === "FAILED" || updated.status === "TIMEOUT")
        error.value = updated.error || (updated.status === "TIMEOUT" ? "任务已超时" : "任务失败");
      if (updated.status === "CANCELLED") notify("任务已终止");
      await refreshList();
    } else if (updated.status === "PAUSED") disconnect();
    else watchRun(updated.id, pid);
  } catch (e) {
    report(e);
  } finally {
    controlling.value = false;
  }
}
function composerKeydown(event: KeyboardEvent) {
  onComposerKeydown(event, () => {
    void submit();
  });
}
async function save() {
  if (!project.value || busy.value) return;
  saving.value = true;
  error.value = "";
  try {
    await api(`/projects/${project.value.id}/versions`, "POST", {
      files: files.value,
      baseVersion: project.value.currentVersion,
    });
    await openProject(project.value.id, true);
    await refreshList();
    notify("已保存为新版本");
  } catch (e) {
    report(e);
  } finally {
    saving.value = false;
  }
}
async function restore(v: Version) {
  if (!project.value || busy.value) return;
  if (dirty.value && !window.confirm("恢复版本会放弃尚未保存的编辑，继续吗？"))
    return;
  saving.value = true;
  try {
    await api(`/projects/${project.value.id}/restore`, "POST", {
      versionId: v.id,
      baseVersion: project.value.currentVersion,
    });
    await openProject(project.value.id, true);
    await refreshList();
    history.value = false;
    notify("已恢复，并创建新版本");
  } catch (e) {
    report(e);
  } finally {
    saving.value = false;
  }
}
async function copyCode() {
  try {
    await navigator.clipboard.writeText(files.value[selectedFile.value] || "");
    notify("代码已复制");
  } catch {
    notify("复制失败，请手动选择代码");
  }
}
function example(text: string) {
  selectMode("build");
  prompt.value = text;
}
function onPreviewMessage(e: MessageEvent) {
  if (
    e.source === frame.value?.contentWindow &&
    e.data?.type === "preview-error" &&
    e.data.token === token.value &&
    typeof e.data.message === "string"
  )
    runtimeError.value = e.data.message.slice(0, 500);
}
function shortcut(e: KeyboardEvent) {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "n") {
    e.preventDefault();
    home();
  }
}
function beforeUnload(e: BeforeUnloadEvent) {
  if (dirty.value) {
    e.preventDefault();
    e.returnValue = "";
  }
}
onMounted(async () => {
  window.addEventListener("keydown", shortcut);
  window.addEventListener("message", onPreviewMessage);
  window.addEventListener("beforeunload", beforeUnload);
  try {
    config.value = await api<ModelConfig>("/config");
    selectPattern(config.value.learningRouting ? "auto" : "react", false);
    const patternOverride = localStorage.getItem("atoms-pattern-override");
    if (patternOverride) selectPattern(patternOverride, false);
    const savedMode = localStorage.getItem("atoms-mode");
    selectMode(
      savedMode === "chat" || savedMode === "image" || savedMode === "research" ? savedMode : "build",
    );
    connected.value = true;
    await loadSessions();
  } catch (e) {
    sessionsError.value = e instanceof Error ? e.message : "工作台连接失败";
    report(e);
  }
});
onUnmounted(() => {
  window.removeEventListener("keydown", shortcut);
  disposed = true;
  disconnect();
  if (toastTimer) clearTimeout(toastTimer);
  window.removeEventListener("message", onPreviewMessage);
  window.removeEventListener("beforeunload", beforeUnload);
});
</script>

<template>
  <div
    class="app-shell atoms-task-app"
    :class="{ 'sidebar-hidden': !sidebar, 'is-workspace': !!project && !browsingSessions, 'atoms-dark': darkTheme }"
    @click="closeAccount()"
    @keydown.esc="closeAccount(true)"
  >
    <aside class="sidebar" v-if="sidebar">
      <div class="brand-row">
        <button class="brand" @click="home" aria-label="Atoms 首页">
          <span class="brand-mark" aria-hidden="true"><AudioLines :size="21" /></span
          ><span>Atoms</span></button
        ><button
          class="icon-button subtle"
          @click="sidebar = false"
          aria-label="收起侧栏"
        >
          <PanelLeftClose :size="17" />
        </button>
      </div>
      <button class="new-project" :aria-current="!project && !browsingSessions ? 'page' : undefined" @click="home">
        <Plus :size="17" /> 新建会话
        <span class="shortcut"><Command :size="10" /> N</span>
      </button>
      <button class="session-nav" @click="agentDialog?.showModal()"><Bot :size="16" /> Agent</button>
      <button class="session-nav atoms-skills-nav" @click="showSkills = true"><WandSparkles :size="16" /> 技能</button>
      <button class="session-nav atoms-schedules-nav" @click="showSchedules = true"><CalendarClock :size="16" /> 定时任务</button>
      <div class="atoms-recents-heading">
        <span>最近会话</span>
        <button class="icon-button" aria-label="会话历史" title="会话历史" :aria-pressed="browsingSessions" @click="showSessions"><History :size="17" /></button>
      </div>
      <nav class="project-list" aria-label="最近会话">
        <button v-for="p in recentProjects" :key="p.id" :class="{ selected: !browsingSessions && project?.id === p.id }"
          @click="openProject(p.id)">
          <MessageSquare :size="16" /><span>{{ p.title }}</span>
        </button>
      </nav>
      <div class="sidebar-bottom">
        <div class="atoms-account-wrap" @click.stop>
          <div v-if="accountOpen" id="atoms-account-menu" class="atoms-account-menu" aria-label="个人账号">
            <div class="atoms-account-identity"><strong>{{ accountName }}</strong><small v-if="accountEmail">{{ accountEmail }}</small></div>
            <button v-if="!gatewayUser" @click="showGatewayAccount = true; closeAccount()">使用 Google 或 Gateway 登录</button>
            <button v-if="gatewayUser" @click="showGatewayMemory = true; closeAccount()"><FilesIcon :size="15" /> 记忆文件</button>
            <button @click="showSupervisorReviews = true; closeAccount()"><ShieldCheck :size="15" /> Supervisor 审核</button>
            <button @click="showDaemonApprovals = true; closeAccount()"><ClipboardCheck :size="15" /> 审批</button>
            <button v-if="gatewayUser" @click="showAccountUsage = true; closeAccount()"><CreditCard :size="15" /> 用量与费用</button>
            <button v-if="gatewayUser" @click="showAccountSettings = true; closeAccount()"><Settings2 :size="15" /> 账号设置</button>
            <button @click="help = true; closeAccount()"><BookOpen :size="15" /> 使用帮助</button>
            <button v-if="gatewayUser" class="atoms-signout" @click="signOutGateway(); accountAnchor?.focus()"><LogOut :size="15" /> 退出登录</button>
          </div>
          <button ref="accountAnchor" class="account" :aria-expanded="accountOpen"
            :aria-controls="accountOpen ? 'atoms-account-menu' : undefined" @click="accountOpen = !accountOpen">
            <span class="avatar"><img v-if="gatewayUser?.googleProfile?.picture && !avatarFailed" :src="gatewayUser.googleProfile.picture" alt="" referrerpolicy="no-referrer" @error="avatarFailed = true" /><span v-else aria-hidden="true">{{ gatewayUser ? Array.from(accountName)[0] : 'A' }}</span></span>
            <span><strong>{{ accountName }}</strong><small>{{ accountEmail || "个人工作区" }}</small></span>
          </button>
          <button class="atoms-theme-toggle icon-button" :aria-label="darkTheme ? '切换到浅色模式' : '切换到深色模式'" @click="toggleTheme">
            <Sun v-if="darkTheme" :size="17" /><Moon v-else :size="17" />
          </button>
        </div>
      </div>
    </aside>
    <main class="main-area">
      <header class="topbar">
        <div v-if="!project || browsingSessions" class="breadcrumbs">
          <button
            v-if="!sidebar"
            class="icon-button"
            @click="sidebar = true"
            aria-label="展开侧栏"
          >
            <PanelLeftOpen :size="18" /></button
          ><span class="top-label">{{ browsingSessions ? "会话历史" : "新任务" }}</span>
        </div>
        <div v-else class="breadcrumbs">
          <button
            v-if="!sidebar"
            class="icon-button"
            @click="sidebar = true"
            aria-label="展开侧栏"
          >
            <PanelLeftOpen :size="18" /></button
          ><button class="icon-button" @click="showSessions" aria-label="返回会话历史">
            <ArrowLeft :size="16" /></button
          ><span class="project-title">{{ project.title }}</span
          ><ChevronDown :size="13" />
        </div>
        <div class="top-actions">
          <button v-if="!sidebar" class="button small" @click="showGatewayAccount = true">{{ gatewayUser ? "账号" : "登录 / 注册" }}</button>
          <label v-if="!browsingSessions" class="task-agent-picker"><Bot :size="16" />
            <select  :value="mode" :disabled="busy" aria-label="选择 Agent"
              @change="selectMode(($event.target as HTMLSelectElement).value as Mode)">
              <option v-for="(label, value) in modeLabels" :key="value" :value="value">{{ label }}</option>
            </select>
          </label>
          <span class="mode-indicator" :class="{ offline: !connected }"
            ><i></i
            >{{
              !connected
                ? "服务未连接"
                : config.configured ? "模型已配置" : "模型未配置"
            }}</span
          ><button
            v-if="project && !browsingSessions"
            class="button small"
            :disabled="!project.currentVersion"
            @click="history = true"
          >
            <History :size="14" /> 版本历史</button
          ><a
            v-if="project?.currentVersion && !browsingSessions"
            class="button small primary"
            :href="`/api/projects/${project.id}/export${selectedVersion ? '?versionId=' + encodeURIComponent(selectedVersion) : ''}`"
            @click="beforeExport"
            ><Download :size="14" /> 导出源码</a
          >
        </div>
      </header>
      <section v-if="browsingSessions" class="session-history" aria-labelledby="session-history-title">
        <div class="session-history-heading">
          <h1 id="session-history-title">会话历史</h1>
          <button class="button" :disabled="sessionsLoading" @click="connected ? loadSessions() : reloadWorkbench()">
            <RotateCcw :size="15" /> 刷新
          </button>
        </div>
        <label class="session-search"><Search :size="17" />
          <input v-model="historyQuery" type="search" aria-label="搜索会话历史" placeholder="搜索会话标题、模型或工作流" />
        </label>
        <p v-if="sessionsError || error" class="error-banner" role="alert">{{ sessionsError || error }}</p>
        <p v-if="sessionsLoading && !projects.length" role="status">正在加载会话…</p>
        <div v-else class="session-table-wrap" :aria-busy="sessionsLoading">
          <table class="session-table">
            <thead><tr><th scope="col">会话</th><th scope="col">Agent</th><th scope="col">任务数</th><th scope="col">用量与费用</th><th scope="col">操作</th></tr></thead>
            <tbody>
              <tr v-for="item in historySessions" :key="item.id">
                <td><button class="session-link" @click="openProject(item.id)">{{ item.title }}</button>
                  <time :datetime="item.updatedAt">{{ dateTime.format(new Date(item.updatedAt)) }}</time></td>
                <td>{{ item.latestPattern && item.latestMode === "build" ? item.latestPattern : item.latestMode ? modeLabels[item.latestMode] : "—" }}
                  <small v-if="item.latestModel">{{ item.latestModel }}</small></td>
                <td>{{ item.runCount ?? "—" }}</td>
                <td>{{ item.costUsd == null && item.totalTokens == null ? "暂无计量" : item.costUsd == null ? "—" : currency.format(item.costUsd) }}
                  <small v-if="item.totalTokens != null">{{ tokenFormat.format(item.totalTokens) }} tokens</small></td>
                <td><button class="button small" :aria-label="'打开会话 ' + item.title" @click="openProject(item.id)">打开 <ArrowUpRight :size="14" /></button></td>
              </tr>
              <tr v-if="!historySessions.length && !sessionsError">
                <td colspan="5" class="session-empty">{{ historyQuery ? "没有符合条件的会话" : "还没有会话。新建会话开始使用 Agent。" }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
      <div v-else-if="!project" class="home-scroll">
        <section class="hero">
          <span class="atoms-task-emblem" aria-hidden="true"><AudioLines :size="30" /></span>
          <h1>你想完成什么？</h1>
          <p class="hero-description">提出问题、研究资料，或让 Agent 帮你构建应用。</p>
          <form class="prompt-box" @submit.prevent="submit">
            <textarea
              v-model="prompt"
              aria-label="描述你的应用想法"
              :placeholder="placeholder"
              maxlength="10000"
              @keydown="composerKeydown"
            ></textarea>
            <div class="prompt-bottom">
              <ComposerOptions :mode="mode" :config="config" :model="selectedModel" :pattern="selectedPattern"
                :skills="availableSkills" :skill="activeSkill ? selectedSkill : ''" :disabled="busy"
                @model="selectModel" @pattern="selectPattern" @skill="selectedSkill = $event"
              /><button
                class="start-button"
                :aria-label="
                  mode === 'chat'
                    ? '发送问题'
                    : mode === 'image'
                      ? '生成图片'
                      : mode === 'research' ? '开始研究' : '开始构建'
                "
                :disabled="!prompt.trim() || busy || !connected"
              >
                <LoaderCircle
                  v-if="creating"
                  class="spin"
                  :size="17"
                /><ArrowUp v-else :size="18" />
              </button>
            </div>
          </form>
          <div v-if="error" class="error-banner">
            <CircleAlert :size="15" />{{ error
            }}<button @click="error = ''" aria-label="关闭错误">
              <X :size="14" />
            </button>
          </div>
        </section>
      </div>
      <div v-else class="workbench" :class="{ 'conversation-only': !showWorkspace && !showTimeline && !showVersionEditor, 'timeline-open': showTimeline || showWorkspace }">
        <section class="chat-panel">
          <div class="session-toolbar" aria-label="会话视图">
            <div>
              <button :aria-pressed="sessionView === 'conversation'" @click="sessionView = 'conversation'">对话</button>
              <button :aria-pressed="sessionView === 'summary'" @click="sessionView = 'summary'">摘要</button>
            </div>
            <div>
              <button :aria-pressed="showTimeline" @click="showTimeline = !showTimeline; if (showTimeline) { showWorkspace = false; showVersionEditor = false }"><Activity :size="15" /> 执行时间线</button>
              <button :aria-pressed="showWorkspace" @click="showWorkspace = !showWorkspace; if (showWorkspace) { showTimeline = false; showVersionEditor = false }"><FolderOpen :size="15" /> 工作空间</button>
              <button v-if="project.currentVersion" :aria-pressed="showVersionEditor" @click="showVersionEditor = !showVersionEditor; if (showVersionEditor) { showWorkspace = false; showTimeline = false }"><Code2 :size="15" /> 版本编辑</button>
            </div>
          </div>
          <section v-if="sessionView === 'summary'" class="session-summary">
            <h2>任务摘要</h2>
            <template v-if="project.run">
              <p>{{ project.run.model }} · {{ project.run.pattern || modeLabels[project.run.mode] }}</p>
              <p v-if="project.run.usage">{{ project.run.usage.modelCalls }} 次模型调用 · {{ project.run.usage.promptTokens + project.run.usage.completionTokens }} tokens</p>
              <p v-if="project.run.error" role="alert">{{ project.run.error }}</p>
              <p class="summary-plan">{{ project.run.plan || "本次任务暂无计划摘要。" }}</p>
            </template>
            <p v-else>会话尚未发起任务。</p>
          </section>
          <div v-show="sessionView === 'conversation'" ref="chatMessages" class="chat-messages">
            <div v-if="!project.messages.length" class="chat-intro">
              <div class="assistant-avatar"><Sparkles :size="18" /></div>
              <h3>从这里，开始创造。</h3>
              <p>
                描述你的需求，我会把它变成可交互的应用。你也可以随时提出修改。
              </p>
            </div>
            <div
              v-for="m in project.messages"
              :key="m.id"
              class="message"
              :class="m.role"
            >
              <div v-if="m.role === 'assistant'" class="message-name">
                <span class="assistant-avatar tiny"
                  ><Sparkles :size="11" /></span
                >Atoms
                <span>{{ m.model || modeLabels[m.mode || "build"] }}</span>
              </div>
              <p>{{ m.content }}</p>
              <div v-if="m.imageUrl" class="message-image">
                <button @click="showWorkspace = true; showTimeline = false; showVersionEditor = false">
                  <img
                    :src="m.imageUrl"
                    alt="根据需求生成的图片"
                    loading="lazy"
                  /></button
                ><a :href="m.imageUrl" download="atoms-image.png"
                  >下载原图 <Download :size="12"
                /></a>
              </div>
            </div>
            <div v-if="active || paused || awaitingApproval" class="generation-card">
              <div class="generation-title">
                <Pause v-if="paused || awaitingApproval" :size="16" /><LoaderCircle
                  v-else
                  class="spin"
                  :size="16"
                />{{
                  awaitingApproval ? (project.run?.approvalTool ? `工具等待审批 · ${project.run.approvalNode ? project.run.approvalNode + " · " : ""}${project.run.approvalTool}` : "任务等待审批") : paused
                    ? "任务已暂停"
                    : project.run?.status === "ANSWERING"
                      ? "正在思考并回答"
                      : project.run?.status === "IMAGING"
                        ? "正在生成图片"
                        : project.run?.status === "REPAIRING"
                          ? "正在修复校验问题"
                          : "正在构建你的想法"
                }}
              </div>
              <div
                v-for="(s, i) in project.run?.mode === 'build' ? stages.filter((stage) => stage.key === project?.run?.status) : []"
                :key="s.key"
                class="generation-step"
                :class="{ current: project.run?.status === s.key }"
              >
                <Check
                  v-if="
                    stages.findIndex((x) => x.key === project?.run?.status) > i
                  "
                  :size="13"
                /><LoaderCircle
                  v-else-if="project.run?.status === s.key"
                  class="spin"
                  :size="13"
                /><span v-else class="step-dot"></span>{{ s.label }}
              </div>
              <p v-if="awaitingApproval && project.run?.approvalReason" class="plan-text">
                {{ project.run.approvalReason }}
              </p>
              <template v-if="awaitingApproval && project.run?.planReviewVersion && !project.run.approvalTool">
                <p class="plan-text">当前计划 · 第 {{ project.run.planReviewVersion }} 版</p>
                <p class="plan-text" style="white-space: pre-wrap">{{ project.run.plan }}</p>
                <form class="run-controls" @submit.prevent="revisePlan">
                  <textarea v-model="planFeedback" aria-label="计划修改意见" maxlength="2000"
                    placeholder="填写希望 Agent 修改的地方" rows="3" :disabled="controlling"></textarea>
                  <button class="button small" :disabled="controlling || !planFeedback.trim()">提交反馈</button>
                </form>
              </template>
              <div v-if="awaitingApproval" class="run-controls">
                <span v-if="project.run?.supervisorReview">等待独立审批人决定</span>
                <template v-else>
                  <button class="button small primary" :disabled="controlling" @click="controlRun('approve')">
                    {{ project.run?.planReviewVersion && !project.run.approvalTool ? `批准第 ${project.run.planReviewVersion} 版并执行` : "批准并运行" }}
                  </button>
                  <button class="button small" :disabled="controlling" @click="controlRun('reject')">拒绝任务</button>
                </template>
              </div>
              <p v-else-if="paused" class="plan-text">已完成的步骤会保留，恢复时重试未完成的模型请求。</p>
              <SwarmInput v-if="project.run?.swarmAcceptingInput" :key="project.run.id" :run-id="project.run.id" />
              <p v-if="project.run?.plan && !(awaitingApproval && project.run.planReviewVersion && !project.run.approvalTool)" class="plan-text">
                {{ project.run.plan }}
              </p>
            </div>
            <div v-if="project.run?.planReviewVersion" class="plan-text">
                <button class="button small" @click="showPlanHistory = !showPlanHistory">
                  {{ showPlanHistory ? "收起审核历史" : "查看审核历史" }}
                </button>
                <ul v-if="showPlanHistory && planReview">
                  <li v-for="revision in planReview.revisions" :key="revision.version">
                    <strong>第 {{ revision.version }} 版</strong>
                    <p v-if="revision.feedback">{{ revision.feedback }}</p>
                    <p style="white-space: pre-wrap">{{ revision.plan_text }}</p>
                  </li>
                </ul>
            </div>
            <p v-if="project.run?.skill" class="plan-text">本次技能 {{ project.run.skill }}</p>
            <div v-if="error" class="error-banner">
              <CircleAlert :size="14" />{{ error }}
            </div>
          </div>
          <form v-show="sessionView === 'conversation'" class="chat-composer" @submit.prevent="submit">
            <textarea
              v-model="prompt"
              :disabled="busy"
              aria-label="提出修改需求"
              :placeholder="paused ? '任务已暂停…' : awaitingApproval ? '等待审批…' : (active || creating) ? '等待 Agent 完成…' : placeholder"
              @keydown="composerKeydown"
              maxlength="10000"
            ></textarea>
            <div class="composer-foot">
              <ComposerOptions :mode="mode" :config="config" :model="selectedModel" :pattern="selectedPattern"
                :skills="availableSkills" :skill="activeSkill ? selectedSkill : ''" :disabled="busy"
                @model="selectModel" @pattern="selectPattern" @skill="selectedSkill = $event"
              />
              <div v-if="active || paused || awaitingApproval" class="composer-run-actions" aria-label="任务控制">
                <button v-if="active || paused" type="button" class="composer-run-pause"
                  :aria-label="paused ? '继续任务' : '暂停任务'" :title="paused ? '继续任务' : '暂停任务'"
                  :disabled="controlling" @click="controlRun(paused ? 'resume' : 'pause')">
                  <Play v-if="paused" :size="17" /><Pause v-else :size="17" />
                </button>
                <button type="button" class="composer-run-stop" aria-label="终止任务" title="终止任务"
                  :disabled="controlling" @click="controlRun('cancel')"><Square :size="17" /></button>
              </div>
              <button v-else type="submit" class="send-button" :disabled="busy || !prompt.trim()" aria-label="发送需求">
                <LoaderCircle v-if="creating" class="spin" :size="17" /><ArrowUp v-else :size="18" />
              </button>
            </div>
          </form>
          <div class="chat-disclaimer">
            Enter 发送 · Shift+Enter 换行 ·
            {{
              mode === "image"
                  ? "图片单独计费 · 每次生成一张"
                  : mode === "chat"
                    ? "对话不会修改代码或创建版本"
                    : mode === "research" ? "研究结果以报告所列资料为准"
                      : "AI 可能出错，请在预览中验证实际功能"
            }}
          </div>
        </section>
            <aside v-if="showTimeline" class="session-timeline" aria-label="执行时间线">
            <header><h2>执行时间线</h2><button class="icon-button" aria-label="关闭时间线" @click="showTimeline = false"><X :size="16" /></button></header>
            <p v-if="!project.run">暂无执行事件。</p>
            <RunTimeline v-else :run="project.run">
              <template v-if="project.run.swarmBoard?.length || project.run.steps?.length || project.run.nodes?.length" #steps>
                <SwarmBoard v-if="project.run.swarmBoard?.length" :teams="project.run.swarmBoard"
                  :run-status="project.run.status" />
                <AgentTrace v-if="project.run.steps?.length || project.run.nodes?.length"
                  :steps="project.run.steps || []"
                  :status="project.run.status"
                  :pattern="project.run.mode === 'research' ? 'research' : project.run.pattern"
                  :nodes="project.run.nodes"
                />
              </template>
            </RunTimeline>
            </aside>
        <SessionWorkspace v-if="showWorkspace" :key="project.id" :project-id="project.id"
          :active="active" :revision="`${project.currentVersion ?? ''}:${project.run?.status ?? ''}`"
          :images="generatedImages" @close="showWorkspace = false" />
        <section v-show="showVersionEditor" class="output-panel">
          <div class="preview-toolbar">
            <div class="view-switch">
              <button
                :class="{ active: view === 'preview' }"
                @click="view = 'preview'"
              >
                <Eye :size="14" />预览</button
              ><button
                :class="{ active: view === 'images' }"
                @click="view = 'images'"
              >
                <ImageIcon :size="14" />图片</button
              ><button
                :class="{ active: view === 'code' }"
                @click="view = 'code'"
              >
                <Code2 :size="14" />代码
              </button>
            </div>
            <div class="preview-tools">
              <span
                v-if="selectedVersion"
                class="version-badge"
                @click="history = true"
                >v{{
                  project.versions.find((v) => v.id === selectedVersion)
                    ?.number
                }}<span v-if="dirty"> · 未保存</span></span
              ><template v-if="view === 'preview'"
                ><button
                  class="icon-button"
                  :class="{ chosen: !mobile }"
                  @click="mobile = false"
                  aria-label="桌面预览"
                >
                  <Monitor :size="16" /></button
                ><button
                  class="icon-button"
                  :class="{ chosen: mobile }"
                  @click="mobile = true"
                  aria-label="手机预览"
                >
                  <Smartphone :size="16" /></button
                ><span class="tool-divider"></span
                ><button
                  class="icon-button"
                  @click="runPreview"
                  :disabled="!selectedVersion || !browserPreview"
                  aria-label="重新运行预览"
                >
                  <RotateCcw :size="15" /></button
                ><button
                  class="icon-button"
                  @click="stopped = true"
                  :disabled="!preview || stopped || !browserPreview"
                  aria-label="停止预览"
                >
                  <Square :size="13" /></button></template
              ><template v-else-if="view === 'code'"
                ><span
                  v-if="selectedVersion && !browserPreview"
                  class="source-only-label"
                  >源码工程</span
                ><button
                  v-if="browserPreview"
                  class="button small"
                  @click="
                    runPreview();
                    view = 'preview';
                  "
                  :disabled="!selectedVersion"
                >
                  <Play :size="13" />运行</button
                ><button
                  class="button small primary"
                  @click="save"
                  :disabled="busy || !dirty"
                >
                  <CheckCheck :size="13" />保存
                </button></template
              >
            </div>
          </div>
          <div v-if="view === 'preview'" class="preview-area">
            <div class="preview-address" v-if="selectedVersion">
              <div class="browser-dots"><i></i><i></i><i></i></div>
              <span
                ><Globe :size="11" />{{ project.title }}
                <span class="address-muted">/ 预览</span></span
              ><span class="isolated-badge">隔离运行</span>
            </div>
            <div class="iframe-shell" :class="{ mobile }">
              <iframe
                v-if="preview && !stopped"
                ref="frame"
                :srcdoc="preview"
                sandbox="allow-scripts allow-forms"
                title="生成应用预览"
                referrerpolicy="no-referrer"
              ></iframe>
              <div v-else class="preview-empty">
                <div class="empty-orbit"><Sparkles :size="28" /></div>
                <h2>
                  {{
                    selectedVersion && !browserPreview
                      ? "源码工程已就绪"
                      : stopped
                        ? "预览已停止"
                        : "你的作品，即将在这里诞生"
                  }}
                </h2>
                <p>
                  {{
                    selectedVersion && !browserPreview
                      ? "此工程需要在本地编译或启动。请查看源码文件，或点击右上角导出源码。"
                      : stopped
                        ? "点击重新运行，继续体验你的应用。"
                        : "在左侧选择构建生成应用；也可以切换对话问答或图像生成。"
                  }}
                </p>
                <button
                  v-if="selectedVersion && !browserPreview"
                  class="button"
                  @click="view = 'code'"
                >
                  <Code2 :size="14" />查看源码</button
                ><span v-if="active" class="building-pill"
                  ><LoaderCircle class="spin" :size="13" /> 正在构建中…</span
                ><button v-if="stopped" class="button" @click="runPreview">
                  <Play :size="13" />重新运行
                </button>
              </div>
            </div>
          </div>
          <div v-else-if="view === 'images'" class="image-gallery">
            <div class="gallery-heading">
              <span class="section-kicker">YOUR IMAGE COLLECTION</span>
              <h2>让灵感，有画面。</h2>
              <p>生成的图片保存在当前项目中，刷新后仍可查看和下载。</p>
            </div>
            <div v-if="!generatedImages.length" class="gallery-empty">
              <ImageIcon :size="35" />
              <h3>还没有图片</h3>
              <p>选择“图像生成”，描述你想要的画面。</p>
              <button class="button primary" @click="selectMode('image')">
                开始生成图片
              </button>
            </div>
            <div v-else class="gallery-grid">
              <article
                v-for="m in generatedImages.slice().reverse()"
                :key="m.id"
                class="generated-image-card"
              >
                <img :src="m.imageUrl!" alt="生成图片" loading="lazy" />
                <div>
                  <strong>{{ m.model }}</strong
                  ><small>{{
                    new Date(m.createdAt).toLocaleString("zh-CN")
                  }}</small
                  ><a
                    class="button small"
                    :href="m.imageUrl!"
                    download="atoms-image.png"
                    ><Download :size="13" />下载原图</a
                  >
                </div>
              </article>
            </div>
          </div>
          <div v-else class="code-area">
            <button v-if="selectedVersion || project.currentVersion" type="button" class="button small"
              :aria-expanded="showDocumentSearch" @click="showDocumentSearch = !showDocumentSearch">
              <Search :size="14" />搜索版本文档
            </button>
            <DocumentSearch v-if="showDocumentSearch && (selectedVersion || project.currentVersion)"
              :key="selectedVersion || project.currentVersion || ''" :project-id="project.id"
              :version-id="selectedVersion || project.currentVersion || ''" @open="selectedFile = $event" />
            <div v-if="sourceNames.length > 3" class="source-file-picker">
              <FolderOpen :size="14" /><select
                v-model="selectedFile"
                aria-label="选择源码文件"
              >
                <option v-for="name in sourceNames" :key="name" :value="name">
                  {{ name }}
                </option></select
              ><span>{{ sourceNames.length }} 个文件</span>
            </div>
            <div class="file-tabs">
              <button
                v-for="name in sourceNames"
                :key="name"
                :title="name"
                :class="{ selected: selectedFile === name }"
                @click="selectedFile = name"
              >
                <FileCode2 :size="13" />{{ name
                }}<span
                  v-if="selectedFile === name"
                  class="file-dot"
                ></span></button
              ><button
                class="copy-code"
                @click="copyCode"
                aria-label="复制当前文件"
              >
                <Copy :size="14" />
              </button>
            </div>
            <div class="editor">
              <div class="line-numbers" aria-hidden="true">
                <div
                  v-for="n in (files[selectedFile] || '').split('\n').length"
                  :key="n"
                >
                  {{ n }}
                </div>
              </div>
              <textarea
                v-model="files[selectedFile]"
                :disabled="busy || !selectedVersion"
                spellcheck="false"
                :aria-label="`编辑 ${selectedFile}`"
              ></textarea>
            </div>
            <div class="code-status">
              <span>{{ fileLanguage }}</span
              ><span
                >UTF-8 ·
                {{ (files[selectedFile] || "").length }} characters</span
              >
            </div>
          </div>
          <div v-if="runtimeError" class="runtime-error">
            <CircleAlert :size="15" /><span>{{ runtimeError }}</span
            ><button
              @click="example('修复以下预览运行错误：' + runtimeError)"
              :disabled="busy"
            >
              请求修复</button
            ><button
              class="icon-button"
              @click="runtimeError = ''"
              aria-label="关闭预览错误"
            >
              <X :size="13" />
            </button>
          </div>
          <div class="output-footer">
            <span
              ><span class="status-dot"></span
              >{{
                paused
                  ? "任务已暂停"
                  : active
                    ? "构建中"
                    : selectedVersion
                      ? "已加载保存版本"
                      : "准备就绪"
              }}</span
            ><span
              >AI workspace
              <span>✧</span></span
            >
          </div>
        </section>
      </div>
    </main>
    <dialog ref="agentDialog" class="modal atoms-agent-dialog" aria-labelledby="atoms-agent-title">
      <div class="modal-heading">
        <h2 id="atoms-agent-title">选择 Agent</h2>
        <button class="icon-button" aria-label="关闭 Agent 选择" @click="agentDialog?.close()"><X :size="18" /></button>
      </div>
      <div class="atoms-agent-list">
        <button v-for="(label, value) in modeLabels" :key="value"
          :disabled="busy && mode !== value" :aria-pressed="mode === value"
          @click="selectMode(value); agentDialog?.close()">
          <component :is="{build: Code2, chat: MessageSquare, research: Search, image: ImageIcon}[value]" :size="19" />
          <span><strong>{{ label }}</strong><small>{{ agentDescriptions[value] }}</small></span>
          <Check v-if="mode === value" :size="15" />
        </button>
      </div>
    </dialog>
    <GatewayMemoryPanel v-if="showGatewayMemory" @close="showGatewayMemory = false" />
    <GatewayAccount v-if="showGatewayAccount" :dark="darkTheme" @close="showGatewayAccount = false" />
    <div v-if="showSkills" class="modal-backdrop" @click.self="showSkills = false" @keydown.esc="showSkills = false">
      <section class="modal atoms-account-sheet" role="dialog" aria-modal="true" aria-labelledby="workbench-skills-title">
        <div class="modal-heading">
          <h2 id="workbench-skills-title">技能</h2>
          <button class="icon-button" aria-label="关闭技能列表" @click="showSkills = false"><X :size="18" /></button>
        </div>
        <p v-if="!config.skills?.length">当前后端没有配置技能。</p>
        <p v-else-if="!availableSkills.length">当前 Agent 或执行方式没有可用技能。</p>
        <div v-else class="atoms-skill-list">
          <button v-for="skill in availableSkills" :key="`${skill.name}@${skill.version}`" class="button"
            :disabled="busy" :aria-pressed="activeSkill === skill" @click="selectedSkill = `${skill.name}@${skill.version}`; showSkills = false">
            {{ skill.name }} <small>{{ skill.version }}</small>
          </button>
        </div>
      </section>
    </div>
    <div v-if="showAccountUsage || showAccountSettings" class="modal-backdrop" @click.self="showAccountUsage = false; showAccountSettings = false" @keydown.esc="showAccountUsage = false; showAccountSettings = false">
      <section class="modal atoms-account-sheet" role="dialog" aria-modal="true" aria-labelledby="account-sheet-title">
        <div class="modal-heading">
          <h2 id="account-sheet-title">{{ showAccountUsage ? "用量与费用" : "账号设置" }}</h2>
          <button class="icon-button" aria-label="关闭账号详情" @click="showAccountUsage = false; showAccountSettings = false"><X :size="18" /></button>
        </div>
        <template v-if="showAccountUsage">
          <p>当前工作台会话的已计量用量</p>
          <dl><dt>Token</dt><dd>{{ projects.some(item => item.totalTokens != null) ? tokenFormat.format(accountUsage) : "暂无计量" }}</dd>
          <dt>费用</dt><dd>{{ accountCost == null ? "暂无计量" : currency.format(accountCost) }}</dd></dl>
        </template>
        <template v-else><p>{{ accountName }}</p><p v-if="accountEmail">{{ accountEmail }}</p><p v-if="gatewayUser">租户 {{ gatewayUser.tenant_id }}</p></template>
      </section>
    </div>
    <SchedulePanel v-if="showSchedules" @close="showSchedules = false"
      @login="showSchedules = false; showGatewayAccount = true" />
    <SupervisorReviewsPanel v-if="showSupervisorReviews" @close="showSupervisorReviews = false" />
    <DaemonApprovalsPanel v-if="showDaemonApprovals" @close="showDaemonApprovals = false" />
    <div v-if="history" class="modal-backdrop" @click.self="history = false">
      <section
        class="modal version-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="history-title"
      >
        <div class="modal-heading">
          <div>
            <span class="section-kicker">YOUR CREATIVE JOURNEY</span>
            <h2 id="history-title">版本历史</h2>
          </div>
          <button
            class="icon-button"
            @click="history = false"
            aria-label="关闭版本历史"
          >
            <X :size="20" />
          </button>
        </div>
        <p class="modal-description">
          每一步创造，都有迹可循。恢复会创建一个新版本。
        </p>
        <div class="version-list">
          <div v-for="v in project?.versions" :key="v.id" class="version-item">
            <div class="version-icon"><History :size="17" /></div>
            <div class="version-details">
              <strong
                >版本 {{ v.number
                }}<span v-if="v.id === project?.currentVersion"
                  >当前版本</span
                ></strong
              >
              <p>{{ v.summary }}</p>
              <small>{{ new Date(v.createdAt).toLocaleString("zh-CN") }}</small>
            </div>
            <div class="version-actions">
              <button
                class="button small"
                @click="
                  chooseVersion(v);
                  history = false;
                "
              >
                查看</button
              ><button
                class="button small primary"
                v-if="v.id !== project?.currentVersion"
                :disabled="busy"
                @click="restore(v)"
              >
                恢复
              </button>
            </div>
          </div>
        </div>
      </section>
    </div>
    <div v-if="help" class="modal-backdrop" @click.self="help = false">
      <section
        class="modal help-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="help-title"
      >
        <div class="modal-heading">
          <h2 id="help-title">让你的想法，开始发生。</h2>
          <button
            class="icon-button"
            @click="help = false"
            aria-label="关闭指南"
          >
            <X :size="20" />
          </button>
        </div>
        <div class="help-step">
          <span>01</span>
          <div>
            <h3>描述你想构建什么</h3>
            <p>说清楚应用用途、主要交互和喜欢的风格。</p>
          </div>
        </div>
        <div class="help-step">
          <span>02</span>
          <div>
            <h3>预览，再继续打磨</h3>
            <p>实际操作应用，也可以在代码标签里编辑并保存。</p>
          </div>
        </div>
        <div class="help-step">
          <span>03</span>
          <div>
            <h3>每个版本，都属于你</h3>
            <p>随时恢复历史版本，或导出三个源码文件。</p>
          </div>
        </div>
        <div class="help-mode">
          <CircleAlert :size="16" />
          <p>
            从右上角切换 Agent，在任务选项里选择模型。对话和生图不会修改应用版本。生成的图片保存在项目中。<br />工作台项目保存于服务端，使用当前浏览器匿名会话访问。
          </p>
        </div>
        <button class="button primary help-start" @click="help = false">
          开始创造 <ArrowUpRight :size="15" />
        </button>
      </section>
    </div>
    <Transition name="toast"
      ><div v-if="toast" class="toast-message">
        <Check :size="16" />{{ toast }}
      </div></Transition
    >
  </div>
</template>
