#!/usr/bin/env bash

set -Eeuo pipefail

STARTED_AT=""
DURATION_HOURS=24
INTERVAL_SECONDS=60
OUTPUT_DIR=""
APP_LOG="/home/user1/app/app.log"
APP_PATTERN="[j]ava.*iot-platform-demo"
TDENGINE_CONTAINER="iot-tdengine"
MYSQL_CONTAINER="iot-mysql"
POINT_IDS="POINT004,POINT005,POINT006,POINT007"
RUN_ONCE=false
SUMMARY_WRITTEN=false

usage() {
  cat <<'EOF'
用法：
  Monitor-HardwareAcceptance24h.sh --started-at <ISO时间> [选项]

必填：
  --started-at <时间>          24小时验收实际开始时间，例如 2026-08-13T15:52:00+08:00

选项：
  --duration-hours <小时>      验收时长，默认 24
  --interval-seconds <秒>      运行状态采样间隔，默认 60
  --output-dir <目录>          证据输出目录
  --app-log <文件>             后端日志，默认 /home/user1/app/app.log
  --app-pattern <正则>         用于 pgrep 查找后端进程
  --tdengine-container <名称>  TDengine 容器名，默认 iot-tdengine
  --mysql-container <名称>     MySQL 容器名，默认 iot-mysql
  --point-ids <逗号列表>       验收测点，默认 POINT004,POINT005,POINT006,POINT007
  --once                       仅采样一次并立即汇总，用于检查脚本配置
  -h, --help                   显示帮助

脚本只读取进程、容器、日志和 TDengine，不会重启服务或修改数据库。
EOF
}

fail() {
  echo "错误：$*" >&2
  exit 2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --started-at) STARTED_AT="${2:-}"; shift 2 ;;
    --duration-hours) DURATION_HOURS="${2:-}"; shift 2 ;;
    --interval-seconds) INTERVAL_SECONDS="${2:-}"; shift 2 ;;
    --output-dir) OUTPUT_DIR="${2:-}"; shift 2 ;;
    --app-log) APP_LOG="${2:-}"; shift 2 ;;
    --app-pattern) APP_PATTERN="${2:-}"; shift 2 ;;
    --tdengine-container) TDENGINE_CONTAINER="${2:-}"; shift 2 ;;
    --mysql-container) MYSQL_CONTAINER="${2:-}"; shift 2 ;;
    --point-ids) POINT_IDS="${2:-}"; shift 2 ;;
    --once) RUN_ONCE=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) fail "未知参数 $1" ;;
  esac
done

[[ -n "$STARTED_AT" ]] || fail "必须提供 --started-at"
[[ "$DURATION_HOURS" =~ ^[1-9][0-9]*$ ]] || fail "--duration-hours 必须是正整数"
[[ "$INTERVAL_SECONDS" =~ ^[1-9][0-9]*$ ]] || fail "--interval-seconds 必须是正整数"
[[ "$POINT_IDS" =~ ^[A-Za-z0-9_]+(,[A-Za-z0-9_]+)*$ ]] || fail "--point-ids 格式无效"
command -v docker >/dev/null 2>&1 || fail "找不到 docker 命令"
command -v date >/dev/null 2>&1 || fail "找不到 date 命令"

START_EPOCH=$(date -d "$STARTED_AT" +%s 2>/dev/null) || fail "无法解析 --started-at"
END_EPOCH=$((START_EPOCH + DURATION_HOURS * 3600))
MONITOR_START_EPOCH=$(date +%s)
START_SQL=$(TZ=Asia/Shanghai date -d "@$START_EPOCH" '+%F %T')
END_SQL=$(TZ=Asia/Shanghai date -d "@$END_EPOCH" '+%F %T')

if [[ -z "$OUTPUT_DIR" ]]; then
  OUTPUT_DIR="$PWD/hardware-acceptance-$(date '+%Y%m%d-%H%M%S')"
fi
mkdir -p "$OUTPUT_DIR"

SAMPLES_FILE="$OUTPUT_DIR/runtime-samples.csv"
LOG_DELTA_FILE="$OUTPUT_DIR/app-log-delta.log"
RAW_QUERY_FILE="$OUTPUT_DIR/tdengine-raw-summary.txt"
MINUTE_QUERY_FILE="$OUTPUT_DIR/tdengine-minute-summary.txt"
REPORT_FILE="$OUTPUT_DIR/report.md"
INITIAL_LOG_LINES=0
if [[ -f "$APP_LOG" ]]; then
  INITIAL_LOG_LINES=$(wc -l < "$APP_LOG")
