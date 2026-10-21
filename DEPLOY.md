# 部署到云服务器 —— 完整操作手册

> 目标:把 `csh` 这个 Spring Boot 项目跑在公网云服务器上,别人用浏览器打开 `http://你的IP:8080/` 就能用。
> 整套方案是 **Docker Compose + MySQL 8**,一条命令起全套,不需要在服务器上装 JDK、Maven、MySQL。

---

## 一、先买服务器(如果还没有)

| 项目 | 建议 | 原因 |
|---|---|---|
| 配置 | **2 核 2G 就够**(预算充足再上 4G) | 已按小内存优化:MySQL 限 800M + JVM 堆 384M,2G 实测可稳定运行 |
| 系统 | **Ubuntu 22.04 LTS** | 脚本适配最完整,资料最多 |
| 磁盘 | 40G 系统盘 | 镜像 + 数据足够 |
| 带宽 | 3M 起步 | 演示站点够用,图片多会稍慢 |
| 地域 | 国内(如杭州/上海) | 同学访问快,不需要备案(用 IP 访问) |

> **关于成本**:2 核 2G 新用户活动价通常在 40~120 元/年区间,学生认证后更低。
> 不要在产品页按原价下单 —— 务必先找「活动 / 新用户特惠 / 学生专区」入口,同一台机器差价可达 3~5 倍。
>
> 预算实在紧张还有一条 0 元路径:**本地跑 + 内网穿透**,见文末「附:零成本演示方案」。

### 如果你的机器只有 1G 内存

1G 可以跑,但内存没有余量,属于「勉强够用」档。脚本会自动识别并切到 **1G 极限模式**。

⚠️ 先纠正一个常见误解:**1G 机型的 `MemTotal` 实际只有 890~980MB**(内核、固件、显存会保留一部分),`free -h` 看到的总量并不是 1024MB。下面的预算按实测的 **894MB** 计算:

| 组件 | 1G 模式配置 | 实际占用 |
|---|---|---|
| Ubuntu 系统 + Docker 守护进程 | — | 约 380 MB（实测空载） |
| MySQL 8 | 缓冲池 64M、单实例、关 binlog、关性能采集、50 连接 | 约 240 MB |
| Spring Boot | 堆 192M、Metaspace 128M、C1 编译、Tomcat 30 线程 | 约 300 MB |
| **合计** | | **约 920 MB** |

合计已经**超过**物理内存 894MB,所以脚本会**自动创建 2G swap**,并把 `swappiness` 调到 45(1G 机器必须允许更早换出,否则内核 OOM Killer 会直接杀进程)。容器上限也刻意设为 400m / 440m,让超额部分走 swap 而不是触发 OOM。

#### 先回收云厂商自带组件占的内存(强烈建议)

云服务器空载就占 380MB,其中一大半是厂商预装的常驻服务,对本项目毫无作用。阿里云 ECS 实测占用:

| 进程 | 内存 | 处理 |
|---|---|---|
| argusagent(云监控) | 40 MB | `slim` 默认保留,加 `--with-monitor` 可停 |
| AliYunDun + Monitor(云安全中心) | 55 MB | **保留**,安全防护不建议停 |
| tuned(性能调优) | 28 MB | `slim` 停用 |
| multipathd(多路径存储) | 27 MB | `slim` 停用 |
| unattended-upgrades(自动更新) | 23 MB | `slim` 停用 |
| udisksd / ModemManager | 18 MB | `slim` 停用 |
| aliyun-service(云助手) | 18 MB | 保留 |

```bash
bash deploy.sh slim --dry-run    # 先看会停哪些,不动手
bash deploy.sh slim              # 执行,通常释放 100~140MB
bash deploy.sh slim --with-monitor   # 连云监控一起停,再多 40MB
```

`MemAvailable` 会从 513MB 提升到 **640MB** 左右,此时 MySQL 240 + 应用 300 = 540MB 就能装下,swap 只作为突发兜底,而不是日常消耗。所有操作都是 `systemctl disable --now`,脚本末尾会打印一键恢复命令,不影响数据。

**部署前再跑一次内存体检:**

```bash
bash deploy.sh mem
```

它会打印总内存、可用内存、占用 TOP 8 的进程、是否装了宝塔/nginx/原生 mysqld,以及部署后的预算。**如果宿主机空载就占了 350MB 以上,先 `slim` 再部署。**

**1G 机型必须注意的两件事:**

