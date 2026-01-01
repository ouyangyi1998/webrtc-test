#!/bin/bash

# 一次性打包 x86 和 ARM64 两个版本的脚本

set -e

export JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
cd "$(dirname "$0")"

echo "======================================"
echo "WebRTC Agent 双架构打包脚本"
echo "======================================"

# 1. 打包 x86_64 版本
echo ""
echo "=== [1/4] 打包 x86_64 版本 ==="
""/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"" clean package -DskipTests -P mac-x86 -q
cp target/webrtc-agent-0.0.1-SNAPSHOT.jar /tmp/webrtc-agent-macos-x86_64.jar
echo "✓ x86_64 版本已保存到 /tmp"

# 2. 打包 ARM64 基础版本（包含 WebRTC ARM64 + JavaFX x86）
echo ""
echo "=== [2/4] 打包 ARM64 基础版本 ==="
""/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"" clean package -DskipTests -P mac-arm -q
echo "✓ ARM64 基础版本打包完成"

# 3. 替换 JavaFX 为 ARM64 原生库
echo ""
echo "=== [3/4] 替换 JavaFX 为 ARM64 原生库 ==="
cd /tmp
rm -rf build-arm64-final
mkdir build-arm64-final
cd build-arm64-final

unzip -q "$(dirname "$0")/target/webrtc-agent-0.0.1-SNAPSHOT.jar"

# 删除 JavaFX x86 库
rm -f libglass.dylib libprism*.dylib libjavafx*.dylib libdecora*.dylib

# 提取 ARM64 JavaFX 库
unzip -jq ~/Downloads/开发材料/centerm/maven/repository/org/openjfx/javafx-graphics/17.0.9/javafx-graphics-17.0.9-mac-aarch64.jar '*.dylib'

# 重新打包
jar cf /tmp/webrtc-agent-macos-arm64.jar -C . .
echo "✓ ARM64 版本已生成"

# 4. 复制回 target 目录
echo ""
echo "=== [4/4] 复制最终文件 ==="
cd "$(dirname "$0")"/target
cp /tmp/webrtc-agent-macos-x86_64.jar .
cp /tmp/webrtc-agent-macos-arm64.jar .

echo ""
echo "======================================"
echo "✓ 打包完成！"
echo "======================================"
ls -lh webrtc-agent-macos-*.jar
