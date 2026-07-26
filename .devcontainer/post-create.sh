#!/bin/bash
set -e

echo "=== 安装 pnpm ==="
npm install -g pnpm@11

echo "=== 安装 Android SDK ==="
if [ ! -d "$ANDROID_HOME/platforms" ]; then
  sudo mkdir -p "$ANDROID_HOME"
  cd /tmp
  curl -fsSLO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q commandlinetools-linux-11076708_latest.zip -d /tmp/cmdline-tools
  sudo mkdir -p "$ANDROID_HOME/cmdline-tools"
  sudo mv /tmp/cmdline-tools/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
  rm -f /tmp/commandlinetools-linux-11076708_latest.zip
  rm -rf /tmp/cmdline-tools
  export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin"
  yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses > /dev/null
  echo "=== 下载 SDK 组件（耗时较久，仅首次） ==="
  sdkmanager --sdk_root="$ANDROID_HOME" \
    "platform-tools" \
    "platforms;android-37.0" \
    "build-tools;36.0.0" \
    "build-tools;36.1.0" \
    "ndk;28.2.13676358"
  # 修复所有权
  sudo chown -R vscode:vscode "$ANDROID_HOME"
else
  echo "Android SDK 已存在，跳过安装"
fi

echo "=== 设置 local.properties ==="
echo "sdk.dir=$ANDROID_HOME" > /workspaces/$(basename $(pwd))/local.properties

echo "=== 安装 web-ui 依赖 ==="
cd /workspaces/$(basename $(pwd))/web-ui
pnpm install --frozen-lockfile

echo ""
echo "======================================"
echo "  Codespaces 环境就绪！"
echo "  8 核编译命令："
echo "  ./gradlew assembleDebug             # 编译 Debug APK"
echo "  ./gradlew assembleRelease           # 编译 Release APK（需签名配置）"
echo "  bash scripts/build-8core.sh debug   # 一键编译 + 上传"
echo "======================================"
