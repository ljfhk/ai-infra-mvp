#!/bin/bash
# AI Infra MVP 启动脚本（生产/演示用）
# 用法：在仓库根目录执行 ./run.sh
# 依赖：Java 17+。若 JAVA_HOME 未设置，则直接使用 PATH 中的 java。

cd "$(dirname "$0")"

# 若设置了 JAVA_HOME 则优先使用，否则回退到 PATH 中的 java
if [ -n "$JAVA_HOME" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

JAR_FILE="target/ai-infra-mvp-1.0.0-SNAPSHOT.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "未找到JAR包: $JAR_FILE"
    echo "请先运行: mvn package -DskipTests"
    exit 1
fi

pkill -9 -f "ai-infra-mvp" 2>/dev/null
sleep 2

nohup java -jar "$JAR_FILE" </dev/null >/tmp/ai-infra-mvp.log 2>&1 &
echo "Started PID: $!"
