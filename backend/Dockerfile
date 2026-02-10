# Build stage
FROM gradle:8.5-jdk17 AS build
WORKDIR /app

# Copy gradle files first for caching
COPY build.gradle settings.gradle ./
COPY api/build.gradle api/
COPY common/build.gradle common/

# Copy source code
COPY api/src api/src
COPY common/src common/src

# Build the application
RUN gradle :api:bootJar --no-daemon -x test

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Install ffmpeg for video processing and multi-language fonts for subtitles
# v2.9.174: font-noto 추가 (해외 크리에이터 다국어 폰트 지원)
RUN apk add --no-cache ffmpeg fontconfig font-noto-cjk font-noto \
    && mkdir -p /usr/share/fonts/truetype

# Copy custom fonts from build context
# v2.9.174: 새 국가 폰트 추가 시 fonts/ 디렉토리에 ttf 파일 추가 후 Docker rebuild
COPY api/src/main/resources/fonts/*.ttf /usr/share/fonts/truetype/
RUN fc-cache -f -v

# Copy the built jar
COPY --from=build /app/api/build/libs/*.jar app.jar

# Expose port
EXPOSE 8080

# Run the application with memory optimization for small instances
# v2.9.76: Heap 256MB → 640MB (6000자+ TTS 응답 처리 위해 증가)
ENTRYPOINT ["java", "-Xms256m", "-Xmx640m", "-XX:+UseSerialGC", "-jar", "-Dspring.profiles.active=prod", "app.jar"]
