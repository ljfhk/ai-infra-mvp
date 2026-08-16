<template>
  <div style="padding: 20px">
    <el-row style="margin-bottom: 20px">
      <el-col :span="18">
        <h3>服务器列表</h3>
      </el-col>
      <el-col :span="6" style="text-align: right">
        <el-button type="primary" @click="showDialog = true">+ 添加服务器</el-button>
        <el-button @click="loadHistory">刷新</el-button>
      </el-col>
    </el-row>

    <!-- 服务器列表表格 -->
    <el-table :data="records" border stripe style="width: 100%">
      <el-table-column prop="ip" label="IP地址" width="150" />
      <el-table-column prop="hostname" label="主机名" width="150" />
      <el-table-column prop="os_info" label="操作系统" />
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'">
            {{ row.status === 'SUCCESS' ? '成功' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="scan_time" label="巡检时间" width="180" />
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="doScan(row)">巡检</el-button>
          <el-button size="small" @click="viewDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 添加服务器对话框 -->
    <el-dialog v-model="showDialog" title="添加服务器" width="500px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="IP地址">
          <el-input v-model="form.ip" placeholder="例如: 192.168.1.10" />
        </el-form-item>
        <el-form-item label="端口">
          <el-input v-model.number="form.port" placeholder="22" />
        </el-form-item>
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="root" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" placeholder="留空使用密钥" />
        </el-form-item>
        <el-form-item label="密钥路径">
          <el-input v-model="form.keyPath" placeholder="例如: /root/.ssh/id_rsa" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showDialog = false">取消</el-button>
        <el-button type="primary" @click="doScan(form)" :loading="scanning">
          开始巡检
        </el-button>
      </template>
    </el-dialog>

    <!-- 巡检结果对话框 -->
    <el-dialog v-model="showResult" title="巡检结果" width="800px">
      <el-descriptions :column="2" border v-if="result">
        <el-descriptions-item label="IP">{{ result.ip }}</el-descriptions-item>
        <el-descriptions-item label="主机名">{{ result.hostname }}</el-descriptions-item>
        <el-descriptions-item label="操作系统">{{ result.osInfo }}</el-descriptions-item>
        <el-descriptions-item label="CPU">{{ result.cpuInfo }}</el-descriptions-item>
        <el-descriptions-item label="内存">{{ result.memInfo }}</el-descriptions-item>
        <el-descriptions-item label="磁盘">{{ result.diskInfo }}</el-descriptions-item>
        <el-descriptions-item label="安全">{{ result.securityInfo }}</el-descriptions-item>
        <el-descriptions-item label="巡检时间">{{ result.scanTime }}</el-descriptions-item>
      </el-descriptions>
      <el-alert v-if="result && !result.success" type="error" :title="result.errorMsg" show-icon />
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { scanServer, getHistory } from '../api/inspection.js'

const records = ref([])
const showDialog = ref(false)
const showResult = ref(false)
const scanning = ref(false)
const result = ref(null)
const form = ref({ ip: '', port: 22, username: 'root', password: '', keyPath: '' })

async function loadHistory() {
  try {
    const res = await getHistory()
    records.value = res.data || []
  } catch (e) {
    ElMessage.error('加载失败: ' + e.message)
  }
}

async function doScan(row) {
  scanning.value = true
  try {
    const res = await scanServer(row)
    result.value = res.data
    showResult.value = true
    ElMessage.success('巡检完成: ' + row.ip)
    loadHistory()
  } catch (e) {
    ElMessage.error('巡检失败: ' + e.message)
  } finally {
    scanning.value = false
    showDialog.value = false
  }
}

function viewDetail(row) {
  try {
    result.value = JSON.parse(row.raw_json || '{}')
  } catch {
    result.value = row
  }
  showResult.value = true
}

onMounted(() => loadHistory())
</script>
