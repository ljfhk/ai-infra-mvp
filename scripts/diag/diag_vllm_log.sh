#!/bin/bash
# @desc 检查 vLLM 日志典型错误
# @args <日志路径> 默认 /opt/mvp/logs/vllm.log
LOG=${1:-/opt/mvp/logs/vllm.log}
if [ ! -f "$LOG" ]; then
  echo "{\"level\":\"info\",\"message\":\"vLLM 日志文件不存在\",\"suggestion\":\"日志路径: $LOG\"}"
  exit 0
fi
TAIL=$(grep -iE "error|exception|traceback|out of memory|missing" "$LOG" 2>/dev/null | tail -15)
if [ -z "$TAIL" ]; then
  echo "{\"level\":\"info\",\"message\":\"vLLM 日志无典型错误\",\"suggestion\":\"日志路径: $LOG\"}"
elif echo "$TAIL" | grep -qi "out of memory"; then
  echo "{\"level\":\"error\",\"message\":\"vLLM 日志出现 OOM\",\"suggestion\":\"降低 max-model-len / gpu-memory-utilization。\"}"
elif echo "$TAIL" | grep -q "missing" && echo "$TAIL" | grep -q "body"; then
  echo "{\"level\":\"error\",\"message\":\"vLLM 报 body 缺失(400 missing body)\",\"suggestion\":\"检查转发层是否触发 HTTP/2 h2c 升级（Windows netsh 转发需锁 HTTP_1_1）。\"}"
else
  FIRST=$(echo "$TAIL" | head -1)
  echo "{\"level\":\"warning\",\"message\":\"vLLM 日志存在异常行\",\"suggestion\":\"$FIRST\"}"
fi
