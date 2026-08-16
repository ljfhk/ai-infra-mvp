<template>
  <div style="padding: 20px">
    <el-row style="margin-bottom: 12px" :gutter="12">
      <el-col :span="18">
        <el-input v-model="vllmInput" clearable
          placeholder="vLLM 地址，如 http://localhost:8000（不要带 /v1，留空用服务端配置）">
          <template #prepend>vLLM 地址</template>
        </el-input>
      </el-col>
      <el-col :span="6">
        <el-button type="primary" @click="applyUrl">应用并刷新</el-button>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-bottom: 16px">
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="kpi-title">vLLM 地址</div>
          <div class="kpi-value" style="font-size:14px;word-break:break-all">{{ status.baseUrl || '-' }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="kpi-title">运行状态</div>
          <div class="kpi-value">
            <el-tag :type="status.online ? 'success' : 'danger'" size="large">
              {{ status.online ? '在线' : '离线' }}
            </el-tag>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="kpi-title">已加载模型</div>
          <div class="kpi-value">{{ status.modelCount ?? '-' }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="kpi-title">显存占用 (KV Cache)</div>
          <div class="kpi-value">
            <span v-if="gpuUsage !== null">{{ gpuUsage }}%</span>
            <span v-else>-</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-alert
      v-if="status.success === false"
      type="error"
      :title="'vLLM 不可达：' + (status.errorMsg || '')"
      show-icon
      style="margin-bottom: 16px"
    />

    <el-row style="margin-bottom: 16px">
      <el-button type="primary" :loading="loading" @click="refresh">刷新</el-button>
      <el-button type="warning" :loading="restarting" @click="doRestart">重启服务</el-button>
      <span style="margin-left:12px;color:#909399;font-size:12px">每 5 秒自动刷新</span>
    </el-row>

    <el-row :gutter="16">
      <el-col :span="14">
        <el-card shadow="never" header="已加载模型">
          <el-table :data="models" border stripe height="320">
            <el-table-column prop="id" label="模型名称" />
            <el-table-column prop="object" label="类型" width="120" />
            <el-table-column prop="owned_by" label="提供方" width="120" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card shadow="never" header="关键运行指标">
          <el-table :data="keyMetrics" border stripe height="320">
            <el-table-column prop="label" label="指标" />
            <el-table-column prop="value" label="值" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getInferenceStatus,
  getInferenceModels,
  getInferenceMetrics,
  restartInference
} from '../api/inference.js'

const status = ref({})
const vllmInput = ref(localStorage.getItem('vllmBaseUrl') || '')
const models = ref([])
const metricsMap = ref({})
const loading = ref(false)
const restarting = ref(false)
let timer = null

const gpuUsage = computed(() => {
  const m = metricsMap.value || {}
  let v = m['vllm:kv_cache_usage_perc']
  if (v == null) v = m['vllm:gpu_cache_usage_sys']
  if (v == null) v = m['vllm:gpu_cache_usage_perc']
  if (v == null) v = m['vllm:gpu_memory_usage']
  if (v == null) return null
  return (v * 100).toFixed(1)
})

// 关键指标的展示映射（usage 类已在顶部卡片展示，这里不再重复）
const KEY_METRIC_LABELS = {
  'vllm:num_requests_running': '运行中请求数',
  'vllm:num_requests_waiting': '排队请求数',
  'vllm:time_to_first_token_seconds_mean': '平均首 token 延迟(s)',
  'vllm:time_per_output_token_seconds_mean': '平均出 token 延迟(s)',
  'vllm:request_prompt_tokens_total': '累计输入 token',
  'vllm:request_generation_tokens_total': '累计生成 token',
  'vllm:request_count_total': '累计请求数',
  'vllm:running_requests': '运行中请求数(兼容字段)',
  'vllm:waiting_requests': '排队请求数(兼容字段)',
  'vllm:kv_cache_usage_perc': 'KV Cache 使用率',
  'process_resident_memory_bytes': '进程内存占用(字节)'
}

const keyMetrics = computed(() => {
  const m = metricsMap.value || {}
  const rows = []
  for (const [name, label] of Object.entries(KEY_METRIC_LABELS)) {
    if (m[name] != null) {
      let val = m[name]
      if (typeof val === 'number') {
        if (name.includes('usage')) val = (val * 100).toFixed(1) + '%'
        else val = val.toLocaleString()
      }
      rows.push({ label, value: val })
    }
  }
  return rows
})

async function refresh() {
  loading.value = true
  try {
    const [s, mod, met] = await Promise.all([
      getInferenceStatus(vllmInput.value),
      getInferenceModels(vllmInput.value),
      getInferenceMetrics(vllmInput.value)
    ])
    status.value = s.data || {}
    models.value = (mod.data && mod.data.data) || []
    metricsMap.value = (met.data && met.data.metrics) || {}
  } catch (e) {
    ElMessage.error('加载失败: ' + e.message)
  } finally {
    loading.value = false
  }
}

async function doRestart() {
  try {
    await ElMessageBox.confirm('确认重启 vLLM 推理服务？', '提示', { type: 'warning' })
  } catch {
    return
  }
  restarting.value = true
  try {
    const res = await restartInference()
    const d = res.data || {}
    if (d.success) {
      ElMessage.success('重启指令已执行（exitCode=' + d.exitCode + '）')
    } else {
      ElMessage.warning('重启未执行：' + (d.errorMsg || '未知原因'))
    }
    refresh()
  } catch (e) {
    ElMessage.error('重启失败: ' + e.message)
  } finally {
    restarting.value = false
  }
}

function applyUrl() {
  let url = (vllmInput.value || '').trim().replace(/\/+$/, '')
  if (url.endsWith('/v1')) {
    url = url.slice(0, -3).replace(/\/+$/, '')
  }
  vllmInput.value = url
  localStorage.setItem('vllmBaseUrl', url)
  refresh()
}

onMounted(() => {
  refresh()
  timer = setInterval(refresh, 5000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.kpi-title { color: #909399; font-size: 13px; margin-bottom: 8px; }
.kpi-value { font-size: 22px; font-weight: 600; color: #303133; }
</style>
