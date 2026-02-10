# AI Video Generation Platform - Development Guide

## Project Overview

Multi-creator AI video generation platform. Each creator has customized prompts, voice styles, and visual themes managed entirely in the database.

## Tech Stack

- **Backend**: Spring Boot 3.x, Java 17, MyBatis, MariaDB
- **Frontend**: Next.js 16+ (App Router), TypeScript, Tailwind CSS
- **AI**: Google Gemini (image/scenario/TTS), Veo (video)
- **Video**: FFmpeg (subtitles, composition)
- **Storage**: AWS S3

## Key Architecture Rules

### 3-Layer Prompt System
- Layer 1 (`creator_prompt_base`): XML templates with placeholders
- Layer 2 (`creator_prompts`): Per-creator content (31 columns)
- Layer 3 (`creator_prompts_length`): Length settings
- All prompts composed via `CreatorConfigService.composePrompt()`
- **No hardcoded prompts allowed**

### AI Model Tiers
- BASIC/PRO/ULTRA tiers in `ai_model` table
- Creator → `model_tier_id` → `ai_model` FK
- Each tier has primary + fallback models

### Critical Constants
| Item | Value | Notes |
|------|-------|-------|
| Opening duration | 8 sec | Veo API fixed |
| Opening narration | 40-60 chars | Fits 8 sec TTS |
| Slide duration | 10 sec default | Adjusted by actual TTS length |

### Scene Updates
Always use `SceneUpdateService` (independent transactions) instead of `sceneMapper` directly. Prevents lock timeouts during long TTS/FFmpeg operations.

## Code Structure

```
backend/api/src/main/java/com/aivideo/api/
  controller/     # REST endpoints
  service/        # Core logic
    scenario/     # ScenarioGeneratorServiceImpl
    image/        # ImageGeneratorServiceImpl
    video/        # VideoCreatorServiceImpl
    tts/          # TtsServiceImpl
    subtitle/     # SubtitleServiceImpl
  entity/         # DB entities (20 tables)
  mapper/         # MyBatis mappers + XML

frontend/src/
  app/(main)/     # Pages (home, chat)
  components/     # React components
  lib/            # API client
```

## Development Notes

- All prompts are DB-managed. No hardcoded fallbacks.
- `creatorId` is propagated via ThreadLocal in async operations.
- FFmpeg uses concat demuxer for memory efficiency.
- Scenario is the single source of truth for all content generation.
