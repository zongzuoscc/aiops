<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'

const API_BASE = import.meta.env.VITE_API_BASE_URL || ''
const API_KEY = import.meta.env.VITE_API_KEY || ''
const incidents = ref([])
const services = ref([])
const selected = ref(null)
const loading = ref(true)
const error = ref('')
const lastRefresh = ref(null)
const showServiceForm = ref(false)
const savingService = ref(false)
let refreshTimer

const serviceForm = reactive({
  id: '', displayName: '', containerName: '', healthUrl: '', stableImage: '',
  minConfidence: 0.85, writeLimit: 2, cooldownSeconds: 300,
  automationEnabled: true, protectedTarget: false,
})

const activeStatuses = new Set([
  'RECEIVED', 'COLLECTING_EVIDENCE', 'DIAGNOSING', 'PLANNED',
  'POLICY_CHECKING', 'EXECUTING', 'VERIFYING', 'RETRY_SCHEDULED', 'ROLLING_BACK',
])
const activeCount = computed(() => incidents.value.filter(item => activeStatuses.has(item.status)).length)
const resolvedCount = computed(() => incidents.value.filter(item => item.status === 'RESOLVED').length)
const manualCount = computed(() => incidents.value.filter(item => item.status === 'MANUAL_REQUIRED').length)
const automatedServices = computed(() => services.value.filter(item => item.automationEnabled).length)
const selectedIncident = computed(() => selected.value?.incident || null)
const selectedTimeline = computed(() => selected.value?.timeline || [])
const selectedActions = computed(() => selected.value?.actions || [])

async function request(path, options = {}) {
  const headers = { ...(options.headers || {}) }
  if (options.body) headers['Content-Type'] = 'application/json'
  if (API_KEY) headers['X-API-Key'] = API_KEY
  const response = await fetch(`${API_BASE}${path}`, { ...options, headers })
  if (!response.ok) throw new Error((await response.text()) || `${response.status} ${response.statusText}`)
  return response.status === 204 ? null : response.json()
}

async function refresh({ quiet = false } = {}) {
  if (!quiet) loading.value = true
  try {
    const [incidentData, serviceData] = await Promise.all([
      request('/api/v2/incidents?limit=100'), request('/api/v2/services'),
    ])
    incidents.value = incidentData
    services.value = serviceData
    error.value = ''
    lastRefresh.value = new Date()
    if (!selected.value && incidents.value.length) await openIncident(incidents.value[0].id)
    else if (selected.value) await openIncident(selected.value.incident.id, true)
  } catch (cause) {
    error.value = `控制面连接失败：${cause.message}`
  } finally {
    loading.value = false
  }
}

async function openIncident(id, quiet = false) {
  try {
    selected.value = await request(`/api/v2/incidents/${id}`)
  } catch (cause) {
    if (!quiet) error.value = `事件详情读取失败：${cause.message}`
  }
}

async function registerService() {
  savingService.value = true
  try {
    await request('/api/v2/services', {
      method: 'POST',
      body: JSON.stringify({
        ...serviceForm,
        allowedActions: [
          'GET_CONTAINER_STATE', 'GET_CONTAINER_LOGS', 'GET_CONTAINER_STATS',
          'CHECK_HTTP_HEALTH', 'RESTART_CONTAINER',
          'RECREATE_WITH_STABLE_IMAGE', 'RESTORE_SNAPSHOT',
        ],
      }),
    })
    showServiceForm.value = false
    await refresh({ quiet: true })
  } catch (cause) {
    error.value = `服务注册失败：${cause.message}`
  } finally {
    savingService.value = false
  }
}

function statusLabel(status) {
  return {
    RECEIVED: '已接收', COLLECTING_EVIDENCE: '采集证据', DIAGNOSING: '诊断中',
    PLANNED: '方案已生成', POLICY_CHECKING: '策略校验', EXECUTING: '执行中',
    VERIFYING: '验证中', RETRY_SCHEDULED: '等待重试', ROLLING_BACK: '回滚中',
    RESOLVED: '已恢复', MANUAL_REQUIRED: '人工接管', FAILED: '处理失败', SUPPRESSED: '已抑制',
  }[status] || status
}