1. **镜像只能选「系统镜像 → Ubuntu 22.04 LTS」或「Docker CE」**
   ⚠️ **绝对不要选宝塔面板镜像** —— 宝塔自身常驻就吃 200-300MB,叠加 MySQL 和 JVM 必然 OOM,容器会反复重启。WordPress / WooCommerce / LNMP 同理。脚本检测到 `/www/server/panel` 会直接拒绝部署并给出提示。

2. **不要在服务器上编译**
   一律用 `pack.sh` 在本地打好包上传(见「二、方式 A」)。Maven + javac 峰值超 1G,1G 机器编译必被 OOM Killer 杀掉。

**性能预期(实测经验,供参考):**

| 场景 | 1G 机器表现 |
|---|---|
| 单人浏览、点详情、发布商品 | 流畅 |
| 2-3 人同时操作 | 正常,偶尔有 1-2 秒停顿 |
| 5 人以上同时刷商品列表 | 明显卡顿,但不会崩(swap 兜住) |
| 首页首次加载 | 2-4 秒(冷启动后依赖页缓存) |

> 用来面试演示、放进简历完全够用。真要做成多人长期使用的站点,升到 2G 会舒服很多。

---

**买完必须做一件事:放行端口。**

在云控制台找到「安全组」→「入方向」→ 添加规则:

| 端口 | 协议 | 授权对象 |
|---|---|---|
| 22 | TCP | 你的本机 IP(SSH 用) |
| 8080 | TCP | 0.0.0.0/0 |

> 阿里云 ECS 在「实例详情 → 安全组 → 配置规则 → 入方向 → 手动添加」,授权对象填 `0.0.0.0/0`,优先级 1。
> 腾讯云轻量在「防火墙」页添加,不是安全组页。两者都改完**立即生效**,不需要重启实例。

> 80% 的「部署成功但打不开」都是这一步没做。MySQL 的 3306 **不要**放行 —— 容器内部通信就够了。

---

## 二、把代码传到服务器

有两种方式,选一个。

### 方式 A:本地打包上传(推荐,尤其适合 2G 小内存机型)

项目里带了一个 `pack.sh`,会把 **已编译好的 jar + 部署文件** 收成一个 39MB 的包。
服务器拿到后**不需要编译**,镜像构建只要 10 秒左右,内存占用极低。

```bash
# 1) 本机执行(Windows 用 Git Bash)
cd /d/360解压/data/2026-10-16-14-38-11/csh
bash pack.sh
# 产出: dist/csh-deploy.tar.gz

# 2) 上传(登录用户不是 root 时,传到自己家目录)
scp dist/csh-deploy.tar.gz root@你的服务器IP:/opt/
#   若是普通用户(例如阿里云 Ubuntu 的 admin):
#   scp dist/csh-deploy.tar.gz admin@你的服务器IP:~/

# 3) 登录并解压
ssh root@你的服务器IP
cd /opt && tar -xzf csh-deploy.tar.gz && cd csh-deploy

#   普通用户的写法(不需要写 /opt,家目录即可):
#   ssh admin@你的服务器IP
#   cd ~ && tar -xzf csh-deploy.tar.gz && cd csh-deploy
#   之后所有命令都用 sudo 执行: sudo bash deploy.sh
```

> **不是 root 登录时**:`deploy.sh` 会自动用 `sudo` 提权,但**启动脚本本身也要用 `sudo bash deploy.sh`**,
> 否则 `.env` 和 `upload/` 目录的属主会变成普通用户,容器写入会失败。


> ⚠️ 不要在 2G 服务器上直接跑 Maven 构建 —— Maven + javac 峰值内存常常超过 1G,
> 会被内核 OOM Killer 杀掉,表现为「构建到一半莫名失败」。用 `pack.sh` 上传就能完全绕开这个问题。
> `deploy.sh` 会自动检测到包里有 jar,并切到 `Dockerfile.jar` 轻量模式。

### 方式 B:从 gitee 拉取(服务器需 4G 内存以上)

```bash
ssh root@你的服务器IP
mkdir -p /opt && cd /opt
git clone https://gitee.com/zhaokelong/csh.git
cd csh
```

> 注意:仓库里如果没有本次改动,拉下来的还是编译不过的版本,而且会在服务器上完整编译。
> **2G 机型请用方式 A。**

---

## 三、一键部署

```bash
cd /opt/csh-deploy      # 若用方式 B 则是 cd /opt/csh
bash deploy.sh
```

脚本会自动做这些事:

