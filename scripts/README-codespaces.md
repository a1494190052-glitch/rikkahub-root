# 🚀 RikkaHub Codespaces 8核编译指南

## 启动 Codespaces

1. 打开仓库: https://github.com/a1494190052-glitch/rikkahub-root
2. 点击绿色 **Code** 按钮 → **Codespaces** 标签页 → **New codespace**
3. **关键：选择 Machine type = 8 核**（8 cores, 16GB RAM）
   - 如果看不到选机器，先点右侧 `...` → `Machine type` 再新建
4. 等待环境自动初始化（首次约 5-10 分钟，下载 Android SDK）

## 一键编译

```bash
# 编译 Debug APK（推荐，无需签名）
bash scripts/build-8core.sh debug

# 编译 Release APK（需要签名配置，即 GitHub Secrets 中的内容）
bash scripts/build-8core.sh release
```

## 编译后上传到 Release

```bash
# 编译 debug 并上传到 Nightly Release
bash scripts/build-8core.sh debug
bash scripts/build-8core.sh upload
```

或者简称访问:
```
https://github.com/a1494190052-glitch/rikkahub-root/releases/tag/nightly
```

## 手动编译（不加脚本也行）

```bash
chmod +x gradlew
./gradlew assembleDebug
```

APK 会生成在:
- Debug: `app/build/outputs/apk/debug/`
- Release: `app/build/outputs/apk/release/`

## 费用说明

| 机器 | 每月免费 | 每月超时费用 |
|------|---------|------------|
| 2 核 | 60 小时 | $0.18/小时 |
| **8 核** | **15 小时** | **$0.72/小时** |
| 16 核 | 7.5 小时 | $1.44/小时 |

> 15 小时/月对日常手动编译绰绰有余。用完可等额度重置或切换到 4 核。

## 常见问题

**Q: 第一次启动很久？**
A: 首次需要下载 Android SDK (~3GB) 和 NDK (~2GB)，后续秒开。

**Q: 编译完 Codespaces 怎么关？**
A: 直接关掉浏览器标签页即可，闲置 30 分钟自动停止，**不会继续计费**。

**Q: 上传到 Release 需要权限？**
A: 第一次使用 `gh release upload` 会提示登录，按指引用浏览器登录 GitHub 即可。
