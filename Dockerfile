# ============================================================
#  校园二手交易平台 · 多阶段构建
#  阶段 1:用 Maven 镜像编译打包(服务器上不需要装 JDK / Maven)
#  阶段 2:只把 jar 放进 JRE 镜像,最终镜像体积小、攻击面小
# ============================================================

# ---------- 阶段 1:构建 ----------
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# 先单独拷 pom.xml 再下依赖:只要 pom 没改,这一层就命中缓存,后续改代码不用重新拉依赖
COPY pom.xml .
RUN mvn -B -q dependency:go-offline -DskipTests || true

# 再拷源码编译。注意:maven-compiler-plugin 里已显式配置 Lombok 注解处理器,
# 否则 JDK 17+ 下 @Data / @Getter 生成的 getter、setter 不会被编译出来
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- 阶段 2:运行 ----------
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# curl 供 HEALTHCHECK 使用;同时把容器时区固定为东八区,避免时间字段差 8 小时
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && ln -snf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone

COPY --from=build /build/target/csh-0.0.1-SNAPSHOT.jar app.jar

# 上传目录需要可写:先在镜像里建好并用非 root 用户持有
RUN mkdir -p /app/upload/product-images /app/upload/avatar \
    && groupadd -r app && useradd -r -g app -d /app app \
    && chown -R app:app /app

USER app

EXPOSE 8080

# JAVA_TOOL_OPTIONS 由 docker-compose 注入,可在 .env 里按机器内存调整
ENV TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-Xms256m -Xmx512m -Dfile.encoding=UTF-8"

# 用公开接口做存活探测:返回 JSON 即说明 Spring 容器 + 数据库都通了
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=6 \
    CMD curl -fsS http://127.0.0.1:8080/category/list || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
