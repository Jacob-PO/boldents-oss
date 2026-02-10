-- ============================================================
-- AI Video Generation Platform - Database Schema
-- MariaDB 10.11+
-- ============================================================

SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- Independent Tables (no FK dependencies)
-- ============================================================

CREATE TABLE IF NOT EXISTS creator_nation (
    nation_code VARCHAR(5) PRIMARY KEY,
    nation_name VARCHAR(100) NOT NULL,
    nation_name_en VARCHAR(100) NOT NULL,
    language_code VARCHAR(10) NOT NULL,
    language_name VARCHAR(100) NOT NULL,
    tts_chars_per_second DOUBLE NOT NULL DEFAULT 4.5,
    max_chars_per_line INT NOT NULL DEFAULT 35,
    is_active BOOLEAN DEFAULT TRUE,
    display_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ai_model (
    tier_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tier_code VARCHAR(20) NOT NULL UNIQUE,
    tier_name VARCHAR(100),
    description VARCHAR(500),
    image_model VARCHAR(100) NOT NULL,
    video_model VARCHAR(100) NOT NULL,
    tts_model VARCHAR(100) NOT NULL,
    scenario_model VARCHAR(100) NOT NULL,
    fallback_image_model VARCHAR(100),
    fallback_video_model VARCHAR(100),
    fallback_tts_model VARCHAR(100),
    fallback_scenario_model VARCHAR(100),
    display_order INT DEFAULT 0,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ai_key (
    ai_key_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider VARCHAR(50),
    account_email VARCHAR(200),
    project_name VARCHAR(200),
    api_key TEXT NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    is_active BOOLEAN DEFAULT TRUE,
    last_used_at TIMESTAMP NULL,
    last_error_at TIMESTAMP NULL,
    error_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS video_formats (
    format_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    format_code VARCHAR(50) NOT NULL UNIQUE,
    format_name VARCHAR(100) NOT NULL,
    format_name_en VARCHAR(100),
    width INT NOT NULL,
    height INT NOT NULL,
    aspect_ratio VARCHAR(10) NOT NULL,
    icon VARCHAR(10),
    platform VARCHAR(50),
    description VARCHAR(500),
    is_default BOOLEAN DEFAULT FALSE,
    is_active BOOLEAN DEFAULT TRUE,
    display_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS video_subtitle (
    video_subtitle_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    subtitle_code VARCHAR(50) NOT NULL UNIQUE,
    subtitle_name VARCHAR(100) NOT NULL,
    subtitle_name_en VARCHAR(100),
    description VARCHAR(500),
    font_name VARCHAR(100),
    font_size INT,
    font_size_vertical INT,
    font_size_emotion INT,
    font_size_emotion_vertical INT,
    bold BOOLEAN DEFAULT TRUE,
    spacing INT DEFAULT 0,
    primary_colour VARCHAR(20),
    secondary_colour VARCHAR(20),
    outline_colour VARCHAR(20),
    back_colour VARCHAR(20),
    border_style INT DEFAULT 1,
    outline INT DEFAULT 2,
    shadow INT DEFAULT 0,
    alignment INT DEFAULT 2,
    margin_l INT DEFAULT 0,
    margin_r INT DEFAULT 0,
    margin_v INT DEFAULT 50,
    margin_v_vertical INT DEFAULT 700,
    emotion_primary_colour VARCHAR(20),
    is_default BOOLEAN DEFAULT FALSE,
    is_active BOOLEAN DEFAULT TRUE,
    display_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS video_thumbnail (
    thumbnail_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    style_code VARCHAR(50) NOT NULL UNIQUE,
    style_name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    border_enabled BOOLEAN DEFAULT FALSE,
    border_color VARCHAR(20),
    border_width INT DEFAULT 0,
    gradient_enabled BOOLEAN DEFAULT TRUE,
    gradient_color VARCHAR(20),
    gradient_height_ratio DOUBLE DEFAULT 0.4,
    gradient_opacity DOUBLE DEFAULT 0.7,
    text_line1_color VARCHAR(20),
    text_line2_color VARCHAR(20),
    outline_color VARCHAR(20),
    outline_thickness INT DEFAULT 8,
    shadow_enabled BOOLEAN DEFAULT TRUE,
    shadow_color VARCHAR(20),
    shadow_opacity DOUBLE DEFAULT 0.5,
    shadow_offset_x INT DEFAULT 3,
    shadow_offset_y INT DEFAULT 3,
    is_default BOOLEAN DEFAULT FALSE,
    is_active BOOLEAN DEFAULT TRUE,
    display_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS api_rate_limits (
    limit_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    api_type VARCHAR(50) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    tier VARCHAR(20) NOT NULL DEFAULT 'TIER_1',
    rpm INT DEFAULT 10,
    rpd INT DEFAULT NULL,
    tpm INT DEFAULT NULL,
    ipm INT DEFAULT NULL,
    min_delay_ms INT DEFAULT 3000,
    max_delay_ms INT DEFAULT 60000,
    initial_delay_ms INT DEFAULT 3000,
    success_decrease_ratio DECIMAL(5,2) DEFAULT 0.90,
    error_increase_ratio DECIMAL(5,2) DEFAULT 1.50,
    success_streak_for_decrease INT DEFAULT 3,
    max_retries INT DEFAULT 5,
    initial_backoff_ms INT DEFAULT 5000,
    max_backoff_ms INT DEFAULT 60000,
    description VARCHAR(500),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_api_model_tier (api_type, model_name, tier)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS api_batch_settings (
    setting_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    api_type VARCHAR(50) NOT NULL UNIQUE,
    batch_size INT NOT NULL DEFAULT 5,
    batch_delay_ms INT NOT NULL DEFAULT 3000,
    max_consecutive_errors INT DEFAULT 3,
    error_backoff_ms INT DEFAULT 5000,
    description VARCHAR(500),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Tables with FK to creator_nation / ai_model
-- ============================================================

CREATE TABLE IF NOT EXISTS video_font (
    font_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    font_code VARCHAR(50) NOT NULL UNIQUE,
    font_name VARCHAR(100) NOT NULL,
    font_name_display VARCHAR(100) NOT NULL,
    font_file_name VARCHAR(200) NOT NULL,
    nation_code VARCHAR(5) NOT NULL,
    description VARCHAR(500),
    is_default BOOLEAN DEFAULT FALSE,
    is_active BOOLEAN DEFAULT TRUE,
    display_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (nation_code) REFERENCES creator_nation(nation_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS creators (
    creator_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    creator_code VARCHAR(50) UNIQUE NOT NULL,
    creator_name VARCHAR(100) NOT NULL,
    creator_birth VARCHAR(20),
    youtube_channel VARCHAR(200),
    description TEXT,
    placeholder_text VARCHAR(200),
    is_active BOOLEAN DEFAULT TRUE,
    model_tier_id BIGINT,
    nation_code VARCHAR(5) NOT NULL DEFAULT 'KR',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (model_tier_id) REFERENCES ai_model(tier_id),
    FOREIGN KEY (nation_code) REFERENCES creator_nation(nation_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Tables with FK to creators
-- ============================================================

CREATE TABLE IF NOT EXISTS creator_prompt_base (
    base_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    prompt_type VARCHAR(50) NOT NULL UNIQUE,
    base_template MEDIUMTEXT NOT NULL,
    description VARCHAR(500),
    required_placeholders TEXT,
    version INT DEFAULT 1,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS creator_prompts (
    creator_id BIGINT PRIMARY KEY,
    -- [A] Character
    identity_anchor TEXT,
    negative_prompts_character TEXT,
    style_lock TEXT,
    character_block_full MEDIUMTEXT,
    -- [B] Scenario
    scenario_content_rules MEDIUMTEXT,
    scenario_visual_rules TEXT,
    scenario_forbidden TEXT,
    scenario_checklist TEXT,
    scenario_user_template MEDIUMTEXT,
    -- [C] Image
    image_photography_rules TEXT,
    image_composition_rules TEXT,
    image_lighting_rules TEXT,
    image_background_rules TEXT,
    image_mandatory_elements TEXT,
    image_negative TEXT,
    -- [D] Opening Video
    opening_timeline_structure MEDIUMTEXT,
    opening_camera_rules TEXT,
    opening_audio_design TEXT,
    opening_forbidden TEXT,
    -- [E] TTS
    tts_voice_name VARCHAR(100),
    tts_persona TEXT,
    -- [F] Thumbnail
    thumbnail_style_prompt TEXT,
    thumbnail_composition TEXT,
    thumbnail_text_rules TEXT,
    thumbnail_metadata_rules TEXT,
    -- [G] Narration
    narration_continuity_rules TEXT,
    narration_voice_rules TEXT,
    narration_expand_rules TEXT,
    -- [H] Safety
    safety_modification_rules TEXT,
    safety_fallback_prompt MEDIUMTEXT,
    -- [I] Other
    reference_image_analysis TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (creator_id) REFERENCES creators(creator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS creator_prompts_length (
    creator_id BIGINT PRIMARY KEY,
    thumbnail_hook_length INT DEFAULT 20,
    youtube_title_min_length INT DEFAULT 35,
    youtube_title_max_length INT DEFAULT 50,
    youtube_description_min_length INT DEFAULT 80,
    youtube_description_max_length INT DEFAULT 150,
    opening_narration_length INT DEFAULT 30,
    slide_narration_length INT DEFAULT 700,
    narration_expand_length INT DEFAULT 700,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (creator_id) REFERENCES creators(creator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS users (
    user_no BIGINT AUTO_INCREMENT PRIMARY KEY,
    login_id VARCHAR(100) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    name VARCHAR(100),
    email VARCHAR(200),
    phone VARCHAR(20),
    birth_date DATE,
    profile_image VARCHAR(500),
    role VARCHAR(20) DEFAULT 'USER',
    tier VARCHAR(20) DEFAULT 'FREE',
    creator_id BIGINT,
    google_api_key TEXT,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    FOREIGN KEY (creator_id) REFERENCES creators(creator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Conversation & Video tables
-- ============================================================

CREATE TABLE IF NOT EXISTS conversations (
    conversation_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_no BIGINT NOT NULL,
    creator_id BIGINT,
    video_id BIGINT,
    initial_prompt TEXT,
    reference_image_url TEXT,
    reference_image_analysis TEXT,
    content_type VARCHAR(50),
    quality_tier VARCHAR(20),
    video_duration INT,
    status VARCHAR(30) DEFAULT 'ACTIVE',
    current_step VARCHAR(50) DEFAULT 'INITIAL',
    question_count INT DEFAULT 0,
    total_messages INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    FOREIGN KEY (user_no) REFERENCES users(user_no),
    FOREIGN KEY (creator_id) REFERENCES creators(creator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS conversation_messages (
    message_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content MEDIUMTEXT,
    message_type VARCHAR(50),
    tokens_used INT DEFAULT 0,
    metadata TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (conversation_id) REFERENCES conversations(conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS videos (
    video_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_no BIGINT,
    creator_id BIGINT,
    format_id BIGINT DEFAULT 1,
    video_subtitle_id BIGINT,
    font_size_level INT DEFAULT 2,
    subtitle_position INT DEFAULT 1,
    thumbnail_id BIGINT,
    font_id BIGINT,
    conversation_id BIGINT,
    title VARCHAR(500),
    description TEXT,
    prompt TEXT,
    content_type VARCHAR(50),
    quality_tier VARCHAR(20),
    hook_type VARCHAR(50),
    status VARCHAR(30) DEFAULT 'PENDING',
    progress INT DEFAULT 0,
    current_step VARCHAR(50),
    duration INT,
    thumbnail_url TEXT,
    video_url TEXT,
    opening_url TEXT,
    final_video_url TEXT,
    presigned_url_expires_at TIMESTAMP NULL,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    deleted_at TIMESTAMP NULL,
    FOREIGN KEY (user_no) REFERENCES users(user_no),
    FOREIGN KEY (creator_id) REFERENCES creators(creator_id),
    FOREIGN KEY (format_id) REFERENCES video_formats(format_id),
    FOREIGN KEY (video_subtitle_id) REFERENCES video_subtitle(video_subtitle_id),
    FOREIGN KEY (thumbnail_id) REFERENCES video_thumbnail(thumbnail_id),
    FOREIGN KEY (font_id) REFERENCES video_font(font_id),
    FOREIGN KEY (conversation_id) REFERENCES conversations(conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS scenes (
    scene_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    video_id BIGINT NOT NULL,
    scene_type VARCHAR(30) NOT NULL,
    scene_order INT NOT NULL,
    title VARCHAR(500),
    narration MEDIUMTEXT,
    prompt MEDIUMTEXT NOT NULL,
    duration INT DEFAULT 10,
    camera_movement VARCHAR(100),
    lighting_preset VARCHAR(100),
    framing_type VARCHAR(100),
    transition_type VARCHAR(50),
    media_url TEXT,
    media_status VARCHAR(30),
    image_url TEXT,
    audio_url TEXT,
    subtitle_url TEXT,
    scene_video_url TEXT,
    scene_status VARCHAR(30) DEFAULT 'PENDING',
    regenerate_count INT DEFAULT 0,
    user_feedback TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (video_id) REFERENCES videos(video_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS scenarios (
    scenario_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    video_id BIGINT NOT NULL,
    scenario_json MEDIUMTEXT,
    opening_prompt MEDIUMTEXT,
    negative_prompt TEXT,
    character_block MEDIUMTEXT,
    timed_segments TEXT,
    version INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (video_id) REFERENCES videos(video_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- View
-- ============================================================

CREATE OR REPLACE VIEW v_active_rate_limits AS
SELECT
    rl.limit_id,
    rl.api_type,
    rl.model_name,
    rl.tier,
    rl.rpm,
    rl.rpd,
    rl.min_delay_ms,
    rl.max_retries,
    rl.initial_backoff_ms,
    rl.max_backoff_ms
FROM api_rate_limits rl
WHERE rl.is_active = TRUE;

SET FOREIGN_KEY_CHECKS = 1;
