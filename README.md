# RikkaHub Root（二改版）

<div align="center">
  <h1>RikkaHub Root 二改版</h1>
  <p>基于 <a href="https://github.com/rikkahub/rikkahub">RikkaHub</a> 的 Android 原生 LLM 客户端深度改造版</p>
  <p>新增 Root 权限增强、Agent 子代理系统、MCP Server / Web Server 等能力</p>
</div>

> ⚠️ 本项目是 **RikkaHub 的衍生（fork）版本**，基于 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)（AGPL v3）改造而来。二改部分遵循原项目的 AGPL v3 开源协议，详见 [LICENSE](LICENSE)。

---

## ✨ 二改新增功能

### 🛡️ Root 能力增强（需 root 设备）
- **root_shell / pty_exec / pty_session**：以 root 身份执行 shell 命令与交互终端
- **root_screenshot / ui_tree**：屏幕截图与 UI 层级分析
- **工作区 Root 模式**：proot Linux 工作区以 root 运行，支持完整系统操作

### 🤖 Agent 子代理系统
- 5 个内置子代理 profile：explore / coder / reviewer / researcher / coordinator
- **嵌套子代理**（coordinator → explore 两层嵌套）+ 并行派发
- **执行可视化**：实时进度回传、嵌套层级标记、token 消耗 / 耗时统计
- **自定义 Profile 管理 UI**：内置开关 + 自定义 profile 创建/编辑（系统提示词/温度/步数/token 预算/输出 Schema）

### 🖥️ MCP Server / Web Server
- **MCP Server**（Streamable HTTP，默认 8081）：将全部本地工具暴露给外部客户端（Claude Desktop 等），Bearer token 鉴权 + mDNS 发现
- **Web Server**（默认 8080）：浏览器访问的 AI 对话界面

### 🌐 其他增强
- **浏览器自动化**：3 标签页池化 WebView 控制（BrowserUse）
- **技能系统**：可加载自定义 skills
- **记忆系统**：全局/助手级记忆 + 记忆检索
- 调度任务、悬浮窗、WebDAV 同步、FTS 全文搜索等（继承原版）

## 📦 构建

```bash
# 需要 Android SDK 37 / JDK 17 / pnpm
cd web-ui && pnpm install --frozen-lockfile
cd .. && ./gradlew assembleDebug
```

CI（GitHub Actions）自动构建 APK，产物见 Actions 页面。

## 🔧 配置

- 首次启动在「设置 → 模型」中添加任意 OpenAI 兼容 API 供应商
- Root 工具需授予 app root 权限（Magisk / KernelSU / APatch）
- MCP Server 在「设置 → MCP Server」开启并查看连接地址/token

## 📄 License

本项目基于 [RikkaHub](https://github.com/rikkahub/rikkahub)（AGPL v3）二次开发，开源部分遵循 **AGPL v3**，商业用途需商业授权。详见 [LICENSE](LICENSE)。

## ⚠️ 免责声明

- Root 工具存在高风险，请自行评估使用
- 请妥善保管 MCP/Web Server 的 token 与鉴权配置，避免局域网泄露
- 本项目与 RikkaHub 官方无关，官方对二改版本的问题概不负责