fi

cat > "$OUTPUT_DIR/metadata.txt" <<EOF
acceptance_started_at=$STARTED_AT
acceptance_started_at_shanghai=$START_SQL
acceptance_ends_at_shanghai=$END_SQL
duration_hours=$DURATION_HOURS
monitor_started_at=$(date -Is)
monitor_started_after_acceptance_seconds=$((MONITOR_START_EPOCH - START_EPOCH))
interval_seconds=$INTERVAL_SECONDS
app_log=$APP_LOG
app_pattern=$APP_PATTERN
tdengine_container=$TDENGINE_CONTAINER
mysql_container=$MYSQL_CONTAINER
point_ids=$POINT_IDS
EOF

echo 'sampled_at,app_pid,app_alive,cpu_percent,rss_kb,mysql_running,tdengine_running,disk_used_percent' > "$SAMPLES_FILE"

container_running() {
  local container="$1"
  local state
  state=$(docker inspect --format '{{if .State.Running}}1{{else}}0{{end}}' "$container" 2>/dev/null || true)
  [[ "$state" == "1" ]] && echo 1 || echo 0
}

sample_runtime() {
  local sampled_at app_pid app_alive cpu_percent rss_kb mysql_running tdengine_running disk_used
  sampled_at=$(date -Is)
  app_pid=$(pgrep -n -f "$APP_PATTERN" 2>/dev/null || true)
  app_alive=0
  cpu_percent=""
  rss_kb=""
  if [[ -n "$app_pid" ]] && ps -p "$app_pid" >/dev/null 2>&1; then
    app_alive=1
    cpu_percent=$(ps -p "$app_pid" -o %cpu= | tr -d ' ')
    rss_kb=$(ps -p "$app_pid" -o rss= | tr -d ' ')
  fi
  mysql_running=$(container_running "$MYSQL_CONTAINER")
  tdengine_running=$(container_running "$TDENGINE_CONTAINER")
  disk_used=$(df -P / | awk 'NR == 2 {gsub(/%/, "", $5); print $5}')
  printf '%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "$sampled_at" "$app_pid" "$app_alive" "$cpu_percent" "$rss_kb" \
    "$mysql_running" "$tdengine_running" "$disk_used" >> "$SAMPLES_FILE"
  echo "[$sampled_at] 后端=$app_alive MySQL=$mysql_running TDengine=$tdengine_running 磁盘=${disk_used}%"
}

sql_point_list() {
  local result="" point
  IFS=',' read -r -a points <<< "$POINT_IDS"
  for point in "${points[@]}"; do
    [[ -n "$result" ]] && result+=","
    result+="'$point'"
  done
  printf '%s' "$result"
}

