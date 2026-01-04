#!/bin/bash

# 一次性打包 x86 和 ARM64 两个版本的脚本

set -e

# 自动检测并设置 JAVA_HOME
if [ -z "$JAVA_HOME" ]; then
    # 方法1: 使用 /usr/libexec/java_home (macOS)
    if [ -x /usr/libexec/java_home ]; then
        JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home 2>/dev/null)
    fi
    
    # 方法2: 从 java 命令路径推断
    if [ -z "$JAVA_HOME" ]; then
        JAVA_CMD=$(which java 2>/dev/null)
        if [ -n "$JAVA_CMD" ]; then
            # 从 /path/to/java 推断为 /path/to/../Home
            JAVA_HOME=$(dirname "$(dirname "$JAVA_CMD")")
            # 验证是否是有效的 JAVA_HOME
            if [ ! -f "$JAVA_HOME/bin/java" ]; then
                JAVA_HOME=""
            fi
        fi
    fi
    
    # 方法3: 常见安装路径
    if [ -z "$JAVA_HOME" ]; then
        for path in \
            "$HOME/Library/Java/JavaVirtualMachines"/*/Contents/Home \
            "/Library/Java/JavaVirtualMachines"/*/Contents/Home \
            "/System/Library/Java/JavaVirtualMachines"/*/Contents/Home
        do
            if [ -d "$path" ] && [ -f "$path/bin/java" ]; then
                # 优先选择 Java 17
                JAVA_VERSION=$("$path/bin/java" -version 2>&1 | head -1 | grep -oE "version \"17" || echo "")
                if [ -n "$JAVA_VERSION" ]; then
                    JAVA_HOME="$path"
                    break
                elif [ -z "$JAVA_HOME" ]; then
                    # 如果没有找到 17，先保存第一个找到的
                    JAVA_HOME="$path"
                fi
            fi
        done
    fi
fi

# 验证 JAVA_HOME
if [ -z "$JAVA_HOME" ] || [ ! -f "$JAVA_HOME/bin/java" ]; then
    echo "错误: 无法自动检测 JAVA_HOME，请手动设置："
    echo "  export JAVA_HOME=/path/to/java/home"
    echo "  或运行: /usr/libexec/java_home -V 查看已安装的 Java 版本"
    exit 1
fi

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

# 显示使用的 Java 版本
echo "使用 Java: $JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1

# Maven 路径
MVN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"

cd "$(dirname "$0")"
SCRIPT_DIR="$(pwd)"

echo "======================================"
echo "WebRTC Agent 双架构打包脚本"
echo "======================================"
echo "JAVA_HOME: $JAVA_HOME"
echo "Maven: $MVN"
echo ""

# 1. 打包 x86_64 版本
echo "=== [1/3] 打包 x86_64 版本 ==="
"$MVN" clean package -DskipTests -P mac-x86 -q

# 清理 x86 版本，确保只包含 x86_64 库（移除可能的 ARM64 库）
echo "清理 x86_64 版本，确保架构正确..."
cd /tmp
rm -rf build-x86-final
mkdir build-x86-final
cd build-x86-final

unzip -q "$SCRIPT_DIR/target/webrtc-agent-0.0.1-SNAPSHOT.jar" || {
    echo "错误: 无法解压 JAR 文件"
    exit 1
}

# 删除 ARM64 的 WebRTC 库
rm -f libwebrtc-java-macos-aarch64.dylib 2>/dev/null || true

# 处理 JavaFX 库：检查架构，确保是 x86_64
if [ -f libglass.dylib ]; then
    ARCH=$(file libglass.dylib 2>/dev/null | grep -oE "x86_64|arm64" | head -1 || echo "")
    if [ "$ARCH" = "arm64" ] || [ -z "$ARCH" ]; then
        echo "检测到 ARM64 JavaFX 库或架构不明，删除并重新添加 x86_64 版本..."
        # 删除所有 JavaFX 库
        rm -f libglass.dylib libprism*.dylib libjavafx*.dylib libdecora*.dylib 2>/dev/null || true
    elif [ "$ARCH" = "x86_64" ]; then
        echo "✓ JavaFX 库架构正确 (x86_64)"
    fi
fi

# 如果 JavaFX 库不存在或是 ARM64，尝试从 Maven 本地仓库或远程下载
if [ ! -f libglass.dylib ]; then
    echo "尝试获取 x86_64 JavaFX 库..."
    # 尝试从 Maven 本地仓库提取
    JAVAFX_MAC_JAR="$HOME/.m2/repository/org/openjfx/javafx-graphics/17.0.9/javafx-graphics-17.0.9-mac.jar"
    
    # 如果本地没有，尝试下载
    if [ ! -f "$JAVAFX_MAC_JAR" ]; then
        echo "本地仓库未找到 mac.jar，尝试从 Maven Central 下载..."
        JAVAFX_DIR="$HOME/.m2/repository/org/openjfx/javafx-graphics/17.0.9"
        mkdir -p "$JAVAFX_DIR"
        curl -sL "https://repo1.maven.org/maven2/org/openjfx/javafx-graphics/17.0.9/javafx-graphics-17.0.9-mac.jar" \
            -o "$JAVAFX_MAC_JAR" 2>/dev/null || echo "下载失败，请手动下载"
    fi
    
    if [ -f "$JAVAFX_MAC_JAR" ]; then
        unzip -jq "$JAVAFX_MAC_JAR" '*.dylib' 2>/dev/null || true
        if [ -f libglass.dylib ]; then
            CHECK_ARCH=$(file libglass.dylib 2>/dev/null | grep -oE "x86_64|arm64" | head -1 || echo "")
            if [ "$CHECK_ARCH" = "arm64" ]; then
                echo "警告: javafx-graphics-17.0.9-mac.jar 包含 ARM64 库"
                echo "      在 ARM64 Mac 上无法获取纯 x86_64 JavaFX 库"
                echo "      建议：1) 在 x86_64 Mac 上构建；2) 或使用 Rosetta 2 运行"
                # 不删除，让用户知道情况
            elif [ "$CHECK_ARCH" = "x86_64" ]; then
                echo "✓ 成功提取 x86_64 JavaFX 库"
            fi
        fi
    else
        echo "警告: 无法获取 javafx-graphics-17.0.9-mac.jar"
        echo "      请手动下载: https://repo1.maven.org/maven2/org/openjfx/javafx-graphics/17.0.9/javafx-graphics-17.0.9-mac.jar"
    fi
fi

# 同时检查并提取其他 JavaFX 模块的库（base, controls, fxml）
for module in base controls fxml; do
    JAVAFX_JAR="$HOME/.m2/repository/org/openjfx/javafx-${module}/17.0.9/javafx-${module}-17.0.9-mac.jar"
    if [ ! -f "$JAVAFX_JAR" ]; then
        JAVAFX_DIR="$HOME/.m2/repository/org/openjfx/javafx-${module}/17.0.9"
        mkdir -p "$JAVAFX_DIR"
        curl -sL "https://repo1.maven.org/maven2/org/openjfx/javafx-${module}/17.0.9/javafx-${module}-17.0.9-mac.jar" \
            -o "$JAVAFX_JAR" 2>/dev/null || true
    fi
    if [ -f "$JAVAFX_JAR" ]; then
        unzip -jq "$JAVAFX_JAR" '*.dylib' 2>/dev/null || true
    fi
done

# 检查是否需要从其他 JavaFX JAR 中提取（controls, base, fxml）
if [ -f libglass.dylib ]; then
    echo "✓ JavaFX graphics 库已就绪"
else
    echo "警告: JavaFX graphics 库缺失，x86 版本可能无法正常运行"
fi

# 确保 META-INF 目录存在
mkdir -p META-INF

# 重新打包，使用 -m 选项指定 MANIFEST 文件以保留 Main-Class
if [ -f META-INF/MANIFEST.MF ]; then
    jar cfm /tmp/webrtc-agent-macos-x86_64.jar META-INF/MANIFEST.MF -C . . 2>/dev/null || {
        echo "错误: 重新打包失败"
        exit 1
    }
else
    echo "错误: MANIFEST.MF 文件不存在"
    exit 1
fi

cd "$SCRIPT_DIR"
echo "✓ x86_64 版本已保存到 /tmp"
echo ""

# 2. 打包 ARM64 基础版本（包含 WebRTC ARM64 + JavaFX ARM64）
echo "=== [2/3] 打包 ARM64 版本 ==="
"$MVN" clean package -DskipTests -P mac-arm -q
# Maven profile mac-arm 已经正确配置了 javafx.classifier=mac-aarch64，
# 所以构建出来的 JAR 已经包含了正确的 ARM64 JavaFX 原生库，直接使用即可
cp target/webrtc-agent-0.0.1-SNAPSHOT.jar /tmp/webrtc-agent-macos-arm64.jar
echo "✓ ARM64 版本已保存到 /tmp"
echo ""

# 3. 复制回 target 目录
echo "=== [3/3] 复制最终文件 ==="
cd "$SCRIPT_DIR/target"
cp /tmp/webrtc-agent-macos-x86_64.jar . 2>/dev/null || true
cp /tmp/webrtc-agent-macos-arm64.jar . 2>/dev/null || true

echo ""
echo "======================================"
echo "✓ 打包完成！"
echo "======================================"
ls -lh webrtc-agent-macos-*.jar 2>/dev/null || echo "注意: 某些文件可能未生成"
