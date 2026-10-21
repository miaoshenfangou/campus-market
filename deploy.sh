#!/usr/bin/env bash
# ============================================================
#  校园二手交易平台 · 一键部署脚本
#  支持:Ubuntu / Debian / CentOS / 阿里云 Linux
#
#  用法(登录服务器后在项目目录执行):
#    bash deploy.sh              # 部署或更新(首次会自动装 Docker、生成 .env)
#    bash deploy.sh status       # 查看容器状态
#    bash deploy.sh logs         # 实时查看应用日志
#    bash deploy.sh restart      # 重启应用
#    bash deploy.sh down         # 停止服务(保留数据)
#    bash deploy.sh reset        # 停止并删除数据卷(清库,慎用)
# ============================================================
set -Eeuo pipefail

cd "$(dirname "$0")"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info() { echo -e "${GREEN}[INFO]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*" >&2; exit 1; }

SUDO=""
DC_CMD=()
APT_OPTS=()
SWAPPINESS=20

# 读取 /proc/meminfo 的某个字段并换算成 MB。
# 字段不存在时(老内核没有 MemAvailable)返回 0,避免空值参与算术让 set -e 直接退出。
mem_mb_of() {
  local v
  v=$(awk -v key="^$1:" '$0 ~ key {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null)
  case "$v" in
    ''|*[!0-9]*) echo 0 ;;
    *)           echo "$v" ;;
  esac
}

# ---------------- 基础工具 ----------------

pick_sudo() {
  if [ "$(id -u)" -eq 0 ]; then
    SUDO=""
  elif command -v sudo >/dev/null 2>&1; then
    SUDO="sudo"
  else
    fail "需要 root 权限运行,且系统没有 sudo。请切换到 root 后重试。"
  fi
}

rand_str() {
  local n="${1:-32}"
  if command -v openssl >/dev/null 2>&1; then
    (openssl rand -base64 96 2>/dev/null | tr -dc 'A-Za-z0-9' | head -c "$n") || true
  else
    (tr -dc 'A-Za-z0-9' < /dev/urandom | head -c "$n") || true
  fi
}

env_get() {
  [ -f .env ] || { echo ""; return 0; }
  grep -E "^$1=" .env 2>/dev/null | tail -n 1 | cut -d= -f2- || true
}

# ---------------- Docker ----------------

docker_usable() {
  docker info >/dev/null 2>&1
}

# Ubuntu 开机后几分钟内,unattended-upgrades / apt-daily 会自动跑一次 apt,
# 期间持有 /var/lib/dpkg/lock-frontend,我们的 apt-get 就会直接报:
#   E: Could not get lock /var/lib/dpkg/lock-frontend. It is held by process 7994
# 云主机第一次登录往往正好撞上这个时间窗。处理办法:先停掉自动更新,等锁释放,
# 再用 DPkg::Lock::Timeout 兜底(apt 1.1+ 支持,拿不到锁会自己等而不是立刻失败)。
apt_prepare() {
  pick_sudo
  export DEBIAN_FRONTEND=noninteractive
  APT_OPTS=(-y -o DPkg::Lock::Timeout=300)

  $SUDO systemctl stop unattended-upgrades.service apt-daily.service apt-daily-upgrade.service >/dev/null 2>&1 || true
  $SUDO systemctl disable unattended-upgrades.service apt-daily.service apt-daily-upgrade.service >/dev/null 2>&1 || true
  $SUDO systemctl stop unattended-upgrades.timer apt-daily.timer apt-daily-upgrade.timer >/dev/null 2>&1 || true

  local i
  for i in $(seq 1 60); do
    if ! dpkg_busy; then
      return 0
    fi
    if [ "$i" -eq 1 ]; then
      info "dpkg 锁被占用(通常是 unattended-upgrades),等待其释放…"
    fi
    sleep 2
  done

  warn "dpkg 锁 120 秒仍未释放,尝试结束自动更新进程"
  $SUDO pkill -f unattended-upgrade >/dev/null 2>&1 || true
  sleep 3
}

# dpkg 是否正被其他进程持有
dpkg_busy() {
  if command -v fuser >/dev/null 2>&1; then
    $SUDO fuser /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock >/dev/null 2>&1
  else
    pgrep -x dpkg >/dev/null 2>&1 \
      || pgrep -f 'unattended-upgr|apt-get|apt-daily' >/dev/null 2>&1
  fi
}