write_summary() {
  [[ "$SUMMARY_WRITTEN" == "false" ]] || return 0
  SUMMARY_WRITTEN=true

  local current_log_lines start_line accepted_count disconnect_count rejected_count
  local app_down mysql_down tdengine_down points_sql raw_exit minute_exit runtime_state query_state
  current_log_lines=0
  if [[ -f "$APP_LOG" ]]; then
    current_log_lines=$(wc -l < "$APP_LOG")
    if (( current_log_lines >= INITIAL_LOG_LINES )); then
      start_line=$((INITIAL_LOG_LINES + 1))
      tail -n "+$start_line" "$APP_LOG" > "$LOG_DELTA_FILE"
    else
      cp "$APP_LOG" "$LOG_DELTA_FILE"
    fi
  else
    : > "$LOG_DELTA_FILE"
  fi

  accepted_count=$(grep -c '标准多字段MQTT报文处理完成: outcome=ACCEPTED' "$LOG_DELTA_FILE" || true)
  disconnect_count=$(grep -Ec 'MQTT连接断开|Connection lost' "$LOG_DELTA_FILE" || true)
  rejected_count=$(grep -Ec 'outcome=REJECTED|报文解析拒绝|设备身份缺失' "$LOG_DELTA_FILE" || true)
  app_down=$(awk -F, 'NR > 1 && $3 != 1 {count++} END {print count+0}' "$SAMPLES_FILE")
  mysql_down=$(awk -F, 'NR > 1 && $6 != 1 {count++} END {print count+0}' "$SAMPLES_FILE")
  tdengine_down=$(awk -F, 'NR > 1 && $7 != 1 {count++} END {print count+0}' "$SAMPLES_FILE")
  points_sql=$(sql_point_list)

  local raw_sql minute_sql
  raw_sql="SELECT point_id,point_code,COUNT(*) AS raw_rows,FIRST(ts) AS first_ts,LAST(ts) AS last_ts,FIRST(received_time) AS first_received,LAST(received_time) AS last_received,SUM(CASE WHEN data_quality <> 0 THEN 1 ELSE 0 END) AS non_q0_rows FROM iot_telemetry.st_raw_event WHERE ts >= '$START_SQL' AND ts < '$END_SQL' AND point_id IN ($points_sql) GROUP BY point_id,point_code ORDER BY point_id;"
  minute_sql="SELECT point_id,point_code,COUNT(*) AS minute_rows,FIRST(ts) AS first_minute,LAST(ts) AS last_minute,SUM(sample_count) AS samples,MIN(data_quality) AS min_quality,MAX(data_quality) AS max_quality FROM iot_telemetry.st_raw_minute WHERE ts >= '$START_SQL' AND ts < '$END_SQL' AND point_id IN ($points_sql) GROUP BY point_id,point_code ORDER BY point_id;"

  set +e
  docker exec "$TDENGINE_CONTAINER" taos -s "$raw_sql" > "$RAW_QUERY_FILE" 2>&1
  raw_exit=$?
  docker exec "$TDENGINE_CONTAINER" taos -s "$minute_sql" > "$MINUTE_QUERY_FILE" 2>&1
  minute_exit=$?
  set -e

  runtime_state="通过"
  if (( app_down > 0 || mysql_down > 0 || tdengine_down > 0 )); then
    runtime_state="需复核"
  fi
  query_state="已生成"
  if (( raw_exit != 0 || minute_exit != 0 )); then
    query_state="查询失败，需复核"
  fi

  cat > "$REPORT_FILE" <<EOF
# 硬件接入 24 小时验收证据

- 验收范围：$START_SQL 至 $END_SQL（Asia/Shanghai）
- 监控脚本启动：$(TZ=Asia/Shanghai date -d "@$MONITOR_START_EPOCH" '+%F %T')
- 运行状态采样：$runtime_state
- TDengine 汇总：$query_state
- 后端异常采样：$app_down
- MySQL 异常采样：$mysql_down
- TDengine 异常采样：$tdengine_down
- 脚本启动后已接受报文日志数：$accepted_count
- MQTT 断开日志数：$disconnect_count
- 报文拒绝日志数：$rejected_count

## 证据文件

- runtime-samples.csv：脚本启动后的进程、容器和磁盘采样
- app-log-delta.log：脚本启动后的后端日志增量
- tdengine-raw-summary.txt：完整验收时间范围内的原始数据汇总
- tdengine-minute-summary.txt：完整验收时间范围内的分钟数据汇总

## 结论边界

脚本晚于验收开始时间的部分，只能通过 TDengine 历史数据回查，无法补回此前的进程和容器状态采样。
最终结论仍需确认 4 个测点均覆盖验收首尾、分钟数据连续，并解释所有断线、拒绝和非 Q0 数据。
EOF

  echo "验收证据已写入：$OUTPUT_DIR"
  echo "查看汇总：cat '$REPORT_FILE'"
}

on_interrupt() {
  echo "收到停止信号，正在保存当前证据。"
  write_summary
  exit 130
}
trap on_interrupt INT TERM

echo "验收范围：$START_SQL 至 $END_SQL（Asia/Shanghai）"
echo "证据目录：$OUTPUT_DIR"

while true; do
  sample_runtime
  now_epoch=$(date +%s)
  if [[ "$RUN_ONCE" == "true" ]] || (( now_epoch >= END_EPOCH )); then
    break
  fi
  remaining=$((END_EPOCH - now_epoch))
  sleep_seconds=$INTERVAL_SECONDS
  (( remaining < sleep_seconds )) && sleep_seconds=$remaining
  sleep "$sleep_seconds"
done

write_summary
