<script setup lang="ts">
import {
  computed,
  nextTick,
  onMounted,
  onUnmounted,
  reactive,
  ref,
  watch,
} from "vue";
import {
  Activity,
  ArrowRight,
  ArrowUpRight,
  Check,
  ChevronDown,
  ChevronRight,
  CircleHelp,
  Clock3,
  Cloud,
  Copy,
  Globe2,
  KeyRound,
  LayoutDashboard,
  Link2,
  LoaderCircle,
  LogOut,
  Menu,
  Monitor,
  Network,
  Pencil,
  Plus,
  RefreshCw,
  Search,
  Server,
  ShieldCheck,
  Terminal,
  Trash2,
  X,
} from "@lucide/vue";
import { api, ApiError, jsonBody, session } from "./api";
import type {
  Dashboard,
  DuplicateMachineGroup,
  Machine,
  NetworkInterface,
  Provider,
  ProviderType,
  Rule,
  SyncLog,
} from "./types";
import {
  dateTime,
  fullDomain,
  relativeTime,
  shortAddress,
  statusLabel,
  statusTone,
} from "./format";

type Page = "overview" | "machines" | "ddns" | "providers" | "logs";
const navItems = [
  { id: "overview" as Page, name: "总览", icon: LayoutDashboard },
  { id: "machines" as Page, name: "机器管理", icon: Server },
  { id: "ddns" as Page, name: "动态域名解析", icon: Globe2 },
  { id: "providers" as Page, name: "DNS 服务商", icon: KeyRound },
  { id: "logs" as Page, name: "同步日志", icon: Activity },
];
const page = ref<Page>("overview");
const sidebarOpen = ref(false);
const authenticated = ref(false),
  initialized = ref(false),
  username = ref("admin");
const loginForm = reactive({ username: "admin", password: "" });
const loginError = ref(""),
  signingIn = ref(false),
  loading = ref(false),
  submitting = ref(false);
const machines = ref<Machine[]>([]),
  duplicateGroups = ref<DuplicateMachineGroup[]>([]),
  providers = ref<Provider[]>([]),
  rules = ref<Rule[]>([]),
  logs = ref<SyncLog[]>([]);
const summary = ref<Dashboard>({
  totalMachines: 0,
  onlineMachines: 0,
  offlineMachines: 0,
  enabledRules: 0,
  failedRules: 0,
});
const now = ref(Date.now()),
  updatedAt = ref(""),
  query = ref(""),
  statusFilter = ref("all");
const selectedMachine = ref<Machine | null>(null);
const modal = ref<"provider" | "rule" | "onboarding" | "confirm" | "merge" | null>(null);
const mergeMembers = ref<Machine[]>([]),
  mergeTargetId = ref(""),
  mergeError = ref("");
const mergeSourceIds = computed(() =>
  mergeMembers.value.filter((item) => item.deviceId !== mergeTargetId.value).map((item) => item.deviceId),
);
const validMergeTarget = computed(() =>
  mergeMembers.value.some((item) => item.deviceId === mergeTargetId.value),
);
const installerOs = ref<"windows" | "unix">("windows");
const releaseState = ref<"loading" | "ready" | "pending" | "error">("pending");
const mirroredRelease = ref<{
  tag: string;
  packages: { platform: string }[];
} | null>(null);
const modalOrigin = ref<HTMLElement | null>(null);
const toast = ref(""),
  toastKind = ref("success");
const syncing = ref<number | null>(null);
let toastTimer: ReturnType<typeof setTimeout> | undefined;
let pollTimer: ReturnType<typeof setInterval> | undefined;
let clockTimer: ReturnType<typeof setInterval> | undefined;
let refreshTask: Promise<void> | undefined;
const providerNames: Record<ProviderType, string> = {
  ALIYUN: "阿里云 DNS",
  TENCENT: "腾讯云 DNSPod",
  CLOUDFLARE: "Cloudflare",
};
const providerHints: Record<ProviderType, string> = {
  ALIYUN: "使用 RAM 子账户的 AccessKey",
  TENCENT: "使用腾讯云 API 的 SecretId / SecretKey",
  CLOUDFLARE: "使用具有 DNS 编辑权限的 API Token",
};
const providerColors: Record<ProviderType, string> = {
  ALIYUN: "orange",
  TENCENT: "blue",
  CLOUDFLARE: "amber",
};
const providerForm = reactive({
  id: 0,
  name: "",
  type: "ALIYUN" as ProviderType,
  accessKeyId: "",
  accessKeySecret: "",
});
const ruleForm = reactive({
  id: 0,
  name: "",
  machineDeviceId: "",
  interfaceId: "",
  interfaceName: "",
  recordType: "A" as "A" | "AAAA",
  providerId: 0,
  domain: "",
  rr: "home",
  ttl: 600,
  line: "",
  recordId: "",
  addressMode: "AUTO" as "AUTO" | "SPECIFIC",
  specificAddress: "",
  allowPrivate: false,
  enabled: false,
});
const confirmation = reactive({
  title: "",
  detail: "",
  action: null as null | (() => Promise<void>),
});
const currentTitle = computed(
  () => navItems.find((item) => item.id === page.value)?.name || "总览",
);
const interfaceCount = computed(() =>
  machines.value.reduce((sum, item) => sum + item.interfaces.length, 0),
);
const filteredMachines = computed(() =>
  machines.value.filter((machine) => {
    if (
      (statusFilter.value === "online" && !machine.online) ||
      (statusFilter.value === "offline" && machine.online)
    )
      return false;
    const text =
      `${machine.hostname} ${machine.deviceId} ${machine.os} ${machine.ipv4.join(" ")} ${machine.ipv6.join(" ")}`.toLowerCase();
    return text.includes(query.value.trim().toLowerCase());
  }),
);
const selectedRuleMachine = computed(() =>
  machines.value.find((item) => item.deviceId === ruleForm.machineDeviceId),
);
const selectedRuleInterface = computed(() =>
  selectedRuleMachine.value?.interfaces.find(
    (item) => (item.id || item.name) === ruleForm.interfaceId,
  ),
);
const selectedRuleAddresses = computed(() =>
  ruleForm.recordType === "A"
    ? selectedRuleInterface.value?.ipv4 || []
    : selectedRuleInterface.value?.ipv6 || [],
);
const selectedRuleProvider = computed(() =>
  providers.value.find((item) => item.id === ruleForm.providerId),
);
const recentLogs = computed(() => logs.value.slice(0, 5));

function notify(message: string, kind = "success") {
  toast.value = message;
  toastKind.value = kind;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (toast.value = ""), 4500);
}
function failure(error: unknown) {
  if (error instanceof ApiError && error.status === 401) {
    authenticated.value = false;
    loginForm.password = "";
  }
  notify(
    error instanceof Error ? error.message : "操作失败，请稍后重试",
    "error",
  );
}
function navigate(value: Page) {
  page.value = value;
  location.hash = value;
  sidebarOpen.value = false;
  selectedMachine.value = null;
  query.value = "";
}
function readHash() {
  const value = location.hash.slice(1);
  if (navItems.some((item) => item.id === value)) page.value = value as Page;
}