install_docker() {
  pick_sudo
  info "未检测到 Docker,开始安装(使用阿里云镜像源,速度更快)…"
  apt_prepare

  [ -f /etc/os-release ] || fail "无法识别系统版本,请手动安装 Docker 后重试"
  # shellcheck disable=SC1091
  . /etc/os-release
  local id="${ID:-}" ver="${VERSION_CODENAME:-}"

  case "$id" in
    ubuntu|debian)
      $SUDO apt-get "${APT_OPTS[@]}" update
      $SUDO apt-get "${APT_OPTS[@]}" install ca-certificates curl gnupg
      $SUDO install -m 0755 -d /etc/apt/keyrings
      curl -fsSL "https://mirrors.aliyun.com/docker-ce/linux/${id}/gpg" \
        | $SUDO gpg --batch --yes --dearmor -o /etc/apt/keyrings/docker.gpg
      $SUDO chmod a+r /etc/apt/keyrings/docker.gpg
      echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://mirrors.aliyun.com/docker-ce/linux/${id} ${ver} stable" \
        | $SUDO tee /etc/apt/sources.list.d/docker.list >/dev/null
      $SUDO apt-get "${APT_OPTS[@]}" update
      $SUDO apt-get "${APT_OPTS[@]}" install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
      ;;
    centos|rhel|rocky|almalinux|alinux|anolis)
      $SUDO yum install -y yum-utils
      $SUDO yum-config-manager --add-repo https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo
      $SUDO yum install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
      ;;
    *)
      fail "暂未适配系统 ${id},请参考 https://docs.docker.com/engine/install/ 手动安装后重试"
      ;;
  esac

  $SUDO systemctl enable --now docker
  info "Docker 安装完成:$(docker --version)"
}

resolve_compose() {
  if docker compose version >/dev/null 2>&1; then
    DC_CMD=(docker compose)
  elif command -v docker-compose >/dev/null 2>&1; then
    DC_CMD=(docker-compose)
  else
    fail "未找到 docker compose 插件,请确认 docker-compose-plugin 已安装"
  fi
  # 非 root 且当前用户不在 docker 组时,需要 sudo 才能调 docker
  if ! docker_usable; then
    pick_sudo
    if [ -n "$SUDO" ]; then
      DC_CMD=($SUDO "${DC_CMD[@]}")
    else
      fail "当前用户无权访问 Docker,请用 root 运行"
    fi
  fi
}

dc() { "${DC_CMD[@]}" "$@"; }

# 商品图片存在 upload-data 命名卷里,应用容器以非 root 的 app 用户运行。
# 若卷是早期版本创建的(chown 之前),属主会是 root,上传时会报 Permission denied。
# 这里以 root 身份幂等纠正一次,失败也不阻断部署。
fix_upload_perm() {
  dc exec -T -u root app chown -R app:app /app/upload >/dev/null 2>&1 || true
}

# 国内服务器拉 Docker Hub 镜像经常超时。跑在腾讯云上时自动启用官方内网镜像源
# (走内网、不限速、不计公网流量),同时限制日志大小避免撑爆小磁盘。
# 国内可达的 Docker Hub 加速地址。公共镜像站变动频繁,按顺序探测取前几个可用的。
# registry 的 /v2/ 在不带 token 时返回 401 属正常(说明服务在线),
# 只有 000(连不上)/超时才算不可用 —— 这点很容易误判。
DOCKER_MIRROR_CANDIDATES="https://docker.m.daocloud.io https://docker.1ms.run https://docker.1panel.live https://docker.xuanyuan.me https://hub.rat.dev"

probe_mirror() {
  local code
  code=$(curl -s -m 6 -o /dev/null -w '%{http_code}' "${1%/}/v2/" 2>/dev/null || echo 000)
  case "$code" in 200|401|403) return 0 ;; *) return 1 ;; esac
}

pick_mirrors() {
  local u out="" n=0
  for u in $DOCKER_MIRROR_CANDIDATES; do
    if probe_mirror "$u"; then out="$out $u"; n=$((n + 1)); fi
    [ "$n" -ge 3 ] && break
  done
  echo "$out"
}

