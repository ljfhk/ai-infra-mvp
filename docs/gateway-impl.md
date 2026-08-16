# 推理网关（档1 轻量）实现文档

> 适用场景：在 `localhost` 的服务器巡检系统（Spring Boot 3.2.1 + Java 17 + Vue3 单文件前端 + SQLite）内嵌一套轻量 AI 推理网关。
> 编写背景：承接《推理网关部署选型速查表》的结论——当前项目属**档1 嵌入式单体**，鉴权 / 限流 / 多模型路由 / 调用链追踪**全部塞进现有单 jar**，不引 Nacos / Gateway / Dashboard，零新增 Maven 依赖。
> 配套文档：同目录下 `推理网关部署选型速查表.md`（讲"为什么是档1 / 要不要上微服务"），本文档讲"档1 具体怎么实现的"。

---

## 一、设计决策（为什么这么干）

| 决策点 | 选择 | 理由 |
|------|------|------|
| 部署形态 | 档1 嵌入式单体 | 182 单机 / 内部工具 / 唯一用户 / 单 vLLM，单体长期够用，不给自己加进程与故障点。 |
| 第三方依赖 | **零新增** | 限流用自研内存滑动窗口，鉴权用 SQLite 存 Key，转发用 JDK17 自带 `HttpClient`。`mvn clean package -DskipTests -o` 离线可编。 |
| 鉴权方案 | API Key（Bearer）+ SQLite | 比 Sa-Token / Spring Security 更轻，且 Key 可页面增删、可审计。 |
| 限流方案 | 内存滑动窗口（每秒） | 单实例下精度足够；多实例才需要 Redis / Sentinel，档1 不做。 |
| 路由方案 | SQLite 路由表 `model → base_url` | 支持按模型前缀转发到不同 vLLM；未命中走默认地址。 |
| 追踪方案 | SQLite `inference_call_log` | 每次调用落库，前端可查最近 N 条。 |

> **与选型文档的关系**：选型文档说"要阿里那套精华（Sentinel + Sa-Token）而不摆排场"，本文档用**更极致的零依赖实现**落地了同样的四个能力——对档1 来说，自研滑动窗口比引 sentinel-core 还省事，且离线可编。

---

## 二、架构总览

```
                         ┌─────────────────────────────────────────────┐
   调用方(Dify/脚本/页面) │  服务器巡检系统  :8080  (单 jar, Spring Boot)  │
   ── POST /api/gateway/  │                                               │
      v1/chat/completions │   GatewayController  (/api/gateway/*)         │
      Authorization:       │        │                                      │
      Bearer <sk-...>      │        ▼                                      │
                         │   GatewayService                               │
                         │   ① 鉴权 findKey()  → 401 无效/禁用             │
                         │   ② 全局限流 allow("global", qps) → 429         │
                         │   ③ Key 级限流 allow("key:"+kv)   → 429         │
                         │   ④ 解析 model + 强制 stream:false              │
                         │   ⑤ 路由 resolveBaseUrl(model)                  │
                         │   ⑥ 日 token 限额 allowDaily()   → 429         │
                         │        │                                      │
                         │        ▼  JDK17 HttpClient (强制 HTTP/1.1)       │
                         └────────┼──────────────────────────────────────┘
                                  │
                                  ▼  http://<vllm>/v1/chat/completions
                         vLLM (WSL2, 经 Windows netsh 转发 localhost:8000)
                                  │
                                  ▼  记录 usage + 写入 inference_call_log
```

请求生命周期：`鉴权 → 全局限流 → Key 限流 → 路由解析 → 日 token 限额 → 转发 vLLM → 解析 usage → 落库追踪`。任意一步不通过即短路返回对应状态码。

---

## 三、数据模型（4 张 SQLite 表）

`GatewayService.init()` 在 `@PostConstruct` 阶段建表并种子化默认配置。

