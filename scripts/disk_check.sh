#!/bin/bash
# @desc 磁盘空间与最大目录占用排查
# @args <路径> 可选，默认 /root/java-pro
TARGET=${1:-/root/java-pro}
echo "=== 分区使用 ==="
df -h "$TARGET" 2>/dev/null | tail -1
echo "=== 占用 TOP5 目录 ==="
du -h "$TARGET" 2>/dev/null | sort -rh | head -5
