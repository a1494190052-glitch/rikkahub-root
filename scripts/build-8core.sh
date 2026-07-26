#!/bin/bash
set -e

# ─────────────────────────────────────────────
# RikkaHub 一键构建脚本 (Codespaces 8核)
# 用法:
#   bash scripts/build-8core.sh debug    编译 Debug APK
#   bash scripts/build-8core.sh release  编译 Release APK
#   bash scripts/build-8core.sh upload   上传本地 APK 到 Release
# ─────────────────────────────────────────────

MODE="${1:-debug}"
REPO="a1494190052-glitch/rikkahub-root"

# 检测当前是否是 Codespaces 环境
if [ "$CODESPACES" = "true" ]; then
  echo "✅ 运行在 Codespaces ($(nproc) 核)"
else
  echo "⚠️  未检测到 Codespaces 环境，继续本地运行..."
fi

# ── 1. 编译 ──
if [ "$MODE" = "debug" ] || [ "$MODE" = "release" ]; then
  echo ""
  echo "=== 开始编译 $MODE APK ==="
  echo "CPU: $(nproc) 核, 内存: $(free -h | awk '/^Mem:/{print $2}')"
  echo ""

  chmod +x gradlew

  if [ "$MODE" = "debug" ]; then
    ./gradlew assembleDebug --no-daemon
    APK_DIR="app/build/outputs/apk/debug"
  else
    ./gradlew assembleRelease --no-daemon
    APK_DIR="app/build/outputs/apk/release"
  fi

  echo ""
  echo "=== 编译完成！APK 位置 ==="
  ls -lh "$APK_DIR"/*.apk 2>/dev/null || echo "未找到 APK"
fi

# ── 2. 上传到 GitHub Release ──
if [ "$MODE" = "upload" ] || [ "$MODE" = "release" ]; then
  APK_DIR="app/build/outputs/apk/$([ "$MODE" = "upload" ] && echo "debug" || echo "release")"

  if [ ! -d "$APK_DIR" ]; then
    echo "❌ 未找到 APK 目录: $APK_DIR"
    echo "   请先编译: bash scripts/build-8core.sh debug"
    exit 1
  fi

  APK_FILES=$(ls "$APK_DIR"/*.apk 2>/dev/null || true)
  if [ -z "$APK_FILES" ]; then
    echo "❌ $APK_DIR 中未找到 APK 文件"
    exit 1
  fi

  echo ""
  echo "=== 上传 APK 到 GitHub Release (nightly) ==="
  for apk in $APK_FILES; do
    echo "上传: $(basename $apk)"
    gh release upload nightly "$apk" --repo "$REPO" --clobber 2>/dev/null || {
      echo "⚠️  nightly release 不存在，创建中..."
      gh release create nightly --repo "$REPO" --prerelease --title "Nightly Build" --notes "Codespaces 手动构建" "$apk"
    }
  done
  echo "✅ 上传完成！下载地址:"
  echo "   https://github.com/$REPO/releases/tag/nightly"
fi
