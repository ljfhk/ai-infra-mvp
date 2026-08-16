#!/bin/bash
# @desc 演示脚本：输出主机名/uptime/date，验证脚本执行通道
# @args （无）
echo "Hello from AI Infra script runner @ $(hostname)"
echo "uptime: $(uptime)"
echo "date:   $(date)"