// 统一刷新真实接口；页面只在可见时定时拉取，不生成演示机器或虚构同步结果。
function refresh(): Promise<void> {
  if (!refreshTask) refreshTask = refreshState().finally(() => { refreshTask = undefined; });
  return refreshTask;
}
async function refreshState() {
  if (loading.value || !authenticated.value) return;
  loading.value = true;
  try {
    const [dashboard, machineList, providerList, ruleList, logList, duplicates] =
      await Promise.all([
        api<Dashboard>("/dashboard"),
        api<Machine[]>("/machines"),
        api<Provider[]>("/providers"),
        api<Rule[]>("/ddns/rules"),
        api<SyncLog[]>("/ddns/logs?limit=100"),
        api<DuplicateMachineGroup[]>("/machines/duplicates"),
      ]);
    summary.value = dashboard;
    machines.value = machineList;
    duplicateGroups.value = duplicates;
    providers.value = providerList;
    rules.value = ruleList;
    logs.value = logList;
    if (selectedMachine.value)
      selectedMachine.value =
        machineList.find(
          (item) => item.deviceId === selectedMachine.value?.deviceId,
        ) || null;
    updatedAt.value = new Date().toLocaleTimeString("zh-CN", { hour12: false });
    now.value = Date.now();
  } catch (error) {
    failure(error);
  } finally {
    loading.value = false;
  }
}
async function login() {
  signingIn.value = true;
  loginError.value = "";
  try {
    await session();
    await api("/auth/login", { method: "POST", body: jsonBody(loginForm) });
    const current = await session();
    authenticated.value = current.authenticated;
    username.value = current.username || loginForm.username;
    loginForm.password = "";
    await refresh();
  } catch (error) {
    loginError.value = error instanceof Error ? error.message : "登录失败";
  } finally {
    signingIn.value = false;
  }
}
async function logout() {
  try {
    await api("/auth/logout", { method: "POST" });
    authenticated.value = false;
    selectedMachine.value = null;
    await session();
  } catch (error) {
    failure(error);
  }
}
function openModal(type: typeof modal.value) {
  modalOrigin.value = document.activeElement as HTMLElement;
  modal.value = type;
  if (type === "onboarding") void loadClientRelease();
  nextTick(() =>
    document
      .querySelector<HTMLElement>(".modal input, .modal select, .modal button")
      ?.focus(),
  );
}
function closeModal() {
  if (submitting.value) return;
  modal.value = null;
  providerForm.accessKeySecret = "";
  mergeMembers.value = [];
  mergeTargetId.value = "";
  mergeError.value = "";
  nextTick(() => modalOrigin.value?.focus());
}
function trapFocus(event: KeyboardEvent) {
  if (event.key === "Escape") {
    closeModal();
    return;
  }
  if (event.key !== "Tab") return;
  const elements = [
    ...document.querySelectorAll<HTMLElement>(
      ".modal button:not(:disabled), .modal input:not(:disabled), .modal select:not(:disabled), .modal textarea:not(:disabled), .modal a[href]",
    ),
  ].filter((item) => item.offsetParent !== null);
  const first = elements[0],
    last = elements[elements.length - 1];
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last?.focus();
  }
  if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first?.focus();
  }
}
async function copy(value: string) {
  try {
    await navigator.clipboard.writeText(value);
    notify("已复制到剪贴板");
  } catch {
    notify("浏览器未开放剪贴板权限，请手动选中复制", "error");
  }
}
function editProvider(value?: Provider) {
  Object.assign(providerForm, {
    id: value?.id || 0,
    name: value?.name || "",
    type: value?.type || "ALIYUN",
    accessKeyId: value?.accessKeyId || "",
    accessKeySecret: "",
  });
  openModal("provider");
}
async function saveProvider() {
  submitting.value = true;
  try {
    await api(`/providers${providerForm.id ? `/${providerForm.id}` : ""}`, {
      method: providerForm.id ? "PUT" : "POST",
      body: jsonBody({
        name: providerForm.name,
        type: providerForm.type,
        accessKeyId: providerForm.accessKeyId,
        ...(providerForm.accessKeySecret
          ? { accessKeySecret: providerForm.accessKeySecret }
          : {}),
      }),
    });
    submitting.value = false;
    closeModal();
    notify("DNS 服务商已保存");
    await refresh();
  } catch (error) {
    failure(error);
  } finally {
    submitting.value = false;
  }
}
function createRule(
  machine?: Machine,
  iface?: NetworkInterface,
  existing?: Rule,
) {
  const firstMachine = machine || machines.value[0];
  const firstInterface = iface || firstMachine?.interfaces[0];
  const defaults = {
    id: 0,
    name: "",
    machineDeviceId: firstMachine?.deviceId || "",
    interfaceId: firstInterface?.id || firstInterface?.name || "",
    interfaceName: firstInterface?.name || "",
    recordType: firstInterface?.ipv4.length ? "A" : "AAAA",
    providerId: providers.value[0]?.id || 0,
    domain: "",
    rr: "home",
    ttl: 600,
    line: "",
    recordId: "",
    addressMode: "AUTO",
    specificAddress: "",
    allowPrivate: false,
    enabled: false,
  };
  for (const key of Object.keys(defaults) as (keyof typeof defaults)[]) {
    if (existing) Object.assign(defaults, { [key]: existing[key] });
  }
  Object.assign(ruleForm, defaults);
  openModal("rule");
}
function changeRuleMachine() {
  const iface = selectedRuleMachine.value?.interfaces[0];
  ruleForm.interfaceId = iface?.id || iface?.name || "";
  ruleForm.interfaceName = iface?.name || "";
  ruleForm.specificAddress = "";
}
function changeRuleInterface() {
  ruleForm.interfaceName = selectedRuleInterface.value?.name || "";
  ruleForm.specificAddress = "";
}
async function saveRule() {
  submitting.value = true;
  try {
    const {
      id,
      name,
      machineDeviceId,
      interfaceId,
      interfaceName,
      recordType,
      providerId,
      domain,
      rr,
      ttl,
      line,
      recordId,
      addressMode,
      specificAddress,
      allowPrivate,
      enabled,
    } = ruleForm;
    const body = {
      name,
      machineDeviceId,
      interfaceId,
      interfaceName,
      recordType,
      providerId,
      domain,
      rr,
      ttl,
      line,
      recordId,
      addressMode,
      specificAddress,
      allowPrivate,
      enabled,
    };
    body.interfaceName =
      selectedRuleInterface.value?.name || body.interfaceName;
    await api(`/ddns/rules${id ? `/${id}` : ""}`, {
      method: id ? "PUT" : "POST",
      body: jsonBody(body),
    });
    submitting.value = false;
    closeModal();
    notify("解析规则已保存");
    await refresh();
  } catch (error) {
    failure(error);
  } finally {
    submitting.value = false;
  }
}
async function toggleRule(rule: Rule) {
  try {
    await api(`/ddns/rules/${rule.id}/enabled`, {
      method: "PATCH",
      body: jsonBody({ enabled: !rule.enabled }),
    });
    await refresh();
  } catch (error) {
    failure(error);
  }
}
async function syncRule(rule: Rule) {
  syncing.value = rule.id;
  try {
    await api(`/ddns/rules/${rule.id}/sync`, { method: "POST" });
    await refresh();
    notify("本次同步已完成，请查看同步状态与日志");
  } catch (error) {
    failure(error);
  } finally {
    syncing.value = null;
  }
}
function requestDelete(kind: "provider" | "rule", value: Provider | Rule) {
  confirmation.title = kind === "rule" ? "删除解析规则？" : "删除 DNS 服务商？";
  confirmation.detail =
    kind === "rule"
      ? `停止管理「${value.name}」，云端现有 DNS 记录会保留。`
      : `删除「${value.name}」及其加密凭据；正在使用此服务商的规则需要先移除。`;
  confirmation.action = async () => {
    await api(
      kind === "rule" ? `/ddns/rules/${value.id}` : `/providers/${value.id}`,
      { method: "DELETE" },
    );
    notify("已删除");
    await refresh();
  };
  openModal("confirm");
}
async function confirmDelete() {
  submitting.value = true;
  try {
    await confirmation.action?.();
    submitting.value = false;
    closeModal();
  } catch (error) {
    failure(error);
  } finally {
    submitting.value = false;
  }
}
function providerName(id: number) {
  return providers.value.find((item) => item.id === id)?.name || "服务商已移除";
}
function machineName(id: string) {
  return machines.value.find((item) => item.deviceId === id)?.hostname || id;
}
function shortDeviceId(id: string) {
  return id.length > 18 ? `${id.slice(0, 8)}…${id.slice(-6)}` : id;
}
function reviewMachineMerge(group: DuplicateMachineGroup) {
  // 候选只用于提示；保留全部原记录，管理员核对完整设备标识后再提交合并。
  const ids = [group.targetDeviceId, ...group.sourceDeviceIds];
  const members = ids.map((id) => machines.value.find((item) => item.deviceId === id));
  if (new Set(ids).size !== ids.length || ids.length < 2 || members.some((item) => !item)) {
    notify("机器记录已更新，请刷新后重新核对", "error");
    return;
  }
  mergeMembers.value = members as Machine[];
  mergeTargetId.value = group.targetDeviceId;
  mergeError.value = "";
  selectedMachine.value = null;
  openModal("merge");
}
async function mergeMachines() {
  if (submitting.value || !validMergeTarget.value || !mergeSourceIds.value.length) return;
  submitting.value = true;
  mergeError.value = "";
  const sourceDeviceIds = [...mergeSourceIds.value];
  try {
    const result = await api<Machine>(`/machines/${encodeURIComponent(mergeTargetId.value)}/merge`, {
      method: "POST", body: jsonBody({ sourceDeviceIds }),
    });
    // 等待可能在合并前发出的轮询结束，再获取一次最新状态，避免旧响应覆盖合并结果。
    if (refreshTask) await refreshTask;
    const mergedIds = new Set([result.deviceId, ...sourceDeviceIds]);
    machines.value = [result, ...machines.value.filter((item) => !mergedIds.has(item.deviceId))];
    duplicateGroups.value = duplicateGroups.value.filter((group) =>
      ![group.targetDeviceId, ...group.sourceDeviceIds].some((id) => mergedIds.has(id)),
    );
    await refresh();
    submitting.value = false;
    closeModal();
    notify("机器记录已合并，原设备标识后续上报将更新保留记录");
  } catch (error) {
    mergeError.value = error instanceof Error ? error.message : "合并失败，请刷新后重试";
    failure(error);
  } finally {
    submitting.value = false;
  }
}
function networkLabel(os: string) {
  return (
    (
      {
        windows: "Windows",
        linux: "Linux",
        macos: "macOS",
        freebsd: "FreeBSD",
      } as Record<string, string>
    )[os] || os
  );
}
const reportEndpoint = computed(() => `${location.origin}/api/report`);

