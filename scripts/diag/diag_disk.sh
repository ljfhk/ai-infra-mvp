#!/bin/bash
# @desc 磁盘空间检查
# @args <路径> 默认 /opt/mvp
TARGET=${1:-/opt/mvp}
LINE=$(df -h "$TARGET" 2>/dev/null | tail -1)
if [ -z "$LINE" ]; then
  echo "{\"level\":\"info\",\"message\":\"磁盘空间检查未获取到数据\",\"suggestion\":\"请确认路径存在\"}"
  exit 0
fi
USE_PCT=$(echo "$LINE" | awk "{print \$5}" | tr -d "%")
USED=$(echo "$LINE" | awk "{print \$3}")
TOTAL=$(echo "$LINE" | awk "{print \$2}")
if [ "$USE_PCT" -gt 90 ]; then
  LVL=error; SUG="磁盘即将写满，及时清理旧日志/镜像，避免推理中断。"
elif [ "$USE_PCT" -gt 75 ]; then
  LVL=warning; SUG="磁盘余量紧张。"
else
  LVL=ok; SUG="磁盘余量充足。"
fi
echo "{\"level\":\"$LVL\",\"message\":\"磁盘空间使用 ${USE_PCT}%（${USED}/${TOTAL}）\",\"suggestion\":\"$SUG\"}"
