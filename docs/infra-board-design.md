# AI Infra 能力看板 · 设计文档

> 配套文档：《AI Infra 能力看板 落地计划》（管"何时做 / 优先级 / 后端 Service 落点"）
> 本文档管"看板是什么 / 长什么样 / 每模块怎么交互"，作为后续**逐模块实现的一比一蓝图**。
> 目标系统：服务器巡检系统 MVP（Spring Boot 3.2.1 + Java 17 + 单文件 Vue3 前端，已部署 `localhost:8080`）

---

## 1. 文档目的与边界

把"JD 里的 9 个 AI Infra 技能点能否挂到页面"这个问题，落成一份**可照着写代码**的产品+接口规格。

- **本文不重复**讲优先级和风险控制（那在《落地计划》里）。
- **本文要给出**：信息架构、视觉规范、9 个模块的**面板级交互规格**、后端接口约定、数据模型补充，让实现时无需再决策"这个按钮点了干啥"。

受众：①面试 demo（最能讲清楚 SRE / AI Infra 日常）；②日常运维自助；③客户演示。

---

## 2. 信息架构（IA）

### 2.1 与现有页面的关系

当前顶部一级 tab：

```
概览(dashboard)  |  服务器列表(list)  |  推理服务管理(inference)
```

**新增一个一级 tab**：

```
概览  |  服务器列表  |  推理服务管理  |  AI Infra 看板(infra)
```

- 「推理服务管理」下的**网关 6 子 tab（状态/Key/限流/路由/追踪/测试）保留**，作为"网关"能力的深度操作入口。
- 「AI Infra 看板」是**总览 + 入口层**：左侧技能矩阵、右侧随选中项切换操作面板。重操作（如压测、量化）的"运行结果详情"可跳到对应子模块或弹窗，但不必每个都占一级 tab。

### 2.2 单页布局（关键设计：不做 9 个 tab）

```
┌───────────────────────────────────────────────────────────┐
│  顶部导航：概览 | 服务器列表 | 推理服务管理 | AI Infra 看板   │
├──────────────────────┬────────────────────────────────────┤
│  左：技能矩阵(固定宽)  │  右：操作面板(随左侧选中行切换)        │
│  ─────────────────    │   ─────────────────────────────    │
│  [✔ 推理平台部署]      │   (示例) 选中"性能评测"时显示：        │
│  [✔ 运行环境]          │    · 压测表单(模型/并发/轮数)        │
│  [✔ OpenAI API]       │    · 运行按钮 + 进度                │
│  [🟡 鉴权限流并发]     │    · 指标卡(TTFT/TPOT/吞吐/显存)     │
│  [🔵 性能评测]         │    · Chart.js 折线图                │
│  [🔵 量化方案]         │                                     │
│  [🔵 故障排查]         │                                     │
│  [🔵 脚本交付]         │                                     │
│  [🔵 文档手册]         │                                     │
│                        │                                     │
│  状态徽标：            │                                     │
│   ✔ 已落地  🟡 已接入   │                                     │
│   🔵 查看/待建          │                                     │
└──────────────────────┴────────────────────────────────────┘
```

**为什么这么做**：9 个一级 tab 会把导航撑爆、且语义重叠。左矩阵右面板 = 一个页面收纳全部能力，面试时"点一下切一个"节奏最好。

---

## 3. 视觉与前端规范

沿用既有约定，**不引入新构建链**：

| 项 | 约定 |
|---|---|
| 前端形态 | 单文件 Vue3（IIFE 全局构建）+ Element Plus + Chart.js + axios，CDN 引入 |
| 托管方式 | 外部 `web-static/index.html`，改前端**只换文件**，无需重打 jar |
| 样式复用 | 直接复用 `index_v5.html` 里已有的 `.gw-subnav` / `.gw-tab` / `.gw-section-title` / `.gw-form-row` / `.gw-key-box` / `.gw-result` 系列 class |
| 响应式 | 左矩阵固定 280px，右面板 `flex:1`；窄屏下矩阵折叠为顶部下拉 |
| 状态色 | 成功 `#67C23A` / 警告 `#E6A23C` / 危险 `#F56C6C` / 主色 `#409EFF`（Element Plus 默认） |
| 代码块 | 浅底 `#f7f7f7`、等宽字体、可横滑（公众号同款规则，页面内调试输出也照此） |
| 危险操作 | 任何"系统级变更"（升级 Docker/CUDA、重启推理进程）必须 `el-popconfirm` 二次确认 + 操作日志 |