| 表名 | 字段 | 说明 |
|------|------|------|
| `api_key` | `id, key_value(UNIQUE), name, status, qps_limit, token_daily_limit, created_at, last_used_at` | API Key 清单；`status=1` 启用；`qps_limit=0` 表示跟随全局；`token_daily_limit=0` 表示不限。 |
| `gateway_route` | `id, model, base_url, priority, enabled` | 路由表；`model='*'` 为兜底；按 `priority DESC` 取第一条命中。 |
| `gateway_config` | `cfg_key(PK), cfg_value` | 全局配置；种子 `global_qps=20`、`default_base_url=<兜底地址>`。 |
| `inference_call_log` | `id, key_prefix, model, prompt_tokens, completion_tokens, latency_ms, status, created_at` | 调用追踪；`key_prefix` 取 Key 前 12 位避免明文泄露。 |

种子逻辑：仅当两张表为空时才写入默认值（`global_qps=20`、`*` 路由指向兜底地址），重启不覆盖已有配置。

---

## 四、核心能力详解

### 4.1 鉴权（API Key）
- Key 格式：`sk-` + 32 位十六进制（`SecureRandom` 生成），如 `sk-3d9c53a344b6bfbb`。
- 校验：`findKey()` 去掉 `Bearer ` 前缀后查 `api_key` 表；`key == null` 或 `status != 1` → 返回 `{status:401}`。
- 前端「测试请求」与所有代理调用都必须带 `Authorization: Bearer <Key>`。

### 4.2 限流（两层）
- **全局 QPS**：`allow("global", getGlobalQps())`，`gateway_config.global_qps`，默认 20（0 = 不限）。
- **Key 级 QPS**：`allow("key:"+keyValue, qps_limit)`，每个 Key 独立滑动窗口（1 秒）。
- **每日 token 限额**：`allowDaily(keyValue, token_daily_limit, tokens)`，Key 级，按 `keyValue|yyyy-MM-dd` 累计（0 = 不限）。
- 滑动窗口实现：`ConcurrentHashMap<String, Deque<Long>>`，入队时间戳，弹出 1 秒前的，队列长度 ≥ limit 即拒绝。

```java
private boolean allow(String bucket, int limit) {
    if (limit <= 0) return true;          // 0 = 不限
    long now = System.currentTimeMillis();
    Deque<Long> q = windows.computeIfAbsent(bucket, k -> new ConcurrentLinkedDeque<>());
    synchronized (q) {
        while (!q.isEmpty() && now - q.peekFirst() > 1000) q.pollFirst();
        if (q.size() >= limit) return false;
        q.addLast(now);
        return true;
    }
}
```

### 4.3 多模型路由
- `resolveBaseUrl(model)`：先精确匹配 `model`（enabled=1，priority 高者优先）；未命中取 `*` 兜底路由；再不行退回 `gateway.default-base-url` / `inference.vllm.base-url`。
- 典型用法：默认 `*` → `http://localhost:8000`（你本机 WSL2 vLLM）；可加 `Qwen/Qwen2.5-7B-Instruct` → 另一台机器的 vLLM，实现按模型分流。

### 4.4 调用链追踪
- 每次代理调用（无论成败）都写 `inference_call_log`：Key 前缀、model、prompt/completion tokens、耗时(ms)、上游 status。
- 同时更新 `api_key.last_used_at`，便于发现长期未用的 Key。
- 前端「调用追踪」按 `id DESC LIMIT N` 展示最近 N 条。

### 4.5 转发细节（必看坑）
- **强制 `stream:false`**：网关只处理非流式响应（便于解析 `usage` 统计 token、便于同步返回）。请求体里的 `stream` 被覆盖为 false。
- **强制 HTTP/1.1**：见第五节，这是整个网关能否跑通的决定性修复。
- 转发地址：`baseUrl + "/v1/chat/completions"`，超时 60s；`/v1/models` 用 GET，超时 10s。
- 透传：Controller 原样返回上游 `status` + `body`；网关自身错误（无 Key / 限流 / 502）返回 `{"error":"..."}`。

---

## 五、关键坑：Java HttpClient 的 h2c 升级 vs Windows netsh（决定成败）

### 现象
代理转发 vLLM 时稳定返回 `400 {'type': 'missing', 'loc': ('body',)}`——vLLM 收到的请求体是空的。