async function loadClientRelease() {
  releaseState.value = "loading";
  mirroredRelease.value = null;
  try {
    const response = await fetch("/client-downloads/latest.json", {
      cache: "no-store",
      signal: AbortSignal.timeout(5000),
    });
    if (response.status === 404) {
      releaseState.value = "pending";
      return;
    }
    if (!response.ok) throw new Error("Could not read the package index");
    const release = await response.json();
    if (
      release.schemaVersion !== 1 ||
      !/^v\d+\.\d+\.\d+$/.test(release.tag) ||
      !Array.isArray(release.packages) ||
      !release.packages.length ||
      !release.packages.every(
        (item: { platform?: unknown }) =>
          item && typeof item.platform === "string",
      )
    ) {
      throw new Error("Invalid package index");
    }
    mirroredRelease.value = release;
    releaseState.value = "ready";
  } catch {
    releaseState.value = "error";
  }
}

const installerCommand = computed(() => {
  const origin = location.origin;
  const insecure = location.protocol === "http:";
  if (installerOs.value === "windows") {
    return [
      "& {",
      "  $installer = Join-Path ([IO.Path]::GetTempPath()) ('ip-reporter-' + [guid]::NewGuid().ToString('N') + '.ps1')",
      "  try {",
      `    Invoke-WebRequest -UseBasicParsing -MaximumRedirection 0 -ErrorAction Stop -Uri '${origin}/client-downloads/install.ps1' -OutFile $installer`,
      `    powershell.exe -NoProfile -ExecutionPolicy Bypass -File $installer -ServerUrl '${origin}'${insecure ? " -AllowHttp" : ""}`,
      "  } finally { Remove-Item -LiteralPath $installer -ErrorAction SilentlyContinue }",
      "}",
    ].join("\n");
  }
  return [
    "(",
    "  installer=$(mktemp) || exit 1",
    "  trap 'rm -f \"$installer\"' EXIT",
    `  curl -q --fail --silent --show-error --proto '=${insecure ? "http" : "https"}' --location --max-redirs 0 '${origin}/client-downloads/install.sh' -o "$installer" &&`,
    `  sudo sh "$installer" --server-url '${origin}'${insecure ? " --allow-http" : ""}`,
    ")",
  ].join("\n");
});
watch(modal, (value) => {
  document.body.style.overflow = value ? "hidden" : "";
});
onMounted(async () => {
  readHash();
  window.addEventListener("hashchange", readHash);
  try {
    const current = await session();
    authenticated.value = current.authenticated;
    username.value = current.username || "admin";
    if (current.authenticated) await refresh();
  } catch {
    loginError.value = "暂时无法连接服务端，请确认 Docker 服务已启动";
  } finally {
    initialized.value = true;
  }
  pollTimer = setInterval(() => {
    if (!document.hidden) refresh();
  }, 30000);
  clockTimer = setInterval(() => (now.value = Date.now()), 1000);
});
onUnmounted(() => {
  clearInterval(pollTimer);
  clearInterval(clockTimer);
  clearTimeout(toastTimer);
  window.removeEventListener("hashchange", readHash);
  document.body.style.overflow = "";
});
</script>

