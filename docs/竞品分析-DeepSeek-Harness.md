# 竞品分析：DeepSeek Harness（dsh） vs AI Infra MVP

> 分析日期：2026-08-19
> 分析对象：`F:\下载1\deepseek-harness-master`（DeepSeek 官方开源 agent 运行时框架，MIT）
> 结论先行：**功能相似度低（约 15–20%），两者本质不是一类东西。**

---

## 1. 它是什么 —— DeepSeek Harness

`dsh` 是 **DeepSeek AI 官方出品的「Agent 运行时框架」**，核心口号是 *everything is a plugin*，底座是 [Cordis](https://github.com/cordiverse/cordis) 插件运行时。

- 用途：**造通用编码 / 自动化 Agent 的底座**（类 Claude Code / Codex 的编码 Agent 框架）。
- 技术栈：TypeScript / pnpm monorepo（~200+ 包）/ ESM / Typert RPC。
- 运行形态：`dsh web`（Web UI，默认 :3080）、`dsh cli`、`acp`（Agent Client Protocol 自动化服务）。
- 能力域（来自 `packages/` 布局）：`shell / subprocess / terminal / fs / lsp / web / skill / workflow / plan / todo / subagent / e2b-sandbox / acp / hooks / compaction / context` —— **全是 Agent 运行时能力**。
- 文档明确为 *developer preview*，会做破坏性变更。

---

## 2. 我们是什么 —— AI Infra MVP

一个**具体的垂直产品**：**私有化 AI 基础设施运维平台**。

- 技术栈：Java 17 / Spring Boot 3.2 / SQLite / 单文件 Vue3 + Element Plus（零构建）。
- 解决的实际问题：让跑在私有环境的 **vLLM / k3s / GPU 推理设施稳定**，出故障能自动诊断 + 人审后修复。
- 五大模块：推理网关（鉴权+限流+路由+重试+假成功识别降级）、故障诊断+一键修复、故障记忆（长短期、自然语言召回）、Agent Loop（诊断→召回→建议→人审→SSH 执行）、MCP 工具封装、A2A AgentCard（演示态）。

---

## 3. 同名模块的真实区别（最易误判，重点）

两者有几个**同名名词，但含义完全不同**，这是判断相似度时最大的干扰项：

| 同名概念 | dsh 里的含义 | 我们 MVP 里的含义 | 是否一回事 |
|---|---|---|---|
| **Agent Loop** | 通用**自主** Agent 循环：LLM 决策→调 shell/fs/web 等工具→再决策，全程无需人介入 | 领域 SRE **状态机**：`诊断→记忆召回→LLM建议→人确认→SSH执行`，**执行必须人审** | ❌ 完全两回事 |
| **Gateway** | `api-gateway` = Host↔Client 的 **Typert 内部 RPC 网关**（供 UI/SDK 调业务服务） | 推理网关 = **API Key 鉴权 + 限流 + 多后端路由 + 重试 + 假成功识别降级 + 转发 vLLM** | ❌ 目的不同 |
| **MCP** | 一整套 capability 注册体系（shell/fs/web/skill…），工具即插件 | 把 `diag/*.sh` 几个脚本包成 MCP 工具（JSON-RPC 2.0） | ⚠️ 概念沾边，规模天差地别 |
| **Memory** | `compaction` / context 压缩，给模型省 token | **故障记忆**：长期故障案例库 + 自然语言召回（如 vLLM OOM 怎么修） | ❌ 语义不同 |
| **SubAgent / A2A** | `subagent` 委派 + `acp` 自动化服务，可自主派活 | A2A 仅 AgentCard **演示态**，未做真实跨 Agent 协作 | ❌ 它真做、我们演示 |

> 取证：扫描 `packages/`，dsh 无任何 `kubernetes / vllm / gpu / nvml / prometheus / grafana / incident / sre` 类垂直运维能力（`infra`/`sre` 的少量命中只是英文散文与依赖声明里的通用词）。

---

## 4. 维度总对比

| 维度 | deepseek-harness (dsh) | AI Infra MVP |
|---|---|---|
| 定位 | 通用 Agent **框架 / 运行时**（造 Agent 的底座） | 垂直**产品**（AI 基础设施私有化运维） |
| 作者 / 体量 | DeepSeek AI 官方，pnpm monorepo ~200+ 包，MIT | 个人，Java17/Spring Boot + 单文件 Vue3，小 |
| 技术栈 | TypeScript / Cordis / ESM / Typert RPC | Java 17 / Spring Boot / SQLite / Vue3 IIFE |
| 要解决啥 | 让 LLM 自主完成编码 / 文件 / Shell / 网页等通用任务 | 让 vLLM/k3s/GPU 这些**推理设施**稳、能自动诊断+人审修复 |
| 自主性 | 高，可自修改插件、派 subagent、进沙箱 | 低，**执行必须人确认**（SSH 命令审批） |
| LLM | 主要 DeepSeek provider | 网关路由任意 OpenAI 兼容后端（现为本地 Qwen/vLLM） |
| 目标用户 | Agent 开发者 / 研究者 | SRE / 运维团队（跑私有 AI 基建的中小团队） |

---

## 5. 战略启示（结合本项目定位）

1. **不构成竞争，无需警惕**。dsh 是"造 Agent 的锤子"，本 MVP 是"用锤子敲出的一个具体钉子（AI Infra 运维）"。
2. **印证既有判断**：本项目此前已论证 *autonomous SRE Agent 对自己用意义不大、诊断+记忆整层已商品化*。dsh 正是"通用 Agent 框架"这条路拥挤的佐证，且不是本产品的形态。
3. **它的价值对咱们只是"潜在工具"，不是对手**：理论上可拿 dsh 当底座搭 SRE Agent，但栈（TS/Cordis）与本项目（Java）不搭；且本产品的护城河在**垂直故障记忆 + 人审闭环 + 私有化不出域**，这些 dsh 都不提供。没必要学它、也不必怕它。
4. **真正该守住的差异化不变**：垂直 AI Infra 故障语料 + 私有化人审闭环 + 数据不出域。

---

## 6. 信息来源

- 仓库路径：`F:\下载1\deepseek-harness-master`
- 关键文档：`README.md`、`AGENTS.md`（packages 布局与 capability 清单）、`docs/agent-lifecycle.md`（agent-loop 语义）、`docs/api-gateway.md`（gateway = Typert RPC）
- 取证命令：
  - `grep -rilE "kubernetes|k8s|vllm|gpu|prometheus|grafana|incident|sre" packages/ docs/ apps/` → 命中多为英文散文 / 依赖声明通用词，无垂直运维能力
  - 读取 `AGENTS.md` 的 Repository layout 段 → 确认能力域为 Agent 运行时
