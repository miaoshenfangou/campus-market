#!/usr/bin/env bash
# ============================================================
#  校园二手交易平台 · 本地打包上传脚本(在【你自己的电脑】上执行)
#  ------------------------------------------------------------
#  作用:把部署需要的文件收拢成一个压缩包,上传到服务器后无需编译。
#
#  为什么需要它:
#    在 2核2G 的服务器上跑 Maven,内存很容易被撑爆导致构建失败。
#    本地打包好 jar 再上传,服务器只做 10 秒的镜像组装,又快又稳。
#
#  用法:
#    bash pack.sh
#    # 产出: dist/csh-deploy.tar.gz
#    # 上传: scp dist/csh-deploy.tar.gz root@你的IP:/opt/
#
#  服务器上:
#    cd /opt && tar -xzf csh-deploy.tar.gz && cd csh-deploy && bash deploy.sh
# ============================================================
set -Eeuo pipefail

cd "$(dirname "$0")"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info() { echo -e "${GREEN}[INFO]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*" >&2; exit 1; }

JAR="target/csh-0.0.1-SNAPSHOT.jar"
NAME="csh-deploy"
DIST="dist"

# ---------- 1. 确保有 jar ----------
if [ ! -f "$JAR" ]; then
  warn "未找到 $JAR,开始本地打包…"
  if [ -x ./mvnw ]; then
    MAVEN_OPTS="-Dfile.encoding=UTF-8" ./mvnw -q clean package -DskipTests
  elif command -v mvn >/dev/null 2>&1; then
    MAVEN_OPTS="-Dfile.encoding=UTF-8" mvn -q clean package -DskipTests
  else
    fail "既没有 mvnw 也没有 mvn,请先在 IDEA 里 Maven 面板执行 package"
  fi
fi

[ -f "$JAR" ] || fail "打包失败,$JAR 不存在"
info "jar 就绪:$(du -h "$JAR" | cut -f1)"

# ---------- 2. 收集文件 ----------
# 不 rm -rf 整个目录:一是覆盖式复制已经能满足更新需求(文件名只增不减),
# 二是批量删除会触发宿主机的删除保护,打包流程会被打断。
mkdir -p "$DIST/$NAME/target" "$DIST/$NAME/src/main/resources/db"

cp -f "$JAR"                       "$DIST/$NAME/target/"
cp -f Dockerfile.jar               "$DIST/$NAME/"
cp -f docker-compose.yml           "$DIST/$NAME/"
cp -f deploy.sh                    "$DIST/$NAME/"
cp -f .env.example                 "$DIST/$NAME/"
cp -f src/main/resources/db/schema.sql "$DIST/$NAME/src/main/resources/db/"
# 增量升级脚本也要带上:deploy.sh 会在应用启动前执行它,给老库补审核字段
cp -f src/main/resources/db/migrate_v2_audit.sql "$DIST/$NAME/src/main/resources/db/"

chmod +x "$DIST/$NAME/deploy.sh"

# 源码一并带上:日后想改用「服务器编译」模式(Dockerfile)时不必再传一次
cp Dockerfile "$DIST/$NAME/"
if [ -d src/main/java ]; then
  mkdir -p "$DIST/$NAME/src/main"
  cp -r src/main/java "$DIST/$NAME/src/main/"
  cp -r src/main/resources "$DIST/$NAME/src/main/" 2>/dev/null || true
  cp pom.xml "$DIST/$NAME/" 2>/dev/null || true
  cp -r .mvn mvnw 2>/dev/null || true
fi

# ---------- 2.5 统一换成 LF 换行 ----------
# Windows 上编辑的文件默认是 CRLF。Linux 的 bash 读到 `set -o pipefail\r` 会报
# "invalid option name",脚本第一行就退出,现场很难看出是换行符问题。
# 在打包这一步统一转换,比让使用者登录服务器手动 sed 更可靠。
find "$DIST/$NAME" -type f \( -name '*.sh' -o -name '*.yml' -o -name '*.yaml' \
  -o -name '*.sql' -o -name '*.properties' -o -name '.env*' -o -name 'Dockerfile*' \
  -o -name 'mvnw' \) -exec sed -i 's/\r$//' {} +

# 兜底校验:awk 认 \r,避免 grep 在某些环境下把 CR 当行尾而漏判
for f in deploy.sh docker-compose.yml .env.example; do
  if [ -f "$DIST/$NAME/$f" ] && ! awk 'sub(/\r$/,""){bad=1} END{exit(bad?1:0)}' "$DIST/$NAME/$f"; then
    fail "$f 仍含 CRLF 换行,转换失败"
  fi
done
info "脚本与配置已统一为 LF 换行"

# ---------- 3. 压缩 ----------
if ! command -v tar >/dev/null 2>&1; then
  fail "未找到 tar 命令"
fi

# 在 dist 目录内压缩,这样解压后是一层 csh-deploy/ 目录,不会污染 /opt
( cd "$DIST" && tar -czf "$NAME.tar.gz" "$NAME" )

OUT="$DIST/$NAME.tar.gz"
[ -f "$OUT" ] || fail "压缩包生成失败"
SIZE=$(du -h "$OUT" | cut -f1)

echo ""
echo "============================================================"
echo -e " ${GREEN}打包完成${NC}: $OUT  ($SIZE)"
echo "------------------------------------------------------------"
echo " 1) 上传到服务器:"
echo "    scp $OUT root@你的服务器IP:/opt/"
echo ""
echo " 2) 登录服务器并部署:"
echo "    ssh root@你的服务器IP"
echo "    cd /opt && tar -xzf $NAME.tar.gz && cd $NAME"
echo "    bash deploy.sh"
echo ""
echo " 3) 放行端口(云控制台安全组/防火墙):TCP 8080"
echo "============================================================"