### 排查路径（耗时最长的一段）
1. 怀疑 body 丢失 → 加 debug 日志，确认网关侧 body 完整。
2. 试手动加 `Content-Length` 头 → Java 报错 `restricted header name: "Content-Length"`（HttpClient 禁止手动设）。
3. 改 Controller `consumes` 从 `ALL_VALUE` 改为 `APPLICATION_JSON_VALUE` → 仍 400。
4. 架一个 Python echo server 接收网关转发 → 网关转发本身完全正常（Content-Type=application/json、body 完整）。说明问题出在**网关 → vLLM** 这一段链路，不在网关内部。
5. **tcpdump 抓包**：发现 Java `HttpClient` 默认发出 `Upgrade: h2c` + `Connection: Upgrade`（HTTP/2 明文升级尝试）。

### 根因
请求经 **Windows `netsh` portproxy**（182 → `localhost:8000` → WSL2 vLLM）转发时，netsh **不会做 h2c 握手**，把升级请求原样转给 uvicorn，uvicorn 解析失败、丢弃 body，vLLM 看到空 body → 400。

### 修复
构造 `HttpClient` 时强制锁定 HTTP/1.1：

```java
private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .version(HttpClient.Version.HTTP_1_1)   // ← 关键：禁掉 h2c 升级
        .build();
```

修复后 400 消失，真实对话返回 200，Qwen 正常回复。

> **经验沉淀**：凡用 JDK17 `HttpClient` 经 Windows netsh / 普通反向代理（nginx 反代 + 老协议）转发，**一律显式 `.version(HttpClient.Version.HTTP_1_1)`**，别信默认值。这一点在 vLLM 转发、Dify 对接等场景都会踩。

---

## 六、API 接口清单

基址：`http://localhost:8080/api/gateway`

### 管理接口（页面调用，无需 Key）
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/keys` | 列出所有 Key |
| POST | `/keys` | 新建 Key，body：`{name, qpsLimit, tokenDailyLimit}` |
| POST | `/keys/{id}/status` | 启停 Key，body：`{status:1\|0}` |
| DELETE | `/keys/{id}` | 删除 Key |
| GET | `/config` | 读全局配置 `{globalQps, defaultBaseUrl}` |
| POST | `/config` | 改全局配置，body：`{globalQps, defaultBaseUrl}` |
| GET | `/routes` | 列出路由 |
| POST | `/routes` | 新建路由，body：`{model, baseUrl}` |
| POST | `/routes/{id}/status` | 启停路由，body：`{enabled:1\|0}` |
| DELETE | `/routes/{id}` | 删除路由 |
| GET | `/calls?limit=50` | 调用追踪（最近 N 条） |

### 代理接口（需 `Authorization: Bearer <Key>`）
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/v1/chat/completions` | OpenAI 兼容对话，透传上游 |
| GET | `/v1/models` | 列出上游模型 |

---

## 七、配置项

`mvp/src/main/resources/application.yml`：

```yaml
# ===== 推理网关（档1 轻量：鉴权/限流/路由/追踪，无第三方依赖） =====
# default-base-url: 路由未命中时的兜底 vLLM 地址；留空则回退到 inference.vllm.base-url。
# global-qps: 全局每秒最大请求数（0 = 不限）。Key 级 QPS 在页面/接口单独配置。
gateway:
  default-base-url:
  global-qps: 20
```

> 实际默认地址在 `init()` 阶段写入 `gateway_config` 表；改表里的 `default_base_url` 比改 yml 更常用（页面「限流配置」可直接改，重启不丢）。

---

## 八、前端控制台

- 文件：`C:\Users\Lenovo\WorkBuddy\2026-06-21-08-30-17\index_v5.html`（单文件 HTML，Vue3 + Element Plus + Chart.js + axios 全走 CDN）。
- 部署位置：拷贝到 `localhost:/opt/mvp/web-static/index.html`，由 Spring Boot 通过外部静态目录直接 serve（**改前端只需替换该文件，必要时 `./run.sh` 重启，无需重打 jar**）。
- 进入路径：首页「推理服务管理」tab → 「网关控制台」子区，含 6 个子页：

