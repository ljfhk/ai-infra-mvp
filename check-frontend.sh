#!/bin/bash
# 前端资源完整性检查/修复
# 用途：每次 mvn 构建或前端改动后运行，防止 AI Infra 看板等模块因产物覆盖丢失
cd /opt/mvp
CANON=_canonical/index.html
MARK="AI Infra 看板"
JAR=target/server-inspection-mvp-1.0.0-SNAPSHOT.jar
FIX=0

check() {  # $1=路径描述 $2=文件
  if grep -q "$MARK" "$2" 2>/dev/null; then
    echo "  [OK]   $1 ($(wc -c < "$2") 字节)"
  else
    echo "  [FAIL] $1 -> 用 _canonical 修复"
    cp "$CANON" "$2"; FIX=1
  fi
}

echo "=== 前端资源检查 ==="
check "web-static/index.html          " web-static/index.html
check "src/main/resources/static/index.html" src/main/resources/static/index.html

if unzip -p "$JAR" BOOT-INF/classes/static/index.html 2>/dev/null | grep -q "$MARK"; then
  echo "  [OK]   jar 内 static/index.html"
else
  echo "  [FAIL] jar 内 static/index.html -> 需重新 mvn package"
  FIX=1
fi

if grep -q "period: 0" src/main/resources/application.yml; then
  echo "  [OK]   application.yml cache.period=0"
else
  echo "  [FAIL] application.yml 缺少 cache.period: 0"
  FIX=1
fi

[ $FIX -eq 0 ] && echo "=== 全部正常 ===" || echo "=== 有修复项，请重新 mvn package 并 bash run.sh ==="
