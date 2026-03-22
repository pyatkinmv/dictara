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
RUN printf '# HELP app_build_info Build information\n# TYPE app_build_info gauge\napp_build_info{git_commit="%s",build_time="%s"} 1\n' \
    "${GIT_COMMIT}" "${BUILD_TIME}" \
    > /usr/share/nginx/html/metrics.txt
COPY nginx.conf /etc/nginx/conf.d/default.conf
