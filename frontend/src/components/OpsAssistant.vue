<script setup>
import { onBeforeUnmount, ref } from 'vue'

defineProps({ services: { type: Array, default: () => [] } })
const emit = defineEmits(['open-incident'])
const apiBase = import.meta.env.VITE_API_BASE_URL || ''
const apiKey = import.meta.env.VITE_API_KEY || ''
const serviceId = ref('')
const question = ref('')
const allowOperations = ref(false)
const busy = ref(false)
const messages = ref([])
const sessionId = crypto.randomUUID()
let controller

function headers() {
  return { 'Content-Type': 'application/json', ...(apiKey ? { 'X-API-Key': apiKey } : {}) }
}

async function startInvestigation() {
  if (!serviceId.value || !question.value.trim() || busy.value) return
  busy.value = true
  const task = question.value.trim()
  try {
    const response = await fetch(`${apiBase}/api/v2/agent/runs`, {
      method: 'POST', headers: headers(),
      body: JSON.stringify({ serviceId: serviceId.value, task, requestId: crypto.randomUUID(),
        allowRemediation: allowOperations.value }),
    })
    if (!response.ok) throw new Error(await response.text())
    const result = await response.json()
    messages.value.push({ role: 'user', text: task }, { role: 'assistant', incidentId: result.incidentId,
      text: `调查已进入队列，事故编号：${result.incidentId}。当前状态：${result.status}。这不代表已经修复；请在事件详情中查看真实进度。` })
    emit('open-incident', result.incidentId)
    question.value = ''
  } catch (error) {
    messages.value.push({ role: 'error', text: `调查未能创建：${error.message}` })
  } finally {
    busy.value = false
  }
}

async function send() {
  if (!question.value.trim() || busy.value) return
  busy.value = true
  const text = question.value.trim()
  question.value = ''
  messages.value.push({ role: 'user', text })
  const answer = { role: 'assistant', text: '' }
  messages.value.push(answer)
  const index = messages.value.length - 1
  controller = new AbortController()
  let completed = false
  try {
    const response = await fetch(`${apiBase}/api/chat_stream`, {
      method: 'POST', headers: headers(), signal: controller.signal,
      body: JSON.stringify({ Id: sessionId, Mode: 'agent', AllowOperations: allowOperations.value,
        Question: serviceId.value ? `本次选择的已注册服务 ID：${serviceId.value}。用户请求：${text}` : text }),
    })
    if (!response.ok) throw new Error(await response.text())
    if (!response.body) throw new Error('服务器未返回响应流')
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, '\n')
      let end
      while ((end = buffer.indexOf('\n\n')) !== -1) {
        const frame = buffer.slice(0, end)
        buffer = buffer.slice(end + 2)
        const data = frame.split('\n').filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).trimStart()).join('\n')
        if (!data) continue
        const event = JSON.parse(data)
        if (event.type === 'error') throw new Error(event.data || '对话处理失败')
        if (event.type === 'content') messages.value[index].text += event.data || ''
        if (event.type === 'done') completed = true
      }
      if (done) break
    }
    if (!completed) throw new Error('连接已中断，回答可能不完整；请查询事故状态确认是否已创建任务')
  } catch (error) {
    messages.value.push({ role: 'error', text: error.name === 'AbortError'
      ? '已停止接收回答。已经受理的运维任务不会因此取消，请查看事件队列。'
      : `回答未完成：${error.message}` })
  } finally {
    busy.value = false
    controller = null
  }
}

onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <section id="assistant" class="assistant-panel panel">
    <div class="section-head"><div><h2>运维助手</h2></div><span class="assistant-caption">问答、调查与事件进度</span></div>
    <p class="assistant-intro">普通问题直接回答；明确发起的调查与自动告警共用同一套 Planner–Executor。默认只读，修复成功以事件验证结果为准。</p>
    <div class="assistant-messages" aria-live="polite" aria-relevant="additions text">
      <p v-if="!messages.length" class="assistant-placeholder">选择服务后，可以问“帮我调查为什么健康检查失败”，也可以用事故编号查询处理进度。</p>
      <article v-for="(message, index) in messages" :key="index" :class="['assistant-message', message.role]">
        <strong>{{ message.role === 'user' ? '你' : message.role === 'error' ? '提示' : '助手' }}</strong>
        <p>{{ message.text || '正在处理…' }}</p>
        <button v-if="message.incidentId" class="button ghost" type="button" @click="emit('open-incident', message.incidentId)">查看事故进度</button>
      </article>
    </div>
    <form class="assistant-compose" @submit.prevent="send">
      <div class="assistant-settings">
        <label>关联服务<select v-model="serviceId"><option value="">未指定，仅问答</option><option v-for="service in services" :key="service.id" :value="service.id">{{ service.displayName }} / {{ service.id }}</option></select></label>
        <label class="assistant-permission"><input v-model="allowOperations" type="checkbox">允许本次请求发起受控修复，仍受服务策略限制</label>
      </div>
      <label class="assistant-question">问题或排查目标<textarea v-model="question" rows="3" maxlength="4000" placeholder="描述希望调查的问题；请不要填写密码或 API Key。" required></textarea></label>
      <div class="assistant-actions">
        <span>{{ allowOperations ? '已授权受控处置，不代表允许任意命令。' : '只读调查，不允许重启或重建。' }}</span>
        <button v-if="busy && controller" class="button ghost" type="button" @click="controller.abort()">停止接收</button>
        <button class="button ghost" type="button" :disabled="busy || !serviceId || !question.trim()" @click="startInvestigation">直接发起调查</button>
        <button class="button primary" :disabled="busy || !question.trim()" type="submit">{{ busy ? '处理中' : '发送问题' }}</button>
      </div>
    </form>
  </section>
</template>

<style scoped>
.assistant-panel { margin: 18px 0; }
.assistant-caption, .assistant-intro, .assistant-placeholder { color: #66675e; font-size: 12px; line-height: 1.8; }
.assistant-intro { margin: 0; padding: 0 24px 16px; }
.assistant-messages { max-height: 360px; overflow-y: auto; padding: 0 24px; }
.assistant-placeholder { padding: 12px 0; }
.assistant-message { padding: 14px 0; border-top: 1px solid var(--line); }
.assistant-message strong { font-size: 12px; }
.assistant-message p { margin: 8px 0; font-size: 13px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.assistant-message.user { color: #625a48; }
.assistant-message.error { color: #9b351f; }
.assistant-compose { padding: 18px 24px 24px; }
.assistant-settings { display: flex; gap: 20px; align-items: center; flex-wrap: wrap; }
.assistant-settings label, .assistant-question { display: grid; gap: 7px; font-size: 12px; }
.assistant-settings .assistant-permission { display: flex; align-items: center; }
select, textarea { font: inherit; color: var(--ink); background: #fbf9f4; border: 1px solid var(--line); padding: 10px; }
select { max-width: 100%; }
.assistant-question { margin-top: 16px; }
textarea { width: 100%; resize: vertical; min-height: 76px; box-sizing: border-box; }
select:focus-visible, textarea:focus-visible { outline: 2px solid var(--ink); outline-offset: 2px; }
.assistant-actions { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-top: 12px; }
.assistant-actions > span { margin-right: auto; color: #66675e; font-size: 11px; }
@media (max-width: 640px) { .assistant-settings { display: grid; } .assistant-actions > span { flex-basis: 100%; } }
</style>