configure_mirror() {
  local f=/etc/docker/daemon.json
  local is_tencent="" is_aliyun="" mirror="${DOCKER_MIRROR:-}"

  # 腾讯云元数据服务在内网,正常 100ms 内返回;超时即视为非腾讯云
  if curl -fsS --max-time 2 "http://metadata.tencentyun.com/latest/meta-data/instance-id" >/dev/null 2>&1; then
    is_tencent="1"
  fi
  # 阿里云 ECS:主机名形如 iZxxxxZ,且带 aegis(云安全中心)目录;元数据服务 100.100.100.200
  if [ -d /usr/local/aegis ] || hostname 2>/dev/null | grep -q '^iZ.*Z$'; then
    is_aliyun="1"
  fi

  pick_sudo

  # 已有 registry-mirrors 配置则不动
  if [ -f "$f" ] && grep -q "registry-mirrors" "$f" 2>/dev/null; then
    info "Docker 已配置镜像加速,跳过"
    return 0
  fi
  # 已有自定义配置时不自动改动,避免破坏用户环境
  if [ -f "$f" ] && [ -s "$f" ]; then
    warn "/etc/docker/daemon.json 已存在内容,为不破坏现有配置,未自动添加镜像加速"
    return 0
  fi

  if [ -z "$mirror" ] && [ -n "$is_tencent" ]; then
    mirror="https://mirror.ccs.tencentyun.com"
  fi

  $SUDO mkdir -p /etc/docker

  if [ -n "$mirror" ]; then
    $SUDO tee "$f" >/dev/null <<JSON
{
  "registry-mirrors": ["${mirror}"],
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
JSON
    $SUDO systemctl restart docker 2>/dev/null || $SUDO service docker restart 2>/dev/null || true
    sleep 2
    info "已启用镜像加速:${mirror},日志上限 30MB"
  else
    # 未指定加速地址:自动探测国内可用镜像站。
    # 直连 registry-1.docker.io 在国内基本是 i/o timeout(实测 69.171.234.48:443 超时),
    # 所以这里不能只写日志配置就撒手,必须自动兜底,否则后面拉镜像必失败。
    local picked list="" u
    picked=$(pick_mirrors)

    if [ -n "$picked" ]; then
      for u in $picked; do list="${list}\"${u}\","; done
      list="${list%,}"
      $SUDO tee "$f" >/dev/null <<JSON
{
  "registry-mirrors": [${list}],
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
JSON
      $SUDO systemctl restart docker 2>/dev/null || $SUDO service docker restart 2>/dev/null || true
      sleep 2
      info "已自动启用镜像加速:$(echo $picked | tr ' ' ',')(日志上限 30MB)"
    else
      # 全部探测失败:至少把日志轮转配上(小磁盘机器必需),并给出人工方案
      $SUDO tee "$f" >/dev/null <<'JSON'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
JSON
      $SUDO systemctl restart docker 2>/dev/null || $SUDO service docker restart 2>/dev/null || true
      sleep 2
      info "已限制 Docker 日志上限(30MB),避免日志撑满磁盘"

      if [ -n "$is_aliyun" ]; then
        warn "检测到阿里云 ECS,但公共镜像源均不可达。请取一次账号专属加速地址:"
        warn "  1) 打开 https://cr.console.aliyun.com/cn-hangzhou/instances/mirrors"
        warn "  2) 复制「加速器地址」(形如 https://xxxxxx.mirror.aliyuncs.com)"
        warn "  3) 重新执行: DOCKER_MIRROR=https://你的地址.mirror.aliyuncs.com bash deploy.sh"
      else
        warn "公共镜像源均不可达,可自行配置 registry-mirrors 后重试。"
      fi
    fi
  fi
}

# ---------------- 配置 ----------------

ensure_env() {
  if [ -f .env ]; then
    info "检测到已有 .env,沿用现有配置"
    if grep -q "CHANGE_ME" .env; then
      fail ".env 里还有 CHANGE_ME 占位符,请先改成真实值(或用 rm .env 让脚本重新生成)"
    fi
  else
    local db_pwd jwt_secret
    db_pwd="Csh@$(rand_str 18)"
    jwt_secret="$(rand_str 48)"
    cat > .env <<EOF
APP_PORT=8080
MYSQL_ROOT_PASSWORD=${db_pwd}
DB_NAME=campus_second_hand
JWT_SECRET=${jwt_secret}
JWT_EXPIRATION=86400
EOF
    chmod 600 .env
    info "已自动生成 .env(随机数据库密码 + 随机 JWT 密钥)"
  fi
}

check_resources() {
  local mem_mb
  mem_mb=$(mem_mb_of MemTotal)
  [ "$mem_mb" -gt 0 ] || return 0

  local avail_mb
  avail_mb=$(mem_mb_of MemAvailable)

  info "检测到物理内存约 ${mem_mb}MB,当前可用 ${avail_mb}MB"

  # 1G 机型实际 MemTotal 通常是 890~980MB(内核与显存保留会吃掉一部分),
  # 阈值必须按 850 卡,否则 894MB 这类型号会被误判为「不足 1G」而直接退出。
  if [ "$mem_mb" -lt 850 ]; then
    fail "内存不足 850MB,MySQL 8 + JVM 即使极限压缩也无法稳定运行。请升级到 1G 或以上。"
  fi

  if [ "$mem_mb" -lt 1200 ]; then
    warn "属于 1G 档机型,将启用极限压缩模式:MySQL 64M 缓冲池 + JVM 192M 堆 + 2G swap 兜底。"
    warn "内存余量很小,适合演示与验证;多人同时访问时会明显变慢,属正常现象。"

    # 1G 机型上,宿主机自身占用超过 350MB 就值得先回收一次
    local base_mb
    base_mb=$(( mem_mb - avail_mb ))
    if [ "$base_mb" -gt 350 ]; then
      warn "宿主机空载已占用 ${base_mb}MB,偏高。建议先执行 bash deploy.sh slim 停用无用服务(可恢复),"
      warn "通常能释放 100MB 左右,显著降低 OOM 风险。"
    fi
  fi

  # 宝塔面板自身常驻 200~300MB,叠加 MySQL + JVM 必然 OOM,提前拦住
  if [ -d /www/server/panel ] || systemctl is-active --quiet bt 2>/dev/null; then
    fail "检测到宝塔面板(/www/server/panel)。面板常驻 200~300MB,1G 机器无法与 MySQL + JVM 共存。
      请重装系统为纯 Ubuntu 22.04 镜像后重试,或执行 bash deploy.sh mem 查看占用明细。"
  fi

  # 小内存机器必须配 swap:构建镜像时 Maven + javac 峰值会超过 1G,
  # 没有 swap 会被内核 OOM Killer 直接杀掉,表现为「构建莫名失败」。
  ensure_swap
}

# 设置 swappiness(运行时 + 持久化)。已存在该键时用 sed 覆盖,不能只 append,
# 否则第一次写入 20 之后,tune_all 想改成 45 永远改不掉。
set_swappiness() {
  local sw="${1:-20}"
  $SUDO sysctl -w vm.swappiness="$sw" >/dev/null 2>&1 || true
  if grep -q '^vm.swappiness' /etc/sysctl.conf 2>/dev/null; then
    $SUDO sed -i "s|^vm.swappiness=.*|vm.swappiness=${sw}|" /etc/sysctl.conf 2>/dev/null || true
  else
    echo "vm.swappiness=${sw}" | $SUDO tee -a /etc/sysctl.conf >/dev/null 2>&1 || true
  fi
}

ensure_swap() {
  local target_mb=2048
  local cur_mb
  cur_mb=$(mem_mb_of SwapTotal)

  if [ "$cur_mb" -ge 1024 ]; then
    info "swap 已有 ${cur_mb}MB,跳过创建"
    # 已有 swap 时仍要应用 swappiness:tune_all 会把 1G 档设成 45,
    # 若这里直接 return,45 永远写不进去。
    pick_sudo
    set_swappiness "${SWAPPINESS:-20}"
    return 0
  fi

  pick_sudo
  info "swap 仅 ${cur_mb}MB,正在创建 ${target_mb}MB swap 文件(小内存机型必需)…"

  if [ -f /swapfile ]; then
    $SUDO swapoff /swapfile 2>/dev/null || true
  fi

  # fallocate 在部分文件系统(如 overlayfs)上不生效,失败则退回 dd
  if ! $SUDO fallocate -l "${target_mb}M" /swapfile 2>/dev/null; then
    $SUDO dd if=/dev/zero of=/swapfile bs=1M count="$target_mb" status=none
  fi

  $SUDO chmod 600 /swapfile
  $SUDO mkswap /swapfile >/dev/null
  $SUDO swapon /swapfile

  # 重启后仍生效
  grep -q '^/swapfile' /etc/fstab 2>/dev/null || echo '/swapfile none swap sw 0 0' | $SUDO tee -a /etc/fstab >/dev/null

  # 降低 swappiness:优先用物理内存,只在压力大时才换出,避免拖慢 MySQL。
  # 1G 机型反而要调高(45):内存实在不够,必须允许更早换出,否则直接 OOM
  set_swappiness "${SWAPPINESS:-20}"
  info "swap 已启用:$(mem_mb_of SwapTotal)MB (swappiness=${SWAPPINESS:-20})"
}

# 写/覆盖 .env 里的一个键值(避免 sed 里出现特殊字符时被误解析,用 | 作分隔符)
env_set() {
  local k="$1" v="$2"
  if [ -f .env ] && grep -q "^${k}=" .env 2>/dev/null; then
    sed -i "s|^${k}=.*|${k}=${v}|" .env
  else
    echo "${k}=${v}" >> .env
  fi
}

# 按物理内存统一决定:JVM 堆、MySQL 缓冲池、容器内存上限、Tomcat 线程数、连接池大小
# 全部写入 .env,由 docker-compose.yml 与 application-prod.yml 读取
tune_all() {
  local mem_mb opts pool chunk my_limit app_limit threads dbpool swappiness tier my_conn tbl_cache
  # MEM_MB 可手动指定机型(排查用,例如 MEM_MB=1024 bash deploy.sh tune)
  mem_mb="${MEM_MB:-$(mem_mb_of MemTotal)}"
  # 非数字或空值一律回落到 2G 档,避免后续数值比较报错
  case "$mem_mb" in ''|*[!0-9]*) mem_mb=2048 ;; esac
  [ "$mem_mb" -gt 0 ] || mem_mb=2048

  if [ "$mem_mb" -lt 1200 ]; then
    # ---------- 1G 极限模式 ----------
    # -Xmx192m:实测本项目空载堆约 110-150MB,192m 留给列表查询的突发分配
    # TieredStopAtLevel=1:只用 C1 编译,启动更快、代码缓存更省,牺牲长期峰值性能
    tier="1G 极限模式"
    opts="-Xms96m -Xmx192m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
    # 894MB 机型实测:宿主机空载 ~380MB,留给容器的约 500MB。
    # MySQL 实际 RSS ≈ 缓冲池 64M + 基础 170M ≈ 240M;应用 ≈ 堆 192M + 元空间 90M ≈ 300M。
    # 两个上限相加刻意小于总内存,超出部分由 swap 吸收,避免内核 OOM Killer 直接杀进程。
    pool="64M"; chunk="32M"; my_limit="400m"; app_limit="440m"
    threads=30; dbpool=3; swappiness=45; my_conn=50; tbl_cache=64
  elif [ "$mem_mb" -lt 2600 ]; then
    tier="2G 标准模式"
    opts="-Xms128m -Xmx384m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC"
    pool="128M"; chunk="32M"; my_limit="800m"; app_limit="700m"
    threads=50; dbpool=5; swappiness=20; my_conn=100; tbl_cache=200
  elif [ "$mem_mb" -lt 6500 ]; then
    tier="4G 模式"
    opts="-Xms256m -Xmx768m -XX:MaxMetaspaceSize=256m"
    pool="256M"; chunk="64M"; my_limit="1500m"; app_limit="1280m"
    threads=100; dbpool=10; swappiness=20; my_conn=150; tbl_cache=400
  else
    tier="8G+ 模式"
    opts="-Xms512m -Xmx1536m -XX:MaxMetaspaceSize=320m"
    pool="512M"; chunk="128M"; my_limit="3g"; app_limit="2g"
    threads=200; dbpool=20; swappiness=10; my_conn=200; tbl_cache=800
  fi

  env_set JAVA_OPTS "${opts} -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"
  env_set MYSQL_BUFFER_POOL "$pool"
  env_set MYSQL_BUFFER_POOL_CHUNK "$chunk"
  env_set MYSQL_MEM_LIMIT "$my_limit"
  env_set APP_MEM_LIMIT "$app_limit"
  env_set TOMCAT_MAX_THREADS "$threads"
  env_set TOMCAT_MIN_SPARE 5
  env_set DB_POOL_SIZE "$dbpool"
  env_set MYSQL_MAX_CONN "$my_conn"
  env_set MYSQL_TABLE_CACHE "$tbl_cache"

  # 把 swappiness 交给 ensure_swap 用
  SWAPPINESS="$swappiness"

  info "机型判定:${mem_mb}MB → ${tier}"
  info "  JVM:        ${opts}"
  info "  MySQL 缓冲池:${pool}  (容器上限 ${my_limit})"
  info "  应用容器上限:${app_limit}   Tomcat 线程:${threads}   连接池:${dbpool}"
}

# ---------------- 部署 ----------------

wait_ready() {
  local port url i
  port="$(env_get APP_PORT)"; port="${port:-8080}"
  url="http://127.0.0.1:${port}/category/list"

  info "等待服务就绪(首次启动要初始化数据库,约 30-60 秒)…"
  for i in $(seq 1 60); do
    if curl -fsS --max-time 3 "$url" >/dev/null 2>&1; then
      fix_upload_perm
      print_result "$port"
      return 0
    fi
    sleep 3
  done

  warn "180 秒内未就绪。以下是应用最近的日志:"
  dc logs --tail=80 app || true
  echo ""
  fail "部署未完成,请把上面日志发我一起排查"
}

print_result() {
  local port="$1" ip
  ip="$(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || true)"
  if [ -z "$ip" ]; then
    ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
  fi

  echo ""
  echo "============================================================"
  echo -e " ${GREEN}部署完成${NC}"
  echo "------------------------------------------------------------"
  echo " 前台页面:  http://${ip}:${port}/"
  echo " 接口文档:  http://${ip}:${port}/doc.html"
  echo " 健康检查:  http://${ip}:${port}/category/list"
  echo "------------------------------------------------------------"
  echo " 数据库密码等配置在 .env 里:cat .env"
  echo " 实时日志:  bash deploy.sh logs"
  echo ""
  echo " 如果打不开,先确认云服务器【安全组/防火墙】已放行 TCP ${port}"
  echo "============================================================"
}

# 检测是否已有打好包的 jar,决定用「轻量运行镜像」还是「服务器内编译」
pick_dockerfile() {
  local jar="target/csh-0.0.1-SNAPSHOT.jar" choice
  if [ -f "$jar" ]; then
    choice="Dockerfile.jar"
    info "检测到已打包的 jar($(du -h "$jar" | cut -f1)),使用轻量模式:服务器不编译,约 10 秒构建完成"
  else
    choice="Dockerfile"
    warn "未找到 target/csh-0.0.1-SNAPSHOT.jar,将在服务器上完整编译(Maven 需 1G+ 内存)"
    warn "2G 小内存机型建议改成轻量模式:本地 ./mvnw clean package -DskipTests,"
    warn "再把 target/csh-0.0.1-SNAPSHOT.jar 一起上传,构建更快也更稳。"
  fi

  if grep -q '^DOCKERFILE=' .env 2>/dev/null; then
    sed -i "s|^DOCKERFILE=.*|DOCKERFILE=${choice}|" .env
  else
    echo "DOCKERFILE=${choice}" >> .env
  fi
}

# ---------------- 数据库结构升级 ----------------

# 等 MySQL 容器进入 healthy(初始化 SQL 执行完才会 healthy)
wait_mysql() {
  local i st
  for i in $(seq 1 40); do
    st=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' csh-mysql 2>/dev/null || echo missing)
    case "$st" in
      healthy) return 0 ;;
      missing) sleep 2 ;;
      *)       sleep 3 ;;
    esac
  done
  warn "MySQL 未在 120 秒内就绪,先跳过结构升级"
  return 1
}

