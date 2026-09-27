# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# 依赖走阿里云镜像：自建 runner 跨境直连 Maven Central >55min 下不完，构建 60min 超时（2026-09-27 实测）
COPY settings.xml /root/.m2/settings.xml

COPY pom.xml ./
COPY zhimesh-common/pom.xml zhimesh-common/
COPY zhimesh-chat/pom.xml zhimesh-chat/
COPY zhimesh-admin/pom.xml zhimesh-admin/
COPY zhimesh-bootstrap/pom.xml zhimesh-bootstrap/
COPY .mvn/local-repo/ .mvn/local-repo/

RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -ntp dependency:go-offline

COPY zhimesh-common/src/ zhimesh-common/src/
COPY zhimesh-chat/src/ zhimesh-chat/src/
COPY zhimesh-admin/src/ zhimesh-admin/src/
COPY zhimesh-bootstrap/src/ zhimesh-bootstrap/src/

RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -ntp package -DskipTests

FROM eclipse-temurin:17-jre

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    TZ=Asia/Shanghai \
    APP_PROFILE=prod \
    JAVA_OPTS="-Xms512m -Xmx1536m -XX:+HeapDumpOnOutOfMemoryError"

WORKDIR /data/app
RUN mkdir -p /data/zhimesh /data/zhimesh-logs

COPY --from=build /app/zhimesh-bootstrap/target/zhimesh-bootstrap-0.0.1-SNAPSHOT.jar ./zhimesh-api.jar

EXPOSE 9999
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /data/app/zhimesh-api.jar --spring.profiles.active=$APP_PROFILE"]
