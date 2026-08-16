#!/bin/bash
# 基线检查脚本 - 适配麒麟/统信/UOS
# 输出JSON格式结果
# 用法: bash baseline_check.sh --json

OUTPUT_JSON=false
if [[ "$1" == "--json" ]]; then
    OUTPUT_JSON=true
fi

# 收集信息
HOSTNAME_VAL=$(hostname 2>/dev/null || echo "unknown")
OS_NAME=$(cat /etc/os-release 2>/dev/null | grep "^NAME=" | cut -d'"' -f2 || echo "unknown")
OS_VERSION=$(cat /etc/os-release 2>/dev/null | grep "^VERSION=" | cut -d'"' -f2 || echo "unknown")
KERNEL=$(uname -r 2>/dev/null || echo "unknown")

# CPU
CPU_MODEL=$(cat /proc/cpuinfo 2>/dev/null | grep "model name" | head -1 | cut -d: -f2 | xargs || echo "unknown")
CPU_CORES=$(nproc 2>/dev/null || echo "unknown")
CPU_USAGE=$(top -bn1 2>/dev/null | grep "Cpu(s)" | awk '{print $2}' | cut -d'%' -f1 || echo "N/A")

# 内存
MEM_TOTAL=$(free -h 2>/dev/null | awk '/^Mem:/{print $2}' || echo "unknown")
MEM_USED=$(free -h 2>/dev/null | awk '/^Mem:/{print $3}' || echo "unknown")
MEM_FREE=$(free -h 2>/dev/null | awk '/^Mem:/{print $4}' || echo "unknown")
MEM_USAGE_PCT=$(free 2>/dev/null | awk '/^Mem:/{printf "%.1f", $3/$2*100}' || echo "N/A")

# 磁盘
DISK_TOTAL=$(df -h / 2>/dev/null | awk 'NR==2{print $2}' || echo "unknown")
DISK_USED=$(df -h / 2>/dev/null | awk 'NR==2{print $3}' || echo "unknown")
DISK_USAGE=$(df -h / 2>/dev/null | awk 'NR==2{print $5}' || echo "unknown")

# 网络
IP_ADDR=$(ip -4 addr show 2>/dev/null | grep "inet " | grep -v "127.0.0.1" | awk '{print $2}' | cut -d/ -f1 | head -3 | tr '\n' ',' | sed 's/,$//' || echo "unknown")

# 安全基线
SELINUX_STATUS=$(getenforce 2>/dev/null || echo "disabled")
FIREWALL_STATUS=$(systemctl is-active firewalld 2>/dev/null || echo "unknown")
SSHD_STATUS=$(systemctl is-active sshd 2>/dev/null || echo "unknown")
ROOT_SSH_LOGIN=$(grep "^PermitRootLogin" /etc/ssh/sshd_config 2>/dev/null | awk '{print $2}' || echo "unknown")

# 系统运行时间
UPTIME=$(uptime -p 2>/dev/null || echo "unknown")
LOAD_AVG=$(cat /proc/loadavg 2>/dev/null | awk '{print $1", "$2", "$3}' || echo "unknown")

# 输出JSON
if $OUTPUT_JSON; then
    echo "{"
    echo "  \"hostname\": \"${HOSTNAME_VAL}\","
    echo "  \"os\": \"${OS_NAME} ${OS_VERSION}\","
    echo "  \"kernel\": \"${KERNEL}\","
    echo "  \"cpu\": {"
    echo "    \"model\": \"${CPU_MODEL}\","
    echo "    \"cores\": \"${CPU_CORES}\","
    echo "    \"usage_percent\": \"${CPU_USAGE}\""
    echo "  },"
    echo "  \"memory\": {"
    echo "    \"total\": \"${MEM_TOTAL}\","
    echo "    \"used\": \"${MEM_USED}\","
    echo "    \"free\": \"${MEM_FREE}\","
    echo "    \"usage_percent\": \"${MEM_USAGE_PCT}\""
    echo "  },"
    echo "  \"disk\": {"
    echo "    \"total\": \"${DISK_TOTAL}\","
    echo "    \"used\": \"${DISK_USED}\","
    echo "    \"usage\": \"${DISK_USAGE}\""
    echo "  },"
    echo "  \"network\": {"
    echo "    \"ip_addresses\": \"${IP_ADDR}\""
    echo "  },"
    echo "  \"security\": {"
    echo "    \"selinux\": \"${SELINUX_STATUS}\","
    echo "    \"firewall\": \"${FIREWALL_STATUS}\","
    echo "    \"sshd\": \"${SSHD_STATUS}\","
    echo "    \"root_ssh_login\": \"${ROOT_SSH_LOGIN}\""
    echo "  },"
    echo "  \"system\": {"
    echo "    \"uptime\": \"${UPTIME}\","
    echo "    \"load_avg\": \"${LOAD_AVG}\","
    echo "    \"check_time\": \"$(date -Iseconds)\""
    echo "  }"
    echo "}"
else
    echo "===== 服务器基线检查报告 ====="
    echo "主机名:       ${HOSTNAME_VAL}"
    echo "操作系统:     ${OS_NAME} ${OS_VERSION}"
    echo "内核版本:     ${KERNEL}"
    echo "CPU型号:      ${CPU_MODEL}"
    echo "CPU核心数:    ${CPU_CORES}"
    echo "内存:         ${MEM_TOTAL} (已用: ${MEM_USED}, 可用: ${MEM_FREE}, 使用率: ${MEM_USAGE_PCT}%)"
    echo "磁盘(/):      ${DISK_TOTAL} (已用: ${DISK_USED}, 使用率: ${DISK_USAGE})"
    echo "IP地址:       ${IP_ADDR}"
    echo "SELinux:      ${SELINUX_STATUS}"
    echo "防火墙:       ${FIREWALL_STATUS}"
    echo "SSH服务:      ${SSHD_STATUS}"
    echo "RootSSH登录:  ${ROOT_SSH_LOGIN}"
    echo "运行时间:     ${UPTIME}"
    echo "负载:         ${LOAD_AVG}"
    echo "检查时间:     $(date)"
fi