# 执行增量升级脚本。
# 老库缺 user.role / product.audit_* 时补字段;新库(初始化脚本已含这些列)自动跳过。
# 必须在应用启动前执行:实体多了新字段,列不存在时所有商品查询都会报 Unknown column。
apply_migrations() {
  local sql="src/main/resources/db/migrate_v2_audit.sql" db pw has
  [ -f "$sql" ] || sql="db/migrate_v2_audit.sql"
  if [ ! -f "$sql" ]; then
    warn "未找到数据库升级脚本 $sql,跳过"
    return 0
  fi

  db="$(env_get DB_NAME)"; db="${db:-campus_second_hand}"
  pw="$(env_get MYSQL_ROOT_PASSWORD)"
  if [ -z "$pw" ]; then
    warn "读不到 MYSQL_ROOT_PASSWORD,跳过数据库升级"
    return 0
  fi

  # 必须走 TCP(-h 127.0.0.1)连 root@'%',与应用同通道;
  # 老数据卷可能 root@localhost 与 root@% 密码不同步,socket 方式会 Access denied。
  # 探测失败(如密码错误)不能让 set -e 静默退出:置空 has,走下面正常迁移流程并显示真实报错
  has=$(docker exec -i csh-mysql mysql -h 127.0.0.1 -uroot -p"$pw" -N -B \
        -e "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='${db}' AND TABLE_NAME='user' AND COLUMN_NAME='role'" \
        2>/dev/null | tr -d '\r[:space:]') || has=""
  if [ "$has" = "1" ]; then
    info "数据库结构已是最新,跳过升级"
    return 0
  fi

  info "检测到旧版数据库,正在补审核相关字段…"
  if docker exec -i csh-mysql mysql -h 127.0.0.1 -uroot -p"$pw" "$db" < "$sql"; then
    info "数据库升级完成"
  else
    fail "数据库升级失败,请把上面的报错发我"
  fi
}

