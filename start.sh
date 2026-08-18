#!/bin/bash
# AI Infra MVP 启动脚本（前台运行，便于看日志）
# 依赖：Java 17+。若 JAVA_HOME 未设置，则直接使用 PATH 中的 java。

if [ -n "$JAVA_HOME" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

JAR_FILE="target/ai-infra-mvp-1.0.0-SNAPSHOT.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "未找到JAR包: $JAR_FILE"
    echo "请先运行: mvn package -DskipTests"
    exit 1
fi

echo "启动 AI Infra MVP..."
echo "访问地址: http://localhost:8080"
echo "API健康检查: curl http://localhost:8080/api/inspection/health"
echo "按 Ctrl+C 停止服务"
echo "============================================"

java -jar "$JAR_FILE"
