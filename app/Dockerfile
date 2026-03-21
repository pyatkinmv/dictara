# Stage 1: build Flutter web
FROM ghcr.io/cirruslabs/flutter:stable AS builder
WORKDIR /app
COPY pubspec.yaml pubspec.lock* ./
RUN flutter pub get
COPY . .
ARG FLUTTER_TARGET=lib/material/main_material.dart
RUN flutter build web --release --target=$FLUTTER_TARGET

# Stage 2: serve with nginx
FROM nginx:alpine
ARG GIT_COMMIT=unknown
ARG BUILD_TIME=unknown
COPY --from=builder /app/build/web /usr/share/nginx/html
RUN echo "{\"git_commit\":\"${GIT_COMMIT}\",\"build_time\":\"${BUILD_TIME}\"}" \
    > /usr/share/nginx/html/version.json
COPY nginx.conf /etc/nginx/conf.d/default.conf