do_deploy() {
  # 先体检再装 Docker:内存不足或装了宝塔时直接拦下,不浪费几分钟去装环境
  check_resources
  command -v docker >/dev/null 2>&1 || install_docker
  configure_mirror
  resolve_compose
  ensure_env
  tune_all
  pick_dockerfile

  # 先单独起 MySQL:结构升级要在应用连库之前完成
  info "启动 MySQL…"
  dc up -d mysql
  if wait_mysql; then
    apply_migrations
  fi

  info "开始构建镜像并启动应用…"
  dc up -d --build
  wait_ready
}

do_status() {
  if ! command -v docker >/dev/null 2>&1; then
    warn "尚未安装 Docker,当前没有可查看的服务。先执行:bash deploy.sh deploy"
    return 0
  fi
  resolve_compose
  dc ps
  echo ""
  dc images 2>/dev/null | head -5 || true
}

# 停用云服务器上对本项目无用的常驻服务,把内存让给 MySQL 与 JVM
# 只做 disable --now(不卸载、不改配置),末尾会打印一键恢复命令
do_slim() {
  local dry=0 with_monitor=0 a
  for a in "$@"; do
    case "$a" in
      --dry-run)      dry=1 ;;
      --with-monitor) with_monitor=1 ;;
    esac
  done

  pick_sudo

  local mem_mb avail_before avail_after
  mem_mb=$(mem_mb_of MemTotal)
  avail_before=$(mem_mb_of MemAvailable)

  echo ""
  echo "=========== 停用无用常驻服务 ==========="
  echo "总内存 ${mem_mb}MB,当前可用 ${avail_before}MB"
  echo "白名单只包含对 Web 服务无作用的组件,可用末尾命令一键恢复。"
  echo ""

  # 这些在纯跑容器的机器上没有任何作用:
  #   tuned               性能调优守护进程(默认策略几乎无收益,却常驻 28MB)
  #   multipathd          多路径存储,云盘单路径用不到
  #   udisks2             桌面磁盘监控,服务器无此需求
  #   ModemManager        移动网络调制解调器管理,云主机没有
  #   unattended-upgrades 自动安全更新,会周期性吃 CPU 与内存
  # cloudmonitor 是云监控 agent(约 40MB),需显式加 --with-monitor 才停,
  # 停掉后云控制台看不到该实例的内存/CPU 曲线,但完全不影响服务器运行。
  local targets="tuned multipathd udisks2 ModemManager unattended-upgrades"
  [ "$with_monitor" -eq 1 ] && targets="$targets cloudmonitor"

  local stopped="" s
  for s in $targets; do
    if ! systemctl list-unit-files 2>/dev/null | grep -q "^${s}\.service"; then
      printf "  %-22s 未安装,跳过\n" "$s"
      continue
    fi
    if ! systemctl is-active --quiet "$s" 2>/dev/null; then
      printf "  %-22s 本就未运行\n" "$s"
      continue
    fi
    if [ "$dry" -eq 1 ]; then
      printf "  %-22s 将停止(dry-run 未执行)\n" "$s"
      continue
    fi
    if $SUDO systemctl disable --now "$s" >/dev/null 2>&1; then
      printf "  %-22s 已停止并禁止开机自启\n" "$s"
      stopped="$stopped $s"
    else
      printf "  %-22s 停用失败(请确认有 sudo 权限)\n" "$s"
    fi
  done

  echo ""
  [ "$dry" -eq 1 ] && { info "dry-run 结束,未做任何改动"; return 0; }

  sleep 2
  avail_after=$(mem_mb_of MemAvailable)
  local delta=$(( avail_after - avail_before ))
  if [ "$delta" -gt 0 ]; then
    info "可用内存:${avail_before}MB → ${avail_after}MB(释放 ${delta}MB)"
  else
    info "可用内存:${avail_before}MB → ${avail_after}MB(变化 ${delta}MB)"
    echo "  说明:MemAvailable 是内核估算值,受 page cache 与水位影响,±10MB 属于测量噪声。"
    echo "  上面若多为「本就未运行」,说明这台机器已没有冗余服务可停,未释放内存是正常的,"
    echo "  不代表优化失败 —— 这些服务原本也没占多少。"
  fi

  if [ -n "$stopped" ]; then
    echo ""
    echo "如需恢复(例如以后不跑本项目了):"
    echo "  sudo systemctl enable --now${stopped}"
  fi

  # 未被停用但仍吃内存的安全组件,仅作提示
  if systemctl is-active --quiet aegis 2>/dev/null || [ -d /usr/local/aegis ]; then
    echo ""
    warn "阿里云安全中心(AliYunDun,约 55MB)保持运行,这是安全防护组件,不建议为了省内存停掉。"
  fi
  echo "========================================"
  echo ""
}