1. 检测 Docker,没装就用**阿里云镜像源**装好(Ubuntu/CentOS 都支持)
2. **内存不足时自动创建 2G swap**(2G 机型必需,否则构建和运行都容易被 OOM 杀掉)
3. 按物理内存自动设定 JVM 参数(2G 机器 → `-Xmx384m` + SerialGC)
4. 检测是否有已打包的 jar,有则走轻量模式(不编译)
5. 生成 `.env`,里面是**随机生成的数据库密码和 JWT 密钥**
6. `docker compose up -d --build` —— 启动 MySQL 和应用
7. 轮询健康检查,就绪后打印访问地址

耗时:轻量模式(方式 A)约 **1~2 分钟**,主要花在拉取镜像;服务器编译模式约 3~8 分钟。
看到这段就成功了:

```
============================================================
 部署完成
------------------------------------------------------------
 前台页面:  http://1.2.3.4:8080/
 接口文档:  http://1.2.3.4:8080/doc.html
============================================================
```

---

## 四、验证清单

浏览器打开 `http://你的IP:8080/`,按顺序确认:

| 检查项 | 预期结果 |
|---|---|
| 首页 | 顶部 3 个分类横幅 + 6 件商品卡片,每张卡片有价格、分类、浏览量 |
| 搜索 | 输入「耳机」,列表只剩索尼耳机那一条 |
| 分类筛选 | 点「教材书籍」,只剩考研书那本 |
| 商品详情 | 点击卡片弹窗,**每打开一次浏览量 +1**(真实写库) |
| 登录 | 右上角登录,账号 `xiaowen`,密码 `123456`(兼任审核员) |
| 收藏 | 详情页点「☆ 收藏」,再点「我的收藏」能看到 |
| 发布 | 点「＋ 发布闲置」提交,商品进入**待审核**,「我发布的」里显示黄色「待审核」角标 |
| 接口文档 | 打开 `/doc.html`,能看到 6 个模块的接口 |

### 审核与下架(第二版功能)

发布商品后不再直接上架,而是进入 `status = 0`(待审核)。审核员在「审核台」处理:

| 角色 | 能做什么 |
|---|---|
| 卖家 | 下架(`1→2`)、重新上架(`2/4→0`,重新走审核)、标记已售(`1→3`)、删除自己的商品 |
| 审核员 | 上面全部 + 审核通过(`0→1`)、驳回(`0→4`,附原因) + 删除任意商品 |
| 游客 | 只能看到 `status = 1` 的商品;未上架商品详情直接返回「该商品尚未上架」 |

- 审核员账号:`xiaowen`(数据库 `user.role = 1`)。登录后导航栏会出现「审核台」,带待审核数量角标。
- 想换审核员:`UPDATE user SET role = 1 WHERE username = '你的账号';`
- 状态码全表:`0 待审核 / 1 已上架 / 2 已下架 / 3 已售出 / 4 审核驳回`。
- 驳回原因写在 `product.audit_remark`,卖家在「我发布的」和详情页都能看到。

> 老库升级不需要 `down -v`:`deploy.sh deploy` 会先起 MySQL、等它 healthy,
> 再执行 `db/migrate_v2_audit.sql` 补 `user.role` 与 `product.audit_*` 字段,最后才启动应用。
> 脚本幂等,重复执行只会打印「已存在,跳过」。

命令行快速验:

```bash
curl http://127.0.0.1:8080/category/list
# 应返回 {"code":200,"message":"操作成功","data":[...],"timestamp":...}
```

---

## 五、常用运维命令

```bash
bash deploy.sh mem         # 内存体检:占用 TOP、是否装宝塔、部署后预算(不启动容器)
bash deploy.sh slim        # 停用 tuned/multipathd 等无用服务回收内存(可恢复)
bash deploy.sh tune        # 按内存算参数写入 .env,但不启动(部署前先看一眼)
bash deploy.sh status      # 看容器状态(应都是 healthy)
bash deploy.sh logs        # 实时看应用日志,Ctrl+C 退出
bash deploy.sh restart     # 重启应用
bash deploy.sh down        # 停止(数据保留)
bash deploy.sh reset       # 停止并清空数据库(会重新初始化,慎用)

docker compose ps                     # 等价的状态查看
docker stats                          # 实时看 CPU / 内存占用
docker compose logs --tail=100 mysql  # 只看 MySQL 日志
cat .env                              # 查看数据库密码与全部调优参数
```

