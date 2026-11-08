# syntax=docker/dockerfile:1

# Stage 1: Build Spring Boot application
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY src ./src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package

# Stage 2: Runtime
FROM eclipse-temurin:21-jre

WORKDIR /app

# VNPay timestamps (vnp_CreateDate/ExpireDate) are GMT+7; do not rely on the host UTC default.
ENV TZ=Asia/Ho_Chi_Minh

RUN groupadd --system appgroup \
    && useradd --system --gid appgroup appuser

COPY --from=build /workspace/target/*.jar /app/app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