# 内存体检:不启动任何容器,只报告现状并给出可用预算
# 用于部署前确认「宿主机自己吃了多少」,以及定位是哪个进程占的
do_mem() {
  local total_kb avail_kb used_mb avail_mb total_mb swap_mb base_mb
  total_kb=$(awk '/^MemTotal:/ {print $2}' /proc/meminfo 2>/dev/null); total_kb="${total_kb:-0}"
  avail_kb=$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo 2>/dev/null); avail_kb="${avail_kb:-0}"
  swap_mb=$(mem_mb_of SwapTotal)
  total_mb=$(( total_kb / 1024 ))
  avail_mb=$(( avail_kb / 1024 ))
  used_mb=$(( total_mb - avail_mb ))
  base_mb=$used_mb

  echo ""
  echo "================= 内存体检 ================="
  free -h 2>/dev/null || true
  echo ""
  echo "总内存      : ${total_mb} MB"
  echo "已用(含缓存可回收): ${used_mb} MB"
  echo "实际可用    : ${avail_mb} MB"
  echo "swap        : ${swap_mb} MB $([ "$swap_mb" -lt 1024 ] && echo '(未启用/不足,部署时脚本会创建 2G)')"
  echo ""

  echo "--------------- 内存占用 TOP 8 ---------------"
  ps -eo pid,rss,comm,args --sort=-rss 2>/dev/null | head -9 \
    | awk 'NR==1{printf "%-7s %-8s %-16s %s\n","PID","RSS(MB)","进程","启动参数"} NR>1{printf "%-7s %-8s %-16s %s\n", $1, int($2/1024), $3, substr($0, index($0,$4), 60)}' \
    || ps aux --sort=-%mem 2>/dev/null | head -9
  echo ""

  echo "--------------- 环境识别 ---------------"
  if [ -d /www/server/panel ] || systemctl is-active --quiet bt 2>/dev/null; then
    warn "检测到【宝塔面板】—— 常驻 200~300MB,1G 机器上无法与 MySQL + JVM 共存。"
    warn "必须重装系统为纯 Ubuntu 22.04 镜像,或在面板里停掉 Nginx/MySQL/PHP 等所有服务。"
  fi
  systemctl is-active --quiet nginx 2>/dev/null && warn "nginx 正在运行(占用端口 80,本项目用 8080 不冲突,但会吃内存)"
  systemctl is-active --quiet apache2 2>/dev/null && warn "apache2 正在运行,建议 systemctl disable --now apache2"
  systemctl is-active --quiet mysqld 2>/dev/null && fail "宿主机已原生运行 mysqld,会占用 3306 端口与大量内存。请先 systemctl disable --now mysql 后再部署。"
  command -v docker >/dev/null 2>&1 && info "已安装 Docker(守护进程常驻约 100MB)" || info "尚未安装 Docker(脚本会自动装,装完常驻约 100MB)"
  echo ""

  echo "--------------- 部署后预算(1G 档参数) ---------------"
  echo "MySQL 容器上限     : 400 MB (实际 RSS 约 240 MB)"
  echo "应用容器上限       : 440 MB (实际 RSS 约 300 MB)"
  echo "预计新增占用       : 约 540 MB"
  if [ "$avail_mb" -lt 600 ]; then
    warn "当前可用 ${avail_mb}MB < 预计新增 540MB + 余量,部署时必须依赖 swap。"
    warn "若宿主机空载占用 ${base_mb}MB 明显偏高,建议先执行 bash deploy.sh mem 定位并停掉无关服务。"
  else
    info "可用内存足够,swap 仅作兜底。"
  fi
  echo "============================================"
  echo ""
}