**`tune` 命令的用途**:部署前先确认脚本会给你分配多少内存,避免「跑起来才发现参数不对」。
它只写 `.env`,不碰容器,可以反复执行。

```bash
bash deploy.sh tune
# 输出示例(1G 机型):
#   机型判定:894MB → 1G 极限模式
#   JVM:        -Xms96m -Xmx192m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1
#   MySQL 缓冲池:64M  (容器上限 400m)
#   应用容器上限:440m   Tomcat 线程:30   连接池:3
```

也可以用 `MEM_MB=2048 bash deploy.sh tune` 手动指定机型看一眼别的档位参数(仅影响 `.env` 的写入值,不改机器实际内存)。

**更新代码后重新部署**:覆盖源码文件,然后 `bash deploy.sh` 即可,数据库数据不受影响。链接不变。

---

## 六、常见问题

**1. 页面打不开 / 一直转圈**
- 先 `curl http://127.0.0.1:8080/category/list` 在服务器上试。本地通、外网不通 → **安全组没放行 8080**。
- 本地也不通 → `bash deploy.sh logs` 看报错。

**2. 容器反复重启 / 构建到一半失败**
- 大概率内存不够。`docker stats` 看是否接近上限。
- **构建阶段失败**(报 `Killed` 或 Maven 无输出就退出):说明是 OOM Killer 杀掉了 Maven 进程。
  不要在 2G 机器上编译,改用 `pack.sh` 在本地打好包上传(见「二、方式 A」)。
- 运行阶段失败:`deploy.sh` 会自动建 2G swap 并按内存设 JVM 参数,一般不会再 OOM。
  如果不放心可以手动检查:`free -h` 看 swap 是否已生效。

**3. 构建卡在拉依赖 / 超时**
Maven 默认走中央仓库,国内会慢。在 `pom.xml` 的 `<project>` 里加阿里云源,或先在服务器上写好 `~/.m2/settings.xml`(镜像地址 `https://maven.aliyun.com/repository/public`)。
> 用 `pack.sh` 上传则完全不涉及这一步。

**3.1 拉取 Docker 镜像很慢(阿里云 ECS 常见)**

本项目只需要拉两个镜像:`mysql:8.0` 和 `eclipse-temurin:17-jre-jammy`。国内直连 Docker Hub 可能 3~10 分钟甚至超时。

腾讯云会被脚本自动配置内网加速;**阿里云的加速地址是账号专属的**,需要你自己取一次:

1. 打开 <https://cr.console.aliyun.com/cn-hangzhou/instances/mirrors>
2. 复制「加速器地址」,形如 `https://xxxxxx.mirror.aliyuncs.com`
3. 部署时带上:

```bash
DOCKER_MIRROR=https://你的地址.mirror.aliyuncs.com bash deploy.sh
```

脚本检测到阿里云(主机名 `iZxxxxZ` 或存在 `/usr/local/aegis`)时也会主动提示这一步。不配也能跑,只是慢。

> 注意:`Dockerfile.jar` 里**刻意没有 `apt-get`** —— 时区靠 JVM 自带的 TZDB(`-Duser.timezone=Asia/Shanghai`),健康检查用 bash 的 `/dev/tcp`。所以构建过程不访问 Ubuntu 源,只有一次 `COPY`,10 秒完成。这一点在访问 `archive.ubuntu.com` 很慢的国内机器上很关键。

**4. 报 `JWT_SECRET` 占位符错误**
你复制了 `.env.example` 但没改。直接 `rm .env && bash deploy.sh` 让它随机生成。

**5. 数据库表是空的**
初始化脚本只在**数据目录为空**时执行。如果你之前起过一次又想重新初始化:`bash deploy.sh reset`(会清空数据)。

**6. 商品图片显示成 emoji 占位**
初始化数据里的 `/product-images/kindle.jpg` 这类图片文件本身不在仓库里,是正常的 —— 前端会优雅降级成分类图标。等你通过发布接口上传真实图片后就会显示。

**7. 时间差 8 小时**
镜像已设 `TZ=Asia/Shanghai`、MySQL 设了 `--default-time-zone=+08:00`,容器内时间是一致的。如果宿主机时间不对,`timedatectl set-timezone Asia/Shanghai`。

---

## 七、之后想配域名 + HTTPS

用 IP 访问没有 HTTPS,浏览器会提示「不安全」。正式投简历时建议加域名:

1. 域名 A 记录指向服务器 IP
2. 备案(国内服务器必须,约 7-20 天;用香港/海外服务器可跳过)
3. 在 `docker-compose.yml` 里加一个 Nginx 服务:

