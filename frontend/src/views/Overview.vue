<template>
  <div class="container">
    <div class="page-title">巡检概览</div>

    <!-- KPI 卡片 -->
    <div class="stat-cards">
      <div class="stat-card blue">
        <div>
          <div class="label">服务器总数</div>
          <div class="value">{{ stats.totalServers || 0 }}</div>
        </div>
        <div class="card-icon">🖥️</div>
      </div>
      <div class="stat-card green">
        <div>
          <div class="label">在线</div>
          <div class="value">{{ stats.online || 0 }}</div>
        </div>
        <div class="card-icon">✅</div>
      </div>
      <div class="stat-card orange">
        <div>
          <div class="label">告警</div>
          <div class="value">{{ stats.alert || 0 }}</div>
        </div>
        <div class="card-icon">⚠️</div>
      </div>
      <div class="stat-card red">
        <div>
          <div class="label">离线</div>
          <div class="value">{{ stats.offline || 0 }}</div>
        </div>
        <div class="card-icon">⛔</div>
      </div>
    </div>

    <!-- 图表区域 -->
    <div class="chart-row">
      <div class="chart-box" style="flex:2">
        <h4>资源利用率趋势（最近巡检）</h4>
        <div style="position:relative;height:300px">
          <canvas id="trendChart"></canvas>
        </div>
      </div>
      <div class="chart-box" style="flex:1">
        <h4>磁盘使用率 Top 5</h4>
        <div style="position:relative;height:300px">
          <canvas id="diskChart"></canvas>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { getStats } from '../api/inspection.js'
import Chart from 'chart.js/auto'

const stats = ref({})
const trends = ref([])
const diskTop = ref([])
let trendChart = null
let diskChart = null
let timer = null

function renderTrendChart() {
  const canvas = document.getElementById('trendChart')
  if (!canvas) return
  const t = trends.value
  const labels = t.map(p => (p.ip || '') + ' ' + (p.scanTime || '').substring(11, 16))
  const cpuData = t.map(p => p.cpuPercent || 0)
  const memData = t.map(p => p.memPercent || 0)
  if (trendChart) trendChart.destroy()
  trendChart = new Chart(canvas, {
    type: 'line',
    data: {
      labels,
      datasets: [
        { label: 'CPU 使用率 (%)', data: cpuData, borderColor: '#409EFF', backgroundColor: 'rgba(64,158,255,0.12)', fill: true, tension: 0.3 },
        { label: '内存使用率 (%)', data: memData, borderColor: '#67C23A', backgroundColor: 'rgba(103,194,58,0.12)', fill: true, tension: 0.3 }
      ]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      scales: { y: { min: 0, max: 100, title: { display: true, text: '%' } } },
      plugins: { legend: { position: 'top' } }
    }
  })
}

function renderDiskChart() {
  const canvas = document.getElementById('diskChart')
  if (!canvas) return
  const d = (diskTop.value || []).slice().reverse()
  const labels = d.map(x => x.ip || '-')
  const data = d.map(x => x.usagePercent || 0)
  const colors = data.map(v => v >= 80 ? '#F56C6C' : v >= 60 ? '#E6A23C' : '#67C23A')
  if (diskChart) diskChart.destroy()
  diskChart = new Chart(canvas, {
    type: 'bar',
    data: { labels, datasets: [{ label: '磁盘使用率 (%)', data, backgroundColor: colors, barThickness: 24 }] },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      indexAxis: 'y',
      scales: { x: { min: 0, max: 100, title: { display: true, text: '%' } } },
      plugins: { legend: { display: false } }
    }
  })
}

async function load() {
  try {
    const res = await getStats()
    stats.value = res.data || {}
    trends.value = stats.value.trends || []
    diskTop.value = stats.value.diskTop || []
    renderTrendChart()
    renderDiskChart()
  } catch (e) {
    console.error('加载概览失败', e)
  }
}

onMounted(() => {
  load()
  timer = setInterval(load, 15000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
  if (trendChart) trendChart.destroy()
  if (diskChart) diskChart.destroy()
})
</script>

<style scoped>
.container { padding: 20px; max-width: 1400px; margin: 0 auto; }
.page-title { font-size: 20px; font-weight: 600; margin-bottom: 16px; color: #303133; }

.stat-cards { display: flex; gap: 20px; margin-bottom: 24px; }
.stat-card {
  flex: 1;
  background: white;
  border-radius: 8px;
  padding: 20px 24px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.06);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.stat-card .label { color: #909399; font-size: 14px; margin-bottom: 8px; }
.stat-card .value { font-size: 32px; font-weight: 700; }
.stat-card .card-icon { font-size: 40px; opacity: 0.25; line-height: 1; }
.stat-card.blue .value { color: #409EFF; }
.stat-card.green .value { color: #67C23A; }
.stat-card.orange .value { color: #E6A23C; }
.stat-card.red .value { color: #F56C6C; }

.chart-row { display: flex; gap: 20px; margin-bottom: 24px; }
.chart-box {
  flex: 1;
  background: white;
  border-radius: 8px;
  padding: 20px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.06);
}
.chart-box h4 { margin: 0 0 16px 0; font-size: 15px; color: #303133; }
</style>
