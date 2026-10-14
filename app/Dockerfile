# Stage 1: build Flutter web
# Flutter is pinned to the version the app is developed and tested with (same as the `flutter-version` in
# .github/workflows/ci.yml). It is installed from the git tag because the cirruslabs/flutter image lags behind
# (its newest tag was 3.44.0 while pubspec.lock needs material_ui/cupertino_ui built for 3.47: `flutter build web`
# failed with "Undefined name 'awaitNotRequired'").
FROM debian:bookworm-slim AS builder
ARG FLUTTER_VERSION=3.47.5
RUN apt-get update     && apt-get install -y --no-install-recommends git curl unzip xz-utils ca-certificates     && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch ${FLUTTER_VERSION} https://github.com/flutter/flutter.git /flutter
ENV PATH="/flutter/bin:${PATH}"
RUN flutter config --no-analytics --no-cli-animations && flutter precache --web && flutter --version
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
