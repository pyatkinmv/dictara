# Build stage
FROM gradle:8.7-jdk21 AS build
WORKDIR /app
COPY . .
RUN gradle shadowJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
ARG GIT_COMMIT=unknown
ARG BUILD_TIME=unknown
ENV GIT_COMMIT=$GIT_COMMIT
ENV BUILD_TIME=$BUILD_TIME
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 9090
CMD ["java", "-jar", "app.jar"]
