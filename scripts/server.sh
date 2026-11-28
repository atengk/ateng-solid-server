#!/usr/bin/env bash
# ==============================================================================
# ateng-solid-server 后端服务运行与构建管理脚本
#
# 兼容两种运行场景：
#   1. 源码仓库内运行（ateng-solid-server/scripts/server.sh）
#   2. 独立部署包运行（同级或上级目录放置 ateng-solid-bootstrap-*.jar）
#
# 支持子命令：
#   start       [--profile dev|prod] [--port 8080] [--mgmt-port 8081] [--env-file path] [--jar path]
#               [--jvm-opts "..."] [--app-args "..."] [--build] [--clean] [--with-tests]
#               [--fg] [--skip-env-check] [--timeout 60] [--log-dir dir] [--pid-dir dir]
#               [-- <Spring Boot 应用运行参数...>]
#   stop        [--port 8080] [--mgmt-port 8081] [--stop-timeout 25] [--force] [--log-dir dir] [--pid-dir dir]
#   restart     [同 start / stop 参数，支持 --app-args 与 -- 透传]
#   status      [--port 8080] [--mgmt-port 8081] [--log-dir dir] [--pid-dir dir]
#   logs        [-f|--follow] [-n|--lines 100] [--error] [--log-dir dir] [--pid-dir dir]
#   build       [--clean] [--with-tests]
#   check       [--profile dev|prod] [--port 8080] [--mgmt-port 8081] [--env-file path] [--jar path] [--skip-env-check]
#   help        显示帮助说明（无子命令时默认执行）
#
# @author Ateng
# @since 2026-11-26
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ "$(basename "$SCRIPT_DIR")" == "scripts" ]]; then
  BASE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
else
  BASE_DIR="$SCRIPT_DIR"
fi

# 运行时默认参数
ACTION=""
OPT_PROFILE="${SPRING_PROFILES_ACTIVE:-dev}"
OPT_PORT="${SERVER_PORT:-8080}"
OPT_MGMT_PORT=""
OPT_MGMT_PORT_SET=false
RESOLVED_MGMT_PORT=""
RESOLVED_MGMT_HOST="127.0.0.1"
OPT_ENV_FILE="${SOLID_ENV_FILE:-}"
OPT_JAR="${APP_JAR:-}"
OPT_JVM_OPTS="${JAVA_OPTS:-}"
OPT_JVM_OPTS_SET=false
OPT_APP_ARGS="${SOLID_APP_ARGS:-}"
OPT_APP_ARGS_SET=false
EXTRA_APP_ARGS=()
RESOLVED_JVM_OPTS_ARR=()
RESOLVED_APP_ARGS_ARR=()
OPT_LOG_DIR="${SOLID_SERVER_LOG_DIR:-$BASE_DIR/logs}"
OPT_PID_DIR="${SOLID_SERVER_PID_DIR:-$BASE_DIR/tmp/run}"
OPT_BUILD=false
OPT_CLEAN=false
OPT_WITH_TESTS=false
OPT_FG=false
OPT_FORCE=false
OPT_SKIP_ENV_CHECK=false
OPT_START_TIMEOUT=60
OPT_STOP_TIMEOUT=25
OPT_FOLLOW=false
OPT_LINES=100
OPT_ERROR_LOG=false

# 生产环境（application-prod.yml）零默认值 Fail-Fast 必填环境变量
REQUIRED_PROD_ENV_VARS=(
  "SOLID_DB_HOST"
  "SOLID_DB_USERNAME"
  "SOLID_DB_PASSWORD"
  "SOLID_REDIS_HOST"
  "SOLID_REDIS_PASSWORD"
  "SOLID_CRYPTO_SECRET_KEY"
  "SOLID_AUTH_SESSION_HANDLE_SECRET"
  "SOLID_S3_ENDPOINT"
  "SOLID_S3_BUCKET"
  "SOLID_S3_ACCESS_KEY"
  "SOLID_S3_SECRET_KEY"
)

if [[ -t 1 ]]; then
  C_RESET="\033[0m"
  C_BOLD="\033[1m"
  C_GREEN="\033[32m"
  C_YELLOW="\033[33m"
  C_RED="\033[31m"
  C_CYAN="\033[36m"
else
  C_RESET=""
  C_BOLD=""
  C_GREEN=""
  C_YELLOW=""
  C_RED=""
  C_CYAN=""
fi

log_info() { printf "%b[INFO]%b %s\n" "${C_CYAN}" "${C_RESET}" "$1"; }
log_ok() { printf "%b[OK]%b %s\n" "${C_GREEN}" "${C_RESET}" "$1"; }
log_warn() { printf "%b[WARN]%b %s\n" "${C_YELLOW}" "${C_RESET}" "$1" >&2; }
log_err() { printf "%b[ERROR]%b %s\n" "${C_RED}" "${C_RESET}" "$1" >&2; }

show_help() {
  cat <<'EOF'
用法: bash scripts/server.sh [子命令] [选项] [-- <应用运行参数...>]

无子命令时默认显示本帮助说明（查询状态请显式执行 status）。

子命令:
  start       启动后端服务（默认后台运行，支持 dev / prod）
  stop        优雅停止后端服务（等待停机窗口，超时兜底清理）
  restart     重启后端服务（先停后启，可组合 --build）
  status      查看后端运行状态、PID、端口、配置与日志路径
  logs        查看后端日志（默认控制台日志最后 100 行）
  build       编译打包 ateng-solid-bootstrap Fat JAR（默认增量跳过测试）
  check       前置巡检（JDK 21、JAR/Maven、环境配置、端口占用）
  help        显示本帮助信息

常用选项（所有带值选项均同时支持 --opt val 与 --opt=val 写法）:
  -p, --profile <dev|prod>    激活 Spring Profile（默认: dev）
      --port <port>           指定服务监听端口（默认: 8080）
      --mgmt-port <port>      指定 Actuator 管理端口（默认: dev 同服务端口 / prod 8081）
      --env-file <path>       指定环境变量文件（不覆盖宿主机已导出变量）
      --jar <path>            指定可运行 Fat JAR 路径
      --jvm-opts "<opts>"     自定义 JVM 参数（覆盖 JAVA_OPTS 与默认堆大小）
      --app-args "<args>"     自定义 Spring Boot 应用运行参数（覆盖 SOLID_APP_ARGS，仅 start/restart 可用）
      --build                 启动或重启前自动重新构建 JAR
      --clean                 构建时附带 clean（默认增量编译）
      --with-tests            构建时执行测试套件（默认 -DskipTests）
      --fg                    前台运行（仅限 start 调试使用）
      --force                 stop/restart 时强制清理未纳管的端口占用进程
      --skip-env-check        prod 模式跳过必填 SOLID_* 环境变量校验
      --timeout <sec>         启动就绪等待超时秒数（默认: 60）
      --stop-timeout <sec>    优雅停机等待超时秒数（默认: 25）
  -f, --follow                logs 子命令持续追踪日志输出
  -n, --lines <num>           logs 子命令显示行数（默认: 100）
      --error                 logs 子命令切看 prod 模式错误日志 (*-error.log)
      --log-dir <dir>         覆盖日志目录（默认: <工程>/logs）
      --pid-dir <dir>         覆盖 PID 目录（默认: <工程>/tmp/run）

运行时参数透传（仅 start / restart 支持）:
  -- <args...>                按原样透传给 SolidApplication.main(args)（追加在 --app-args 之后）
                              注：--server.port* / --management.server.port* / --spring.profiles.active*
                                  请统一使用 --port / --mgmt-port / -p
EOF
}

