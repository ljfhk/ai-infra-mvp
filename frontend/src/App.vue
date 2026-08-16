<template>
  <el-container style="height: 100vh">
    <el-header class="app-header">
      <h2 class="app-title">服务器巡检系统</h2>
      <div class="app-meta">MVP v1.0 | {{ buildTime }}</div>
    </el-header>
    <el-main>
      <el-tabs v-model="activeTab" class="app-tabs">
        <el-tab-pane label="概览" name="overview">
          <Overview />
        </el-tab-pane>
        <el-tab-pane label="服务器列表" name="servers">
          <ServerList />
        </el-tab-pane>
        <el-tab-pane label="推理服务管理" name="inference">
          <InferenceManage />
        </el-tab-pane>
      </el-tabs>
    </el-main>
  </el-container>
</template>

<script setup>
import { ref } from "vue";
import Overview from "./views/Overview.vue";
import ServerList from "./views/ServerList.vue";
import InferenceManage from "./views/InferenceManage.vue";

const activeTab = ref("overview");
const now = new Date();
const buildTime = ref(
  `${now.getFullYear()}/${String(now.getMonth() + 1).padStart(2, "0")}/${String(now.getDate()).padStart(2, "0")} ${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}`
);
</script>

<style>
body {
  margin: 0;
  font-family: "Helvetica Neue", Helvetica, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", Arial, sans-serif;
  background: #f5f7fa;
}

.app-header {
  background: #409eff;
  color: white;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.25);
}

.app-title {
  margin: 0;
  font-size: 20px;
  font-weight: 500;
}

.app-meta {
  font-size: 13px;
  opacity: 0.95;
  font-weight: 400;
}

.app-tabs > .el-tabs__header {
  margin-bottom: 16px;
}

.app-tabs .el-tabs__nav-wrap::after {
  height: 1px;
}
</style>
