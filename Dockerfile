FROM node:24-alpine AS web-build
WORKDIR /workspace/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM maven:3.9.15-eclipse-temurin-21 AS java-build
WORKDIR /workspace/app
COPY app/pom.xml ./
RUN mvn --batch-mode --no-transfer-progress -DskipTests package \
    && rm -rf target
COPY app/src ./src
COPY --from=web-build /workspace/web/dist ./src/main/resources/webroot
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre-ubi10-minimal
LABEL org.opencontainers.image.source="https://github.com/hyeonsangjeon/kafka-metric-example" \
      org.opencontainers.image.title="Foundry Stream Lab" \
      org.opencontainers.image.description="Privacy-safe Microsoft Foundry workload telemetry over Kafka and Vert.x" \
      org.opencontainers.image.licenses="Apache-2.0"
RUN groupadd --gid 10001 streamlab \
    && useradd --uid 10001 --gid streamlab --no-create-home \
      --home-dir /nonexistent --shell /bin/false streamlab
WORKDIR /app
COPY --from=java-build --chown=streamlab:streamlab \
  /workspace/app/target/foundry-stream-lab.jar /app/foundry-stream-lab.jar

USER 10001:10001
EXPOSE 8080
ENV APP_HOST="0.0.0.0" \
    APP_PORT="8080" \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/api/v1/health | grep -q '"ready":true' || exit 1
ENTRYPOINT ["java", "-jar", "/app/foundry-stream-lab.jar"]