| 子页 | 功能 |
|------|------|
| 网关状态 | 展示网关地址 `/api/gateway/v1/chat/completions` 与转发说明 |
| API Key | 新建 / 启停 / 删除 Key，显示 Key 掩码与 qps/token 限额 |
| 限流配置 | 改全局 QPS 与默认 vLLM 地址 |
| 模型路由 | 维护 `model → base_url` 路由表 |
| 调用追踪 | 最近调用记录（model / tokens / 耗时 / status） |
| 测试请求 | 填 Key + 模型名直发对话，验证网关可用性 |

> Vue3 global IIFE 模式下 `computed` 渲染不稳，前端状态变量全部用 `ref` + 手动赋值。

---

## 九、构建与部署

```bash
# 182 上（JAVA_HOME=/usr/local/jdk-17.0.16）
cd /opt/mvp
/usr/local/maven/bin/mvn clean package -DskipTests        # 离线可编，无新依赖
./run.sh                                                 # 重启 jar

# 仅改前端时（无需重打 jar）
cp index_v5.html /opt/mvp/web-static/index.html
# 必要时 ./run.sh 重启
```

> 部署覆盖前端前务必先备份 `static/` 与 jar（`static.bak.*` / `jar.bak.*` 已留）；优先用外部 `web-static/` 目录，避免 repackage 误伤页面。

---

## 十、端到端验证结果（verify_gw.sh，全绿）

| 检查项 | 期望 | 实际 |
|------|------|------|
| ① 读配置 | 200, globalQps=20 | ✅ |
| ② 新建 Key | 返回 `sk-...` | ✅ |
| ③ 真实对话 | 200（Qwen 真回复） | ✅ |
| ④ 无效/无 Key | 401 | ✅ |
| ⑤ 限流 qps=1 连发3次 | 200 / 429 / 429 | ✅ |
| ⑥ 调用追踪 | 有记录 | ✅ |
| ⑦ 前端含网关 UI | 是 | ✅ |

复测（本会话）：`GET /api/gateway/config` → 200，`GET /api/gateway/keys` → 返回 SQLite 真实 Key 数据，服务在跑。

---

## 十一、怎么用（含 Dify 接入）

### 页面操作
182 打开 `:8080` → 推理服务管理 → 网关控制台，在「API Key」建 Key、「限流配置」调全局 QPS、「模型路由」改后端地址、「测试请求」试对话。

### 外部接 Dify / 脚本
把调用方的 Base URL 从直连 vLLM（`localhost:8000/v1`）改为网关：

```
Base URL : http://localhost:8080/api/gateway/v1
API Key  : sk-xxxxxxxxxxxxxxxx   （网关发的 Key，非 vLLM 的）
```

即统一收口鉴权 + 限流，后端仍转发到你的 WSL2 vLLM。适合多 Dify 应用 / 多团队共用一个 vLLM 的场景。

### curl 直测
```bash
curl -s http://localhost:8080/api/gateway/v1/chat/completions \
  -H "Authorization: Bearer sk-3d9c53a344b6bfbb" \
  -H "Content-Type: application/json" \
  -d '{"model":"Qwen/Qwen2.5-1.5B-Instruct","messages":[{"role":"user","content":"你好"}]}'
```

---

## 十二、升级路径 / 后续

档1 撑满单机内部够用。若触发《推理网关部署选型速查表》的"升级自检清单"（峰值 QPS>50、开放给 2+ 团队、需故障隔离、客户要求微服务、需按模型/租户扩缩容），按文档平滑切到：
- **档2**：抽出一个独立 `gateway-service`（Gateway + Sentinel）与巡检系统并排跑，不强制 Nacos。
- **档3**：SCA 全套（Nacos + Gateway + 多服务 + K8s）。代码无需重写，只拆进程。

可选增强（仍在档1 内）：按模型分别限流、Key 过期时间、调用追踪分页 + 按状态筛选、失败调用告警。

---

*文档对应代码：`mvp/src/main/java/com/inspection/service/GatewayService.java`、`mvp/src/main/java/com/inspection/controller/GatewayController.java`、`mvp/src/main/resources/application.yml`、`2026-06-21-08-30-17/index_v5.html`。*
