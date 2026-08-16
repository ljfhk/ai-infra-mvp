#!/bin/bash
# @desc GPU 状态速览：显存占用/利用率/温度（依赖 nvidia-smi）
# @args （无）
echo "=== GPU 概览 ==="
nvidia-smi --query-gpu=index,name,memory.used,memory.total,utilization.gpu,temperature.gpu \
  --format=csv,noheader 2>/dev/null || echo "nvidia-smi 不可用"
