-- ============================================================
-- AI Video Generation Platform - Sample Seed Data
-- Run after 001-schema.sql
-- ============================================================

-- Nation
INSERT INTO creator_nation (nation_code, nation_name, nation_name_en, language_code, language_name, tts_chars_per_second, max_chars_per_line, is_active, display_order)
VALUES ('KR', '대한민국', 'South Korea', 'ko', 'Korean', 4.5, 35, TRUE, 1);

-- AI Model Tiers
INSERT INTO ai_model (tier_code, tier_name, description, image_model, video_model, tts_model, scenario_model, fallback_image_model, fallback_video_model, fallback_tts_model, fallback_scenario_model, display_order)
VALUES
('BASIC', 'Basic', 'Standard quality tier', 'gemini-2.5-flash-image', 'veo-2.0-generate-001', 'gemini-2.5-flash-preview-tts', 'gemini-2.5-flash', 'gemini-2.5-flash-image', 'veo-2.0-generate-001', 'gemini-2.5-flash-preview-tts', 'gemini-2.5-flash', 1),
('PRO', 'Pro', 'Enhanced quality tier', 'gemini-2.5-flash-image', 'veo-2.0-generate-001', 'gemini-2.5-flash-preview-tts', 'gemini-2.5-pro', 'gemini-2.5-flash-image', 'veo-2.0-generate-001', 'gemini-2.5-flash-preview-tts', 'gemini-2.5-flash', 2),
('ULTRA', 'Ultra', 'Premium quality tier', 'gemini-3-pro-image-preview', 'veo-3.1-generate-preview', 'gemini-2.5-pro-preview-tts', 'gemini-3-pro-preview', 'gemini-2.5-flash-image', 'veo-2.0-generate-001', 'gemini-2.5-pro-preview-tts', 'gemini-2.5-pro', 3);

-- Font
INSERT INTO video_font (font_code, font_name, font_name_display, font_file_name, nation_code, description, is_default, display_order)
VALUES ('SUIT_BOLD', 'SUIT-Bold', 'SUIT Bold', 'SUIT-Bold.ttf', 'KR', 'Korean default font (SIL OFL 1.1)', TRUE, 1);

-- Video Formats
INSERT INTO video_formats (format_code, format_name, format_name_en, width, height, aspect_ratio, platform, description, is_default, display_order)
VALUES
('YOUTUBE_STANDARD', 'YouTube Standard', 'YouTube Standard', 1920, 1080, '16:9', 'YouTube', 'Standard landscape video', TRUE, 1),
('YOUTUBE_SHORTS', 'YouTube Shorts', 'YouTube Shorts', 1080, 1920, '9:16', 'YouTube', 'Vertical short-form video', FALSE, 2);

-- Subtitle Templates
INSERT INTO video_subtitle (subtitle_code, subtitle_name, subtitle_name_en, description, font_name, font_size, font_size_vertical, font_size_emotion, font_size_emotion_vertical, bold, primary_colour, outline_colour, outline, margin_v, margin_v_vertical, is_default, display_order)
VALUES
('BASIC', 'Basic', 'Basic', 'Clean white text', 'SUIT-Bold', 60, 180, 70, 200, TRUE, '&H00FFFFFF', '&H00000000', 2, 50, 700, TRUE, 1),
('OUTLINE', 'Outline', 'Outline', 'Bold outline style', 'SUIT-Bold', 65, 190, 75, 210, TRUE, '&H00FFFFFF', '&H00000000', 3, 50, 700, FALSE, 2);

-- Thumbnail Styles
INSERT INTO video_thumbnail (style_code, style_name, description, gradient_enabled, gradient_color, gradient_height_ratio, gradient_opacity, text_line1_color, text_line2_color, outline_color, outline_thickness, shadow_enabled, shadow_color, shadow_opacity, is_default, display_order)
VALUES
('CLASSIC', 'Classic', 'Standard thumbnail with gradient overlay', TRUE, '#000000', 0.4, 0.7, '#FFFFFF', '#FFD700', '#000000', 8, TRUE, '#000000', 0.5, TRUE, 1),
('ACCENT', 'Accent', 'Vibrant accent color style', TRUE, '#1A0033', 0.5, 0.8, '#FFFFFF', '#FF6B9D', '#330033', 10, TRUE, '#000000', 0.6, FALSE, 2);

-- Sample Creator
INSERT INTO creators (creator_code, creator_name, description, placeholder_text, is_active, model_tier_id, nation_code)
VALUES ('SAMPLE', 'Sample Creator', 'A sample creator for testing', 'Enter your video topic...', TRUE, 1, 'KR');