```yaml
  nginx:
    image: nginx:alpine
    restart: unless-stopped
    depends_on: [app]
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx/conf.d:/etc/nginx/conf.d:ro
      - ./nginx/certs:/etc/letsencrypt:ro
      - certbot-webroot:/var/www/certbot
```

4. `nginx/conf.d/app.conf` 里 `proxy_pass http://app:8080;` 并转发 `Host`、`X-Forwarded-For`
5. 用 certbot 申请证书:`certbot certonly --webroot -w /var/www/certbot -d 你的域名`

> 应用侧已经支持:`server.forward-headers-strategy: framework`,能正确还原协议和客户端 IP。

---

## 八、安全提醒(投简历前必看)

- `.env` 里的数据库密码和 `JWT_SECRET` 是随机生成的,**不要提交到 git**。
- 目前数据库连接用的是 `root`。要做成作品集里的加分项,可以改成专用账号:

```sql
CREATE USER 'csh_app'@'%' IDENTIFIED BY '你的密码';
GRANT SELECT, INSERT, UPDATE, DELETE ON campus_second_hand.* TO 'csh_app'@'%';
```

然后把 `.env` 的 `DB_USER` 改成 `csh_app`(compose 里已支持该变量)。
- Swagger(`/doc.html`)对公网开放方便演示,但线上环境一般会关掉或加白名单。
- 上传接口没有做文件内容校验(只校验了 `Content-Type`),这是原项目的已知短板,面试被问到可以主动说「下一步会加magic number 校验 + 图片压缩」。

---

## 九、目录里新增了哪些文件

```
csh/
├── Dockerfile                  # 多阶段构建:编译 → 精简运行镜像(需 4G 内存)
├── Dockerfile.jar              # 轻量运行镜像:直接跑已打好的 jar(2G 够用)
├── docker-compose.yml          # mysql + app 编排(含健康检查、数据卷、内存上限)
├── pack.sh                     # 本地打包 → 产出可上传的 tar.gz
├── .env.example                # 环境变量模板
├── deploy.sh                   # 一键部署脚本(自动装 Docker / 建 swap / 调 JVM)
├── .dockerignore
├── DEPLOY.md                   # 本文档
└── src/main/resources/
    ├── application-prod.yml    # 生产配置(全部走环境变量)
    ├── static/                 # 前端页面(由 Spring Boot 直接托管)
    │   ├── index.html
    │   └── assets/{app.js,style.css}
    └── db/schema.sql           # 建表 + 初始化数据(容器首启自动执行)
```

架构上有一点值得说清楚:**前端页面打包在 jar 里,由 Spring Boot 一起提供**,所以页面和接口同源(都是 `IP:8080`),不存在跨域问题,也不用单独部署 Nginx 静态站点。

---

## 附:零成本演示方案(不买服务器)

如果只是**面试 / 答辩时给面试官看**,完全不需要租服务器。做法是:在自己电脑上跑项目,再通过内网穿透把它映射成公网链接。

**步骤:**

```bash
# 1) 本机先确认 MySQL 已启动、schema.sql 已执行
# 2) 启动项目
cd /d/360解压/data/2026-10-16-14-38-11/csh
java -jar target/csh-0.0.1-SNAPSHOT.jar --server.port=8080

# 3) 另开一个终端,起临时隧道(需先装 cloudflared)
cloudflared tunnel --url http://localhost:8080
```

cloudflared 会打印一个 `https://xxx-xxx.trycloudflare.com` 的地址,**自带 HTTPS,不用备案,不用公网 IP**,发给别人就能直接打开。

**各方案对比:**

| 方案 | 成本 | 需要常开电脑 | 链接稳定性 | 适合场景 |
|---|---|---|---|---|
| 本地 + cloudflared 临时隧道 | **0 元** | 是 | 重启后链接变 | 面试演示、答辩 |
| 腾讯云/阿里云新客特惠 2核2G | 约 40~120 元/年 | 否 | 固定 IP | 长期挂着、写进简历 |
| 学生机(需学生认证) | 约 10 元/月 | 否 | 固定 IP,续费稳定 | 在校生首选 |
| 新用户免费试用 | 0 元(1 个月) | 否 | 固定 IP | 先验证部署流程 |

> 建议路径:**先用免费试用跑通一遍部署流程**,确认没问题再决定要不要付费。
> 如果只是想放进简历当作品链接,2核2G 的特惠机已经足够,不必上 4G。
