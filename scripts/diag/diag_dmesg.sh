#!/bin/bash
# @desc 检查 dmesg 中 OOM/错误关键字
# @args （无）
OUT=$(dmesg 2>/dev/null | grep -iE "error|oom|kill|nvrm|nvida" | tail -20)
if [ -z "$OUT" ]; then
  echo "{\"level\":\"info\",\"message\":\"dmesg 无相关错误\",\"suggestion\":\"（或当前无权限读取 dmesg）\"}"
elif echo "$OUT" | grep -qi oom; then
  echo "{\"level\":\"error\",\"message\":\"检测到 OOM / 内存杀死\",\"suggestion\":\"降低 batch/gpu-memory-utilization，或减少并发。\"}"
else
  FIRST=$(echo "$OUT" | head -1)
  echo "{\"level\":\"warning\",\"message\":\"dmesg 发现错误关键字\",\"suggestion\":\"$FIRST\"}"
fi
