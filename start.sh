#!/bin/bash
# 服务器巡检MVP 启动脚本

export JAVA_HOME=/usr/local/jdk-17.0.16
export PATH=$JAVA_HOME/bin:$PATH

JAR_FILE="target/server-inspection-mvp-1.0.0-SNAPSHOT.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "未找到JAR包: $JAR_FILE"
    echo "请先运行: mvn package -DskipTests"
    exit 1
fi

echo "启动服务器巡检MVP..."
echo "访问地址: http://localhost:8080"
echo "API健康检查: curl http://localhost:8080/api/inspection/health"
echo "按 Ctrl+C 停止服务"
echo "============================================"

java -jar "$JAR_FILE"
