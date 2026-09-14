# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace
COPY gradlew ./
COPY gradle ./gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --version
COPY . .
RUN --mount=type=cache,target=/root/.gradle ./gradlew :telegram:installDist --no-daemon

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /workspace/telegram/build/install/telegram ./

ENTRYPOINT ["./bin/telegram"]
