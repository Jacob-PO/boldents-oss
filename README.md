# AI Video Generation Platform

An open-source, multi-creator AI video generation platform built with Spring Boot and Next.js. Generate complete videos with AI-powered scenarios, images, TTS narration, subtitles, and thumbnails.

## Features

- **Multi-Creator System** — Each creator has customized AI prompts, voice styles, and visual themes
- **3-Layer Prompt Architecture** — Base templates (Layer 1) + per-creator content (Layer 2) + length settings (Layer 3), all managed in the database
- **AI Model Tier System** — BASIC / PRO / ULTRA tiers with automatic fallback
- **Full Video Pipeline** — Scenario → Images → TTS → Subtitles → FFmpeg composition → Final MP4
- **Internationalization** — Per-nation fonts, language settings, and TTS configurations
- **Thumbnail Generation** — Auto-generated YouTube thumbnails with customizable styles

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot 3.x, Java 17, MyBatis, MariaDB |
| Frontend | Next.js 16+ (App Router), TypeScript, Tailwind CSS |
| AI | Google Gemini (image/scenario/TTS), Veo (video) |
| Video | FFmpeg (subtitle burn-in, scene composition) |
| Storage | AWS S3 (temporary, auto-cleanup) |

## Quick Start

See [SETUP.md](docs/SETUP.md) for detailed setup instructions.

```bash
# 1. Database
mysql -u root -e "CREATE DATABASE aivideo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -u root aivideo < backend/sql/001-schema.sql
mysql -u root aivideo < backend/sql/002-seed-data.sql

# 2. Backend
cd backend
cp .env.example .env   # Edit with your settings
./gradlew :api:bootRun

# 3. Frontend
cd frontend
npm install
npm run dev
```

## Architecture

See [ARCHITECTURE.md](docs/ARCHITECTURE.md) for detailed system design.

```
User Input (topic)
  → Scenario Generation (Gemini)
  → Image Generation (Gemini) + Opening Video (Veo)
  → TTS Narration (Gemini TTS)
  → ASS Subtitle Generation
  → FFmpeg Scene Composition
  → Final MP4
```

## Project Structure

```
backend/
  api/src/main/java/com/aivideo/api/
    controller/    # REST API endpoints
    service/       # Business logic (scenario, image, video, tts, subtitle)
    entity/        # Database entities
    mapper/        # MyBatis mappers
  sql/             # Schema and seed data
frontend/
  src/app/         # Next.js App Router pages
  src/components/  # React components
  src/lib/         # API client, utilities
```

## Contributing

See [CONTRIBUTING.md](docs/CONTRIBUTING.md) for guidelines.

## Author

Built by **Jacob Sunho Kim** at **Boldents AI**.

## License

[MIT](LICENSE)