# ---------------- 入口 ----------------

case "${1:-deploy}" in
  deploy|"") do_deploy ;;
  # 只按内存算参数并写入 .env,不构建不启动。用于部署前确认参数,
  # 或手动覆盖机型: MEM_MB=1024 bash deploy.sh tune
  tune)
    ensure_env
    tune_all
    echo ""
    info "以下调优项已写入 .env(尚未启动容器):"
    grep -E '^(JAVA_OPTS|MYSQL_BUFFER_POOL|MYSQL_BUFFER_POOL_CHUNK|MYSQL_MEM_LIMIT|APP_MEM_LIMIT|TOMCAT_MAX_THREADS|DB_POOL_SIZE|MYSQL_MAX_CONN|MYSQL_TABLE_CACHE)=' .env | sed 's/^/    /'
    ;;
  status)    do_status ;;
  # 内存体检:不启动容器,报告内存占用 TOP、是否装了宝塔/nginx,以及部署后预算
  mem)       do_mem ;;
  # 停用无用常驻服务回收内存(可恢复);加 --dry-run 只看不动,加 --with-monitor 连云监控一起停
  slim)      shift; do_slim "$@" ;;
  logs)      resolve_compose; dc logs -f --tail=200 app ;;
  restart)   resolve_compose; dc restart app && wait_ready ;;
  down)      resolve_compose; dc down && info "已停止容器(数据卷保留,重新 deploy 即可恢复)" ;;
  reset)
    resolve_compose
    warn "即将删除容器与【数据库数据卷】,所有数据会丢失。5 秒后执行,Ctrl+C 可取消…"
    sleep 5
    dc down -v
    info "已清空。重新执行 bash deploy.sh 会按 schema.sql 重新初始化数据库"
    ;;
  *)
    echo "用法: bash deploy.sh [deploy|tune|mem|slim|status|logs|restart|down|reset]"
    echo "  mem   内存体检(不启动容器),部署前建议先跑一次"
    echo "  slim  停用 tuned/multipathd 等无用服务回收内存,可加 --dry-run / --with-monitor"
    echo "  环境变量 MEM_MB=<数字> 可手动指定机型,例如 MEM_MB=1024 bash deploy.sh tune"
    exit 1
    ;;
esac
