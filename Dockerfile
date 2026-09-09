FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
ARG MODULE

COPY pom.xml .
COPY ticket-common ./ticket-common
COPY ticket-center-api ./ticket-center-api
COPY order-service ./order-service
COPY ticket-gateway ./ticket-gateway
RUN --mount=type=cache,target=/root/.m2 mvn -B --no-transfer-progress -pl "$MODULE" -am package -Dmaven.test.skip=true
RUN cp "$MODULE"/target/*.jar app.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
ENV TZ=Asia/Shanghai

RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 -m ticket \
    && mkdir -p /app/uploads \
    && chown -R ticket:ticket /app
USER ticket
COPY --from=builder --chown=ticket:ticket /build/app.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
