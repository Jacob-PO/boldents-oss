# Architecture

## System Overview

```
┌─────────────┐     ┌──────────────────┐     ┌──────────────┐
│  Next.js     │────▶│  Spring Boot     │────▶│  MariaDB     │
│  Frontend    │◀────│  Backend API     │◀────│  (MyBatis)   │
└─────────────┘     └──────┬───────────┘     └──────────────┘
                           │
                    ┌──────┴───────┐
                    │  External    │
                    │  Services    │
                    ├──────────────┤
                    │ Gemini API   │  Image, Scenario, TTS
                    │ Veo API      │  Video generation
                    │ AWS S3       │  File storage
                    │ FFmpeg       │  Video composition
                    └──────────────┘
```

## Video Generation Pipeline

```
1. User Input (topic + creator selection)
   ↓
2. Scenario Generation (ScenarioGeneratorServiceImpl)
   - Gemini generates JSON: title, hook, opening, slides[]
   - Each slide: imagePrompt, narration, duration
   ↓
3. Scene Preview Generation
   - Opening video via Veo API (8 seconds)
   - Slide images via Gemini Image API
   ↓
4. TTS + Subtitles (per scene)
   - Gemini TTS generates narration audio
   - ASS subtitle files generated with timing
   ↓
5. Scene Composition (FFmpeg)
   - Image + TTS + Subtitle → individual scene MP4
   - Ken Burns effect on still images
   - Fade in/out transitions
   ↓
6. Final Video
   - All scenes concatenated via FFmpeg concat demuxer
   - Thumbnail generated and appended
   ↓
7. Upload to S3 (temporary, auto-cleanup)
```

## 3-Layer Prompt Architecture

All AI prompts are composed from three database layers:

| Layer | Table | Role |
|-------|-------|------|
| **Layer 1** | `creator_prompt_base` | XML templates with placeholders (8 types) |
| **Layer 2** | `creator_prompts` | Per-creator content (31 columns, Wide Table) |
| **Layer 3** | `creator_prompts_length` | Length settings (8 fields) |

```
CreatorConfigService.composePrompt(creatorId, baseType)
  1. Load Layer 1 template (e.g., SCENARIO type)
  2. Replace placeholders with Layer 2 content
  3. Replace length placeholders with Layer 3 values
  4. Resolve nested placeholders (up to 2 extra passes)
  → Final composed prompt
```

## AI Model Tier System

Creators are assigned a tier via `creators.model_tier_id → ai_model.tier_id`:

| Tier | Image | Video | TTS | Scenario |
|------|-------|-------|-----|----------|
| BASIC | gemini-2.5-flash-image | veo-2.0 | flash-tts | gemini-2.5-flash |
| PRO | gemini-2.5-flash-image | veo-2.0 | flash-tts | gemini-2.5-pro |
| ULTRA | gemini-3-pro-image | veo-3.1 | pro-tts | gemini-3-pro |

Each tier includes fallback models for automatic retry on failure.

## Database Schema

20 tables organized by domain:

**Creator System**: `creators`, `creator_prompts`, `creator_prompt_base`, `creator_prompts_length`, `creator_nation`

**AI System**: `ai_model`, `ai_key`, `api_rate_limits`, `api_batch_settings`

**Video System**: `videos`, `scenes`, `scenarios`, `video_formats`, `video_subtitle`, `video_thumbnail`, `video_font`

**User System**: `users`, `conversations`, `conversation_messages`

## Key Design Decisions

- **DB-driven prompts**: No hardcoded AI prompts. All managed via 3-Layer architecture.
- **SceneUpdateService**: All scene DB updates use independent transactions (`REQUIRES_NEW`) to prevent lock timeouts during long-running operations.
- **ThreadLocal for context**: `creatorId`, `fontId`, `formatId` are propagated via ThreadLocal across async operations.
- **Concat demuxer**: FFmpeg uses concat demuxer (no re-encoding) for memory efficiency on small instances.