`computed` 在 IIFE 全局模式下不可靠 → 统一用 `ref` + 手动赋值（既有代码已验证）。

---

## 4. 模块详细规格（逐模块蓝图）

> 每模块给出：面板布局 / 控件 / 数据来源 / 交互 / 后端接口约定。
> 状态：`✔` 已落地 · `🟡` 部分落地需扩展 · `🔵` 待建。

### 4.1 推理平台部署（vLLM / SGLang）— `✔`→扩展为 `🟡`

**面板布局**
- 平台类型切换：`el-radio` [vLLM | SGLang]
- 进程控制：`启动 / 停止 / 重启` 按钮（带 loading）
- 状态区：状态灯（绿=running / 灰=stopped）、PID、启动时长、监听地址
- 已加载模型：`el-table`（模型名 / 上下文长度 / 占用显存）
- 启动参数：`el-input` 多行（model、tensor-parallel、gpu-memory-utilization、max-model-len 等）

**数据来源**：后端 SSH 到推理机执行 `ps`/`curl /models`，或读 vLLM `/metrics`。
**后端接口**：`InferenceService`（扩展现有）：
- `GET /api/infra/platform/status` → `{type, running, pid, models:[...]}`
- `POST /api/infra/platform/start` `{type, args}` → 后台拉起（nohup + 日志落文件）
- `POST /api/infra/platform/stop` `{type}` → 杀进程（按 pid / pkill）

**交互**：启动后用轮询刷新状态；启动失败弹日志片段。

---

### 4.2 运行环境（Docker / CUDA / Container Toolkit）— `🔵` 只读

**面板布局**
- 三张信息卡：`docker version` / `nvidia-smi`（含驱动版本、CUDA 版本、显存） / `nvcc -V`
- 每项显示"采集时间" + 来源主机
- 底部一行："环境升级属于系统级操作，**不在页面提供按钮**，请参考《部署文档》手动执行" + 文档链接

**数据来源**：`EnvService` 通过 SSH 采集，`env_snapshot` 表缓存（避免频繁采集）。
**后端接口**：
- `GET /api/infra/env` → `{docker, nvidiaDriver, cuda, toolkit, capturedAt}`

**红线**：本模块**只读**，任何升级入口一律不开放。

---

### 4.3 OpenAI 兼容 API 服务 — `✔` 已落地

复用网关控制台（推理服务管理 → 网关状态/Key/路由/测试）。本面板只做**摘要 + 跳转**：
- 显示网关基地址 `http://<巡检系统IP>:8080/api/gateway/v1`
- "管理 Key / 限流 / 路由" 按钮 → 切到「推理服务管理」tab
- "在线测试" 内联小窗（复用测试请求子页逻辑）

---

### 4.4 鉴权 / 限流 / 并发控制 — `🟡`（新增并发）

**面板布局**：直接复用网关 4 子 tab（Key / 限流 / 路由 / 追踪），本处补一项：
- 「并发上限」配置卡：`el-input-number` 设置全局最大并发（语义 = 同时进行的推理请求数）
- 后端以 `Semaphore` 实现：超并发时返回 `429 {error:"concurrency limit exceeded"}`

**后端接口（扩展 GatewayService）**：
- `gateway_config` 表增 `global_concurrency` 字段（默认如 10）
- `chat()` 入口 `tryAcquire` 信号量，finally 释放

**交互**：改并发数即时生效（写表即可，无需重启）。

---

### 4.5 性能评测（TTFT / TPOT / 吞吐 / 显存）— `🔵`

**面板布局**
- 压测表单：`el-form`（模型名 / 并发数 / 请求数 / Prompt 长度 / 是否流式）
- 运行区：`开始压测` 按钮 + 进度条 + 实时日志滚动
- 结果区：
  - 指标卡：`TTFT(avg/p99)`、`TPOT`、`吞吐(req/s)`、`峰值显存`
  - Chart.js 折线：横轴请求序号，纵轴 TTFT / 吞吐双 Y 轴
  - 历史记录 `el-table`（可点开任一次看详情）

