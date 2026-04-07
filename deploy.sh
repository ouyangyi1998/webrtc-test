#!/bin/bash
set -e

echo "========================================="
echo "  WebRTC 远程控制系统 - 一键部署脚本"
echo "========================================="

# 检查 .env 文件
if [ ! -f .env ]; then
    echo ""
    echo "⚠️  未找到 .env 文件"
    echo ""

    # 自动检测公网IP
    PUBLIC_IP=$(curl -s --connect-timeout 5 ifconfig.me 2>/dev/null || curl -s --connect-timeout 5 ip.sb 2>/dev/null || echo "")

    if [ -n "$PUBLIC_IP" ]; then
        echo "🌐 检测到公网IP: $PUBLIC_IP"
        read -p "使用此IP? (y/n): " use_detected
        if [ "$use_detected" != "y" ] && [ "$use_detected" != "Y" ]; then
            read -p "请输入服务器公网IP: " PUBLIC_IP
        fi
    else
        read -p "请输入服务器公网IP: " PUBLIC_IP
    fi

    read -p "TURN 用户名 (默认: admin): " TURN_USER
    TURN_USER=${TURN_USER:-admin}

    read -p "TURN 密码 (默认: 123456): " TURN_PASS
    TURN_PASS=${TURN_PASS:-123456}

    read -p "Web 端口 (默认: 8080): " WEB_PORT
    WEB_PORT=${WEB_PORT:-8080}

    cat > .env << EOF
PUBLIC_IP=${PUBLIC_IP}
TURN_USERNAME=${TURN_USER}
TURN_PASSWORD=${TURN_PASS}
WEB_PORT=${WEB_PORT}
EOF

    echo ""
    echo "✅ 已生成 .env 文件"
fi

# 加载配置
source .env
echo ""
echo "📋 部署配置:"
echo "  公网IP:     ${PUBLIC_IP}"
echo "  Web端口:    ${WEB_PORT:-8080}"
echo "  TURN用户:   ${TURN_USERNAME}"
echo ""

# 检查防火墙提示
echo "🔥 请确保以下端口已开放:"
echo "  - ${WEB_PORT:-8080}/tcp  (Web信令)"
echo "  - 3478/tcp+udp           (TURN/STUN)"
echo "  - 49152-49252/udp        (TURN中继)"
echo ""

read -p "开始部署? (y/n): " confirm
if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
    echo "已取消"
    exit 0
fi

echo ""
echo "🚀 开始构建和启动..."
docker compose up -d --build

echo ""
echo "========================================="
echo "  ✅ 部署完成!"
echo "========================================="
echo ""
echo "  🌐 Web控制端:  http://${PUBLIC_IP}:${WEB_PORT:-8080}"
echo "  📡 信令地址:    ws://${PUBLIC_IP}:${WEB_PORT:-8080}/ws"
echo "  🔄 TURN服务器:  turn:${PUBLIC_IP}:3478"
echo ""
echo "  查看日志:  docker compose logs -f"
echo "  停止服务:  docker compose down"
echo "  重启服务:  docker compose restart"
echo ""
