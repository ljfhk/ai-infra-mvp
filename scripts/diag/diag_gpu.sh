#!/bin/bash
# @desc GPU 状态与显存检查（依赖 nvidia-smi）
# @args （无）
OUT=$(nvidia-smi --query-gpu=memory.used,memory.total,utilization.gpu,temperature.gpu --format=csv,noheader,nounits 2>/dev/null)
if [ -z "$OUT" ]; then
  echo "{\"level\":\"warning\",\"message\":\"未检测到 GPU / nvidia-smi 不可用\",\"suggestion\":\"推理机若为 WSL2，请在 Windows 侧查看；本命令在巡检服务器(182)执行。\"}"
  exit 0
fi
USED=$(echo "$OUT" | awk -F"," "{gsub(/ /,\"\",\$1); print \$1}")
TOTAL=$(echo "$OUT" | awk -F"," "{gsub(/ /,\"\",\$2); print \$2}")
UTIL=$(echo "$OUT" | awk -F"," "{gsub(/ /,\"\",\$3); print \$3}")
TEMP=$(echo "$OUT" | awk -F"," "{gsub(/ /,\"\",\$4); print \$4}")
PCT=0
if [ -n "$TOTAL" ] && [ "$TOTAL" -gt 0 ]; then PCT=$((USED*100/TOTAL)); fi
if [ "$PCT" -gt 92 ]; then
  LVL=error; SUG="显存接近打满，推理可能 OOM；下调 gpu-memory-utilization 或 max-model-len。"
elif [ "$PCT" -gt 75 ]; then
  LVL=warning; SUG="显存偏高，留意并发与上下文长度。"
else
  LVL=ok; SUG="显存余量充足。"
fi
echo "{\"level\":\"$LVL\",\"message\":\"GPU 显存占用 ${USED}/${TOTAL} MiB（${PCT}%）, 利用率 ${UTIL}%, 温度 ${TEMP}°C\",\"suggestion\":\"$SUG\"}"
