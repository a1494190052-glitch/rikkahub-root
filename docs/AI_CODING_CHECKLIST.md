# AI 编码检查清单（rikkahub-root）

> 本清单给修改本仓库的 AI agent（主代理 & 子代理）使用。
> 背景：本项目无本地编译能力，CI（~3min）是唯一的编译器。
> 目标：**在 push 之前拦住错误，减少失败的 build 轮次。**

## 🔴 推代码前必做（防止最常见的两类 build 失败）

### 0. 绝对不要对同一个文件并行编辑（最重要！）
**踩过的坑（根因级）**：对同一文件同时发多个 `workspace_edit_file`，它们读同一份原文、各自改、各自写回，**后写的覆盖先写的**。每个都报"replacements: 1 成功"，但最终文件只剩一个改动 → 编译失败。

- 同一文件的多处编辑**必须串行**：改一处 → 验证 → 再改下一处。
- 不同文件可以并行编辑。
- 注：app 已把 `writeText` 改为原子写+fsync+读回校验，能防"写未落地"，但**防不了并发覆盖**——并发互斥仍需靠这条纪律。

### 1. 引用任何 API 前，先确认它真实存在
**踩过的坑**：子代理调用 `ctx.executePendingJob()`，但 QuickJS wrapper 3.2.3 根本没有这个方法 → 整个 build 挂。

- 调用某个类的方法前，`grep` 该类源码或依赖，确认方法存在：
  ```
  grep -rn "fun 方法名" <相关源码路径>
  ```
- 对第三方库（QuickJS wrapper / Koin / Ktor / Compose），不确定就**不要凭印象写**，
  要么 grep 验证，要么用最保守、确定存在的 API。
- 宁可功能弱一点用确定存在的 API，也不要用"可能存在"的 API。

### 2. 改函数签名时，同步改所有调用点
**踩过的坑**：给 `buildRootShellTool` 加了参数，但 `LocalTools.kt` 的调用没更新 → 参数错位编译失败。

- 改任何函数签名后，`grep -rn "函数名("` 找出所有调用点，逐一核对。
- 加参数尽量加在末尾并给默认值，减少破坏性。

## 🟡 编辑文件后必做（防止"改了但没生效"）

### 3. 编辑后必须 read 复核
**踩过的坑**：`workspace_edit_file` 偶尔报告"成功"但实际没写入（连续编辑时）。

- 每次 `workspace_edit_file` 后，用 `workspace_read_file` 或 `grep` 确认改动真的在文件里。
- 多处编辑时，逐个验证，不要假设全部成功。

### 4. 不要混用工具编辑同一个文件
- 一个文件要么全用 `workspace_edit_file`，要么全用 `root_shell + sed`，别交替。
- `root_shell` 创建的文件是 root 属主，workspace 工具写不了 → 先 `chmod 666`。
- `root_shell` 的 grep 可能读到缓存旧数据，先 `sync` 再 `cp` 出来 grep。

### 5. 推送前检查文件完整性
- 大文件编辑后，确认结尾完整（不要出现重复/截断的尾巴）。
- 确认大括号大致配平（注意：含正则/JSON 字符串的文件，裸计数会误报，需人工看）。

## 🟢 root_shell 命令注意（动态 Shell Guard）

guard 已分级：主代理宽松、子代理严格。但仍有硬拦截：
- **灾难级**（谁都拦）：`rm -rf /`、`dd of=/dev/sda`、fork bomb、`mkfs`、`reboot`、删 root
- **敏感路径写**（谁都拦）：写 `/system` `/vendor` `/boot`
- 主代理可用（降为可审批 WRITE）：`python3`、`sh -c`、`eval`、`base64 | sh`
- 已放行：`2>/dev/null`、`rm -f 具体文件`（非递归）

**避免触发误报的技巧**：
- 别在命令里用 `python3`/`perl` 当管道执行器（会被 guard 拦）；数据处理用 `awk`/`sed`/`grep`。
- 需要复杂文本处理时，把内容写成文件再处理，而不是 `python3 -c "..."`。

## 📋 push 后

- push 后盯 CI 结果（`/actions/runs`），**CI 绿了才算完成**。
- 如果 CI 红了，读编译错误（`grep -oE` 提取 `e: file://...` 行），定位修复。