is_windows() {
  case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*|MSYS*|CYGWIN*) return 0 ;;
    *) return 1 ;;
  esac
}

is_positive_int() {
  [[ "${1:-}" =~ ^[0-9]+$ ]] && [[ "$1" -gt 0 ]]
}

to_native_path() {
  local path="$1"
  if is_windows && command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$path"
  else
    printf '%s' "$path"
  fi
}

meta_get() {
  local file="$1"
  local target_key="$2"
  if [[ ! -f "$file" ]]; then
    return 0
  fi
  local line key val
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ -z "$line" || "$line" == \#* ]] && continue
    key="${line%%=*}"
    val="${line#*=}"
    if [[ "$key" == "$target_key" ]]; then
      printf '%s' "$val"
      return 0
    fi
  done < "$file"
}

trim_spaces() {
  local s="$1"
  s="${s#"${s%%[![:space:]]*}"}"
  s="${s%"${s##*[![:space:]]}"}"
  printf '%s' "$s"
}

SPLIT_ARGS_RESULT=()

split_cli_args() {
  local raw="${1:-}"
  SPLIT_ARGS_RESULT=()
  local len=${#raw}
  local i=0
  local current=""
  local has_token=false
  local quote=""
  local ch=""
  local next_ch=""

  while [[ "$i" -lt "$len" ]]; do
    ch="${raw:i:1}"
    if [[ -n "$quote" ]]; then
      if [[ "$quote" == "'" ]]; then
        if [[ "$ch" == "'" ]]; then
          quote=""
        else
          current+="$ch"
        fi
      else
        if [[ "$ch" == '"' ]]; then
          quote=""
        elif [[ "$ch" == '\' ]]; then
          if [[ $((i + 1)) -ge "$len" ]]; then
            log_err "参数字符串末尾存在悬空转义符: $raw"
            exit 1
          fi
          next_ch="${raw:$((i + 1)):1}"
          case "$next_ch" in
            '"'|'\'|'$'|'`') current+="$next_ch" ;;
            *) current+="\\$next_ch" ;;
          esac
          i=$((i + 1))
        else
          current+="$ch"
        fi
      fi
    else
      case "$ch" in
        ' '|$'\t'|$'\n'|$'\r')
          if [[ "$has_token" == "true" ]]; then
            SPLIT_ARGS_RESULT+=("$current")
            current=""
            has_token=false
          fi
          ;;
        "'"|'"')
          quote="$ch"
          has_token=true
          ;;
        '\')
          if [[ $((i + 1)) -ge "$len" ]]; then
            log_err "参数字符串末尾存在悬空转义符: $raw"
            exit 1
          fi
          i=$((i + 1))
          current+="${raw:i:1}"
          has_token=true
          ;;
        *)
          current+="$ch"
          has_token=true
          ;;
      esac
    fi
    i=$((i + 1))
  done

  if [[ -n "$quote" ]]; then
    log_err "参数字符串引号未闭合: $raw"
    exit 1
  fi
  if [[ "$has_token" == "true" ]]; then
    SPLIT_ARGS_RESULT+=("$current")
  fi
}

validate_runtime_args() {
  if [[ "$OPT_APP_ARGS_SET" == "true" || "${#EXTRA_APP_ARGS[@]}" -gt 0 ]]; then
    if [[ "$ACTION" != "start" && "$ACTION" != "restart" ]]; then
      log_err "仅 start / restart 支持通过 --app-args 或 -- 传入应用运行参数（当前子命令: $ACTION）"
      exit 1
    fi
  fi

  if [[ "$ACTION" != "start" && "$ACTION" != "restart" ]]; then
    return 0
  fi

  RESOLVED_JVM_OPTS_ARR=()
  if [[ -n "$OPT_JVM_OPTS" ]]; then
    split_cli_args "$OPT_JVM_OPTS"
    if [[ "${#SPLIT_ARGS_RESULT[@]}" -gt 0 ]]; then
      RESOLVED_JVM_OPTS_ARR=("${SPLIT_ARGS_RESULT[@]}")
    fi
  fi

  local item
  if [[ "${#RESOLVED_JVM_OPTS_ARR[@]}" -gt 0 ]]; then
    for item in "${RESOLVED_JVM_OPTS_ARR[@]}"; do
      case "$item" in
        -Dserver.port|-Dserver.port=*|-Dmanagement.server.port|-Dmanagement.server.port=*|-Dspring.profiles.active|-Dspring.profiles.active=*)
          log_err "检测到与脚本主参数冲突的 JVM 属性 ($item)，请统一使用脚本选项 --port / --mgmt-port 或 -p/--profile"
          exit 1
          ;;
      esac
    done
  fi

  RESOLVED_APP_ARGS_ARR=()
  if [[ -n "$OPT_APP_ARGS" ]]; then
    split_cli_args "$OPT_APP_ARGS"
    if [[ "${#SPLIT_ARGS_RESULT[@]}" -gt 0 ]]; then
      RESOLVED_APP_ARGS_ARR=("${SPLIT_ARGS_RESULT[@]}")
    fi
  fi
  if [[ "${#EXTRA_APP_ARGS[@]}" -gt 0 ]]; then
    RESOLVED_APP_ARGS_ARR+=("${EXTRA_APP_ARGS[@]}")
  fi

  if [[ "${#RESOLVED_APP_ARGS_ARR[@]}" -gt 0 ]]; then
    for item in "${RESOLVED_APP_ARGS_ARR[@]}"; do
      case "$item" in
        --server.port|--server.port=*|--management.server.port|--management.server.port=*|--spring.profiles.active|--spring.profiles.active=*)
          log_err "检测到与脚本主参数冲突的 Spring Boot 参数 ($item)，请统一使用脚本选项 --port / --mgmt-port 或 -p/--profile"
          exit 1
          ;;
      esac
    done
  fi
}

load_env_file() {
  local env_file="$1"
  local line key val first_char last_char
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    line="$(trim_spaces "$line")"
    [[ -z "$line" || "$line" == \#* ]] && continue
    if [[ "$line" == export\ * ]]; then
      line="$(trim_spaces "${line#export }")"
    fi
    if [[ "$line" != *=* ]]; then
      continue
    fi
    key="$(trim_spaces "${line%%=*}")"
    val="$(trim_spaces "${line#*=}")"
    if [[ ! "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
      log_warn "忽略非法环境变量名: $key ($env_file)"
      continue
    fi
    if [[ "${#val}" -ge 2 ]]; then
      first_char="${val:0:1}"
      last_char="${val: -1}"
      if [[ ( "$first_char" == '"' && "$last_char" == '"' ) || ( "$first_char" == "'" && "$last_char" == "'" ) ]]; then
        val="${val:1:${#val}-2}"
      fi
    fi
    # 仅在宿主机未显式导出时注入，保留外部环境变量最高优先级
    if [[ -z "${!key+x}" ]]; then
      export "${key}=${val}"
    fi
  done < "$env_file"
}

load_windows_solid_env() {
  is_windows || return 0
  command -v powershell.exe >/dev/null 2>&1 || return 0

  local loaded_count=0
  local line key val
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ "$line" == *=* ]] || continue
    key="${line%%=*}"
    val="${line#*=}"
    [[ "$key" =~ ^SOLID_[A-Za-z0-9_]*$ ]] || continue
    if [[ -z "${!key+x}" ]]; then
      export "${key}=${val}"
      loaded_count=$((loaded_count + 1))
    fi
  done < <(powershell.exe -NoProfile -NonInteractive -Command '
    foreach ($scope in @("User", "Machine")) {
      $vars = [Environment]::GetEnvironmentVariables($scope)
      foreach ($k in $vars.Keys) {
        if ($k -like "SOLID_*") {
          $v = [string]$vars[$k]
          if ($v -notmatch "[\r\n]") {
            [Console]::Out.WriteLine("$k=$v")
          }
        }
      }
    }
  ' 2>/dev/null || true)

  if [[ "$loaded_count" -gt 0 ]]; then
    log_info "已从 Windows 用户/系统环境变量补齐 ${loaded_count} 项未继承的 SOLID_* 配置"
  fi
}

resolve_and_load_env() {
  if [[ -n "$OPT_ENV_FILE" ]]; then
    if [[ ! -f "$OPT_ENV_FILE" ]]; then
      log_err "指定的环境变量文件不存在: $OPT_ENV_FILE"
      exit 1
    fi
    load_env_file "$OPT_ENV_FILE"
    log_info "已加载环境变量文件: $OPT_ENV_FILE"
  elif [[ "$OPT_PROFILE" == "prod" ]]; then
    local candidate
    for candidate in "$BASE_DIR/.env.prod" "$BASE_DIR/.env" "$SCRIPT_DIR/.env.prod" "$SCRIPT_DIR/.env"; do
      if [[ -f "$candidate" ]]; then
        load_env_file "$candidate"
        log_info "已自动加载环境变量文件: $candidate"
        break
      fi
    done
  fi

  # dev 等本地联调环境兜底从 Windows 用户/系统环境变量同步未继承的 SOLID_* 变量（prod 保持严格隔离）
  if [[ "$OPT_PROFILE" != "prod" ]]; then
    load_windows_solid_env
  fi
}

format_url_host() {
  local host="${1:-127.0.0.1}"
  if [[ "$host" == *:* && "$host" != \[*\] ]]; then
    printf '[%s]' "$host"
  else
    printf '%s' "$host"
  fi
}

resolve_management_endpoint() {
  local mgmt_port=""
  if [[ "$OPT_MGMT_PORT_SET" == "true" ]]; then
    mgmt_port="$OPT_MGMT_PORT"
  elif [[ "$OPT_PROFILE" == "prod" ]]; then
    mgmt_port="${SOLID_MANAGEMENT_PORT:-8081}"
  else
    mgmt_port="$OPT_PORT"
  fi

  if ! is_positive_int "$mgmt_port"; then
    log_err "Actuator 管理端口配置无效（必须为正整数）: $mgmt_port"
    exit 1
  fi
  RESOLVED_MGMT_PORT="$mgmt_port"

  local mgmt_host="127.0.0.1"
  if [[ "$OPT_PROFILE" == "prod" ]]; then
    mgmt_host="${SOLID_MANAGEMENT_ADDRESS:-127.0.0.1}"
  fi
  case "$mgmt_host" in
    ""|"0.0.0.0"|"::"|"[::]"|"*")
      mgmt_host="127.0.0.1"
      ;;
  esac
  RESOLVED_MGMT_HOST="$mgmt_host"
}

get_java_major() {
  local ver_line major
  ver_line="$(java -version 2>&1 | head -n 1 | tr -d '\r')"
  major="$(printf '%s\n' "$ver_line" | sed -n 's/.*"\([0-9][0-9]*\).*/\1/p')"
  printf '%s' "${major:-0}"
}

is_source_repo() {
  [[ -f "$BASE_DIR/pom.xml" ]] && [[ -d "$BASE_DIR/ateng-solid-bootstrap" ]]
}

find_jar() {
  if [[ -n "$OPT_JAR" ]]; then
    if [[ -f "$OPT_JAR" ]]; then
      printf '%s' "$OPT_JAR"
      return 0
    fi
    return 1
  fi

  local search_dirs=()
  if [[ -d "$BASE_DIR/ateng-solid-bootstrap/target" ]]; then
    search_dirs+=("$BASE_DIR/ateng-solid-bootstrap/target")
  fi
  search_dirs+=("$BASE_DIR" "$BASE_DIR/lib" "$SCRIPT_DIR")

  local prev_nullglob=false
  if shopt -q nullglob; then
    prev_nullglob=true
  fi
  shopt -s nullglob

  local dir f found=""
  for dir in "${search_dirs[@]}"; do
    [[ -d "$dir" ]] || continue
    for f in "$dir"/ateng-solid-bootstrap-*.jar; do
      case "$f" in
        *.jar.original|*-sources.jar|*-javadoc.jar) continue ;;
      esac
      found="$f"
      break
    done
    if [[ -n "$found" ]]; then
      break
    fi
  done

  if [[ "$prev_nullglob" == "false" ]]; then
    shopt -u nullglob
  fi

  if [[ -n "$found" ]]; then
    printf '%s' "$found"
    return 0
  fi
  return 1
}

get_port_pids() {
  local port="$1"
  if is_windows; then
    netstat -ano -p tcp 2>/dev/null | tr -d '\r' | awk -v p=":${port}" '
      $1 == "TCP" && $4 == "LISTENING" {
        plen = length(p)
        alen = length($2)
        if (alen > plen && substr($2, alen - plen + 1) == p && $5 ~ /^[0-9]+$/) {
          print $5
        }
      }
    ' | sort -u
    return 0
  fi

  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null | sort -u || true
  elif command -v ss >/dev/null 2>&1; then
    ss -tlnp 2>/dev/null | awk -v p=":${port}" '
      $1 == "LISTEN" {
        plen = length(p)
        alen = length($4)
        if (alen > plen && substr($4, alen - plen + 1) == p) {
          line = $0
          while (match(line, /pid=[0-9]+/)) {
            pid = substr(line, RSTART + 4, RLENGTH - 4)
            print pid
            line = substr(line, RSTART + RLENGTH)
          }
        }
      }
    ' | sort -u
  elif command -v netstat >/dev/null 2>&1; then
    netstat -tlnp 2>/dev/null | awk -v p=":${port}" '
      $6 == "LISTEN" {
        plen = length(p)
        alen = length($4)
        if (alen > plen && substr($4, alen - plen + 1) == p) {
          split($7, arr, "/")
          if (arr[1] ~ /^[0-9]+$/) print arr[1]
        }
      }
    ' | sort -u
  fi
}

is_port_listening() {
  local port="$1"
  local pids
  pids="$(get_port_pids "$port" || true)"
  if [[ -n "$pids" ]]; then
    return 0
  fi
  # 若 Linux 精简镜像缺少 ss/lsof/netstat，退回 Bash TCP 探针
  if ! is_windows && ! command -v lsof >/dev/null 2>&1 && ! command -v ss >/dev/null 2>&1 && ! command -v netstat >/dev/null 2>&1; then
    if (echo >/dev/tcp/127.0.0.1/"$port") >/dev/null 2>&1; then
      return 0
    fi
  fi
  return 1
}

get_winpid_for_msys_pid() {
  local msys_pid="$1"
  local tries=0
  local winpid=""
  while [[ "$tries" -lt 3 ]]; do
    winpid="$(ps -W -p "$msys_pid" 2>/dev/null | awk 'NR==2 {print $4}' || true)"
    if [[ "$winpid" =~ ^[0-9]+$ ]]; then
      printf '%s' "$winpid"
      return 0
    fi
    tries=$((tries + 1))
    sleep 1
  done
}

is_pid_alive() {
  local msys_pid="${1:-}"
  local winpid="${2:-}"
  if [[ -n "$msys_pid" ]] && kill -0 "$msys_pid" 2>/dev/null; then
    return 0
  fi
  if is_windows && [[ -n "$winpid" ]]; then
    if ps -W 2>/dev/null | awk -v wp="$winpid" '$4 == wp { found=1 } END { exit !found }'; then
      return 0
    fi
  fi
  return 1
}

read_runtime_state() {
  PID_FILE="$OPT_PID_DIR/server.pid"
  META_FILE="$OPT_PID_DIR/server.meta"
  CONSOLE_LOG="$OPT_LOG_DIR/server.log"

  STATE_PID=""
  STATE_WINPID=""
  STATE_PORT_PID=""
  STATE_PORT="$OPT_PORT"
  STATE_MGMT_PORT=""
  STATE_MGMT_HOST=""
  STATE_PROFILE="$OPT_PROFILE"
  STATE_LOG="$CONSOLE_LOG"
  STATE_STARTED_AT=""

  if [[ -f "$PID_FILE" ]]; then
    STATE_PID="$(tr -d '[:space:]' < "$PID_FILE" || true)"
  fi
  if [[ -f "$META_FILE" ]]; then
    local val
    val="$(meta_get "$META_FILE" "WINPID")"
    [[ -n "$val" ]] && STATE_WINPID="$val"
    val="$(meta_get "$META_FILE" "PORT_PID")"
    [[ -n "$val" ]] && STATE_PORT_PID="$val"
    val="$(meta_get "$META_FILE" "PORT")"
    [[ -n "$val" ]] && STATE_PORT="$val"
    val="$(meta_get "$META_FILE" "MGMT_PORT")"
    [[ -n "$val" ]] && STATE_MGMT_PORT="$val"
    val="$(meta_get "$META_FILE" "MGMT_HOST")"
    [[ -n "$val" ]] && STATE_MGMT_HOST="$val"
    val="$(meta_get "$META_FILE" "PROFILE")"
    [[ -n "$val" ]] && STATE_PROFILE="$val"
    val="$(meta_get "$META_FILE" "LOG_FILE")"
    [[ -n "$val" ]] && STATE_LOG="$val"
    val="$(meta_get "$META_FILE" "STARTED_AT")"
    [[ -n "$val" ]] && STATE_STARTED_AT="$val"
  fi
}

clear_runtime_state() {
  rm -f "$OPT_PID_DIR/server.pid" "$OPT_PID_DIR/server.meta"
}

write_runtime_state() {
  local msys_pid="$1"
  local winpid="$2"
  local port_pid="$3"
  local jar_path="$4"
  local started_at
  started_at="$(date '+%Y-%m-%d %H:%M:%S')"

  mkdir -p "$OPT_PID_DIR"
  printf '%s\n' "$msys_pid" > "$OPT_PID_DIR/server.pid"
  cat > "$OPT_PID_DIR/server.meta" <<EOF
PID=$msys_pid
WINPID=$winpid
PORT_PID=$port_pid
PORT=$OPT_PORT
MGMT_PORT=$RESOLVED_MGMT_PORT
MGMT_HOST=$RESOLVED_MGMT_HOST
PROFILE=$OPT_PROFILE
JAR_PATH=$jar_path
LOG_FILE=$OPT_LOG_DIR/server.log
STARTED_AT=$started_at
EOF
}

kill_windows_pid_tree() {
  local winpid="$1"
  local force="${2:-false}"
  [[ -n "$winpid" ]] || return 0
  if [[ "$force" == "true" ]]; then
    taskkill //PID "$winpid" //T //F >/dev/null 2>&1 || true
  else
    taskkill //PID "$winpid" //T >/dev/null 2>&1
  fi
}

stop_process_gracefully() {
  local msys_pid="${1:-}"
  local winpid="${2:-}"
  local port_pid="${3:-}"
  local timeout="$4"

  if is_windows; then
    local target_wp="${winpid:-$port_pid}"
    local wm_close_ok=false
    if [[ -n "$target_wp" ]] && kill_windows_pid_tree "$target_wp" false; then
      wm_close_ok=true
    elif [[ -n "$msys_pid" ]]; then
      kill -TERM "$msys_pid" 2>/dev/null || true
    fi

    local wait_limit="$timeout"
    if [[ "$wm_close_ok" == "false" ]] && [[ "$wait_limit" -gt 5 ]]; then
      wait_limit=5
    fi

    local elapsed=0
    while [[ "$elapsed" -lt "$wait_limit" ]]; do
      if ! is_pid_alive "$msys_pid" "$winpid" && { [[ -z "$port_pid" ]] || ! is_pid_alive "" "$port_pid"; }; then
        return 0
      fi
      sleep 1
      elapsed=$((elapsed + 1))
    done

    if is_pid_alive "$msys_pid" "$winpid" || { [[ -n "$port_pid" ]] && is_pid_alive "" "$port_pid"; }; then
      log_warn "优雅停机等待超时，强制终止后端进程树..."
      [[ -n "$winpid" ]] && kill_windows_pid_tree "$winpid" true
      [[ -n "$port_pid" && "$port_pid" != "$winpid" ]] && kill_windows_pid_tree "$port_pid" true
      [[ -n "$msys_pid" ]] && kill -KILL "$msys_pid" 2>/dev/null || true
      sleep 1
    fi
    return 0
  fi

  # Linux / macOS: 发送 SIGTERM 以触发 Spring Boot server.shutdown=graceful 与 SmartLifecycle
  if [[ -n "$msys_pid" ]]; then
    kill -TERM "$msys_pid" 2>/dev/null || true
  fi
  if [[ -n "$port_pid" && "$port_pid" != "$msys_pid" ]]; then
    kill -TERM "$port_pid" 2>/dev/null || true
  fi

  local elapsed=0
  while [[ "$elapsed" -lt "$timeout" ]]; do
    if ! is_pid_alive "$msys_pid" "" && { [[ -z "$port_pid" ]] || ! is_pid_alive "$port_pid" ""; }; then
      return 0
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done

  log_warn "优雅停机超时 (${timeout}s)，执行 SIGKILL 强杀..."
  [[ -n "$msys_pid" ]] && kill -KILL "$msys_pid" 2>/dev/null || true
  [[ -n "$port_pid" && "$port_pid" != "$msys_pid" ]] && kill -KILL "$port_pid" 2>/dev/null || true
  sleep 1
}

check_toolchain() {
  if ! command -v java >/dev/null 2>&1; then
    log_err "未检测到 java 命令，请先安装并配置 OpenJDK 21+"
    return 1
  fi
  local major
  major="$(get_java_major)"
  if [[ "$major" -lt 21 ]]; then
    log_err "Java 版本过低（要求 21+，当前主版本: $major）"
    return 1
  fi
  log_ok "Java 基线就绪: $(java -version 2>&1 | head -n 1 | tr -d '\r')"

  if is_source_repo; then
    if command -v mvn >/dev/null 2>&1; then
      log_ok "Maven 基线就绪: $(mvn -v 2>&1 | head -n 1 | tr -d '\r')"
    else
      log_warn "未检测到 mvn 命令（直接运行已有 JAR 不受影响，重编构建需要 Maven 3.9+）"
    fi
  fi
}

check_profile_config() {
  if [[ "$OPT_PROFILE" == "dev" ]] && is_source_repo; then
    local local_yml="$BASE_DIR/ateng-solid-bootstrap/src/main/resources/application-local.yml"
    if [[ ! -f "$local_yml" ]]; then
      log_warn "未检测到本地配置 $local_yml（dev 将缺失 MySQL/Redis/S3 连接凭据；请从 application-local.example.yml 复制配置）"
    else
      log_ok "已检测到本地开发配置文件: application-local.yml"
      if [[ "$OPT_SKIP_ENV_CHECK" == "false" ]]; then
        local required_local_vars=""
        local missing_local=()
        local token var_name
        required_local_vars="$(grep -oE '\$\{[A-Za-z0-9_]+\}' "$local_yml" 2>/dev/null | sort -u || true)"
        while IFS= read -r token; do
          [[ -n "$token" ]] || continue
          var_name="${token#\$\{}"
          var_name="${var_name%\}}"
          if [[ -z "${!var_name:-}" ]]; then
            missing_local+=("$var_name")
          fi
        done <<< "$required_local_vars"
        if [[ "${#missing_local[@]}" -gt 0 ]]; then
          log_err "application-local.yml 中引用了以下未设置且无默认值的环境变量："
          for var_name in "${missing_local[@]}"; do
            printf '  - %s\n' "$var_name" >&2
          done
          log_err "请设置环境变量或通过 --env-file <path> 传入（如确需跳过校验请附加 --skip-env-check）"
          return 1
        fi
      fi
    fi
  fi

  if [[ "$OPT_PROFILE" == "prod" ]] && [[ "$OPT_SKIP_ENV_CHECK" == "false" ]]; then
    local missing=()
    local var_name
    for var_name in "${REQUIRED_PROD_ENV_VARS[@]}"; do
      if [[ -z "${!var_name:-}" ]]; then
        missing+=("$var_name")
      fi
    done
    if [[ "${#missing[@]}" -gt 0 ]]; then
      log_err "prod 模式缺失必填环境变量（application-prod.yml 零默认值 Fail-Fast）："
      for var_name in "${missing[@]}"; do
        printf '  - %s\n' "$var_name" >&2
      done
      log_err "请导出上述变量或通过 --env-file <path> 传入（如确需跳过校验请附加 --skip-env-check）"
      return 1
    fi
    log_ok "prod 必填环境变量校验通过（共 ${#REQUIRED_PROD_ENV_VARS[@]} 项）"
  fi
}

cmd_build() {
  if ! is_source_repo; then
    log_err "当前目录非 ateng-solid-server 源码仓库，无法执行 Maven 打包 ($BASE_DIR)"
    exit 1
  fi
  if ! command -v mvn >/dev/null 2>&1; then
    log_err "未检测到 mvn 命令，无法执行构建"
    exit 1
  fi
  if [[ "${1:-}" != "--skip-toolchain-check" ]]; then
    check_toolchain
  fi

  local mvn_args=()
  if [[ "$OPT_CLEAN" == "true" ]]; then
    mvn_args+=("clean")
  fi
  mvn_args+=("package" "-B" "-T" "1C")
  if [[ "$OPT_WITH_TESTS" == "false" ]]; then
    mvn_args+=("-DskipTests")
  fi

  log_info "正在构建 ateng-solid-server (mvn ${mvn_args[*]})..."
  (
    cd "$BASE_DIR" || exit 1
    mvn "${mvn_args[@]}"
  )

  local built_jar
  if built_jar="$(find_jar)"; then
    log_ok "构建完成: $built_jar"
  else
    log_err "Maven 执行完成，但未在预期目录找到 ateng-solid-bootstrap-*.jar"
    exit 1
  fi
}

cmd_check() {
  printf "\n%b=== ateng-solid-server 环境与配置巡检 ===%b\n" "${C_BOLD}" "${C_RESET}"
  local failed=false

  if ! check_toolchain; then
    failed=true
  fi

  resolve_and_load_env
  resolve_management_endpoint

  if ! check_profile_config; then
    failed=true
  fi

  local jar_path=""
  if jar_path="$(find_jar)"; then
    log_ok "可执行 JAR 就绪: $jar_path"
  else
    if is_source_repo; then
      log_warn "暂未找到 Fat JAR（首次 start 时将自动构建，或手动运行 bash scripts/server.sh build）"
    else
      log_err "未找到可执行 Fat JAR（请放置 ateng-solid-bootstrap-*.jar 或通过 --jar 指定）"
      failed=true
    fi
  fi

  read_runtime_state
  local port_pids
  port_pids="$(get_port_pids "$OPT_PORT" || true)"
  if [[ -n "$port_pids" ]]; then
    if [[ -n "$STATE_PID" ]] && is_pid_alive "$STATE_PID" "$STATE_WINPID"; then
      log_ok "端口 $OPT_PORT 由本脚本纳管实例监听中 (PID: $STATE_PID)"
    else
      log_warn "端口 $OPT_PORT 已被外部进程占用 (PID: $(printf '%s' "$port_pids" | tr '\n' ' '))"
    fi
  else
    log_ok "端口 $OPT_PORT 当前空闲可用"
  fi

  if [[ "$RESOLVED_MGMT_PORT" != "$OPT_PORT" ]]; then
    local mgmt_pids
    mgmt_pids="$(get_port_pids "$RESOLVED_MGMT_PORT" || true)"
    if [[ -n "$mgmt_pids" ]]; then
      if [[ -n "$STATE_PID" ]] && is_pid_alive "$STATE_PID" "$STATE_WINPID"; then
        log_ok "管理端口 $RESOLVED_MGMT_PORT 由本脚本纳管实例监听中 (PID: $STATE_PID)"
      else
        log_warn "管理端口 $RESOLVED_MGMT_PORT 已被外部进程占用 (PID: $(printf '%s' "$mgmt_pids" | tr '\n' ' '))"
      fi
    else
      log_ok "管理端口 $RESOLVED_MGMT_PORT 当前空闲可用"
    fi
  fi

  if [[ "$failed" == "true" ]]; then
    exit 1
  fi
}

cmd_start() {
  check_toolchain
  resolve_and_load_env
  if [[ "$OPT_JVM_OPTS_SET" != "true" ]]; then
    OPT_JVM_OPTS="${JAVA_OPTS:-}"
  fi
  if [[ "$OPT_APP_ARGS_SET" != "true" ]]; then
    OPT_APP_ARGS="${SOLID_APP_ARGS:-}"
  fi
  validate_runtime_args
  resolve_management_endpoint
  read_runtime_state

  # 1. 幂等校验：若已由脚本启动且存活
  if [[ -n "$STATE_PID" ]] && is_pid_alive "$STATE_PID" "$STATE_WINPID"; then
    local running_port="${STATE_PORT:-$OPT_PORT}"
    if is_port_listening "$running_port"; then
      log_ok "ateng-solid-server 已在运行中 (PID: $STATE_PID | Profile: ${STATE_PROFILE:-unknown} | Port: $running_port)"
      return 0
    fi
  fi

  # 清理失效的陈旧 PID 文件
  if [[ -f "$PID_FILE" || -f "$META_FILE" ]]; then
    clear_runtime_state
  fi

  # 2. 端口冲突拦截：禁止擅自杀死 IDEA 等外部拉起的进程
  local existing_port_pids
  existing_port_pids="$(get_port_pids "$OPT_PORT" || true)"
  if [[ -n "$existing_port_pids" ]]; then
    log_err "端口 $OPT_PORT 已被其他进程占用 (PID: $(printf '%s' "$existing_port_pids" | tr '\n' ' '))，请先停止占用进程或改用 --port 指定其他端口"
    exit 1
  fi
  if [[ "$RESOLVED_MGMT_PORT" != "$OPT_PORT" ]]; then
    local existing_mgmt_pids
    existing_mgmt_pids="$(get_port_pids "$RESOLVED_MGMT_PORT" || true)"
    if [[ -n "$existing_mgmt_pids" ]]; then
      log_err "管理端口 $RESOLVED_MGMT_PORT 已被其他进程占用 (PID: $(printf '%s' "$existing_mgmt_pids" | tr '\n' ' '))，请先停止占用进程或改用 --mgmt-port 指定其他端口"
      exit 1
    fi
  fi

  # 3. 校验环境配置
  if ! check_profile_config; then
    exit 1
  fi

  # 4. 定位或按需构建 JAR
  if [[ "$OPT_BUILD" == "true" ]]; then
    cmd_build --skip-toolchain-check
  fi

  local jar_path=""
  if ! jar_path="$(find_jar)"; then
    if is_source_repo; then
      log_info "未检测到成品 Fat JAR，自动执行构建..."
      cmd_build --skip-toolchain-check
      jar_path="$(find_jar)"
    else
      log_err "未找到可执行 JAR（请将 ateng-solid-bootstrap-*.jar 置于当前部署目录或使用 --jar 指定）"
      exit 1
    fi
  fi

  mkdir -p "$OPT_LOG_DIR" "$OPT_PID_DIR"
  local console_log="$OPT_LOG_DIR/server.log"
  local native_jar native_log_dir
  native_jar="$(to_native_path "$jar_path")"
  native_log_dir="$(to_native_path "$OPT_LOG_DIR")"

  # 确保 prod 模式下 ateng-boot 文件日志与控制台日志同目录落盘
  if [[ -z "${SOLID_LOG_PATH:-}" ]]; then
    export SOLID_LOG_PATH="$native_log_dir"
  fi

  # 5. 组装 JVM 与 Spring Boot 启动参数
  local jvm_args=(
    "-Dfile.encoding=UTF-8"
    "-Dsun.stdout.encoding=UTF-8"
    "-Dsun.stderr.encoding=UTF-8"
    "-Dstdout.encoding=UTF-8"
    "-Dstderr.encoding=UTF-8"
    "-XX:+ExitOnOutOfMemoryError"
  )

  if [[ "${#RESOLVED_JVM_OPTS_ARR[@]}" -gt 0 ]]; then
    jvm_args+=("${RESOLVED_JVM_OPTS_ARR[@]}")
  elif [[ "$OPT_PROFILE" == "prod" ]]; then
    jvm_args+=("-Xms512m" "-Xmx1024m")
  else
    jvm_args+=("-Xms256m" "-Xmx512m")
  fi

  local app_args=(
    "--spring.profiles.active=${OPT_PROFILE}"
    "--server.port=${OPT_PORT}"
  )
  if [[ "$OPT_MGMT_PORT_SET" == "true" ]]; then
    app_args+=("--management.server.port=${OPT_MGMT_PORT}")
  fi
  if [[ "${#RESOLVED_APP_ARGS_ARR[@]}" -gt 0 ]]; then
    app_args+=("${RESOLVED_APP_ARGS_ARR[@]}")
  fi

  local mgmt_desc=""
  if [[ "$RESOLVED_MGMT_PORT" != "$OPT_PORT" ]]; then
    mgmt_desc=" | MgmtPort: $RESOLVED_MGMT_PORT"
  fi
  log_info "正在启动 ateng-solid-server (Profile: $OPT_PROFILE | Port: $OPT_PORT${mgmt_desc} | JAR: $(basename "$jar_path"))..."
  if [[ "${#RESOLVED_APP_ARGS_ARR[@]}" -gt 0 ]]; then
    log_info "应用附加运行参数: ${RESOLVED_APP_ARGS_ARR[*]}"
  fi

  if [[ "$OPT_FG" == "true" ]]; then
    log_info "当前为前台模式，按 Ctrl+C 停止服务"
    cd "$BASE_DIR" || exit 1
    exec java "${jvm_args[@]}" -jar "$native_jar" "${app_args[@]}"
  fi

  # 后台启动
  (
    cd "$BASE_DIR" || exit 1
    nohup java "${jvm_args[@]}" -jar "$native_jar" "${app_args[@]}" >> "$console_log" 2>&1 < /dev/null &
    printf '%s' "$!" > "$OPT_PID_DIR/.server.spawn.pid"
  )

  local spawned_pid
  spawned_pid="$(cat "$OPT_PID_DIR/.server.spawn.pid")"
  rm -f "$OPT_PID_DIR/.server.spawn.pid"

  local winpid=""
  if is_windows; then
    winpid="$(get_winpid_for_msys_pid "$spawned_pid" || true)"
  fi

  # 写初版状态文件（就绪后再补齐 PORT_PID）
  write_runtime_state "$spawned_pid" "$winpid" "" "$jar_path"

  # 6. 阻塞轮询就绪状态（双端口模式下同时要求业务端口与管理端口监听，且优先校验 /actuator/health/readiness HTTP 200）
  local probe_url_host
  probe_url_host="$(format_url_host "$RESOLVED_MGMT_HOST")"
  local readiness_url="http://${probe_url_host}:${RESOLVED_MGMT_PORT}/actuator/health/readiness"
  local elapsed=0
  local ready=false
  while [[ "$elapsed" -lt "$OPT_START_TIMEOUT" ]]; do
    if ! is_pid_alive "$spawned_pid" "$winpid"; then
      log_err "后端进程启动过程中异常退出，最后 30 行控制台日志 ($console_log)："
      [[ -f "$console_log" ]] && tail -n 30 "$console_log" >&2 || true
      clear_runtime_state
      exit 1
    fi
    local ports_listening=false
    if is_port_listening "$OPT_PORT"; then
      if [[ "$RESOLVED_MGMT_PORT" == "$OPT_PORT" ]] || is_port_listening "$RESOLVED_MGMT_PORT"; then
        ports_listening=true
      fi
    fi
    if [[ "$ports_listening" == "true" ]]; then
      if command -v curl >/dev/null 2>&1; then
        if curl -sf -o /dev/null --max-time 2 "$readiness_url"; then
          ready=true
          break
        fi
      else
        ready=true
        break
      fi
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done

  if [[ "$ready" != "true" ]]; then
    log_err "等待后端端口 $OPT_PORT (管理端口 $RESOLVED_MGMT_PORT) 就绪超时 (${OPT_START_TIMEOUT}s)，正在清理未就绪进程并回显末尾日志："
    stop_process_gracefully "$spawned_pid" "$winpid" "" 10
    clear_runtime_state
    [[ -f "$console_log" ]] && tail -n 30 "$console_log" >&2 || true
    exit 1
  fi

  local port_pid
  port_pid="$(get_port_pids "$OPT_PORT" | head -n 1 || true)"
  write_runtime_state "$spawned_pid" "$winpid" "$port_pid" "$jar_path"

  log_ok "ateng-solid-server 启动成功！"
  printf "  - 服务地址: http://127.0.0.1:%s\n" "$OPT_PORT"
  printf "  - 管理探针: http://%s:%s/actuator/health\n" "$probe_url_host" "$RESOLVED_MGMT_PORT"
  printf "  - 运行环境: %s\n" "$OPT_PROFILE"
  printf "  - 进程标识: PID=%s%s\n" "$spawned_pid" "${winpid:+ (WINPID=$winpid)}"
  printf "  - 日志文件: %s\n" "$console_log"
}

cmd_stop() {
  read_runtime_state
  local target_port="${STATE_PORT:-$OPT_PORT}"

  if [[ -n "$STATE_PID" ]] && is_pid_alive "$STATE_PID" "$STATE_WINPID"; then
    log_info "正在优雅停止 ateng-solid-server (PID: $STATE_PID | Port: $target_port | 等待上限: ${OPT_STOP_TIMEOUT}s)..."
    stop_process_gracefully "$STATE_PID" "$STATE_WINPID" "$STATE_PORT_PID" "$OPT_STOP_TIMEOUT"
    clear_runtime_state
    log_ok "ateng-solid-server 已停止"
    return 0
  fi

  clear_runtime_state

  local external_pids
  external_pids="$(get_port_pids "$OPT_PORT" || true)"
  if [[ -z "$external_pids" ]]; then
    log_info "ateng-solid-server 未运行（端口 $OPT_PORT 空闲）"
    return 0
  fi

  if [[ "$OPT_FORCE" != "true" ]]; then
    log_warn "未发现脚本纳管的后端 PID，但端口 $OPT_PORT 正被外部进程占用 (PID: $(printf '%s' "$external_pids" | tr '\n' ' '))；为避免误杀已跳过（强制清理请加 --force）"
    return 0
  fi

  log_warn "正在按 --force 强制清理端口 $OPT_PORT 上的外部进程: $(printf '%s' "$external_pids" | tr '\n' ' ')..."
  local p
  while IFS= read -r p; do
    [[ -n "$p" ]] || continue
    if is_windows; then
      kill_windows_pid_tree "$p" true
    else
      kill -TERM "$p" 2>/dev/null || true
    fi
  done <<< "$external_pids"

  if ! is_windows; then
    local i=0
    local remain_pids=""
    while [[ "$i" -lt 10 ]]; do
      remain_pids="$(get_port_pids "$OPT_PORT" || true)"
      [[ -z "$remain_pids" ]] && break
      sleep 1
      i=$((i + 1))
    done
    remain_pids="$(get_port_pids "$OPT_PORT" || true)"
    while IFS= read -r p; do
      [[ -n "$p" ]] || continue
      kill -KILL "$p" 2>/dev/null || true
    done <<< "$remain_pids"
  fi
  sleep 1

  if is_port_listening "$OPT_PORT"; then
    log_err "强制清理端口 $OPT_PORT 失败，请手动排查占用进程"
    exit 1
  fi
  log_ok "已强制释放端口 $OPT_PORT"
}

cmd_restart() {
  cmd_stop
  cmd_start
}

cmd_status() {
  read_runtime_state
  local target_port="${STATE_PORT:-$OPT_PORT}"
  local target_profile="${STATE_PROFILE:-$OPT_PROFILE}"
  local target_mgmt_port="${STATE_MGMT_PORT:-}"
  if [[ -z "$target_mgmt_port" ]]; then
    if [[ "$OPT_MGMT_PORT_SET" == "true" ]]; then
      target_mgmt_port="$OPT_MGMT_PORT"
    elif [[ "$target_profile" == "prod" ]]; then
      target_mgmt_port="${SOLID_MANAGEMENT_PORT:-8081}"
    else
      target_mgmt_port="$target_port"
    fi
  fi
  local target_mgmt_host="${STATE_MGMT_HOST:-127.0.0.1}"
  local mgmt_url_host
  mgmt_url_host="$(format_url_host "$target_mgmt_host")"

  local port_pids
  port_pids="$(get_port_pids "$target_port" || true)"

  printf "\n%b=== ateng-solid-server Status ===%b\n" "${C_BOLD}" "${C_RESET}"
  if [[ -n "$STATE_PID" ]] && is_pid_alive "$STATE_PID" "$STATE_WINPID"; then
    local listen_desc="端口监听中"
    if ! is_port_listening "$target_port"; then
      listen_desc="启动中/端口未就绪"
    elif [[ "$target_mgmt_port" != "$target_port" ]] && ! is_port_listening "$target_mgmt_port"; then
      listen_desc="启动中/管理端口未就绪"
    fi
    local mgmt_tag=""
    if [[ "$target_mgmt_port" != "$target_port" ]]; then
      mgmt_tag=" | mgmt_port:${target_mgmt_port}"
    fi
    printf "%b[RUNNING]%b ateng-solid-server [profile:%s | port:%s%s | pid:%s%s | %s]\n" \
      "${C_GREEN}" "${C_RESET}" "$target_profile" "$target_port" "$mgmt_tag" "$STATE_PID" \
      "${STATE_WINPID:+ | winpid:$STATE_WINPID}" "$listen_desc"
    printf "          url: http://127.0.0.1:%s\n" "$target_port"
    printf "          actuator: http://%s:%s/actuator/health\n" "$mgmt_url_host" "$target_mgmt_port"
    printf "          log: %s\n" "$STATE_LOG"
    if [[ -n "$STATE_STARTED_AT" ]]; then
      printf "          started: %s\n" "$STATE_STARTED_AT"
    fi
    return 0
  fi

  if [[ -n "$port_pids" ]]; then
    printf "%b[EXTERNAL]%b 端口 %s 被非脚本进程占用 (PID: %s)\n" \
      "${C_YELLOW}" "${C_RESET}" "$target_port" "$(printf '%s' "$port_pids" | tr '\n' ' ')"
    return 0
  fi

  printf "%b[STOPPED]%b ateng-solid-server 未运行 (默认端口: %s)\n" "${C_CYAN}" "${C_RESET}" "$target_port"
}

cmd_logs() {
  read_runtime_state
  local target_log="${STATE_LOG:-$OPT_LOG_DIR/server.log}"

  if [[ "$OPT_ERROR_LOG" == "true" ]]; then
    local err_log="$OPT_LOG_DIR/ateng-solid-server-error.log"
    if [[ ! -f "$err_log" ]]; then
      log_warn "未找到独立错误日志文件: $err_log（dev 模式不拆分子日志文件，请直接查看控制台日志 $target_log）"
      exit 1
    fi
    target_log="$err_log"
  fi

  if [[ ! -f "$target_log" ]]; then
    log_warn "日志文件尚不存在: $target_log"
    exit 1
  fi

  if [[ "$OPT_FOLLOW" == "true" ]]; then
    log_info "正在追踪日志 (Ctrl+C 退出): $target_log"
    tail -n "$OPT_LINES" -f "$target_log"
  else
    tail -n "$OPT_LINES" "$target_log"
  fi
}

parse_args() {
  local normalized_args=()
  local passthrough_seen=false
  while [[ "$#" -gt 0 ]]; do
    if [[ "$passthrough_seen" == "true" ]]; then
      normalized_args+=("$1")
      shift
      continue
    fi
    case "$1" in
      --)
        passthrough_seen=true
        normalized_args+=("--")
        shift
        ;;
      -p=*|--profile=*|--port=*|--mgmt-port=*|--env-file=*|--jar=*|--jvm-opts=*|--app-args=*|--log-dir=*|--pid-dir=*|--timeout=*|--stop-timeout=*|-n=*|--lines=*)
        normalized_args+=("${1%%=*}" "${1#*=}")
        shift
        ;;
      *)
        normalized_args+=("$1")
        shift
        ;;
    esac
  done

  if [[ "${#normalized_args[@]}" -gt 0 ]]; then
    set -- "${normalized_args[@]}"
  else
    set --
  fi

  while [[ "$#" -gt 0 ]]; do
    case "$1" in
      --)
        shift
        while [[ "$#" -gt 0 ]]; do
          EXTRA_APP_ARGS+=("$1")
          shift
        done
        break
        ;;
      start|stop|restart|status|logs|build|check|help)
        if [[ -z "$ACTION" ]]; then
          ACTION="$1"
          shift
        else
          log_err "重复或位置错误的子命令: $1"
          exit 1
        fi
        ;;
      -p|--profile)
        [[ "$#" -ge 2 ]] || { log_err "$1 缺少参数"; exit 1; }
        OPT_PROFILE="$2"
        [[ -n "$OPT_PROFILE" ]] || { log_err "--profile 缺少参数"; exit 1; }
        shift 2
        ;;
      --port)
        [[ "$#" -ge 2 ]] || { log_err "--port 缺少参数"; exit 1; }
        OPT_PORT="$2"
        is_positive_int "$OPT_PORT" || { log_err "--port 必须为正整数: $OPT_PORT"; exit 1; }
        shift 2
        ;;
      --mgmt-port)
        [[ "$#" -ge 2 ]] || { log_err "--mgmt-port 缺少参数"; exit 1; }
        OPT_MGMT_PORT="$2"
        is_positive_int "$OPT_MGMT_PORT" || { log_err "--mgmt-port 必须为正整数: $OPT_MGMT_PORT"; exit 1; }
        OPT_MGMT_PORT_SET=true
        shift 2
        ;;
      --env-file)
        [[ "$#" -ge 2 ]] || { log_err "--env-file 缺少路径参数"; exit 1; }
        OPT_ENV_FILE="$2"
        [[ -n "$OPT_ENV_FILE" ]] || { log_err "--env-file 缺少路径参数"; exit 1; }
        shift 2
        ;;
      --jar)
        [[ "$#" -ge 2 ]] || { log_err "--jar 缺少路径参数"; exit 1; }
        OPT_JAR="$2"
        [[ -n "$OPT_JAR" ]] || { log_err "--jar 缺少路径参数"; exit 1; }
        shift 2
        ;;
      --jvm-opts)
        [[ "$#" -ge 2 ]] || { log_err "--jvm-opts 缺少参数值"; exit 1; }
        OPT_JVM_OPTS="$2"
        OPT_JVM_OPTS_SET=true
        shift 2
        ;;
      --app-args)
        [[ "$#" -ge 2 ]] || { log_err "--app-args 缺少参数值"; exit 1; }
        OPT_APP_ARGS="$2"
        OPT_APP_ARGS_SET=true
        shift 2
        ;;
      --log-dir)
        [[ "$#" -ge 2 ]] || { log_err "--log-dir 缺少路径参数"; exit 1; }
        OPT_LOG_DIR="$2"
        [[ -n "$OPT_LOG_DIR" ]] || { log_err "--log-dir 缺少路径参数"; exit 1; }
        shift 2
        ;;
      --pid-dir)
        [[ "$#" -ge 2 ]] || { log_err "--pid-dir 缺少路径参数"; exit 1; }
        OPT_PID_DIR="$2"
        [[ -n "$OPT_PID_DIR" ]] || { log_err "--pid-dir 缺少路径参数"; exit 1; }
        shift 2
        ;;
      --build)
        OPT_BUILD=true
        shift
        ;;
      --clean)
        OPT_CLEAN=true
        shift
        ;;
      --with-tests)
        OPT_WITH_TESTS=true
        shift
        ;;
      --fg)
        OPT_FG=true
        shift
        ;;
      --force)
        OPT_FORCE=true
        shift
        ;;
      --skip-env-check)
        OPT_SKIP_ENV_CHECK=true
        shift
        ;;
      --timeout)
        [[ "$#" -ge 2 ]] || { log_err "--timeout 缺少参数"; exit 1; }
        OPT_START_TIMEOUT="$2"
        is_positive_int "$OPT_START_TIMEOUT" || { log_err "--timeout 必须为正整数: $OPT_START_TIMEOUT"; exit 1; }
        shift 2
        ;;
      --stop-timeout)
        [[ "$#" -ge 2 ]] || { log_err "--stop-timeout 缺少参数"; exit 1; }
        OPT_STOP_TIMEOUT="$2"
        is_positive_int "$OPT_STOP_TIMEOUT" || { log_err "--stop-timeout 必须为正整数: $OPT_STOP_TIMEOUT"; exit 1; }
        shift 2
        ;;
      -f|--follow)
        OPT_FOLLOW=true
        shift
        ;;
      -n|--lines)
        [[ "$#" -ge 2 ]] || { log_err "$1 缺少参数"; exit 1; }
        OPT_LINES="$2"
        is_positive_int "$OPT_LINES" || { log_err "--lines 必须为正整数: $OPT_LINES"; exit 1; }
        shift 2
        ;;
      --error)
        OPT_ERROR_LOG=true
        shift
        ;;
      -h|--help)
        ACTION="help"
        shift
        ;;
      *)
        log_err "未知参数: $1"
        show_help
        exit 1
        ;;
    esac
  done

  if [[ -z "$ACTION" ]]; then
    ACTION="help"
  fi
}

parse_args "$@"
validate_runtime_args

case "$ACTION" in
  start) cmd_start ;;
  stop) cmd_stop ;;
  restart) cmd_restart ;;
  status) cmd_status ;;
  logs) cmd_logs ;;
  build) cmd_build ;;
  check) cmd_check ;;
  help) show_help ;;
esac
