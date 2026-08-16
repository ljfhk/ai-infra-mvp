# 服务器巡检系统 MVP（server-inspection-mvp）

> 私有化部署的服务器巡检与 AI Infra 运维辅助系统。面向中小团队本地 / 私有化部署场景，把服务器巡检、脚本交付、故障诊断、一键修复、本地推理（vLLM）管理收敛到一个 Web 平台。
>
> 当前处于 **MVP（最小可行产品）** 阶段，聚焦「私有化 AI Infra 运维」场景。

## 技术栈

- **后端**：Spring Boot 3.2.1 + Java 17 + SQLite（零外部依赖，单机可跑）
  - 关键依赖：`spring-boot-starter-web` / `jsch`（SSH 执行远程脚本）/ `sqlite-jdbc` / `spring-boot-starter-jdbc` / `jackson-databind` / `lombok` / `poi-ooxml`（Excel 报告）/ `spring-boot-starter-validation`
- **前端**：Vue 3 + Vite + Element Plus（源码在 `frontend/`，构建后拷贝到 `web-static/` 与 `src/main/resources/static/`）
- **运维脚本**：Bash（`scripts/`、`scripts/diag/`），经 SSH 在目标机执行

## 目录结构

```
.
├── src/main/java/com/inspection/        # 后端：controller / service / dto
├── src/main/resources/                  # application.yml / schema.sql / 内嵌脚本
├── frontend/                            # Vue3 源码（npm run build 产出 dist/）
├── web-static/                          # 前端部署副本（由 Spring Boot 直接 serve）
├── scripts/                             # 巡检 / 监控脚本（baseline_check / disk_check / gpu_watch / demo_hello）
├── scripts/diag/                        # 诊断脚本（diag_disk / diag_gpu / diag_tcp / diag_dmesg / diag_api / diag_vllm_log）
├── docs/                                # 设计文档（网关 gateway-*、看板 infra-board-*）
├── _canonical/index.html                # 前端基线副本
├── mvp.db                               # 运行时 SQLite（首次启动自动建表，不入库）
├── pom.xml / run.sh / start.sh / check-frontend.sh
```

## 功能特性

| 模块 | 说明 |
| --- | --- |
| 概览 / 看板 | 服务器健康总览与可视化看板（infra-board） |
| 服务器列表 | 纳管服务器、批量扫描 |
| 推理管理 | vLLM / Qwen 等本地推理服务管理（InferenceManage） |
| 网关 | 网关透传 / 接入管理（Gateway） |
| 脚本交付 | 后端解析脚本 `@desc` / `@args` 元信息，前端展示参数说明并下发执行 |
| 诊断 | `InfraService.diagnose()` 调用 `scripts/diag/*.sh`，解析脚本输出 JSON（level / message / suggestion） |
| 一键修复 | 后端 `buildFixes()` 依据脚本名 + 级别生成修复命令，前端渲染「一键修复」区块 |

## 本地开发

### 后端
```bash
# 需 JDK 17
mvn package            # 产出 target/*.jar
java -jar target/*.jar # 默认 8080 端口
```
- 首次启动按 `src/main/resources/schema.sql` 自动建表，SQLite 库文件 `mvp.db` 在运行时生成（**不入库**）。
- 配置见 `src/main/resources/application.yml`（含 `cache.period` 等）。

### 前端
```bash
cd frontend
npm install
npm run dev            # 本地开发预览
npm run build          # 产出 dist/，拷贝到 web-static/ 与 src/main/resources/static/
```

## 部署

- 生产前端：`web-static/index.html` 由 Spring Boot 直接 serve，**部署后无需重启 jar**（注意浏览器缓存，强刷验证）。
- 完整 Maven 构建会重新打包 `src/main/resources/static/` 并覆盖 `application.yml`，若使用手写单 HTML 方案需重新同步并补 `cache.period: 0`。

## 开发规范

- 构建产物（`target/`、`*/assets/`、`frontend/dist/`、`node_modules/`）与运行时库（`*.db`）**不入库**，已写入 `.gitignore`。
- 前端源码以 `frontend/` 为准，构建后产物拷贝到 `web-static/` 与 `src/main/resources/static/`，请勿直接编辑部署副本源码。
- 诊断脚本输出统一 JSON（含 `level` / `message` / `suggestion`）；新增修复命令优先改后端 `buildFixes()`，避免前端写死规则。

## 安全约定

- 自动修复需**人工确认**，禁止 AI 在无确认情况下直接修改目标机器。
- 面向私有化 / 等保不出域场景，敏感数据不上公网推理。

## 说明

- 本项目为 MVP 阶段，是后续「私有化 AI Infra 运维」能力的底座，欢迎共建。
