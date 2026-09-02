# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 先只拷 pom，利用 Docker 层缓存：依赖没变时跳过重新下载
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# 与 Application.initTimeZone() 保持一致：
# 缓存中的 LocalDateTime 以毫秒时间戳存储，时区不一致会产生 8 小时偏差
ENV TZ=Asia/Shanghai

# 非 root 运行。uploads 必须预先建好并移交属主：
# WORKDIR 创建的 /app 属主是 root，未开 OSS 时 FileStorageService
# 会往 /app/uploads 写图片，ticket 用户没有写权限会导致上传失败
RUN useradd -r -u 1001 -m ticket \
    && mkdir -p /app/uploads \
    && chown -R ticket:ticket /app
USER ticket

COPY --from=builder --chown=ticket:ticket /build/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
