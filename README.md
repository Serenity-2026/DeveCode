# DeveCode

> 一个从零手写的终端 AI Coding Agent：多协议 LLM 接入、工具调用、权限裁决、上下文压缩、Skill、MCP、多 Agent 协作与 git worktree 隔离，全部自己实现。

![Java](https://img.shields.io/badge/Java-21-orange)
![Maven](https://img.shields.io/badge/build-Maven-blue)
![Status](https://img.shields.io/badge/status-work%20in%20progress-yellow)

---

## 这是什么

DeveCode 是一个跑在终端里的 AI 编程助手。你用自然语言描述任务，它读代码、改文件、跑命令、调用外部工具，并在一个自绘的全屏 TUI 里把整个过程流式展示出来。

和"调一个 SDK 然后套个壳"的项目不同，这里几乎所有 Agent 该有的机制都是手写的：

| 你在别处可能直接调库解决的事 | 在这个项目里 |
|---|---|
| 流式协议解析 | 手写 SSE 解析，Anthropic 与 OpenAI 两套协议统一成一个事件流 |
| 工具调用与并发 | 自己定义 `Tool` 契约，按工具类别切批（只读并行 / 写与命令串行） |
| 权限与沙箱 | 八层递进裁决 + 路径沙箱 + YAML 规则 |
| 上下文压缩 | 两层防线（结果溢写 + 历史摘要）+ prompt cache 前缀稳定性 |
| Skill | 三层目录发现、inline / fork 两种执行模式、可撤销的工具白名单 |
| MCP | 自己写 stdio 与 Streamable HTTP 两种传输的客户端，把外部工具包成内部工具 |
| 多 Agent | 四种派生形态 + 文件邮箱通信 + 任务板 + worktree 隔离 |
| 终端界面 | 基于 JLine 的全屏 TUI：流式渲染、Markdown、权限弹窗、结构化问卷 |

**一句话定位**：这不是一个"能用的产品"，而是一份把 Agent 内部机制摊开给你看的实现。

---

## 特性

### 对话与模型接入

- 同时实现 **Anthropic** 与 **OpenAI** 两套协议适配，一份配置切换；OpenAI 侧支持自定义 `base_url` 接入兼容端点（Anthropic 侧的端点与密钥尚未接入配置，见「已知限制」）
- 完整流式输出：文本增量、思考（thinking）增量、工具调用参数分片聚合
- 扩展思考（extended thinking）支持，含签名回传；OpenAI 侧兼容 DeepSeek 的 `reasoning_content`
- 多轮上下文、响应计时、错误恢复（限流等待 / 上下文超长自动压缩重试 / `max_tokens` 续写）
- 前缀缓存用量解析（`cache_read_input_tokens` 等），兼容只在 `message_delta` 回报的端点

### 工具层

- 内置 `ReadFile` / `EditFile` / `WriteFile` / `Glob` / `Grep` / `Bash` / `Math` / `ToolSearch` / `Skill` / `Agent` / `AskUserQuestion`
- 只读工具按相邻批次并行（虚拟线程），写与命令工具串行独占
- **先读后改**强制校验（未读过的文件拒绝编辑；读后被外部修改则要求重读）
- 编辑返回精确 diff；每轮对话结束打文件快照，支持 `/rewind` 回退
- 延迟加载（`shouldDefer`）：不常用的工具 schema 不常驻上下文，由 `ToolSearch` 按需发现

### 上下文治理

- **Layer 1**：工具结果超过阈值时溢写到磁盘，历史里只留路径 + 2KB 预览；决策冻结保证重放逐字节一致
- **Layer 2**：软/硬双阈值触发历史摘要；摘要失败带熔断器；摘要请求自身超长时按 API 轮次分批丢弃重试
- 压缩后自动挂回"最近读过的文件 / 激活的 Skill / 可用工具清单"，避免压缩即失忆
- 会话以 JSONL 追加写 + 压缩边界书签保存，支持 `/resume` 恢复与崩溃截断恢复

### 权限与安全

- 四种权限模式：`default` / `acceptEdits` / `plan` / `bypassPermissions`
- 八层递进裁决：Plan 例外 → 安全命令白名单 → 危险命令黑名单 → 保护路径 → 路径沙箱 → YAML 规则 → 会话级规则 → 模式矩阵
- 保护 `.devecode/config.yaml` 等文件不被自身改写（防自我提权）
- 工具调用前可被 Hook 拦截；Bash 支持 OS 级沙箱包装（预留接口）

### 扩展机制

- **Skill**：`~/.devecode/skills` 与 `<项目>/.devecode/skills` 三层加载，支持 `SKILL.md`（front-matter）或 `skill.yaml + prompt.md`；inline 注入正文，fork 在隔离子 Agent 执行；支持工具白名单约束与退出撤销
- **SubAgent**：`~/.devecode/agents/*.md` 定义专用子 Agent（工具白名单/黑名单、模型、最大轮数、系统提示词）
- **Hook**：9 个生命周期事件 × 4 种动作（命令 / 注入提示 / HTTP / 子 Agent），支持条件表达式、once、async、reject
- **MCP**：stdio 与 Streamable HTTP 两种传输，自动把 server 的工具注册成 `mcp__<server>__<tool>`
- **自定义命令**：`.devecode/commands/**.md` 自动变成斜杠命令

### 多 Agent 协作

- 四种派生形态：fork（继承父历史）、预定义 spec、后台任务、常驻队友
- 文件邮箱做跨进程通信（文件锁 + 租约心跳 + 原子替换写入）
- 共享任务板（append-only 事件日志）支持 `TaskCreate / TaskList / TaskGet / TaskUpdate`
- git worktree 隔离：子 Agent 可在一次性隔离树里干活，主 Agent 可进入会话式隔离树

---

## 快速开始

### 环境要求

- **JDK 21+**（项目使用虚拟线程与 pattern matching for switch）
- **Maven 3.9+**
- 终端建议：Windows Terminal / iTerm2 / 任意支持 256 色的现代终端
- 可选：`git`（worktree 隔离与 git 相关功能需要）

### 构建

```bash
git clone https://github.com/Serenity-2026/DeveCode.git
cd DeveCode
mvn -q clean package -DskipTests
```

### 配置

在**任意一处**放 `config.yaml`（按优先级从低到高叠加）：

```
~/.devecode/config.yaml               # 用户级
<项目>/.devecode/config.yaml          # 项目级
<项目>/.devecode/config.local.yaml    # 本地覆盖（不进版本库）
```

最小可用配置：

```yaml
providers:
  - name: "deepseek"
    protocol: "anthropic"            # anthropic | openai | openai-compat
    base_url: "https://api.deepseek.com/anthropic/v1/messages"
    model: "deepseek-v4-flash"
    api_key: "${ANTHROPIC_API_KEY}"  # 推荐用环境变量，不要写死在文件里
    thinking: true

permission_mode: default
```

> **不要把自己的密钥提交进仓库。** `api_key` 可以留空，程序会回退读环境变量：`anthropic` → `ANTHROPIC_API_KEY`，`openai` / `openai-compat` → `OPENAI_API_KEY`。

参考样例见 [`src/main/resources/providers.yaml`](src/main/resources/providers.yaml)（含 MCP 与 Hook 的配置示例）。

### 运行

```bash
mvn -q compile exec:java -Dexec.mainClass=com.agent.tui.Main
```

配置了多个 provider 时，启动先出现选择界面；只有一个时直接进入对话。

---

## 使用

进入界面后直接输入需求即可。常用斜杠命令：

| 命令 | 作用 |
|---|---|
| `/help` (`/h`, `/?`) | 命令列表；`/help <cmd>` 看单条详情 |
| `/status` (`/s`) | 当前模式、token 用量、工具数、模型与目录 |
| `/plan` (`/p`) | 切到计划模式（只读，只允许写计划文件） |
| `/permission` (`/perm`) | 权限模式管理 |
| `/compact` (`/c`) | 手动压缩上下文 |
| `/clear` | 清空对话并开启新会话 |
| `/resume` (`/r`) / `/change` | 恢复 / 按 ID 或名称切换历史会话 |
| `/rewind` | 回退到某一轮对话结束时的文件快照 |
| `/mcp` | MCP server 连接状态与工具数 |
| `/memory` | 查看 / 清空自动记忆 |
| `/skill` | 管理 Skill：`list` / `reload` / `install <url>` / `uninstall <name>` / `exit <name>` |
| `/sandbox` | 沙箱模式切换 |
| `/review` | 让模型审查当前 git diff |
| `/prompt` | 查看当前系统提示词摘要 |
| `/exit` (`/quit`) | 退出 |

已安装的 Skill 会自动注册成同名命令（例如 `/my-skill 参数`）。

---

## 架构

![DeveCode 架构](docs/images/architecture.png)

*六层结构 + 每轮迭代的主循环：交互层 → Agent 核心 → 核心能力（模型 / 工具 / 上下文）→ 支撑机制（权限 / Hook / 持久化）→ 扩展点 → 基础设施。可编辑源文件：[`docs/images/architecture.drawio`](docs/images/architecture.drawio) · 矢量版：[`docs/images/architecture.svg`](docs/images/architecture.svg)*

### 模块索引

| 包 | 职责 |
|---|---|
| `agent` | Agent 主循环、事件定义、工具并发执行器 |
| `llm` | `LlmClient` 接口与 Anthropic / OpenAI 两套流式实现、消息与事件模型 |
| `tool` | `Tool` 契约、注册表、路径上下文、文件快照、结果预算、内置工具 |
| `permission` | 权限模式与多层裁决器 |
| `hook` | 生命周期 Hook 引擎（条件匹配 + 4 种动作） |
| `compact` | 上下文压缩与压缩后恢复快照 |
| `history` / `session` | 对话状态管理、JSONL 会话持久化与恢复 |
| `memory` | 指令文件加载（`DEVECODE.md` / `AGENTS.md` + `@include`）、自动记忆与召回 |
| `prompt` | 系统提示词分层组装、计划模式提示词 |
| `skill` | Skill 目录发现、执行分发、GitHub 安装器 |
| `subAgent` | 子 Agent 模板、工具过滤、后台任务台账 |
| `teams` | 团队、文件邮箱、共享任务板、常驻队友 |
| `worktree` | git worktree 隔离、创建后布置、残留清理 |
| `mcp` | MCP 客户端与外部工具包装 |
| `command` | 斜杠命令注册与自定义命令加载 |
| `tui` | 全屏终端界面 |

---

## 扩展自己的能力

### 加一个 Skill

```text
.devecode/skills/my-skill/SKILL.md
```

```markdown
---
name: my-skill
description: 一句话说明它做什么、什么时候该用（模型只看到这一行）
mode: inline          # inline（默认）| fork
allowed-tools:        # 可选：限定这个 Skill 只能用这些工具
  - ReadFile
  - Grep
---

（正文：完整的操作规范，激活后才注入上下文）
```

也可以直接从 GitHub 安装：`/skill install https://github.com/<owner>/<repo>/tree/main/<path>`。

### 加一个子 Agent

```text
.devecode/agents/reviewer.md
```

```markdown
---
name: reviewer
description: 代码审查专家，只看不改
disallowedTools: [EditFile, WriteFile]
model: inherit
maxTurns: 20
---

（正文即这个子 Agent 的系统提示词）
```

### 加一个 Hook

```yaml
hooks:
  - id: block-destructive-rm
    event: pre_tool_use
    condition: 'tool == "Bash" && args.command =~ /rm -rf \//'
    type: prompt
    reject: true
    message: "Blocked: destructive rm command is not allowed."
```

### 接入 MCP server

```yaml
mcp_servers:
  - name: "github"
    command: "npx"                       # 有 command → stdio，启动子进程
    args: ["-y", "@modelcontextprotocol/server-github"]
    env:
      GITHUB_TOKEN: "${GITHUB_TOKEN}"    # ${VAR} 会在连接时展开

  - name: "remote-tool"
    url: "http://localhost:3001"         # 有 url → Streamable HTTP
```
