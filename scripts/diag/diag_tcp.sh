#!/bin/bash
# @desc 推理服务端口的 TCP 探活
# @args target:string:目标 host:port:localhost:8000
TARGET=${1:-localhost:8000}
HOST=$(echo "$TARGET" | cut -d: -f1)
PORT=$(echo "$TARGET" | cut -d: -f2)
if timeout 5 bash -c "echo >/dev/tcp/$HOST/$PORT" 2>/dev/null; then
  echo "{\"level\":\"ok\",\"message\":\"推理服务端口可达（$TARGET）\",\"suggestion\":\"TCP 连接正常。\"}"
else
  echo "{\"level\":\"error\",\"message\":\"推理服务端口不可达（$TARGET）\",\"suggestion\":\"确认 vLLM 已启动、WSL2 端口转发(netsh)已配置、防火墙放行。\"}"
fi