**数据来源**：`BenchmarkService` 在推理机跑压测（自研轻量发压，避免强依赖 locust；或复用 locust 若已装）。采集 `/metrics` 或解析响应时间戳算 TTFT/TPOT。
**后端接口**：
- `POST /api/infra/benchmark/run` `{model, concurrency, requests, promptLen}` → 返回 `runId`，后台跑
- `GET /api/infra/benchmark/{runId}` → 轮询进度 + 中间指标
- `GET /api/infra/benchmark/list` → 历史
- 落库 `benchmark_run`

**风险**：压测会瞬时吃满 GPU/显存，可能 OOM → 并发数上限 + 一键中止接口 `POST /benchmark/{runId}/abort`。

---

### 4.6 量化方案（FP16 / BF16 / AWQ / GPTQ）— `🔵` 任务化

**面板布局**
- 任务提交：`el-form`（基座模型路径 / 量化方式 radio / 目标精度 / GPU 数）
- 任务列表：`el-table`（状态：排队/进行中/完成/失败、进度条、输出路径、操作[查看日志/下载]）
- 日志抽屉：点开看实时量化日志

**数据来源**：`QuantizeService` 提交**异步**任务（AWQ/GPTQ 需加载原模型再量化，吃显存+磁盘）。
**后端接口**：
- `POST /api/infra/quantize/submit` `{baseModel, method, bits}` → `taskId`
- `GET /api/infra/quantize/{taskId}` → 进度 + 日志
- 落库 `quantize_task`

**红线（流量敏感）**：
- 量化**默认离线**（`HF_HUB_OFFLINE=1`），不自动下载基座模型；
- 若本地无基座模型，先弹确认"将下载约 X GB"再执行；
- 任务可取消，磁盘/显存不足时提前拦住。

---

### 4.7 故障排查（OOM / CUDA 报错 / API 超时）— `🔵`

**面板布局**
- 「一键诊断」按钮 + 主机选择
- 诊断结果：`el-timeline` 或 `el-table`（级别 / 现象 / 可能原因 / 修复建议）
- 快捷动作：`nvidia-smi 快照`、`dmesg | grep -i error`、`查 vLLM 日志尾部`
- 每条建议附"去服务器确认"提示（页面不越权自动修系统）

**数据来源**：`DiagnoseService` SSH 聚合 vLLM 日志 + `dmesg` + `nvidia-smi`，正则匹配：
- `OutOfMemoryError` / `CUDA out of memory` → 建议降 `gpu-memory-utilization` / 减 `max-model-len`
- `CUDA error` / `driver version` → 建议核对驱动
- `Timeout` / `connection refused` → 建议查网关转发 / netsh（呼应 h2c 坑）
- `{'type':'missing','loc':('body',)}` → 提示 HttpClient h2c 升级问题
**后端接口**：
- `POST /api/infra/diagnose` `{host, checks:[...]}` → 报告
- 落库 `diagnose_log`

---

### 4.8 脚本交付（Shell / Python）— `🔵`（最有说服力）

**面板布局**
- 左侧脚本仓库树（读工作区 `_*.py` / `verify_gw.sh` 等，元数据来自 `script_repo` 表）
- 选中脚本 → 右侧：描述 + 参数表单（按 `params_schema` 动态生成）+ 目标主机输入
- `执行` 按钮 → 后端 SSH 到目标机运行 → 下方实时输出（浅底代码块、可横滑）

**数据来源**：`ScriptService`：
- `GET /api/infra/scripts` → 脚本列表（name/desc/params）
- `POST /api/infra/scripts/{id}/run` `{host, params}` → 后端 `ProcessBuilder`/SSH 执行，流式回传输出

**价值点**：把你已经写的大量 `_*.py`、`verify_gw.sh` 直接外化为"可点可跑"的能力页。

---

### 4.9 文档 / API 手册 / 故障复盘 — `🔵` 链接聚合

**面板布局**
- 按分类 `el-tabs`：部署文档 / API 手册 / 故障复盘 / 公众号文章
- 每类下列表卡片（标题 + 摘要 + 打开按钮），点击在新标签打开对应 `.md` / `.html`
**数据来源**：`DocService` 扫描工作区 `.md`/`.html` 生成 `doc_index`；或静态配置索引。
**后端接口**：`GET /api/infra/docs?category=` → 列表。

---

## 5. 后端约定（统一）

