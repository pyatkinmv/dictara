# Stage 1: build Flutter web
FROM ghcr.io/cirruslabs/flutter:stable AS builder
WORKDIR /app
COPY pubspec.yaml pubspec.lock* ./
COPY lib/generated/pubspec.yaml ./lib/generated/pubspec.yaml
RUN flutter pub get
COPY . .
ARG FLUTTER_TARGET=lib/material/main_material.dart
RUN flutter build web --release --target=$FLUTTER_TARGET

# Stage 2: serve with nginx
FROM nginx:alpine
COPY --from=builder /app/build/web /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