function formatTime(value, withDate = false) {
  if (!value) return '未记录'
  return new Intl.DateTimeFormat('zh-CN', {
    month: withDate ? '2-digit' : undefined, day: withDate ? '2-digit' : undefined,
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(new Date(value))
}

function compactId(value) { return value ? value.slice(0, 8).toUpperCase() : '--------' }
function severityClass(severity) { return `severity-${String(severity || 'info').toLowerCase()}` }
function parseDiagnosis(value) {
  if (!value) return null
  try { return JSON.parse(value) } catch { return { plan: { summary: value, rationale: '' } } }
}

onMounted(() => {
  refresh()
  refreshTimer = window.setInterval(() => refresh({ quiet: true }), 5000)
})
onBeforeUnmount(() => window.clearInterval(refreshTimer))
</script>

<template>
  <div class="app-shell">
    <aside class="rail">
      <a class="brand" href="#" aria-label="AIOps 首页">
        <span class="brand-mark">AO</span>
        <span class="brand-copy">AUTONOMOUS<br>OPERATIONS</span>
      </a>
      <nav class="rail-nav" aria-label="主导航">
        <a class="nav-item active" href="#incidents"><span class="nav-index">01</span><span>事件台</span></a>
        <a class="nav-item" href="#services"><span class="nav-index">02</span><span>托管服务</span></a>
        <a class="nav-item" href="#policy"><span class="nav-index">03</span><span>策略边界</span></a>
      </nav>
      <div class="rail-foot">
        <span class="pulse-dot" :class="{ offline: error }"></span>
        <span>{{ error ? 'CONTROL PLANE OFFLINE' : 'CONTROL PLANE ONLINE' }}</span>
      </div>
    </aside>

    <main class="workspace">
      <header class="topbar">
        <div><p class="eyebrow">DOCKER OPERATIONS / LOCAL CONTROL PLANE</p><h1>事件指挥台</h1></div>
        <div class="topbar-actions">
          <div class="refresh-meta"><span>自动刷新 5s</span><strong>{{ lastRefresh ? formatTime(lastRefresh) : '连接中' }}</strong></div>
          <button class="button ghost" type="button" @click="refresh()">立即刷新</button>
          <button class="button primary" type="button" @click="showServiceForm = true">接入服务</button>
        </div>
      </header>

      <div v-if="error" class="error-strip">
        <span>{{ error }}</span><button type="button" @click="refresh()">重新连接</button>
      </div>

      <section class="metrics" aria-label="实时指标">
        <article class="metric-card alert-card"><p>处理中</p><strong>{{ activeCount.toString().padStart(2, '0') }}</strong><span>ACTIVE INCIDENTS</span></article>
        <article class="metric-card"><p>已验证恢复</p><strong>{{ resolvedCount.toString().padStart(2, '0') }}</strong><span>VERIFIED RECOVERY</span></article>
        <article class="metric-card"><p>需要人工</p><strong>{{ manualCount.toString().padStart(2, '0') }}</strong><span>HUMAN REQUIRED</span></article>
        <article class="metric-card service-card"><p>自动托管</p><strong>{{ automatedServices }}/{{ services.length }}</strong><span>MANAGED SERVICES</span></article>
      </section>

      <section id="incidents" class="incident-layout">
        <div class="queue-panel panel">
          <div class="section-head"><div><span class="section-number">01</span><h2>事件队列</h2></div><span class="record-count">{{ incidents.length }} RECORDS</span></div>
          <div v-if="loading && !incidents.length" class="empty-state"><span class="loader"></span><p>正在读取控制面事件</p></div>
          <div v-else-if="!incidents.length" class="empty-state"><strong>队列为空</strong><p>接入服务后，健康巡检或 Alertmanager 会在这里创建事件。</p></div>
          <div v-else class="incident-list">
            <button v-for="incident in incidents" :key="incident.id" class="incident-row" :class="{ selected: selectedIncident?.id === incident.id }" type="button" @click="openIncident(incident.id)">
              <span class="severity-bar" :class="severityClass(incident.severity)"></span>
              <span class="incident-main">
                <span class="incident-meta"><b>#{{ compactId(incident.id) }}</b><span>{{ incident.serviceId }}</span><time>{{ formatTime(incident.createdAt) }}</time></span>
                <strong>{{ incident.title }}</strong>
              </span>
              <span class="status-tag" :data-status="incident.status">{{ statusLabel(incident.status) }}</span>
            </button>
          </div>
        </div>

        <aside class="detail-panel panel">
          <template v-if="selectedIncident">
            <div class="detail-kicker"><span>INCIDENT #{{ compactId(selectedIncident.id) }}</span><span class="status-tag" :data-status="selectedIncident.status">{{ statusLabel(selectedIncident.status) }}</span></div>
            <h2>{{ selectedIncident.title }}</h2>
            <div class="detail-facts">
              <div><span>服务</span><strong>{{ selectedIncident.serviceId }}</strong></div><div><span>来源</span><strong>{{ selectedIncident.source }}</strong></div>
              <div><span>级别</span><strong>{{ selectedIncident.severity }}</strong></div><div><span>更新</span><strong>{{ formatTime(selectedIncident.updatedAt) }}</strong></div>
            </div>
            <section v-if="parseDiagnosis(selectedIncident.diagnosis)" class="diagnosis-note">
              <span class="note-label">AGENT DIAGNOSIS</span><h3>{{ parseDiagnosis(selectedIncident.diagnosis).plan?.summary }}</h3>
              <p>{{ parseDiagnosis(selectedIncident.diagnosis).plan?.rationale }}</p>
              <div class="confidence-line"><span>置信度</span><b>{{ Math.round((parseDiagnosis(selectedIncident.diagnosis).plan?.confidence || 0) * 100) }}%</b></div>
            </section>
            <section v-if="selectedIncident.policyReason" class="policy-note"><span>POLICY DECISION</span><p>{{ selectedIncident.policyReason }}</p></section>
            <section class="timeline-section">
              <div class="subhead"><h3>处置时间线</h3><span>{{ selectedTimeline.length }} STEPS</span></div>
              <ol class="timeline">
                <li v-for="item in selectedTimeline" :key="item.id"><span class="timeline-node" :data-outcome="item.outcome"></span><div><span>{{ item.stage }} / {{ item.outcome }}</span><strong>{{ item.summary }}</strong><time>{{ formatTime(item.createdAt, true) }}</time></div></li>
              </ol>
            </section>
            <section v-if="selectedActions.length" class="action-section">
              <div class="subhead"><h3>动作记录</h3><span>{{ selectedActions.length }} ACTIONS</span></div>
              <article v-for="action in selectedActions" :key="action.id" class="action-entry"><div><span>{{ action.actionType }}</span><strong>{{ action.state }}</strong></div><p>{{ action.errorMessage || '执行结果已记录，可用于审计与幂等恢复。' }}</p></article>
            </section>
          </template>
          <div v-else class="empty-state detail-empty"><strong>选择一个事件</strong><p>查看证据、策略判断、动作、验证与回滚记录。</p></div>
        </aside>
      </section>

      <section id="services" class="services-panel panel">
        <div class="section-head"><div><span class="section-number">02</span><h2>托管服务</h2></div><span class="record-count">DOCKER ALLOWLIST</span></div>
        <div class="service-grid">
          <article v-for="service in services" :key="service.id" class="service-item">
            <div class="service-title"><span class="service-state" :class="{ disabled: !service.automationEnabled }"></span><div><strong>{{ service.displayName }}</strong><span>{{ service.containerName }}</span></div></div>
            <dl><div><dt>置信阈值</dt><dd>{{ Math.round(service.minConfidence * 100) }}%</dd></div><div><dt>写入上限</dt><dd>{{ service.writeLimit }}/h</dd></div><div><dt>冷却时间</dt><dd>{{ service.cooldownSeconds }}s</dd></div></dl>
            <span class="service-mode">{{ service.protectedTarget ? 'PROTECTED' : service.automationEnabled ? 'AUTONOMOUS' : 'OBSERVE ONLY' }}</span>
          </article>
          <button class="service-add" type="button" @click="showServiceForm = true"><span>+</span><strong>注册 Docker 服务</strong><small>配置白名单与自动化边界</small></button>
        </div>
      </section>

      <section id="policy" class="boundary-panel"><div><span class="section-number">03</span><h2>明确的自动化边界</h2></div><p>允许读取状态、日志、资源与健康；写操作仅限白名单容器重启、稳定镜像重建和快照回滚。禁止任意 Shell、宿主机命令、卷删除、镜像清理和未注册目标。</p></section>
    </main>

    <div v-if="showServiceForm" class="modal-backdrop" @click.self="showServiceForm = false">
      <form class="service-modal" @submit.prevent="registerService">
        <div class="modal-head"><div><span>MANAGED SERVICE</span><h2>接入 Docker 服务</h2></div><button type="button" aria-label="关闭" @click="showServiceForm = false">×</button></div>
        <label>服务标识<input v-model="serviceForm.id" required placeholder="payment-service"></label><label>显示名称<input v-model="serviceForm.displayName" required placeholder="支付服务"></label>
        <label>容器名称<input v-model="serviceForm.containerName" required placeholder="payment-api"></label><label class="wide">健康检查地址<input v-model="serviceForm.healthUrl" placeholder="http://payment-api:8080/actuator/health"></label>
        <label class="wide">稳定镜像<input v-model="serviceForm.stableImage" placeholder="registry/payment-api:stable"></label><label>最低置信度<input v-model.number="serviceForm.minConfidence" type="number" min="0.5" max="1" step="0.01"></label>
        <label>每小时写入上限<input v-model.number="serviceForm.writeLimit" type="number" min="1" max="10"></label><label>冷却时间（秒）<input v-model.number="serviceForm.cooldownSeconds" type="number" min="30"></label>
        <label class="check-label"><input v-model="serviceForm.automationEnabled" type="checkbox">启用自动处置</label>
        <div class="modal-actions"><button class="button ghost" type="button" @click="showServiceForm = false">取消</button><button class="button primary" :disabled="savingService" type="submit">{{ savingService ? '注册中' : '确认接入' }}</button></div>
      </form>
    </div>
  </div>
</template>