-- Sample Creator Prompts (minimal working set)
INSERT INTO creator_prompts (creator_id, identity_anchor, scenario_content_rules, tts_voice_name, safety_fallback_prompt)
VALUES (1,
    'A professional Korean narrator for informative content',
    'Create engaging, informative content suitable for general audiences. Focus on clear storytelling with vivid imagery.',
    'Kore',
    'A calm, professional scene with soft lighting and neutral background. Clean composition, photorealistic style.'
);

-- Sample Creator Length Settings
INSERT INTO creator_prompts_length (creator_id)
VALUES (1);

-- Base Prompt Templates (Layer 1) - minimal working templates
INSERT INTO creator_prompt_base (prompt_type, base_template, description)
VALUES
('SCENARIO', '<scenario_system>\n{{SCENARIO_CONTENT_RULES}}\n\n<output_format>\nJSON with: title, hook, opening (videoPrompt, narration), slides[] (sceneNumber, imagePrompt, narration, duration)\n</output_format>\n</scenario_system>', 'Scenario generation system prompt'),
('IMAGE_STYLE', '<image_style>\n{{IMAGE_PHOTOGRAPHY_RULES}}\n{{IMAGE_COMPOSITION_RULES}}\n{{IMAGE_LIGHTING_RULES}}\nPhotorealistic, high quality, no text overlay.\n</image_style>', 'Image generation style prompt'),
('IMAGE_NEGATIVE', '<image_negative>\n{{IMAGE_NEGATIVE}}\nblurry, distorted, anime, cartoon, text, watermark, low quality\n</image_negative>', 'Image negative prompt'),
('OPENING_VIDEO', '<opening_video>\n{{OPENING_TIMELINE_STRUCTURE}}\n{{OPENING_CAMERA_RULES}}\n{{OPENING_AUDIO_DESIGN}}\nNo subtitles, no on-screen text, no watermarks, clean frame.\n</opening_video>', 'Opening video style prompt'),
('TTS_INSTRUCTION', '<tts_instruction>\nVoice: {{TTS_VOICE_NAME}}\n{{TTS_PERSONA}}\nSpeak naturally and engagingly.\n</tts_instruction>', 'TTS voice instruction'),
('NARRATION_EXPAND', '<narration_expand>\n{{NARRATION_EXPAND_RULES}}\nExpand the narration while maintaining the original meaning and tone.\n</narration_expand>', 'Narration expansion prompt'),
('THUMBNAIL', '<thumbnail>\n{{THUMBNAIL_STYLE_PROMPT}}\n{{THUMBNAIL_COMPOSITION}}\n{{THUMBNAIL_TEXT_RULES}}\n</thumbnail>', 'Thumbnail generation prompt'),
('SAFETY', '<safety>\n{{SAFETY_MODIFICATION_RULES}}\n{{SAFETY_FALLBACK_PROMPT}}\n</safety>', 'Safety filter/fallback prompt');

-- Sample Admin User (password: use bcrypt hash of your choice)
INSERT INTO users (login_id, password, name, role, tier, creator_id, status)
VALUES ('admin', '$2a$10$dummyhashreplacewithreal000000000000000000000000', 'Admin', 'ADMIN', 'FREE', 1, 'ACTIVE');

-- API Rate Limits
INSERT INTO api_rate_limits (api_type, model_name, tier, rpm, min_delay_ms, max_retries, initial_backoff_ms, max_backoff_ms, description)
VALUES
('IMAGE', 'gemini-3-pro-image-preview', 'TIER_1', 20, 3000, 5, 5000, 60000, 'Gemini 3 Pro Image'),
('IMAGE', 'gemini-2.5-flash-image', 'TIER_1', 25, 2500, 5, 5000, 60000, 'Gemini 2.5 Flash Image'),
('TTS', 'gemini-2.5-flash-preview-tts', 'TIER_1', 10, 6000, 3, 5000, 60000, 'Gemini Flash TTS'),
('TTS', 'gemini-2.5-pro-preview-tts', 'TIER_1', 10, 6000, 3, 5000, 60000, 'Gemini Pro TTS'),
('VIDEO', 'veo-3.1-generate-preview', 'TIER_1', 5, 12000, 3, 10000, 120000, 'Veo 3.1'),
('VIDEO', 'veo-2.0-generate-001', 'TIER_1', 10, 6000, 3, 5000, 60000, 'Veo 2.0'),
('SCENARIO', 'gemini-2.5-flash', 'TIER_1', 300, 200, 3, 3000, 30000, 'Gemini Flash Scenario'),
('SCENARIO', 'gemini-2.5-pro', 'TIER_1', 100, 600, 3, 3000, 30000, 'Gemini Pro Scenario');

-- Batch Settings
INSERT INTO api_batch_settings (api_type, batch_size, batch_delay_ms, description)
VALUES
('IMAGE', 3, 5000, 'Image generation batch'),
('TTS', 1, 6000, 'TTS generation batch');
