# syntax=docker/dockerfile:1
# RENDER VARIANT of backend/Dockerfile: build context = repository root (Render cannot pass named build contexts).
# Keep it in sync with the original; CI builds both.
FROM maven:3.9-eclipse-temurin-26@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS build
WORKDIR /src
COPY backend/pom.xml .
COPY contracts/messaging /contracts/messaging
RUN mvn -B -q dependency:go-offline -Dcontracts.dir=/contracts/messaging
COPY backend/src ./src
RUN mvn -B -q package -DskipTests -Dcontracts.dir=/contracts/messaging

FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","app.jar"]