| 约定 | 说明 |
|---|---|
| 进程边界 | 全部在现有单 Spring Boot jar 内，新增 `*Service` 类，**零/极少新增 Maven 依赖** |
| 外部命令 | `ProcessBuilder` + SSH（复用既有 `_ssh*.py` 模式封装为 `SshUtil`） |
| 接口风格 | REST `+ JSON`，统一 `{success, data, message}` 包络，错误码语义化（401/429/500） |
| 持久化 | SQLite（JdbcTemplate），新增表见 §6 |
| 流量敏感 | 量化/压测/下载类一律任务化、可取消、离线优先（见各模块红线） |
| 危险操作 | 系统级变更（升级、强杀）必须二次确认 + 写操作日志 |

---

## 6. 数据模型补充（SQLite 新表）

```sql
-- 环境快照缓存
CREATE TABLE env_snapshot (
  id INTEGER PRIMARY KEY, host TEXT, docker_ver TEXT,
  nvidia_driver TEXT, cuda_ver TEXT, toolkit_ver TEXT,
  captured_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 压测记录
CREATE TABLE benchmark_run (
  id INTEGER PRIMARY KEY, model TEXT, concurrency INT, requests INT,
  ttft_avg REAL, ttft_p99 REAL, tpot REAL, throughput REAL,
  mem_peak_mb INT, status TEXT, created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 量化任务
CREATE TABLE quantize_task (
  id INTEGER PRIMARY KEY, base_model TEXT, method TEXT, bits INT,
  status TEXT, progress INT DEFAULT 0, output_path TEXT,
  log TEXT, created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 诊断日志
CREATE TABLE diagnose_log (
  id INTEGER PRIMARY KEY, host TEXT, check_type TEXT,
  level TEXT, message TEXT, suggestion TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 脚本仓库元数据
CREATE TABLE script_repo (
  id INTEGER PRIMARY KEY, name TEXT, path TEXT,
  desc TEXT, params_schema TEXT  -- JSON: [{key,label,default}]
);

-- 文档索引
CREATE TABLE doc_index (
  id INTEGER PRIMARY KEY, title TEXT, path TEXT,
  category TEXT, summary TEXT
);
```

`gateway_config` 需扩列：`ALTER TABLE gateway_config ADD COLUMN global_concurrency INT DEFAULT 10;`

---

## 7. 逐模块实现顺序（对照《落地计划》）

| 阶段 | 模块 | 说明 |
|---|---|---|
| P0 | 4.4 并发控制 | 网关加 Semaphore，最小改动 |
| P1 | 4.8 脚本执行台 | 外化已有脚本，最像日常 |
| P1 | 4.5 压测台 | TTFT/TPOT/吞吐图，可演示 |
| P1 | 4.7 诊断助手 | 日志正则 + 建议 |
| P1 | 4.2 环境信息（只读） | 低风险展示 |
| P2 | 4.1 平台启停 | 系统级，需谨慎 |
| P2 | 4.6 量化任务 | 资源敏感，最后 |
| P2 | 4.9 文档聚合 | 纯链接 |

> "一个一个来"：每完成一个模块，前端加一行矩阵 + 对应面板，后端加一个 Service + 接口，部署到 182 验证后再下一个。

---

## 8. 验收标准（每个模块交付前自查）

- [ ] 后端接口在 182 上 `curl` 通（含异常路径：无效参数 / 超时 / 无权限）
- [ ] 前端面板能在 `web-static/index.html` 打开、交互无报错（F12 无红）
- [ ] 危险操作有二次确认 + 操作日志
- [ ] 流量敏感操作（量化/压测）确认不会静默下载大模型
- [ ] 该模块的矩阵行状态徽标更新为对应状态
- [ ] 改动只涉及"新增文件 / 改前端文件"，未破坏既有 3 个 tab 与网关功能

---

## 9. 与既有交付物的关系

| 文档 | 角色 |
|---|---|
| 推理网关部署选型速查表.md | 为什么选"档1"（决策依据） |
| 推理网关实现文档.md | 档1 网关怎么实现（代码/坑/验证） |
| 推理网关公众号版.html | 对外传播版 |
| AI Infra 能力看板 落地计划.md | 9 点→页面 的优先级与后端落点 |
| **本文（设计文档）** | **逐模块交互蓝图，照此实现** |