<template>
  <div v-if="!initialized" class="boot-screen">
    <div class="brand-mark"><Network :size="23" /></div>
    <LoaderCircle :size="22" class="spin" /><span>正在连接网络控制台…</span>
  </div>
  <main v-else-if="!authenticated" class="login-shell">
    <section class="login-story">
      <div class="brand">
        <div class="brand-mark"><Network :size="22" /></div>
        <span>IP Reporter <small>NETWORK CONSOLE</small></span>
      </div>
      <div class="login-headline">
        <span class="eyebrow">YOUR NETWORK, IN SIGHT</span>
        <h1>连接每一台机器。<br />掌握每一次变化。</h1>
        <p>从网卡地址到动态域名，<br />让分散的设备回到一个清晰的视野。</p>
        <div class="network-illustration" aria-hidden="true">
          <div class="illustration-node node-a">
            <Server :size="25" /><span>设备</span>
          </div>
          <div class="illustration-line line-a"></div>
          <div class="illustration-node node-center">
            <Network :size="30" />
          </div>
          <div class="illustration-line line-b"></div>
          <div class="illustration-node node-b">
            <Globe2 :size="25" /><span>域名</span>
          </div>
          <span class="illustration-packet packet-a"></span
          ><span class="illustration-packet packet-b"></span>
        </div>
      </div>
      <div class="login-foot">
        <ShieldCheck :size="15" /> 独立部署 · 凭据加密 · 始终在你掌控之中
      </div>
    </section>
    <section class="login-panel">
      <form class="login-card" @submit.prevent="login">
        <span class="eyebrow">欢迎回来</span>
        <h2>登录控制台</h2>
        <p>使用部署时配置的管理员账户。</p>
        <label for="username">用户名</label
        ><input
          id="username"
          v-model="loginForm.username"
          autocomplete="username"
          required
          autofocus
          placeholder="admin"
        /><label for="password">密码</label
        ><input
          id="password"
          v-model="loginForm.password"
          type="password"
          autocomplete="current-password"
          required
          placeholder="输入管理员密码"
        />
        <p v-if="loginError" class="form-error" role="alert">
          {{ loginError }}
        </p>
        <button class="button primary login-submit" :disabled="signingIn">
          <LoaderCircle v-if="signingIn" :size="17" class="spin" /><span>{{
            signingIn ? "登录中" : "进入控制台"
          }}</span
          ><ArrowRight v-if="!signingIn" :size="17" />
        </button>
        <div class="login-note">
          <KeyRound :size="15" /> 密码在服务器 .env 文件的 ADMIN_PASSWORD 中配置
        </div>
      </form>
      <span class="login-copyright">IP Reporter / 私有网络管理工作台</span>
    </section>
  </main>
  <div v-else class="app-shell">
    <div
      v-if="sidebarOpen"
      class="sidebar-shade"
      @click="sidebarOpen = false"
    ></div>
    <aside class="sidebar" :class="{ 'mobile-open': sidebarOpen }">
      <a class="brand" href="#overview" @click.prevent="navigate('overview')"
        ><div class="brand-mark"><Network :size="22" /></div>
        <span>IP Reporter <small>NETWORK CONSOLE</small></span></a
      >
      <div class="workspace-label">
        <span class="workspace-icon"><Network :size="15" /></span>
        <div><strong>我的网络空间</strong><span>自托管控制台</span></div>
        <ChevronDown :size="15" />
      </div>
      <div class="nav-caption">工作空间</div>
      <nav aria-label="主导航">
        <a
          v-for="item in navItems"
          :key="item.id"
          :href="`#${item.id}`"
          :class="{ active: page === item.id }"
          @click.prevent="navigate(item.id)"
          ><component :is="item.icon" :size="18" /><span>{{ item.name }}</span
          ><span v-if="item.id === 'machines'" class="nav-count">{{
            machines.length
          }}</span
          ><span v-if="item.id === 'ddns' && rules.length" class="nav-count">{{
            rules.length
          }}</span></a
        >
      </nav>
      <div class="sidebar-bottom">
        <div class="connection-note">
          <span class="status-dot"></span><span>与服务端连接正常</span
          ><span class="version-tag">v1.1</span>
        </div>
        <button class="account-button" title="退出登录" @click="logout">
          <span class="avatar">{{ username.slice(0, 1).toUpperCase() }}</span
          ><span
            ><strong>{{ username }}</strong
            ><small>管理员</small></span
          ><LogOut :size="17" />
        </button>
      </div>
    </aside>
    <div class="main-shell">
      <header class="topbar">
        <div class="breadcrumb">
          <button
            class="icon-button mobile-menu"
            aria-label="打开导航"
            @click="sidebarOpen = true"
          >
            <Menu :size="20" /></button
          ><span class="breadcrumb-home">工作空间</span
          ><ChevronRight :size="14" /><span>{{ currentTitle }}</span>
        </div>
        <div class="topbar-right">
          <span class="environment-pill"
            ><span class="status-dot"></span> 私有部署</span
          ><button
            class="icon-button"
            title="接入帮助"
            aria-label="接入帮助"
            @click="openModal('onboarding')"
          >
            <CircleHelp :size="19" /></button
          ><span class="top-avatar">{{
            username.slice(0, 1).toUpperCase()
          }}</span>
        </div>
      </header>
      <main class="page-container">
        <div class="page-heading">
          <div>
            <div class="eyebrow">
              {{
                page === "overview"
                  ? "NETWORK OVERVIEW"
                  : page === "machines"
                    ? "CONNECTED MACHINES"
                    : page === "ddns"
                      ? "DYNAMIC DNS"
                      : page === "providers"
                        ? "DNS PROVIDERS"
                        : "ACTIVITY LOG"
              }}
            </div>
            <h1>
              {{ page === "overview" ? "网络总览" : currentTitle
              }}<span v-if="page === 'overview'" class="heading-dot">.</span>
            </h1>
            <p>
              {{
                page === "overview"
                  ? "每台设备的网络动态，尽在此处。"
                  : page === "machines"
                    ? "查看设备状态、网卡及其对应的网络地址。"
                    : page === "ddns"
                      ? "从指定网卡获取地址，让域名随设备保持更新。"
                      : page === "providers"
                        ? "连接你的云端 DNS，凭据在服务器加密保存。"
                        : "追踪每次解析变更与同步结果。"
              }}
            </p>
          </div>
          <div class="heading-actions">
            <button
              class="button secondary"
              :disabled="loading"
              @click="refresh"
            >
              <RefreshCw :size="15" :class="{ spin: loading }" /><span
                >刷新</span
              ></button
            ><button
              v-if="page === 'overview' || page === 'machines'"
              class="button primary"
              @click="openModal('onboarding')"
            >
              <Plus :size="17" />接入机器</button
            ><button
              v-if="page === 'ddns'"
              class="button primary"
              @click="createRule()"
            >
              <Plus :size="17" />新建解析</button
            ><button
              v-if="page === 'providers'"
              class="button primary"
              @click="editProvider()"
            >
              <Plus :size="17" />添加服务商
            </button>
          </div>
        </div>

        <template v-if="page === 'overview' || page === 'machines'">
          <section class="stats-grid" aria-label="网络统计">
            <div class="stat-card">
              <div class="stat-top">
                <span>机器总数</span
                ><span class="stat-icon gray"><Server :size="18" /></span>
              </div>
              <div class="stat-value">
                {{ summary.totalMachines }}<small>台</small>
              </div>
              <div class="stat-foot">
                <span class="small-indicator green"></span> 已接入你的网络空间
              </div>
            </div>
            <div class="stat-card">
              <div class="stat-top">
                <span>在线机器</span
                ><span class="stat-icon green"><Activity :size="18" /></span>
              </div>
              <div class="stat-value">
                {{ summary.onlineMachines }}<small>台</small>
              </div>
              <div class="stat-foot">
                <span class="metric-badge"
                  >{{
                    summary.totalMachines
                      ? Math.round(
                          (summary.onlineMachines / summary.totalMachines) *
                            100,
                        )
                      : 0
                  }}%</span
                >
                设备保持在线
              </div>
            </div>
            <div class="stat-card">
              <div class="stat-top">
                <span>网络接口</span
                ><span class="stat-icon blue"><Network :size="18" /></span>
              </div>
              <div class="stat-value">
                {{ interfaceCount }}<small>个</small>
              </div>
              <div class="stat-foot">覆盖 IPv4 与 IPv6 双栈地址</div>
            </div>
            <div class="stat-card">
              <div class="stat-top">
                <span>动态解析</span
                ><span class="stat-icon violet"><Globe2 :size="18" /></span>
              </div>
              <div class="stat-value">{{ rules.length }}<small>条</small></div>
              <div class="stat-foot">
                <span class="small-indicator green"></span
                >{{ summary.enabledRules }} 条规则已启用<span
                  v-if="summary.failedRules"
                  class="text-red"
                >
                  · {{ summary.failedRules }} 条异常</span
                >
              </div>
            </div>
          </section>
          <div
            class="overview-content"
            :class="{ 'full-width': page === 'machines' }"
          >
            <section class="surface machine-surface">
              <div class="section-heading">
                <div>
                  <h2>
                    已接入的机器
                    <span class="count-badge">{{ machines.length }}</span>
                  </h2>
                  <span class="section-subtitle"
                    >设备上报后将自动出现在这里</span
                  >
                </div>
                <button
                  v-if="page === 'overview'"
                  class="text-button"
                  @click="navigate('machines')"
                >
                  查看全部 <ArrowUpRight :size="15" />
                </button>
              </div>
              <section
                v-if="page === 'machines' && duplicateGroups.length"
                class="duplicate-candidates"
                aria-label="待核对的重复机器"
              >
                <div class="duplicate-heading">
                  <Copy :size="17" />
                  <div>
                    <strong>发现 {{ duplicateGroups.length }} 组可能重复的机器</strong>
                    <p>这些记录的主机名、系统及网卡标识相同，请核对后合并。</p>
                  </div>
                </div>
                <div v-for="group in duplicateGroups" :key="group.targetDeviceId" class="duplicate-group">
                  <span><strong>{{ machineName(group.targetDeviceId) }}</strong> · {{ group.sourceDeviceIds.length + 1 }} 条记录</span>
                  <button class="button secondary" @click="reviewMachineMerge(group)">
                    核对并合并<ArrowRight :size="14" />
                  </button>
                </div>
              </section>
              <div class="table-toolbar">
                <div class="filter-tabs">
                  <button
                    v-for="option in [
                      { id: 'all', label: '全部' },
                      { id: 'online', label: '在线' },
                      { id: 'offline', label: '离线' },
                    ]"
                    :key="option.id"
                    :class="{ selected: statusFilter === option.id }"
                    @click="statusFilter = option.id"
                  >
                    {{ option.label
                    }}<span v-if="option.id === 'all'">{{
                      machines.length
                    }}</span>
                  </button>
                </div>
                <div class="search-field">
                  <Search :size="16" /><input
                    v-model="query"
                    aria-label="搜索机器"
                    placeholder="搜索机器名称或 IP…"
                  />
                </div>
              </div>
              <div class="table-scroll">
                <table class="machine-table">
                  <thead>
                    <tr>
                      <th>机器 / 系统</th>
                      <th>网络地址</th>
                      <th>状态</th>
                      <th>最近上报</th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="machine in filteredMachines"
                      :key="machine.deviceId"
                      tabindex="0"
                      @click="selectedMachine = machine"
                      @keydown.enter="selectedMachine = machine"
                    >
                      <td>
                        <div class="machine-name">
                          <div
                            class="device-icon"
                            :class="{ offline: !machine.online }"
                          >
                            <Monitor :size="19" />
                          </div>
                          <div>
                            <strong>{{ machine.hostname }}</strong
                            ><span
                              >{{ networkLabel(machine.os) }}<i>·</i
                              >{{ machine.interfaces.length }} 个网卡</span
                            >
                            <code class="machine-device-id" :title="machine.deviceId">ID {{ shortDeviceId(machine.deviceId) }}</code>
                          </div>
                        </div>
                      </td>
                      <td>
                        <div class="address-stack">
                          <span v-if="machine.ipv4[0]" class="ip-text"
                            ><span class="ip-family">v4</span
                            >{{ machine.ipv4[0]
                            }}<small v-if="machine.ipv4.length > 1"
                              >+{{ machine.ipv4.length - 1 }}</small
                            ></span
                          ><span v-if="machine.ipv6[0]" class="ip-text ipv6"
                            ><span class="ip-family">v6</span
                            ><span :title="machine.ipv6[0]">{{
                              shortAddress(machine.ipv6[0])
                            }}</span></span
                          ><span
                            v-if="!machine.ipv4.length && !machine.ipv6.length"
                            class="muted"
                            >暂无地址</span
                          >
                        </div>
                      </td>
                      <td>
                        <span
                          class="pill"
                          :class="machine.online ? 'green' : 'gray'"
                          ><span class="status-dot"></span
                          >{{ machine.online ? "在线" : "离线" }}</span
                        >
                      </td>
                      <td>
                        <span
                          class="subtle"
                          :title="dateTime(machine.lastSeen)"
                          >{{ relativeTime(machine.lastSeen, now) }}</span
                        >
                      </td>
                      <td>
                        <button
                          class="icon-button"
                          :aria-label="`查看 ${machine.hostname} 网卡`"
                          @click.stop="selectedMachine = machine"
                        >
                          <ChevronRight :size="17" />
                        </button>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div v-if="!filteredMachines.length" class="empty-state">
                <div class="empty-icon"><Server :size="27" /></div>
                <h3>
                  {{
                    machines.length ? "没有匹配的机器" : "等待第一台机器上报"
                  }}
                </h3>
                <p>
                  {{
                    machines.length
                      ? "尝试其他名称、地址或连接状态。"
                      : "在设备上启动 IP Reporter，网络信息就会出现在这里。"
                  }}
                </p>
                <button
                  v-if="!machines.length"
                  class="button secondary"
                  @click="openModal('onboarding')"
                >
                  <Terminal :size="15" />查看接入命令
                </button>
              </div>
              <footer class="table-footer">
                <span><span class="status-dot"></span> 每 30 秒自动更新</span
                ><span>上次刷新 {{ updatedAt || "—" }}</span>
              </footer>
            </section>
            <aside v-if="page === 'overview'" class="overview-aside">
              <section class="dns-intro">
                <div class="intro-orbit" aria-hidden="true">
                  <Globe2 :size="29" /><span></span><i></i>
                </div>
                <span class="eyebrow">KEEP YOUR DOMAIN CONNECTED</span>
                <h2>动态域名解析</h2>
                <p>选择机器网卡，将最新地址自动同步到你的 DNS 服务商。</p>
                <button class="button primary" @click="navigate('ddns')">
                  配置动态解析<ArrowRight :size="16" />
                </button>
                <div class="provider-mini">
                  <span class="provider-mini-icon orange">阿</span
                  ><span class="provider-mini-icon blue">腾</span
                  ><span class="provider-mini-icon amber"
                    ><Cloud :size="14" /></span
                  ><span>支持三大 DNS 服务商</span>
                </div>
              </section>
              <section class="surface activity-surface">
                <div class="section-heading compact">
                  <h2>最近同步</h2>
                  <Activity :size="16" class="muted" />
                </div>
                <div v-if="!recentLogs.length" class="activity-empty">
                  <Clock3 :size="20" />
                  <p>这里将记录解析的每次更新</p>
                </div>
                <div
                  v-for="log in recentLogs.slice(0, 3)"
                  :key="log.id"
                  class="activity-entry"
                >
                  <span class="activity-mark" :class="statusTone(log.status)"
                    ><Check
                      v-if="statusTone(log.status) === 'green'"
                      :size="12" /><Activity v-else :size="12"
                  /></span>
                  <div>
                    <strong>{{ log.ruleName }}</strong
                    ><span
                      >{{ statusLabel(log.status) }} ·
                      {{ relativeTime(log.createdAt, now) }}</span
                    >
                  </div>
                </div>
                <button class="activity-link" @click="navigate('logs')">
                  查看全部日志<ArrowRight :size="14" />
                </button>
              </section>
            </aside>
          </div>
        </template>

        <template v-else-if="page === 'ddns'"
          ><div class="notice-bar">
            <div class="notice-symbol"><Link2 :size="19" /></div>
            <div>
              <strong>从网卡到域名，自动保持同步</strong
              ><span
                >仅同步你选择的网卡地址。设备离线或地址缺失时保留云端记录；默认每
                60 秒检查一次。</span
              >
            </div>
            <span class="pill green">{{ summary.enabledRules }} 条已启用</span>
          </div>
          <section class="surface">
            <div class="section-heading">
              <div>
                <h2>
                  解析规则 <span class="count-badge">{{ rules.length }}</span>
                </h2>
                <span class="section-subtitle"
                  >A 记录对应 IPv4，AAAA 记录对应 IPv6</span
                >
              </div>
            </div>
            <div v-if="rules.length" class="table-scroll">
              <table class="rules-table">
                <thead>
                  <tr>
                    <th>域名 / 类型</th>
                    <th>地址来源</th>
                    <th>上次成功解析 / 状态</th>
                    <th>自动同步</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="rule in rules" :key="rule.id">
                    <td>
                      <strong class="domain-name">{{
                        fullDomain(rule.rr, rule.domain)
                      }}</strong>
                      <div class="cell-secondary">
                        <span class="record-type">{{ rule.recordType }}</span
                        >{{ providerName(rule.providerId) }}
                      </div>
                    </td>
                    <td>
                      <div class="source-label">
                        <Monitor :size="14" />{{
                          machineName(rule.machineDeviceId)
                        }}
                      </div>
                      <div class="cell-secondary">
                        {{ rule.interfaceName || rule.interfaceId }} · TTL
                        {{ rule.ttl }}s
                      </div>
                    </td>
                    <td>
                      <span class="ip-text">{{
                        rule.lastAddress || "等待首次同步"
                      }}</span>
                      <div class="cell-secondary">
                        <span
                          class="mini-dot"
                          :class="statusTone(rule.lastStatus)"
                        ></span
                        ><span :title="rule.lastMessage">{{
                          statusLabel(rule.lastStatus)
                        }}</span
                        ><span v-if="rule.lastSyncAt">
                          · {{ relativeTime(rule.lastSyncAt, now) }}</span
                        >
                      </div>
                    </td>
                    <td>
                      <button
                        class="toggle"
                        role="switch"
                        :aria-checked="rule.enabled"
                        :aria-label="`${rule.enabled ? '暂停' : '启用'} ${rule.name}`"
                        :class="{ enabled: rule.enabled }"
                        @click="toggleRule(rule)"
                      >
                        <span></span>
                      </button>
                    </td>
                    <td>
                      <div class="row-actions">
                        <button
                          class="icon-button"
                          :disabled="syncing === rule.id"
                          :aria-label="`立即同步 ${rule.name}`"
                          title="立即同步"
                          @click="syncRule(rule)"
                        >
                          <RefreshCw
                            :size="16"
                            :class="{ spin: syncing === rule.id }"
                          /></button
                        ><button
                          class="icon-button"
                          :aria-label="`编辑 ${rule.name}`"
                          title="编辑"
                          @click="createRule(undefined, undefined, rule)"
                        >
                          <Pencil :size="15" /></button
                        ><button
                          class="icon-button danger"
                          :aria-label="`删除 ${rule.name}`"
                          title="删除规则"
                          @click="requestDelete('rule', rule)"
                        >
                          <Trash2 :size="15" />
                        </button>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div v-else class="empty-state tall">
              <div class="empty-icon"><Globe2 :size="29" /></div>
              <h3>让域名跟随你的设备</h3>
              <p>添加 DNS 服务商后，选择机器与网卡，创建第一条动态解析规则。</p>
              <button
                class="button primary"
                @click="providers.length ? createRule() : navigate('providers')"
              >
                <Plus :size="16" />{{
                  providers.length ? "新建解析规则" : "先添加 DNS 服务商"
                }}
              </button>
            </div>
            <footer class="table-footer">
              <span
                ><ShieldCheck
                  :size="14"
                />仅启用的规则定时同步，手动同步以本次操作为准</span
              ><span>离线保护已开启</span>
            </footer>
          </section></template
        >

        <template v-else-if="page === 'providers'"
          ><div class="provider-security">
            <ShieldCheck :size="20" />
            <div>
              <strong>密钥只在服务端使用</strong
              ><span
                >凭据通过 AES-256-GCM 加密保存，页面不会再次返回
                Secret。请使用仅有 DNS 编辑权限的子账户或 Token。</span
              >
            </div>
          </div>
          <div class="provider-grid">
            <section
              v-for="provider in providers"
              :key="provider.id"
              class="surface provider-card"
            >
              <div class="provider-card-top">
                <div
                  class="provider-logo"
                  :class="providerColors[provider.type]"
                >
                  <Cloud
                    v-if="provider.type === 'CLOUDFLARE'"
                    :size="25"
                  /><span v-else>{{
                    provider.type === "ALIYUN" ? "阿" : "腾"
                  }}</span>
                </div>
                <span class="pill green"><ShieldCheck :size="12" />已配置</span>
              </div>
              <h2>{{ provider.name }}</h2>
              <p>{{ providerNames[provider.type] }}</p>
              <div class="credential-preview">
                <span>{{
                  provider.type === "CLOUDFLARE"
                    ? "Zone ID"
                    : provider.type === "TENCENT"
                      ? "SecretId"
                      : "AccessKey ID"
                }}</span
                ><code>{{
                  provider.accessKeyId.length > 22
                    ? `${provider.accessKeyId.slice(0, 12)}…${provider.accessKeyId.slice(-5)}`
                    : provider.accessKeyId
                }}</code>
              </div>
              <div class="provider-card-footer">
                <span
                  ><Globe2 :size="14" />{{
                    rules.filter((rule) => rule.providerId === provider.id)
                      .length
                  }}
                  条解析规则</span
                >
                <div>
                  <button
                    class="icon-button"
                    :aria-label="`编辑 ${provider.name}`"
                    @click="editProvider(provider)"
                  >
                    <Pencil :size="15" /></button
                  ><button
                    class="icon-button danger"
                    :aria-label="`删除 ${provider.name}`"
                    @click="requestDelete('provider', provider)"
                  >
                    <Trash2 :size="15" />
                  </button>
                </div>
              </div>
            </section>
            <button class="provider-add-card" @click="editProvider()">
              <span><Plus :size="25" /></span><strong>连接 DNS 服务商</strong>
              <p>阿里云、腾讯云 DNSPod 或 Cloudflare</p>
            </button>
          </div>
          <section class="surface setup-guide">
            <h2>三步，开始动态解析</h2>
            <div>
              <article>
                <span>01</span>
                <h3>添加服务商</h3>
                <p>填写 DNS 托管平台的 API 凭据。</p>
              </article>
              <ArrowRight :size="18" />
              <article>
                <span>02</span>
                <h3>选定地址来源</h3>
                <p>选择上报机器及其中一张网卡。</p>
              </article>
              <ArrowRight :size="18" />
              <article>
                <span>03</span>
                <h3>保持域名在线</h3>
                <p>启用规则，地址变化会自动同步。</p>
              </article>
            </div>
          </section></template
        >

        <template v-else-if="page === 'logs'"
          ><section class="surface">
            <div class="section-heading">
              <div>
                <h2>解析同步记录</h2>
                <span class="section-subtitle"
                  >最近 {{ logs.length }} 条记录 ·
                  时间按浏览器所在时区显示</span
                >
              </div>
            </div>
            <div v-if="logs.length" class="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>同步时间</th>
                    <th>解析规则</th>
                    <th>地址</th>
                    <th>状态</th>
                    <th>详细信息</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="log in logs" :key="log.id">
                    <td class="subtle">{{ dateTime(log.createdAt) }}</td>
                    <td>
                      <strong>{{ log.ruleName }}</strong>
                    </td>
                    <td class="ip-text">{{ log.address || "—" }}</td>
                    <td>
                      <span class="pill" :class="statusTone(log.status)">{{
                        statusLabel(log.status)
                      }}</span>
                    </td>
                    <td class="log-message">
                      {{ log.message || statusLabel(log.action) }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div v-else class="empty-state tall">
              <div class="empty-icon"><Activity :size="29" /></div>
              <h3>还没有同步记录</h3>
              <p>创建并启用解析规则后，可以在这里查看每次处理结果。</p>
              <button class="button secondary" @click="navigate('ddns')">
                前往动态域名解析<ArrowRight :size="15" />
              </button>
            </div></section
        ></template>
        <footer class="page-footer">
          <span
            >IP Reporter
            <span class="footer-separator">/</span> 网络控制台</span
          ><span><ShieldCheck :size="13" />数据保存在你的服务器</span>
        </footer>
      </main>
    </div>

    <div
      v-if="selectedMachine"
      class="drawer-backdrop"
      @click.self="selectedMachine = null"
    >
      <aside class="machine-drawer" aria-label="机器网络详情">
        <div class="drawer-heading">
          <div class="device-icon"><Monitor :size="23" /></div>
          <div>
            <h2>{{ selectedMachine.hostname }}</h2>
            <span
              >{{ networkLabel(selectedMachine.os) }} · Agent
              {{ selectedMachine.agentVersion }}</span
            >
          </div>
          <button
            class="icon-button"
            aria-label="关闭详情"
            @click="selectedMachine = null"
          >
            <X :size="20" />
          </button>
        </div>
        <div class="drawer-content">
          <div class="machine-meta">
            <span
              class="pill"
              :class="selectedMachine.online ? 'green' : 'gray'"
              ><span class="status-dot"></span
              >{{ selectedMachine.online ? "在线" : "离线" }}</span
            ><span
              >{{ relativeTime(selectedMachine.lastSeen, now) }}收到上报</span
            >
          </div>
          <div class="machine-identifiers">
            <label>设备标识</label><code>{{ selectedMachine.deviceId }}</code
            ><label>连接对端</label
            ><code>{{ selectedMachine.peerIp || "—" }}</code>
          </div>
          <div class="drawer-section-heading">
            <h3>网络接口</h3>
            <span>{{ selectedMachine.interfaces.length }} 个网卡</span>
          </div>
          <section
            v-for="iface in selectedMachine.interfaces"
            :key="iface.id || iface.name"
            class="interface-card"
          >
            <div class="interface-header">
              <span
                ><Network :size="17" /><strong>{{ iface.name }}</strong></span
              ><span class="interface-live"
                ><span class="status-dot"></span>已上报</span
              >
            </div>
            <div class="mac-row">
              <span>MAC</span
              ><code>{{ iface.mac || "旧版客户端或此接口未提供" }}</code
              ><button
                v-if="iface.mac"
                class="icon-button"
                :aria-label="`复制 ${iface.name} MAC`"
                @click="copy(iface.mac)"
              >
                <Copy :size="13" />
              </button>
            </div>
            <div v-if="iface.id" class="interface-id" :title="iface.id">
              ID <code>{{ iface.id }}</code>
            </div>
            <div class="interface-addresses">
              <div v-for="address in iface.ipv4" :key="address">
                <span class="ip-version">IPv4</span><code>{{ address }}</code
                ><button
                  class="icon-button"
                  aria-label="复制 IPv4 地址"
                  @click="copy(address)"
                >
                  <Copy :size="13" />
                </button>
              </div>
              <div v-for="address in iface.ipv6" :key="address">
                <span class="ip-version violet-text">IPv6</span
                ><code>{{ address }}</code
                ><button
                  class="icon-button"
                  aria-label="复制 IPv6 地址"
                  @click="copy(address)"
                >
                  <Copy :size="13" />
                </button>
              </div>
            </div>
            <button
              class="interface-ddns"
              @click="createRule(selectedMachine!, iface)"
            >
              <Link2 :size="14" />为此网卡创建解析<ArrowUpRight :size="14" />
            </button>
          </section>
        </div>
      </aside>
    </div>
  </div>

  <div
    v-if="modal"
    class="modal-backdrop"
    @click.self="closeModal"
    @keydown="trapFocus"
  >
    <section
      class="modal"
      :class="{ 'wide-modal': modal === 'rule' || modal === 'onboarding' || modal === 'merge' }"
      role="dialog"
      aria-modal="true"
      aria-labelledby="modal-title"
    >
      <div class="modal-header">
        <div>
          <span class="eyebrow">{{
            modal === "provider"
              ? "DNS PROVIDER"
              : modal === "rule"
                ? "DYNAMIC DNS"
                : modal === "onboarding"
                  ? "CONNECT A MACHINE"
                  : modal === "merge"
                    ? "MERGE MACHINE RECORDS"
                    : "CONFIRM ACTION"
          }}</span>
          <h2 id="modal-title">
            {{
              modal === "provider"
                ? providerForm.id
                  ? "编辑 DNS 服务商"
                  : "添加 DNS 服务商"
                : modal === "rule"
                  ? ruleForm.id
                    ? "编辑解析规则"
                    : "新建解析规则"
                  : modal === "onboarding"
                    ? "把机器接入你的网络空间"
                    : modal === "merge"
                      ? "核对并合并机器记录"
                      : confirmation.title
            }}
          </h2>
        </div>
        <button
          class="icon-button"
          :disabled="submitting"
          aria-label="关闭对话框"
          @click="closeModal"
        >
          <X :size="20" />
        </button>
      </div>
      <form v-if="modal === 'provider'" @submit.prevent="saveProvider">
        <div class="modal-body">
          <label for="provider-type">DNS 服务商</label
          ><select id="provider-type" v-model="providerForm.type">
            <option value="ALIYUN">阿里云 DNS</option>
            <option value="TENCENT">腾讯云 DNSPod</option>
            <option value="CLOUDFLARE">Cloudflare</option>
          </select>
          <p class="field-hint">{{ providerHints[providerForm.type] }}</p>
          <label for="provider-name">显示名称</label
          ><input
            id="provider-name"
            v-model="providerForm.name"
            required
            maxlength="80"
            placeholder="例如：个人域名账户"
          /><label for="provider-key">{{
            providerForm.type === "CLOUDFLARE"
              ? "Zone ID"
              : providerForm.type === "TENCENT"
                ? "SecretId"
                : "AccessKey ID"
          }}</label
          ><input
            id="provider-key"
            v-model="providerForm.accessKeyId"
            required
            maxlength="256"
            autocomplete="off"
            spellcheck="false"
          /><label for="provider-secret">{{
            providerForm.type === "CLOUDFLARE"
              ? "API Token"
              : providerForm.type === "TENCENT"
                ? "SecretKey"
                : "AccessKey Secret"
          }}</label
          ><input
            id="provider-secret"
            v-model="providerForm.accessKeySecret"
            type="password"
            :required="!providerForm.id"
            autocomplete="new-password"
            maxlength="4096"
            :placeholder="
              providerForm.id ? '留空保留已保存的密钥' : '填写服务商 API 密钥'
            "
          />
          <div class="form-note">
            <ShieldCheck :size="16" />密钥加密存储，保存后无法从页面读取。
          </div>
        </div>
        <div class="modal-footer">
          <button
            type="button"
            class="button secondary"
            :disabled="submitting"
            @click="closeModal"
          >
            取消</button
          ><button class="button primary" :disabled="submitting">
            <LoaderCircle v-if="submitting" :size="16" class="spin" />保存服务商
          </button>
        </div>
      </form>
      <form v-else-if="modal === 'rule'" @submit.prevent="saveRule">
        <div class="modal-body">
          <div
            v-if="!machines.length || !providers.length"
            class="inline-warning"
          >
            {{
              !machines.length
                ? "请先接入一台机器，再选择其网卡。"
                : "请先在 DNS 服务商页面添加 API 凭据。"
            }}
          </div>
          <div class="form-grid">
            <div class="span-two">
              <label for="rule-name">规则名称</label
              ><input
                id="rule-name"
                v-model="ruleForm.name"
                required
                maxlength="80"
                placeholder="例如：家中 NAS · IPv6"
              />
            </div>
            <div>
              <label for="rule-machine">上报机器</label
              ><select
                id="rule-machine"
                v-model="ruleForm.machineDeviceId"
                required
                @change="changeRuleMachine"
              >
                <option disabled value="">选择机器</option>
                <option
                  v-for="machine in machines"
                  :key="machine.deviceId"
                  :value="machine.deviceId"
                >
                  {{ machine.hostname }}{{ machine.online ? "" : "（离线）" }}
                </option>
              </select>
            </div>
            <div>
              <label for="rule-interface">地址来源网卡</label
              ><select
                id="rule-interface"
                v-model="ruleForm.interfaceId"
                required
                @change="changeRuleInterface"
              >
                <option disabled value="">选择网卡</option>
                <option
                  v-for="iface in selectedRuleMachine?.interfaces || []"
                  :key="iface.id || iface.name"
                  :value="iface.id || iface.name"
                >
                  {{ iface.name }}
                </option>
              </select>
            </div>
            <div>
              <label for="rule-provider">DNS 服务商</label
              ><select
                id="rule-provider"
                v-model.number="ruleForm.providerId"
                required
              >
                <option disabled :value="0">选择服务商</option>
                <option
                  v-for="provider in providers"
                  :key="provider.id"
                  :value="provider.id"
                >
                  {{ provider.name }}
                </option>
              </select>
            </div>
            <div>
              <label for="rule-type">记录类型</label
              ><select
                id="rule-type"
                v-model="ruleForm.recordType"
                @change="ruleForm.specificAddress = ''"
              >
                <option value="A">A · IPv4</option>
                <option value="AAAA">AAAA · IPv6</option>
              </select>
            </div>
            <div>
              <label for="rule-domain">主域名</label
              ><input
                id="rule-domain"
                v-model.trim="ruleForm.domain"
                required
                placeholder="example.com"
                autocapitalize="off"
                spellcheck="false"
              />
            </div>
            <div>
              <label for="rule-rr">主机记录</label
              ><input
                id="rule-rr"
                v-model.trim="ruleForm.rr"
                required
                placeholder="home（根域名填写 @）"
                autocapitalize="off"
                spellcheck="false"
              />
            </div>
            <div>
              <label for="rule-mode">地址选择方式</label
              ><select id="rule-mode" v-model="ruleForm.addressMode">
                <option value="AUTO">自动选择可用地址</option>
                <option value="SPECIFIC">指定网卡中的某个地址</option>
              </select>
            </div>
            <div>
              <label for="rule-ttl">TTL（秒）</label
              ><input
                id="rule-ttl"
                v-model.number="ruleForm.ttl"
                type="number"
                required
                min="60"
                max="86400"
                step="1"
              />
            </div>
            <div v-if="ruleForm.addressMode === 'SPECIFIC'" class="span-two">
              <label for="rule-address">指定地址</label
              ><select
                id="rule-address"
                v-model="ruleForm.specificAddress"
                required
              >
                <option disabled value="">选择当前网卡地址</option>
                <option
                  v-for="address in selectedRuleAddresses"
                  :key="address"
                  :value="address"
                >
                  {{ address }}
                </option>
              </select>
            </div>
            <div class="span-two address-preview">
              <Network :size="16" />
              <div>
                <span
                  >网卡当前
                  {{ ruleForm.recordType === "A" ? "IPv4" : "IPv6" }}</span
                ><code>{{
                  selectedRuleAddresses.join(" / ") ||
                  "当前暂无此地址族，规则将等待地址上报"
                }}</code>
              </div>
            </div>
            <div>
              <label for="rule-line">解析线路 <small>可选</small></label
              ><input
                id="rule-line"
                v-model="ruleForm.line"
                :placeholder="
                  selectedRuleProvider?.type === 'TENCENT'
                    ? '默认'
                    : selectedRuleProvider?.type === 'CLOUDFLARE'
                      ? 'Cloudflare 无需填写'
                      : 'default'
                "
              />
            </div>
            <div>
              <label for="rule-record">云端记录 ID <small>可选</small></label
              ><input
                id="rule-record"
                v-model="ruleForm.recordId"
                placeholder="有多条同名记录时指定"
              />
            </div>
            <div class="span-two form-checks">
              <label
                ><input v-model="ruleForm.enabled" type="checkbox" /><span
                  ><strong>启用自动同步</strong
                  ><small
                    >保存后按计划调用服务商 API，创建或更新所选 DNS
                    记录。</small
                  ></span
                ></label
              ><label
                ><input v-model="ruleForm.allowPrivate" type="checkbox" /><span
                  ><strong>允许解析到内网地址</strong
                  ><small
                    >仅在需要使用私网、VPN 或内网 DNS 时开启。</small
                  ></span
                ></label
              >
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <span class="rule-domain-preview">{{
            fullDomain(ruleForm.rr, ruleForm.domain || "example.com")
          }}</span
          ><button
            type="button"
            class="button secondary"
            :disabled="submitting"
            @click="closeModal"
          >
            取消</button
          ><button
            class="button primary"
            :disabled="submitting || !machines.length || !providers.length"
          >
            <LoaderCircle v-if="submitting" :size="16" class="spin" />保存规则
          </button>
        </div>
      </form>
      <div v-else-if="modal === 'onboarding'">
        <div class="modal-body onboarding-body">
          <p>
            在需要接入的设备上运行安装命令。脚本会选择适合系统和架构的安装包，校验后配置上报并注册服务。
          </p>
          <div class="distribution-status" role="status">
            <span v-if="releaseState === 'loading'">正在读取镜像版本…</span>
            <span v-else-if="releaseState === 'ready' && mirroredRelease">
              已同步 {{ mirroredRelease.tag }} ·
              {{ mirroredRelease.packages.length }} 个平台包
            </span>
            <span v-else-if="releaseState === 'pending'">
              尚无可下载版本。请配置客户端 GitHub 仓库，并等待首个正式 Release
              同步完成。
            </span>
            <span v-else>暂时无法读取镜像版本，请稍后重试。</span>
            <button
              class="icon-button"
              type="button"
              aria-label="刷新客户端版本"
              @click="loadClientRelease"
            >
              <RefreshCw :size="15" />
            </button>
          </div>
          <div class="onboarding-step">
            <span>01</span>
            <div>
              <h3>准备上报 Token</h3>
              <p>
                首次安装时输入服务器 .env 中的
                REPORT_TOKEN。无人值守部署可以指定设备本地的 Token 文件。
              </p>
              <div class="copy-field">
                <code>{{ reportEndpoint }}</code
                ><button
                  class="icon-button"
                  aria-label="复制接收地址"
                  @click="copy(reportEndpoint)"
                >
                  <Copy :size="15" />
                </button>
              </div>
            </div>
          </div>
          <div class="onboarding-step">
            <span>02</span>
            <div>
              <h3>选择系统并运行安装命令</h3>
              <div class="distribution-toolbar">
                <div class="filter-tabs" aria-label="安装目标系统">
                  <button
                    type="button"
                    :class="{ selected: installerOs === 'windows' }"
                    @click="installerOs = 'windows'"
                  >
                    Windows
                  </button>
                  <button
                    type="button"
                    :class="{ selected: installerOs === 'unix' }"
                    @click="installerOs = 'unix'"
                  >
                    Linux / macOS
                  </button>
                </div>
                <button
                  type="button"
                  class="button secondary"
                  aria-label="复制安装命令"
                  @click="copy(installerCommand)"
                >
                  <Copy :size="14" />复制
                </button>
              </div>
              <p>
                {{
                  installerOs === "windows"
                    ? "在管理员 PowerShell 中运行。"
                    : "在终端运行，需要 curl 和 sudo。"
                }}远程设备请用设备可访问的服务端地址。
              </p>
              <pre><code>{{ installerCommand }}</code></pre>
            </div>
          </div>
          <div class="onboarding-step">
            <span>03</span>
            <div>
              <h3>查看网卡，连接域名</h3>
              <p>
                首次上报后，机器会自动出现。点击机器即可查看网卡、MAC 和
                IP，并为指定网卡创建 DDNS。
              </p>
            </div>
          </div>
          <div class="form-note">
            <ShieldCheck :size="16" />升级默认保留上报配置。HTTP
            命令适用于受控测试，正式部署使用 HTTPS。
          </div>
        </div>
        <div class="modal-footer">
          <button class="button primary" @click="closeModal">
            已了解<Check :size="16" />
          </button>
        </div>
      </div>
      <form v-else-if="modal === 'merge'" @submit.prevent="mergeMachines">
        <div class="modal-body merge-body">
          <p class="confirm-detail">请确认以下记录属于同一台机器，并选择要保留的设备标识。</p>
          <label for="merge-target">保留的设备标识</label>
          <select id="merge-target" v-model="mergeTargetId" :disabled="submitting" @change="mergeError = ''">
            <option v-for="machine in mergeMembers" :key="machine.deviceId" :value="machine.deviceId">
              {{ machine.hostname }} · {{ machine.deviceId }}
            </option>
          </select>
          <p class="field-hint">默认选择最近上报的记录。合并后使用所有记录中最新的网络快照。</p>
          <ul class="merge-members" aria-label="将合并的机器记录">
            <li v-for="machine in mergeMembers" :key="machine.deviceId">
              <div class="merge-member-heading">
                <strong>{{ machine.hostname }}</strong>
                <span class="pill" :class="machine.deviceId === mergeTargetId ? 'green' : 'gray'">
                  {{ machine.deviceId === mergeTargetId ? "保留" : "并入" }}
                </span>
              </div>
              <code>{{ machine.deviceId }}</code>
              <span>{{ networkLabel(machine.os) }} · {{ machine.interfaces.length }} 个网卡 · 最近上报 {{ dateTime(machine.lastSeen) }}</span>
            </li>
          </ul>
          <div class="form-note">
            <Link2 :size="16" />
            <span>关联的 DDNS 规则将保留并绑定到合并后的机器。所有原设备标识继续上报时，都会更新这条保留记录。</span>
          </div>
          <p v-if="mergeError" class="form-error" role="alert">{{ mergeError }}</p>
        </div>
        <div class="modal-footer">
          <button type="button" class="button secondary" :disabled="submitting" @click="closeModal">取消</button>
          <button type="submit" class="button primary" :disabled="submitting || !validMergeTarget || !mergeSourceIds.length">
            <LoaderCircle v-if="submitting" class="spin" :size="16" />
            确认合并 {{ mergeMembers.length }} 条记录
          </button>
        </div>
      </form>
      <div v-else-if="modal === 'confirm'">
        <div class="modal-body">
          <p class="confirm-detail">{{ confirmation.detail }}</p>
        </div>
        <div class="modal-footer">
          <button
            class="button secondary"
            :disabled="submitting"
            @click="closeModal"
          >
            取消</button
          ><button
            class="button danger-button"
            :disabled="submitting"
            @click="confirmDelete"
          >
            <LoaderCircle v-if="submitting" class="spin" :size="16" />确认删除
          </button>
        </div>
      </div>
    </section>
  </div>
  <Transition name="toast"
    ><div v-if="toast" class="toast-message" :class="toastKind" role="status">
      <Check v-if="toastKind === 'success'" :size="17" /><Activity
        v-else
        :size="17"
      />{{ toast }}
    </div></Transition
  >
</template>
