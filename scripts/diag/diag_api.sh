#!/bin/bash
# @desc 推理平台 API 延迟探针
# @args base_url:string:推理平台 base URL:http://localhost:8000
BASE=${1:-http://localhost:8000}
T0=$(date +%s%3N)
HTTP=$(curl -s -o /tmp/diag_api_resp.json -w "%{http_code}" -m 10 "$BASE/v1/models")
LAT=$(($(date +%s%3N) - T0))
if [ "$HTTP" = "200" ]; then
  if [ "$LAT" -gt 3000 ]; then
    echo "{\"level\":\"warning\",\"message\":\"推理平台 API 正常（HTTP $HTTP，延迟 ${LAT}ms）\",\"suggestion\":\"API 延迟偏高，检查模型加载/队列堆积。\"}"
  else
    echo "{\"level\":\"ok\",\"message\":\"推理平台 API 正常（HTTP $HTTP，延迟 ${LAT}ms）\",\"suggestion\":\"OpenAI 兼容接口响应正常。\"}"
  fi
else
  echo "{\"level\":\"error\",\"message\":\"推理平台 API 异常（HTTP $HTTP）\",\"suggestion\":\"平台不可达\"}"
fi
