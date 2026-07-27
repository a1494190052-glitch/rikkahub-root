# 子代理（Subagent）使用指南

子代理在独立上下文中自主运行自己的工具循环，完成后向父代理回报摘要，适合避免主对话上下文膨胀。

## 内置 Profile

代码定义于 `SubagentProfile.kt` 的 `BUILTIN`（默认 `maxSteps=32`、`timeoutSeconds=600`）：

| Profile | 定位 | maxSteps | 超时 | 工具 |
|---|---|---|---|---|
| `explore` | 调研/读文件/搜索，产出事实性摘要 | 16 | 600s | 只读（排除写文件、workspace_shell 及 root_shell/pty 等宿主高危工具） |
| `coder` | 执行明确的编码任务，内置"推前验证"纪律 | 30 | 900s | 继承全部工具 |
| `reviewer` | 审查/批评，返回结构化反馈 | 12 | 600s | 只读（同 explore 排除集） |

只读排除集 `FULLY_READONLY_EXCLUDED_TOOLS` = `workspace_write_file`、`workspace_edit_file`、`workspace_shell` 以及 `root_shell`、`pty_exec`、`pty_session`、`root_screenshot`、`ui_tree`。

## spawn_subagent 参数

- `profile_name`（必填）：要启动的 profile，取值为可用 profile 名。
- `task`（必填）：完整、自包含的任务提示。子代理零上下文启动，需像交代同事一样说明目标、已知信息与具体细节。
- `description`（可选）：3–5 词展示用简述。

返回包含 `succeeded`、`summary`、`steps`、`tool_calls`，成功时附带 `session_id`。

## 并行与追问

- **并行**：在同一条回复中发出多个 `spawn_subagent` 调用会并发执行，适合相互独立的子任务。
- **追问**：成功的 spawn 返回 `session_id`，用 `resume_subagent`（参数 `session_id` + `follow_up`）可复用该子代理的完整上下文继续追问，比重新 spawn 更省 token。
