package com.aivideo.api.service;

import com.aivideo.api.entity.AiModel;
import com.aivideo.api.entity.Creator;
import com.aivideo.api.entity.CreatorNation;
import com.aivideo.api.entity.CreatorPrompt;
import com.aivideo.api.entity.CreatorPromptBase;
import com.aivideo.api.entity.CreatorPromptLength;
import com.aivideo.api.entity.VideoFont;
import com.aivideo.api.mapper.AiModelMapper;
import com.aivideo.api.mapper.CreatorMapper;
import com.aivideo.api.mapper.CreatorNationMapper;
import com.aivideo.api.mapper.CreatorPromptBaseMapper;
import com.aivideo.api.mapper.CreatorPromptLengthMapper;
import com.aivideo.api.mapper.CreatorPromptMapper;
import com.aivideo.api.mapper.VideoFontMapper;
import com.aivideo.common.exception.ApiException;
import com.aivideo.common.exception.ErrorCode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 크리에이터 설정 서비스
 * 크리에이터별 프롬프트 등 설정 정보를 관리
 * v2.9.121: Genre → Creator 리네이밍
 * v2.9.127: showOnHome → isActive 통합, homeDescription → description 변경
 * v2.9.129: scenarioSystem + scenarioUserTemplate → scenarioPrompt 통합
 * v2.9.130: imageSafetyFallback + safetyFilterInstruction → safetyPrompt 통합
 * v2.9.131: 7개 캐릭터 필드 → characterPrompt 통합
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreatorConfigService {

    private final CreatorMapper creatorMapper;
    private final CreatorPromptMapper creatorPromptMapper;
    private final CreatorPromptBaseMapper creatorPromptBaseMapper;  // v2.9.152: Base 템플릿 매퍼
    private final CreatorPromptLengthMapper creatorPromptLengthMapper;  // v2.9.155: 길이 설정 매퍼
    private final AiModelMapper aiModelMapper;  // v2.9.120
    private final CreatorNationMapper creatorNationMapper;  // v2.9.174: 국가/언어 매퍼
    private final VideoFontMapper videoFontMapper;  // v2.9.174: 폰트 매퍼

    // v2.9.12: Caffeine 캐시 (TTL 1시간)
    private Cache<Long, Creator> creatorCache;
    private Cache<Long, CreatorPrompt> promptCache;  // v2.9.122: Wide Table - 크리에이터별 전체 프롬프트 캐싱
    private Cache<String, CreatorPromptBase> baseTemplateCache;  // v2.9.152: Base 템플릿 캐싱
    private Cache<Long, CreatorPromptLength> lengthCache;  // v2.9.155: 길이 설정 캐싱
    private Cache<String, List<Creator>> allCreatorsCache;
    private Cache<Long, AiModel> aiModelCache;  // v2.9.120
    private Cache<String, CreatorNation> nationCache;  // v2.9.174: 국가/언어 캐시
    private Cache<Long, VideoFont> fontCache;  // v2.9.174: 폰트 캐시

    // Default creator ID - should match a valid creator in the DB
    public static final Long DEFAULT_CREATOR_ID = 1L;

    @PostConstruct
    public void initCache() {
        this.creatorCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(100)
            .build();
        this.promptCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(500)
            .build();
        // v2.9.152: Base 템플릿 캐시 추가
        this.baseTemplateCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(10)  // 7개 프롬프트 타입
            .build();
        // v2.9.155: 길이 설정 캐시 추가
        this.lengthCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(100)  // 크리에이터별 길이 설정
            .build();
        // v2.9.12: 전체 크리에이터 목록 캐시 추가
        this.allCreatorsCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(1)
            .build();
        // v2.9.120: AI 모델 티어 캐시 추가
        this.aiModelCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(10)
            .build();
        // v2.9.174: 국가/언어 캐시 추가
        this.nationCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(50)
            .build();
        // v2.9.174: 폰트 캐시 추가
        this.fontCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(100)
            .build();
        log.info("CreatorConfigService 캐시 초기화 완료 (TTL: 1시간, Base 템플릿 캐시, 길이 설정 캐시, 국가/폰트 캐시 포함)");
    }

    // 프롬프트 타입 상수
    // v2.9.129: SCENARIO_SYSTEM + SCENARIO_USER_TEMPLATE → SCENARIO 통합
    // v2.9.130: IMAGE_SAFETY_FALLBACK + SAFETY_FILTER_INSTRUCTION → SAFETY 통합
    // v2.9.131: 7개 캐릭터 필드 → CHARACTER 통합
    public static final String PROMPT_TYPE_SCENARIO = "SCENARIO";
    public static final String PROMPT_TYPE_IMAGE_STYLE = "IMAGE_STYLE";
    public static final String PROMPT_TYPE_IMAGE_NEGATIVE = "IMAGE_NEGATIVE";
    public static final String PROMPT_TYPE_OPENING_VIDEO = "OPENING_VIDEO";
    public static final String PROMPT_TYPE_TTS_INSTRUCTION = "TTS_INSTRUCTION";
    public static final String PROMPT_TYPE_SAFETY = "SAFETY";  // v2.9.130: IMAGE_SAFETY_FALLBACK + SAFETY_FILTER_INSTRUCTION 통합
    public static final String PROMPT_TYPE_THUMBNAIL = "THUMBNAIL";  // v2.9.27
    public static final String PROMPT_TYPE_NARRATION_EXPAND = "NARRATION_EXPAND";  // v2.9.98
    public static final String PROMPT_TYPE_REFERENCE_IMAGE_ANALYSIS = "REFERENCE_IMAGE_ANALYSIS";  // v2.9.150
    // v2.9.132: 7개 캐릭터 필드 개별 컬럼 복원
    public static final String PROMPT_TYPE_IDENTITY_ANCHOR = "IDENTITY_ANCHOR";
    public static final String PROMPT_TYPE_CHARACTER_BLOCK_FULL = "CHARACTER_BLOCK_FULL";
    public static final String PROMPT_TYPE_NEGATIVE_PROMPTS_CHARACTER = "NEGATIVE_PROMPTS_CHARACTER";
    public static final String PROMPT_TYPE_STYLE_LOCK = "STYLE_LOCK";
    // v2.9.133: APPEARANCE_PROMPT_BLOCK 제거 (CHARACTER_BLOCK_FULL에 통합)
    public static final String PROMPT_TYPE_VIDEO_PROMPT_BLOCK = "VIDEO_PROMPT_BLOCK";
    public static final String PROMPT_TYPE_THUMBNAIL_STYLE_PROMPT = "THUMBNAIL_STYLE_PROMPT";

    // v2.9.105: 캐릭터 일관성 플레이스홀더 (v2.9.132: 개별 컬럼에서 직접 조회)
    // v2.9.128: PLACEHOLDER_TTS_PERSONA 삭제 (tts_instruction 프롬프트에 통합)
    public static final String PLACEHOLDER_IDENTITY_ANCHOR = "{{IDENTITY_ANCHOR}}";
    public static final String PLACEHOLDER_CHARACTER_BLOCK = "{{CHARACTER_BLOCK}}";
    public static final String PLACEHOLDER_NEGATIVE_PROMPTS_CHARACTER = "{{NEGATIVE_PROMPTS_CHARACTER}}";
    public static final String PLACEHOLDER_STYLE_LOCK = "{{STYLE_LOCK}}";
    // v2.9.133: PLACEHOLDER_APPEARANCE_PROMPT 제거 (CHARACTER_BLOCK에 통합)
    public static final String PLACEHOLDER_VIDEO_PROMPT_BLOCK = "{{VIDEO_PROMPT_BLOCK}}";
    public static final String PLACEHOLDER_THUMBNAIL_STYLE = "{{THUMBNAIL_STYLE}}";
    // v2.9.143: 크리에이터 기본 정보 플레이스홀더 (creators 테이블)
    public static final String PLACEHOLDER_CREATOR_NAME = "{{CREATOR_NAME}}";
    public static final String PLACEHOLDER_CREATOR_DESCRIPTION = "{{CREATOR_DESCRIPTION}}";
    public static final String PLACEHOLDER_YOUTUBE_CHANNEL = "{{YOUTUBE_CHANNEL}}";
    public static final String PLACEHOLDER_CREATOR_BIRTH = "{{CREATOR_BIRTH}}";

    // ========== 크리에이터 조회 ==========

    /**
     * 활성화된 모든 크리에이터 조회 (v2.9.12: 캐싱 적용)
     */
    public List<Creator> getAllActiveCreators() {
        return allCreatorsCache.get("all", key -> creatorMapper.findAllActive());
    }

    // v2.9.127: getCreatorsShowOnHome() 삭제 (showOnHome이 isActive로 통합됨)
    // 홈 화면 표시에는 getAllActiveCreators() 사용

    /**
     * 크리에이터 ID로 조회 (Caffeine 캐싱)
     */
    public Creator getCreator(Long creatorId) {
        if (creatorId == null) {
            creatorId = DEFAULT_CREATOR_ID;
        }
        final Long finalCreatorId = creatorId;
        return creatorCache.get(finalCreatorId,
            id -> creatorMapper.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "크리에이터를 찾을 수 없습니다: " + id)));
    }

    /**
     * 크리에이터 코드로 조회
     */
    public Creator getCreatorByCode(String creatorCode) {
        return creatorMapper.findByCode(creatorCode)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "크리에이터를 찾을 수 없습니다: " + creatorCode));
    }

    /**
     * 안전한 크리에이터 ID 반환 (null이면 기본값)
     */
    public Long getSafeCreatorId(Long creatorId) {
        return creatorId != null ? creatorId : DEFAULT_CREATOR_ID;
    }

    // ========== v2.9.174: 국가/언어/폰트 조회 ==========

    /**
     * 국가 코드로 CreatorNation 조회 (Caffeine 캐싱)
     */
    public CreatorNation getNation(String nationCode) {
        if (nationCode == null) return null;
        return nationCache.get(nationCode,
            code -> creatorNationMapper.findByCode(code).orElse(null));
    }

    /**
     * 크리에이터 ID로 해당 국가 정보 조회
     */
    public CreatorNation getNationForCreator(Long creatorId) {
        Creator creator = getCreator(creatorId);
        if (creator == null || creator.getNationCode() == null) return null;
        return getNation(creator.getNationCode());
    }

    /**
     * 크리에이터의 언어 코드 조회 ('ko', 'en', 'ja' 등)
     */
    public String getLanguageCode(Long creatorId) {
        CreatorNation nation = getNationForCreator(creatorId);
        return nation != null ? nation.getLanguageCode() : "ko";
    }

    /**
     * 크리에이터의 TTS 발화 속도 (자/초)
     */
    public double getTtsCharsPerSecond(Long creatorId) {
        CreatorNation nation = getNationForCreator(creatorId);
        return nation != null && nation.getTtsCharsPerSecond() != null ? nation.getTtsCharsPerSecond() : 4.5;
    }

    /**
     * 크리에이터의 자막 줄바꿈 기준 글자 수
     */
    public int getMaxCharsPerLine(Long creatorId) {
        CreatorNation nation = getNationForCreator(creatorId);
        return nation != null && nation.getMaxCharsPerLine() != null ? nation.getMaxCharsPerLine() : 35;
    }

    /**
     * 폰트 ID로 VideoFont 조회 (Caffeine 캐싱)
     */
    public VideoFont getFont(Long fontId) {
        if (fontId == null) return null;
        return fontCache.get(fontId,
            id -> videoFontMapper.findById(id).orElse(null));
    }

    /**
     * 국가 기본 폰트 조회
     */
    public VideoFont getDefaultFont(String nationCode) {
        if (nationCode == null) return null;
        return videoFontMapper.findDefaultByNationCode(nationCode).orElse(null);
    }

    /**
     * 국가별 활성 폰트 목록 조회
     */
    public List<VideoFont> getFontsByNation(String nationCode) {
        return videoFontMapper.findByNationCode(nationCode);
    }

    /**
     * 활성화된 모든 국가 목록 조회
     */
    public List<CreatorNation> getAllActiveNations() {
        return creatorNationMapper.findAllActive();
    }

    // ========== 버추얼 크리에이터 조회 (v2.9.123: creator_prompts 테이블에서 조회, v2.9.131: character_prompt 통합) ==========

    /**
     * v2.9.123: 크리에이터에 버추얼 크리에이터 프롬프트가 설정되어 있는지 확인
     * (기존 hasFixedCharacter() 대체 - creator_prompts 테이블에서 조회)
     * v2.9.131: character_prompt 컬럼의 [IDENTITY_ANCHOR] 섹션 존재 여부로 확인
     * @param creatorId 크리에이터 ID
     * @return 버추얼 크리에이터 프롬프트 존재 여부
     */
    public boolean hasFixedCharacter(Long creatorId) {
        CreatorPrompt prompts = getCreatorPrompts(getSafeCreatorId(creatorId));
        if (prompts == null) {
            return false;
        }
        // v2.9.131: getIdentityAnchor()는 이제 character_prompt에서 [IDENTITY_ANCHOR] 섹션 파싱
        return prompts.getIdentityAnchor() != null && !prompts.getIdentityAnchor().isBlank();
    }

    /**
     * v2.9.123: 크리에이터의 썸네일 스타일 프롬프트 조회
     * v2.9.131: character_prompt의 [THUMBNAIL_STYLE] 섹션에서 파싱
     * v2.9.149: injectFixedCharacterValues() 추가 - 썸네일과 씬 이미지 얼굴 일관성 보장
     */
    public String getFixedCharacterThumbnailStyle(Long creatorId) {
        CreatorPrompt prompts = getCreatorPrompts(getSafeCreatorId(creatorId));
        if (prompts == null) {
            log.debug("[v2.9.149] 크리에이터 프롬프트 없음: creatorId={}", creatorId);
            return "";
        }
        String thumbnailStyle = prompts.getThumbnailStylePrompt();
        if (thumbnailStyle == null || thumbnailStyle.isBlank()) {
            log.debug("[v2.9.149] 크리에이터에 썸네일 스타일 없음: creatorId={}", creatorId);
            return "";
        }
        // v2.9.149: identity_anchor, character_block 등 플레이스홀더 치환
        // 이를 통해 썸네일 이미지와 씬 이미지의 캐릭터 얼굴이 일관되게 유지됨
        String result = injectFixedCharacterValues(thumbnailStyle, creatorId);
        log.info("[v2.9.149] Got thumbnail style with character injection: creatorId={}, rawLength={}, resultLength={}",
            creatorId, thumbnailStyle.length(), result.length());
        return result;
    }

    // v2.9.128: getTtsPersonaPrompt() 삭제 (tts_instruction 프롬프트에 통합)

    // v2.9.159: getVideoPromptBlock() 제거 - 외부 호출처 없음
    // composePrompt() 내부에서 composeVideoPromptBlock()을 직접 호출하여 {{VIDEO_PROMPT_BLOCK}} 치환

    /**
     * v2.9.152: Opening Video 관련 필드들을 조합하여 VIDEO_PROMPT_BLOCK 생성
     */
    private String composeVideoPromptBlock(CreatorPrompt prompts) {
        StringBuilder sb = new StringBuilder();

        if (prompts.getOpeningTimelineStructure() != null && !prompts.getOpeningTimelineStructure().isBlank()) {
            sb.append(prompts.getOpeningTimelineStructure()).append("\n\n");
        }
        if (prompts.getOpeningCameraRules() != null && !prompts.getOpeningCameraRules().isBlank()) {
            sb.append(prompts.getOpeningCameraRules()).append("\n\n");
        }
        if (prompts.getOpeningAudioDesign() != null && !prompts.getOpeningAudioDesign().isBlank()) {
            sb.append(prompts.getOpeningAudioDesign()).append("\n\n");
        }
        if (prompts.getOpeningForbidden() != null && !prompts.getOpeningForbidden().isBlank()) {
            sb.append(prompts.getOpeningForbidden());
        }

        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /**
     * v2.9.133: 크리에이터의 캐릭터 블록 전체 조회
     * 버추얼 크리에이터의 characterBlock을 AI 생성 대신 사용
     * @return 캐릭터 블록 전체 (없으면 null)
     */
    public String getCharacterBlockFull(Long creatorId) {
        CreatorPrompt prompts = getCreatorPrompts(getSafeCreatorId(creatorId));
        if (prompts == null) {
            return null;
        }
        String characterBlock = prompts.getCharacterBlockFull();
        if (characterBlock != null && !characterBlock.isBlank()) {
            log.debug("[v2.9.133] Got character block full: creatorId={}, length={}",
                creatorId, characterBlock.length());
        }
        return characterBlock;
    }

    /**
     * v2.9.123: 프롬프트에 버추얼 크리에이터 값 주입
     * v2.9.132: 7개 캐릭터 필드에서 직접 조회하여 플레이스홀더 치환
     * v2.9.143: 크리에이터 기본 정보 플레이스홀더 치환 추가 (creators 테이블)
     * v2.9.145: 중첩 플레이스홀더 처리를 위한 반복 치환 방식 적용
     * @param prompt 원본 프롬프트 (플레이스홀더 포함)
     * @param creatorId 크리에이터 ID
     * @return 캐릭터 정보가 주입된 프롬프트
     */
    public String injectFixedCharacterValues(String prompt, Long creatorId) {
        if (prompt == null || prompt.isBlank()) {
            return prompt;
        }

        String result = prompt;
        Long safeCreatorId = getSafeCreatorId(creatorId);

        // v2.9.143: 크리에이터 기본 정보 먼저 치환 (creators 테이블)
        // 이 플레이스홀더들은 모든 크리에이터에 적용 가능
        Creator creator = getCreator(safeCreatorId);
        if (creator != null) {
            if (creator.getCreatorName() != null) {
                result = result.replace(PLACEHOLDER_CREATOR_NAME, creator.getCreatorName());
            }
            if (creator.getDescription() != null) {
                result = result.replace(PLACEHOLDER_CREATOR_DESCRIPTION, creator.getDescription());
            }
            if (creator.getYoutubeChannel() != null) {
                result = result.replace(PLACEHOLDER_YOUTUBE_CHANNEL, creator.getYoutubeChannel());
            }
            if (creator.getCreatorBirth() != null) {
                result = result.replace(PLACEHOLDER_CREATOR_BIRTH, creator.getCreatorBirth());
            }
            log.debug("[v2.9.143] 크리에이터 기본 정보 주입: name={}, channel={}",
                creator.getCreatorName(), creator.getYoutubeChannel());
        }

        CreatorPrompt prompts = getCreatorPrompts(safeCreatorId);

        // 버추얼 크리에이터 프롬프트가 없으면 기본 정보만 치환하고 반환
        // v2.9.132: getIdentityAnchor()는 identity_anchor 컬럼에서 직접 조회
        if (prompts == null || prompts.getIdentityAnchor() == null || prompts.getIdentityAnchor().isBlank()) {
            log.debug("[v2.9.143] 크리에이터에 버추얼 크리에이터 프롬프트 없음, 기본 정보만 치환: creatorId={}", creatorId);
            return result;
        }

        // v2.9.145: 중첩 플레이스홀더 처리를 위한 반복 치환
        // 예: character_block_full 안에 {{IDENTITY_ANCHOR}}가 있을 때
        // 최대 3회 반복하여 모든 중첩 플레이스홀더 해결
        final int MAX_ITERATIONS = 3;
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            String beforeIteration = result;
            result = applyAllPlaceholderReplacements(result, prompts);

            // 변경이 없으면 더 이상 플레이스홀더가 없음
            if (result.equals(beforeIteration)) {
                log.debug("[v2.9.145] 플레이스홀더 치환 완료: iteration={}, creatorId={}", iteration + 1, creatorId);
                break;
            }
            log.debug("[v2.9.145] 중첩 플레이스홀더 발견, 재치환: iteration={}, creatorId={}", iteration + 1, creatorId);
        }

        log.debug("[v2.9.145] 버추얼 크리에이터 값 주입 완료: creatorId={}", creatorId);
        return result;
    }

    /**
     * v2.9.145: 모든 플레이스홀더를 한 번에 치환하는 헬퍼 메서드
     * 중첩 플레이스홀더 지원을 위해 별도 메서드로 분리
     */
    private String applyAllPlaceholderReplacements(String input, CreatorPrompt prompts) {
        String result = input;

        // Identity Anchor 주입 (모든 프롬프트 시작에 필수)
        if (prompts.getIdentityAnchor() != null) {
            result = result.replace(PLACEHOLDER_IDENTITY_ANCHOR, prompts.getIdentityAnchor());
        }

        // Character Block 전체 주입 (상세 캐릭터 정의)
        if (prompts.getCharacterBlockFull() != null) {
            result = result.replace(PLACEHOLDER_CHARACTER_BLOCK, prompts.getCharacterBlockFull());
        }

        // Negative Prompts 주입 (캐릭터 일관성 금지 요소)
        if (prompts.getNegativePromptsCharacter() != null) {
            result = result.replace(PLACEHOLDER_NEGATIVE_PROMPTS_CHARACTER, prompts.getNegativePromptsCharacter());
        }

        // Style Lock 주입 (시각적 스타일 고정)
        if (prompts.getStyleLock() != null) {
            result = result.replace(PLACEHOLDER_STYLE_LOCK, prompts.getStyleLock());
        }

        // v2.9.133: Appearance Prompt Block 제거 (CHARACTER_BLOCK에 통합)

        // v2.9.152: Video Prompt Block 주입 (Opening Video 세분화 필드들 조합)
        String videoPromptBlock = composeVideoPromptBlock(prompts);
        if (videoPromptBlock != null) {
            result = result.replace(PLACEHOLDER_VIDEO_PROMPT_BLOCK, videoPromptBlock);
        }

        // v2.9.128: TTS Persona 주입 삭제 (tts_instruction 프롬프트에 통합)

        // Thumbnail Style 주입 (썸네일 스타일)
        if (prompts.getThumbnailStylePrompt() != null) {
            result = result.replace(PLACEHOLDER_THUMBNAIL_STYLE, prompts.getThumbnailStylePrompt());
        }

        return result;
    }

    // ========== 프롬프트 조회 (v2.9.152: 2단계 아키텍처) ==========

    /**
     * v2.9.122: 크리에이터별 전체 프롬프트 조회 (Caffeine 캐싱)
     * Wide Table 구조: 크리에이터당 1개 row에 모든 프롬프트 포함
     * v2.9.161: public으로 변경 (ImageGeneratorServiceImpl에서 character_block_full 직접 접근)
     */
    public CreatorPrompt getCreatorPrompts(Long creatorId) {
        final Long safeCreatorId = getSafeCreatorId(creatorId);
        return promptCache.get(safeCreatorId, id ->
            creatorPromptMapper.findByCreatorId(id).orElse(null)
        );
    }

    /**
     * v2.9.152: Base 템플릿 조회 (Caffeine 캐싱)
     * @param promptType 프롬프트 타입 (SCENARIO, IMAGE_STYLE 등)
     * @return Base 템플릿 또는 null
     */
    private CreatorPromptBase getBaseTemplate(String promptType) {
        return baseTemplateCache.get(promptType, type ->
            creatorPromptBaseMapper.findByPromptType(type).orElse(null)
        );
    }

    /**
     * v2.9.155: 크리에이터별 길이 설정 조회 (Caffeine 캐싱)
     * creator_prompts_length 테이블에서 모든 길이 설정을 한 번에 조회
     */
    private CreatorPromptLength getCreatorPromptLength(Long creatorId) {
        final Long safeCreatorId = getSafeCreatorId(creatorId);
        return lengthCache.get(safeCreatorId, id ->
            creatorPromptLengthMapper.findByCreatorId(id)  // null이면 기본값 사용
        );
    }

    /**
     * v2.9.152: 2단계 프롬프트 조합 - Base 템플릿 + 크리에이터 값
     *
     * Layer 1: creator_prompt_base (XML 템플릿)
     * Layer 2: creator_prompts (크리에이터별 세분화 컬럼)
     *
     * @param creatorId 크리에이터 ID
     * @param promptType 프롬프트 타입 (SCENARIO, IMAGE_STYLE 등)
     * @return 조합된 프롬프트 또는 null
     */
    public String getPrompt(Long creatorId, String promptType) {
        // 캐릭터 필드는 직접 조회 (Base 템플릿 불필요)
        if (isCharacterFieldType(promptType)) {
            return getCharacterFieldDirectly(creatorId, promptType);
        }

        // Base 템플릿 조회
        CreatorPromptBase baseTemplate = getBaseTemplate(promptType);
        if (baseTemplate == null || baseTemplate.getBaseTemplate() == null) {
            log.warn("[v2.9.152] Base 템플릿 없음: promptType={}", promptType);
            return null;
        }

        // 크리에이터 값 조회
        CreatorPrompt prompts = getCreatorPrompts(creatorId);
        if (prompts == null) {
            log.warn("[v2.9.152] 크리에이터 프롬프트 없음: creatorId={}", creatorId);
            return null;
        }

        // 2단계 조합: Base 템플릿 + 크리에이터 값
        String composedPrompt = composePrompt(baseTemplate.getBaseTemplate(), prompts, creatorId);
        log.debug("[v2.9.152] 프롬프트 조합 완료: creatorId={}, type={}, length={}",
            creatorId, promptType, composedPrompt != null ? composedPrompt.length() : 0);

        return composedPrompt;
    }

    /**
     * v2.9.152: 캐릭터 필드 타입 여부 확인
     */
    private boolean isCharacterFieldType(String promptType) {
        return PROMPT_TYPE_IDENTITY_ANCHOR.equals(promptType)
            || PROMPT_TYPE_CHARACTER_BLOCK_FULL.equals(promptType)
            || PROMPT_TYPE_NEGATIVE_PROMPTS_CHARACTER.equals(promptType)
            || PROMPT_TYPE_STYLE_LOCK.equals(promptType)
            || PROMPT_TYPE_VIDEO_PROMPT_BLOCK.equals(promptType)
            || PROMPT_TYPE_THUMBNAIL_STYLE_PROMPT.equals(promptType)
            || PROMPT_TYPE_REFERENCE_IMAGE_ANALYSIS.equals(promptType);
    }

    /**
     * v2.9.152: 캐릭터 필드 직접 조회 (Base 템플릿 불필요)
     */
    private String getCharacterFieldDirectly(Long creatorId, String promptType) {
        CreatorPrompt prompts = getCreatorPrompts(creatorId);
        if (prompts == null) {
            return null;
        }
        return switch (promptType) {
            case PROMPT_TYPE_IDENTITY_ANCHOR -> prompts.getIdentityAnchor();
            case PROMPT_TYPE_CHARACTER_BLOCK_FULL -> prompts.getCharacterBlockFull();
            case PROMPT_TYPE_NEGATIVE_PROMPTS_CHARACTER -> prompts.getNegativePromptsCharacter();
            case PROMPT_TYPE_STYLE_LOCK -> prompts.getStyleLock();
            case PROMPT_TYPE_VIDEO_PROMPT_BLOCK -> composeVideoPromptBlock(prompts);  // v2.9.152: 세분화 필드 조합
            case PROMPT_TYPE_THUMBNAIL_STYLE_PROMPT -> prompts.getThumbnailStylePrompt();
            case PROMPT_TYPE_REFERENCE_IMAGE_ANALYSIS -> prompts.getReferenceImageAnalysis();
            default -> null;
        };
    }

    /**
     * v2.9.152: Base 템플릿 + 크리에이터 값 조합
     * 모든 플레이스홀더를 해당 크리에이터의 값으로 치환
     */
    private String composePrompt(String baseTemplate, CreatorPrompt prompts, Long creatorId) {
        if (baseTemplate == null) {
            return null;
        }

        String result = baseTemplate;

        // 크리에이터 기본 정보 치환 (creators 테이블)
        Creator creator = getCreator(creatorId);
        if (creator != null) {
            result = result.replace("{{CREATOR_NAME}}", nullSafe(creator.getCreatorName()));
            result = result.replace("{{CREATOR_DESCRIPTION}}", nullSafe(creator.getDescription()));
            result = result.replace("{{YOUTUBE_CHANNEL}}", nullSafe(creator.getYoutubeChannel()));
            result = result.replace("{{CREATOR_BIRTH}}", nullSafe(creator.getCreatorBirth()));

            // v2.9.174: 국가/언어 플레이스홀더
            if (creator.getNationCode() != null) {
                CreatorNation nation = getNation(creator.getNationCode());
                if (nation != null) {
                    result = result.replace("{{NATION_CODE}}", nullSafe(nation.getNationCode()));
                    result = result.replace("{{LANGUAGE_CODE}}", nullSafe(nation.getLanguageCode()));
                    result = result.replace("{{CONTENT_LANGUAGE}}", nullSafe(nation.getLanguageName()));
                }
            }
        }

        // [A] 캐릭터 정의 (4개)
        result = result.replace("{{IDENTITY_ANCHOR}}", nullSafe(prompts.getIdentityAnchor()));
        result = result.replace("{{CHARACTER_BLOCK}}", nullSafe(prompts.getCharacterBlockFull()));
        result = result.replace("{{NEGATIVE_PROMPTS_CHARACTER}}", nullSafe(prompts.getNegativePromptsCharacter()));
        result = result.replace("{{STYLE_LOCK}}", nullSafe(prompts.getStyleLock()));

        // [B] SCENARIO 세분화 (5개) - v2.9.153: SCENARIO_JSON_FORMAT 제거 (Base 템플릿에서 관리)
        result = result.replace("{{SCENARIO_CONTENT_RULES}}", nullSafe(prompts.getScenarioContentRules()));
        result = result.replace("{{SCENARIO_VISUAL_RULES}}", nullSafe(prompts.getScenarioVisualRules()));
        result = result.replace("{{SCENARIO_FORBIDDEN}}", nullSafe(prompts.getScenarioForbidden()));
        result = result.replace("{{SCENARIO_CHECKLIST}}", nullSafe(prompts.getScenarioChecklist()));
        result = result.replace("{{SCENARIO_USER_TEMPLATE}}", nullSafe(prompts.getScenarioUserTemplate()));

        // [C] IMAGE 세분화 (6개)
        result = result.replace("{{IMAGE_PHOTOGRAPHY_RULES}}", nullSafe(prompts.getImagePhotographyRules()));
        result = result.replace("{{IMAGE_COMPOSITION_RULES}}", nullSafe(prompts.getImageCompositionRules()));
        result = result.replace("{{IMAGE_LIGHTING_RULES}}", nullSafe(prompts.getImageLightingRules()));
        result = result.replace("{{IMAGE_BACKGROUND_RULES}}", nullSafe(prompts.getImageBackgroundRules()));
        result = result.replace("{{IMAGE_MANDATORY_ELEMENTS}}", nullSafe(prompts.getImageMandatoryElements()));
        result = result.replace("{{IMAGE_NEGATIVE}}", nullSafe(prompts.getImageNegative()));

        // [D] OPENING VIDEO 세분화 (4개)
        result = result.replace("{{OPENING_TIMELINE_STRUCTURE}}", nullSafe(prompts.getOpeningTimelineStructure()));
        result = result.replace("{{OPENING_CAMERA_RULES}}", nullSafe(prompts.getOpeningCameraRules()));
        result = result.replace("{{OPENING_AUDIO_DESIGN}}", nullSafe(prompts.getOpeningAudioDesign()));
        result = result.replace("{{OPENING_FORBIDDEN}}", nullSafe(prompts.getOpeningForbidden()));

        // [E] TTS 세분화 (2개)
        result = result.replace("{{TTS_VOICE_NAME}}", nullSafe(prompts.getTtsVoiceName()));
        result = result.replace("{{TTS_PERSONA}}", nullSafe(prompts.getTtsPersona()));

        // [F] THUMBNAIL 세분화 (4개)
        result = result.replace("{{THUMBNAIL_STYLE}}", nullSafe(prompts.getThumbnailStylePrompt()));
        result = result.replace("{{THUMBNAIL_COMPOSITION}}", nullSafe(prompts.getThumbnailComposition()));
        result = result.replace("{{THUMBNAIL_TEXT_RULES}}", nullSafe(prompts.getThumbnailTextRules()));
        result = result.replace("{{THUMBNAIL_METADATA_RULES}}", nullSafe(prompts.getThumbnailMetadataRules()));

        // [G] NARRATION EXPAND 세분화 (3개)
        result = result.replace("{{NARRATION_CONTINUITY_RULES}}", nullSafe(prompts.getNarrationContinuityRules()));
        result = result.replace("{{NARRATION_VOICE_RULES}}", nullSafe(prompts.getNarrationVoiceRules()));
        result = result.replace("{{NARRATION_EXPAND_RULES}}", nullSafe(prompts.getNarrationExpandRules()));

        // [H] SAFETY 세분화 (2개)
        result = result.replace("{{SAFETY_MODIFICATION_RULES}}", nullSafe(prompts.getSafetyModificationRules()));
        result = result.replace("{{SAFETY_FALLBACK_PROMPT}}", nullSafe(prompts.getSafetyFallbackPrompt()));

        // [I] 설정값 - v2.9.155: creator_prompts_length 테이블에서 조회
        // v2.9.170: 하드코딩 폴백 제거 - DB 필수 (113-ensure-creator-prompts-length.sql 선행 필요)
        CreatorPromptLength lengthSettings = getCreatorPromptLength(creatorId);
        if (lengthSettings == null) {
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 길이 설정(creator_prompts_length)이 DB에 없습니다. 관리자에게 문의하세요.");
        }

        // 나레이션 관련 길이 (3개 + 오프닝 최대)
        result = result.replace("{{OPENING_NARRATION_LENGTH}}", requireLength(lengthSettings.getOpeningNarrationLength(), creatorId, "OPENING_NARRATION_LENGTH"));
        // v2.9.175: 오프닝 나레이션 최대 길이 (130%)
        int openingMax = (int) (lengthSettings.getOpeningNarrationLength() * 1.3);
        result = result.replace("{{OPENING_NARRATION_MAX}}", String.valueOf(openingMax));
        result = result.replace("{{SLIDE_NARRATION_LENGTH}}", requireLength(lengthSettings.getSlideNarrationLength(), creatorId, "SLIDE_NARRATION_LENGTH"));
        // v2.9.175: 슬라이드 나레이션 최대 길이 (130%)
        int slideMax = (int) (lengthSettings.getSlideNarrationLength() * 1.3);
        result = result.replace("{{SLIDE_NARRATION_MAX}}", String.valueOf(slideMax));
        result = result.replace("{{NARRATION_EXPAND_LENGTH}}", requireLength(lengthSettings.getNarrationExpandLength(), creatorId, "NARRATION_EXPAND_LENGTH"));

        // 썸네일 관련 길이 (1개)
        result = result.replace("{{THUMBNAIL_HOOK_LENGTH}}", requireLength(lengthSettings.getThumbnailHookLength(), creatorId, "THUMBNAIL_HOOK_LENGTH"));

        // 유튜브 메타데이터 관련 길이 (4개)
        result = result.replace("{{YOUTUBE_TITLE_MIN_LENGTH}}", requireLength(lengthSettings.getYoutubeTitleMinLength(), creatorId, "YOUTUBE_TITLE_MIN_LENGTH"));
        result = result.replace("{{YOUTUBE_TITLE_MAX_LENGTH}}", requireLength(lengthSettings.getYoutubeTitleMaxLength(), creatorId, "YOUTUBE_TITLE_MAX_LENGTH"));
        result = result.replace("{{YOUTUBE_DESCRIPTION_MIN_LENGTH}}", requireLength(lengthSettings.getYoutubeDescriptionMinLength(), creatorId, "YOUTUBE_DESCRIPTION_MIN_LENGTH"));
        result = result.replace("{{YOUTUBE_DESCRIPTION_MAX_LENGTH}}", requireLength(lengthSettings.getYoutubeDescriptionMaxLength(), creatorId, "YOUTUBE_DESCRIPTION_MAX_LENGTH"));

        // v2.9.159: 중첩 플레이스홀더 해결 (최대 2회 추가 패스)
        // 예: characterBlockFull 안에 {{IDENTITY_ANCHOR}}가 포함된 경우
        // 단일 패스에서는 CHARACTER_BLOCK 치환 후 내부의 IDENTITY_ANCHOR가 남음
        for (int pass = 0; pass < 2; pass++) {
            String before = result;
            // 중첩 가능한 필드만 재치환 (캐릭터 + 크리에이터 기본 정보)
            result = result.replace("{{IDENTITY_ANCHOR}}", nullSafe(prompts.getIdentityAnchor()));
            result = result.replace("{{CHARACTER_BLOCK}}", nullSafe(prompts.getCharacterBlockFull()));
            result = result.replace("{{NEGATIVE_PROMPTS_CHARACTER}}", nullSafe(prompts.getNegativePromptsCharacter()));
            result = result.replace("{{STYLE_LOCK}}", nullSafe(prompts.getStyleLock()));
            if (creator != null) {
                result = result.replace("{{CREATOR_NAME}}", nullSafe(creator.getCreatorName()));
                result = result.replace("{{YOUTUBE_CHANNEL}}", nullSafe(creator.getYoutubeChannel()));
            }
            if (result.equals(before)) break; // 변경 없으면 종료
        }

        return result;
    }

    /**
     * v2.9.152: null을 빈 문자열로 변환 (플레이스홀더 치환 안전)
     */
    private String nullSafe(String value) {
        return value != null ? value : "";
    }

    /**
     * v2.9.170: 길이 설정값 null 시 예외 발생 (하드코딩 폴백 제거)
     * 113-ensure-creator-prompts-length.sql 마이그레이션으로 모든 활성 크리에이터에 레코드 보장
     */
    private String requireLength(Integer value, Long creatorId, String fieldName) {
        if (value == null || value <= 0) {
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 길이 설정(" + fieldName + ")이 DB에 없습니다. creator_prompts_length 테이블을 확인하세요.");
        }
        return String.valueOf(value);
    }

    /**
     * 프롬프트 조회 (없으면 기본값 반환)
     */
    public String getPromptOrDefault(Long creatorId, String promptType, String defaultValue) {
        String prompt = getPrompt(creatorId, promptType);
        if (prompt == null || prompt.isBlank()) {
            log.debug("프롬프트 없음, 기본값 사용: creatorId={}, type={}", creatorId, promptType);
            return defaultValue;
        }
        return prompt;
    }

    /**
     * 시나리오 시스템 프롬프트 조회 (DB 필수 - 하드코딩 폴백 없음)
     * v2.9.105: creators 테이블에서 캐릭터 값 자동 주입
     * v2.9.152: 2단계 아키텍처 - Base 템플릿 + 크리에이터 값 조합
     * @throws ApiException DB에 프롬프트가 없으면 예외 발생
     */
    public String getScenarioSystemPrompt(Long creatorId) {
        String scenarioPrompt = getPrompt(creatorId, PROMPT_TYPE_SCENARIO);
        if (scenarioPrompt == null || scenarioPrompt.isBlank()) {
            log.error("[v2.9.152] 시나리오 프롬프트 없음: creatorId={}", creatorId);
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 시나리오 프롬프트가 DB에 없습니다. 관리자에게 문의하세요.");
        }
        // v2.9.152: 이미 composePrompt()에서 캐릭터 값이 치환됨
        return scenarioPrompt;
    }

    /**
     * 이미지 스타일 프롬프트 조회 (DB 필수 - 하드코딩 폴백 없음)
     * v2.9.105: creators 테이블에서 캐릭터 값 자동 주입
     * @throws ApiException DB에 프롬프트가 없으면 예외 발생
     */
    public String getImageStylePrompt(Long creatorId) {
        String prompt = getPrompt(creatorId, PROMPT_TYPE_IMAGE_STYLE);
        if (prompt == null || prompt.isBlank()) {
            log.error("이미지 스타일 프롬프트 없음 (DB 필수): creatorId={}", creatorId);
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 이미지 스타일 프롬프트가 DB에 없습니다. 관리자에게 문의하세요.");
        }
        // v2.9.159: composePrompt()에서 중첩 플레이스홀더까지 치환 완료됨
        return prompt;
    }

    /**
     * 이미지 네거티브 프롬프트 조회 (DB 필수 - 하드코딩 폴백 없음)
     * v2.9.105: creators 테이블에서 캐릭터 네거티브 프롬프트 자동 주입
     * @throws ApiException DB에 프롬프트가 없으면 예외 발생
     */
    public String getImageNegativePrompt(Long creatorId) {
        String prompt = getPrompt(creatorId, PROMPT_TYPE_IMAGE_NEGATIVE);
        if (prompt == null || prompt.isBlank()) {
            log.error("이미지 네거티브 프롬프트 없음 (DB 필수): creatorId={}", creatorId);
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 이미지 네거티브 프롬프트가 DB에 없습니다. 관리자에게 문의하세요.");
        }
        return prompt;
    }

    /**
     * 오프닝 영상 스타일 프롬프트 조회 (DB 필수 - 하드코딩 폴백 없음)
     * v2.9.105: creators 테이블에서 캐릭터 값 자동 주입
     * @throws ApiException DB에 프롬프트가 없으면 예외 발생
     */
    public String getOpeningVideoPrompt(Long creatorId) {
        String prompt = getPrompt(creatorId, PROMPT_TYPE_OPENING_VIDEO);
        if (prompt == null || prompt.isBlank()) {
            log.error("오프닝 영상 프롬프트 없음 (DB 필수): creatorId={}", creatorId);
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 오프닝 영상 프롬프트가 DB에 없습니다. 관리자에게 문의하세요.");
        }
        return prompt;
    }

    /**
     * TTS 지시 프롬프트 조회 (DB 필수 - 하드코딩 폴백 없음)
     * v2.9.14: 선택 → 필수로 변경 (100% DB 기반)
     * v2.9.105: creators 테이블에서 TTS 페르소나 자동 주입
     * @throws ApiException DB에 프롬프트가 없으면 예외 발생
     */
    public String getTtsInstruction(Long creatorId) {
        String prompt = getPrompt(creatorId, PROMPT_TYPE_TTS_INSTRUCTION);
        if (prompt == null || prompt.isBlank()) {
            log.error("TTS 지시 프롬프트 없음 (DB 필수): creatorId={}", creatorId);
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 TTS 지시 프롬프트가 DB에 없습니다. 관리자에게 문의하세요.");
        }
        return prompt;
    }

    /**
     * v2.9.14: 이미지 안전 폴백 프롬프트 조회 (DB 필수 - 하드코딩 폴백 없음)
     * 콘텐츠 필터링 실패 시 사용되는 안전한 프롬프트 템플릿
     * 플레이스홀더: {{NARRATION}}
     * v2.9.152: 2단계 아키텍처 - safety_fallback_prompt 컬럼에서 직접 조회
     * @throws ApiException DB에 프롬프트가 없으면 예외 발생
     */
    public String getImageSafetyFallback(Long creatorId) {
        CreatorPrompt prompts = getCreatorPrompts(getSafeCreatorId(creatorId));
        if (prompts == null) {
            log.error("[v2.9.152] 크리에이터 프롬프트 없음: creatorId={}", creatorId);
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 프롬프트가 DB에 없습니다. 관리자에게 문의하세요.");
        }
        String fallbackPrompt = prompts.getSafetyFallbackPrompt();
        if (fallbackPrompt == null || fallbackPrompt.isBlank()) {
            log.error("[v2.9.152] 안전 폴백 프롬프트 없음: creatorId={}", creatorId);
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 안전 폴백 프롬프트가 DB에 없습니다. 관리자에게 문의하세요.");
        }
        return injectFixedCharacterValues(fallbackPrompt, creatorId);
    }

    /**
     * v2.9.14: 이미지 안전 폴백 프롬프트 생성 (템플릿 + 플레이스홀더 치환)
     * @param creatorId 크리에이터 ID
     * @param narration 나레이션 텍스트 (시나리오 일관성 유지용)
     * @return 플레이스홀더가 치환된 안전 폴백 프롬프트
     */
    public String buildImageSafetyFallbackPrompt(Long creatorId, String narration) {
        String template = getImageSafetyFallback(creatorId);
        return template.replace("{{NARRATION}}", narration != null ? narration : "");
    }

    /**
     * v2.9.114: Safety Filter AI 지시문 조회 (DB 필수 - 하드코딩 폴백 없음)
     * 안전 필터에 걸렸을 때 AI에게 프롬프트 수정을 요청하는 시스템 지시문
     * 플레이스홀더: {{ASPECT_RATIO}}
     * v2.9.152: 2단계 아키텍처 - safety_modification_rules 컬럼에서 직접 조회
     * @throws ApiException DB에 프롬프트가 없으면 예외 발생
     */
    public String getSafetyFilterInstruction(Long creatorId) {
        CreatorPrompt prompts = getCreatorPrompts(getSafeCreatorId(creatorId));
        if (prompts == null) {
            log.error("[v2.9.152] 크리에이터 프롬프트 없음: creatorId={}", creatorId);
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 프롬프트가 DB에 없습니다. 관리자에게 문의하세요.");
        }
        String filterInstruction = prompts.getSafetyModificationRules();
        if (filterInstruction == null || filterInstruction.isBlank()) {
            log.error("[v2.9.152] 안전 필터 지시문 없음: creatorId={}", creatorId);
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 안전 필터 지시문이 DB에 없습니다. 관리자에게 문의하세요.");
        }
        return filterInstruction;
    }

    /**
     * v2.9.114: Safety Filter AI 지시문 빌드 (플레이스홀더 치환)
     * @param creatorId 크리에이터 ID
     * @param aspectRatio 화면 비율 (16:9 또는 9:16)
     * @return 플레이스홀더가 치환된 Safety Filter 지시문
     */
    public String buildSafetyFilterInstruction(Long creatorId, String aspectRatio) {
        String template = getSafetyFilterInstruction(creatorId);
        String orientationText = "9:16".equals(aspectRatio) ? "vertical 9:16" : "horizontal 16:9";
        return template.replace("{{ASPECT_RATIO}}", orientationText);
    }

    /**
     * v2.9.27: 썸네일 프롬프트 조회 (DB 필수 - 하드코딩 폴백 없음)
     * v2.9.105: creators 테이블에서 캐릭터 값 자동 주입
     * @throws ApiException DB에 프롬프트가 없으면 예외 발생
     */
    public String getThumbnailPrompt(Long creatorId) {
        String prompt = getPrompt(creatorId, PROMPT_TYPE_THUMBNAIL);
        if (prompt == null || prompt.isBlank()) {
            log.error("썸네일 프롬프트 없음 (DB 필수): creatorId={}", creatorId);
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 썸네일 프롬프트가 DB에 없습니다. 관리자에게 문의하세요.");
        }
        return prompt;
    }

    /**
     * v2.9.13: 시나리오 사용자 프롬프트 템플릿 조회 (DB 필수 - 하드코딩 폴백 없음)
     * 플레이스홀더: {{USER_INPUT}}, {{SLIDE_COUNT}}, {{DURATION_MINUTES}}, {{CURRENT_YEAR}}
     * v2.9.152: 2단계 아키텍처 - scenario_user_template 컬럼에서 직접 조회
     * @throws ApiException DB에 템플릿이 없으면 예외 발생
     */
    public String getScenarioUserTemplate(Long creatorId) {
        CreatorPrompt prompts = getCreatorPrompts(getSafeCreatorId(creatorId));
        if (prompts == null) {
            log.error("[v2.9.152] 크리에이터 프롬프트 없음: creatorId={}", creatorId);
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 프롬프트가 DB에 없습니다. 관리자에게 문의하세요.");
        }
        String userTemplate = prompts.getScenarioUserTemplate();
        if (userTemplate == null || userTemplate.isBlank()) {
            log.error("[v2.9.152] 시나리오 사용자 템플릿 없음: creatorId={}", creatorId);
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 시나리오 사용자 템플릿이 DB에 없습니다. 관리자에게 문의하세요.");
        }
        return userTemplate;
    }

    /**
     * v2.9.13: 시나리오 사용자 프롬프트 생성 (템플릿 + 플레이스홀더 치환)
     * @param creatorId 크리에이터 ID
     * @param userInput 사용자 입력 (해시태그 + 채팅 내용)
     * @param slideCount 슬라이드 수
     * @param durationMinutes 영상 길이 (분)
     * @param currentYear 현재 연도
     * @return 플레이스홀더가 치환된 사용자 프롬프트
     */
    public String buildScenarioUserPrompt(Long creatorId, String userInput, int slideCount, int durationMinutes, int currentYear) {
        String template = getScenarioUserTemplate(creatorId);

        return template
            .replace("{{USER_INPUT}}", userInput != null ? userInput : "")
            .replace("{{SLIDE_COUNT}}", String.valueOf(slideCount))
            .replace("{{DURATION_MINUTES}}", String.valueOf(durationMinutes))
            .replace("{{CURRENT_YEAR}}", String.valueOf(currentYear))
            .replace("{{PREV_YEAR}}", String.valueOf(currentYear - 1))
            .replace("{{NEXT_YEAR}}", String.valueOf(currentYear + 1));
    }

    /**
     * v2.9.98: 나레이션 확장 프롬프트 템플릿 조회 (DB 필수 - 하드코딩 폴백 없음)
     * 플레이스홀더: {{EXPAND_LENGTH}}, {{USER_INPUT}}, {{SCENARIO_TITLE}},
     *              {{SCENARIO_HOOK}}, {{SLIDE_COUNT}}, {{SCENARIO_CONTEXT}},
     *              {{PREVIOUS_SLIDE_INFO}}, {{SLIDE_NUMBER}}, {{IMAGE_PROMPT}},
     *              {{ORIGINAL_NARRATION}}, {{NEXT_SLIDE_INFO}}
     * v2.9.105: creators 테이블에서 TTS 페르소나/말투 자동 주입
     * @throws ApiException DB에 템플릿이 없으면 예외 발생
     */
    public String getNarrationExpandPrompt(Long creatorId) {
        String template = getPrompt(creatorId, PROMPT_TYPE_NARRATION_EXPAND);
        if (template == null || template.isBlank()) {
            log.error("나레이션 확장 프롬프트 없음 (DB 필수): creatorId={}", creatorId);
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 나레이션 확장 프롬프트가 DB에 없습니다. 관리자에게 문의하세요.");
        }
        return template;
    }

    /**
     * v2.9.98: 나레이션 확장 프롬프트 생성 (템플릿 + 플레이스홀더 치환)
     * @param creatorId 크리에이터 ID
     * @param expandLength 목표 나레이션 길이
     * @param userInput 사용자 원본 입력
     * @param scenarioTitle 시나리오 제목
     * @param scenarioHook 시나리오 후킹
     * @param slideCount 총 슬라이드 수
     * @param scenarioContext 전체 스토리 흐름
     * @param previousSlideInfo 이전 슬라이드 정보 (null 가능)
     * @param slideNumber 현재 슬라이드 번호
     * @param imagePrompt 현재 슬라이드 이미지 프롬프트
     * @param originalNarration 현재 슬라이드 원본 나레이션
     * @param nextSlideInfo 다음 슬라이드 정보 (null 가능)
     * @return 플레이스홀더가 치환된 나레이션 확장 프롬프트
     */
    public String buildNarrationExpandPrompt(
            Long creatorId, int expandLength, String userInput, String scenarioTitle,
            String scenarioHook, int slideCount, String scenarioContext,
            String previousSlideInfo, int slideNumber, String imagePrompt,
            String originalNarration, String nextSlideInfo
    ) {
        String template = getNarrationExpandPrompt(creatorId);

        return template
            .replace("{{EXPAND_LENGTH}}", String.valueOf(expandLength))
            .replace("{{USER_INPUT}}", userInput != null ? userInput : "")
            .replace("{{SCENARIO_TITLE}}", scenarioTitle != null ? scenarioTitle : "")
            .replace("{{SCENARIO_HOOK}}", scenarioHook != null ? scenarioHook : "")
            .replace("{{SLIDE_COUNT}}", String.valueOf(slideCount))
            .replace("{{SCENARIO_CONTEXT}}", scenarioContext != null ? scenarioContext : "")
            .replace("{{PREVIOUS_SLIDE_INFO}}", previousSlideInfo != null ? previousSlideInfo : "")
            .replace("{{SLIDE_NUMBER}}", String.valueOf(slideNumber))
            .replace("{{IMAGE_PROMPT}}", imagePrompt != null ? imagePrompt : "")
            .replace("{{ORIGINAL_NARRATION}}", originalNarration != null ? originalNarration : "")
            .replace("{{NEXT_SLIDE_INFO}}", nextSlideInfo != null ? nextSlideInfo : "");
    }

    /**
     * v2.9.150: 참조 이미지 분석 프롬프트 템플릿 조회 (선택적 - 하드코딩 폴백 있음)
     * 플레이스홀더: {{USER_PROMPT}}
     * @return DB에 설정된 분석 프롬프트 또는 null (없으면 ReferenceImageService가 폴백 사용)
     */
    public String getReferenceImageAnalysisPrompt(Long creatorId) {
        String template = getPrompt(creatorId, PROMPT_TYPE_REFERENCE_IMAGE_ANALYSIS);
        if (template == null || template.isBlank()) {
            log.debug("[v2.9.150] 참조 이미지 분석 프롬프트 없음 (폴백 사용): creatorId={}", creatorId);
            return null;
        }
        log.info("[v2.9.150] 참조 이미지 분석 프롬프트 조회: creatorId={}, length={}", creatorId, template.length());
        return template;
    }

    /**
     * v2.9.150: 참조 이미지 분석 프롬프트 생성 (템플릿 + 플레이스홀더 치환)
     * @param creatorId 크리에이터 ID
     * @param userPrompt 사용자가 입력한 상품 설명 (null 가능)
     * @return 플레이스홀더가 치환된 분석 프롬프트 또는 null (폴백 사용)
     */
    public String buildReferenceImageAnalysisPrompt(Long creatorId, String userPrompt) {
        String template = getReferenceImageAnalysisPrompt(creatorId);
        if (template == null) {
            return null;  // 폴백 사용
        }
        String promptWithUser = template.replace("{{USER_PROMPT}}",
            userPrompt != null && !userPrompt.isBlank()
                ? "User's product description: " + userPrompt
                : "");
        return promptWithUser;
    }

    // ========== AI 모델 티어 조회 (v2.9.120) ==========

    /**
     * v2.9.120: 크리에이터의 AI 모델 티어 조회 (Caffeine 캐싱)
     * creators.model_tier_id -> ai_model 테이블에서 조회
     */
    private AiModel getAiModel(Long creatorId) {
        Creator creator = getCreator(getSafeCreatorId(creatorId));
        Long tierId = creator.getModelTierId();
        if (tierId == null) {
            tierId = 2L;  // 기본값: PRO 티어
        }
        final Long finalTierId = tierId;
        return aiModelCache.get(finalTierId,
            id -> aiModelMapper.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                    "AI 모델 티어를 찾을 수 없습니다: tierId=" + id)));
    }

    // ========== 모델 설정 조회 (v2.9.120: ai_model 테이블에서 조회) ==========

    /**
     * 크리에이터별 이미지 생성 모델 조회
     * v2.9.120: ai_model 테이블에서 조회
     */
    public String getImageModel(Long creatorId) {
        AiModel aiModel = getAiModel(creatorId);
        log.info("[v2.9.120] 이미지 모델: creatorId={}, tier={}, model={}",
            creatorId, aiModel.getTierCode(), aiModel.getImageModel());
        return aiModel.getImageModel();
    }

    /**
     * 크리에이터별 영상 생성 모델 조회
     * v2.9.120: ai_model 테이블에서 조회
     */
    public String getVideoModel(Long creatorId) {
        AiModel aiModel = getAiModel(creatorId);
        log.info("[v2.9.120] 영상 모델: creatorId={}, tier={}, model={}",
            creatorId, aiModel.getTierCode(), aiModel.getVideoModel());
        return aiModel.getVideoModel();
    }

    /**
     * v2.9.120: 크리에이터별 TTS 모델 조회
     */
    public String getTtsModel(Long creatorId) {
        AiModel aiModel = getAiModel(creatorId);
        log.info("[v2.9.120] TTS 모델: creatorId={}, tier={}, model={}",
            creatorId, aiModel.getTierCode(), aiModel.getTtsModel());
        return aiModel.getTtsModel();
    }

    /**
     * v2.9.120: 크리에이터별 TTS 폴백 모델 조회
     */
    public String getFallbackTtsModel(Long creatorId) {
        AiModel aiModel = getAiModel(creatorId);
        log.info("[v2.9.120] TTS 폴백 모델: creatorId={}, tier={}, model={}",
            creatorId, aiModel.getTierCode(), aiModel.getFallbackTtsModel());
        return aiModel.getFallbackTtsModel();
    }

    /**
     * v2.9.120: 크리에이터별 이미지 폴백 모델 조회
     */
    public String getFallbackImageModel(Long creatorId) {
        AiModel aiModel = getAiModel(creatorId);
        log.info("[v2.9.120] 이미지 폴백 모델: creatorId={}, tier={}, model={}",
            creatorId, aiModel.getTierCode(), aiModel.getFallbackImageModel());
        return aiModel.getFallbackImageModel();
    }

    /**
     * v2.9.120: 크리에이터별 영상 폴백 모델 조회
     */
    public String getFallbackVideoModel(Long creatorId) {
        AiModel aiModel = getAiModel(creatorId);
        log.info("[v2.9.120] 영상 폴백 모델: creatorId={}, tier={}, model={}",
            creatorId, aiModel.getTierCode(), aiModel.getFallbackVideoModel());
        return aiModel.getFallbackVideoModel();
    }

    /**
     * v2.9.120: 크리에이터별 시나리오 생성 모델 조회
     */
    public String getScenarioModel(Long creatorId) {
        AiModel aiModel = getAiModel(creatorId);
        log.info("[v2.9.120] 시나리오 모델: creatorId={}, tier={}, model={}",
            creatorId, aiModel.getTierCode(), aiModel.getScenarioModel());
        return aiModel.getScenarioModel();
    }

    /**
     * v2.9.120: 크리에이터별 시나리오 폴백 모델 조회
     */
    public String getFallbackScenarioModel(Long creatorId) {
        AiModel aiModel = getAiModel(creatorId);
        log.info("[v2.9.120] 시나리오 폴백 모델: creatorId={}, tier={}, model={}",
            creatorId, aiModel.getTierCode(), aiModel.getFallbackScenarioModel());
        return aiModel.getFallbackScenarioModel();
    }

    /**
     * v2.9.120: ULTRA 티어 여부 확인
     * 이미지 업로드 허용 등 프리미엄 기능 제어에 사용
     * @return ULTRA 티어인 경우 true
     */
    public boolean isUltraTier(Long creatorId) {
        AiModel aiModel = getAiModel(creatorId);
        boolean isUltra = "ULTRA".equals(aiModel.getTierCode());
        log.debug("[v2.9.120] ULTRA 티어 확인: creatorId={}, tier={}, isUltra={}",
            creatorId, aiModel.getTierCode(), isUltra);
        return isUltra;
    }

    /**
     * v2.9.120: 크리에이터의 AI 모델 티어 코드 조회
     * @return 티어 코드 (BASIC, PRO, ULTRA)
     */
    public String getTierCode(Long creatorId) {
        AiModel aiModel = getAiModel(creatorId);
        return aiModel.getTierCode();
    }

    /**
     * v2.9.72: 크리에이터별 나레이션 확장 모델 조회
     * Pro로 시나리오 생성 후, Flash로 각 슬라이드 나레이션을 5000-7000자로 확장
     * 기본적으로 fallback_scenario_model(Flash)을 사용
     * @return 나레이션 확장용 모델 (Flash 모델)
     */
    public String getNarrationExpandModel(Long creatorId) {
        // 나레이션 확장에는 Flash 모델 사용 (비용 효율적 + 빠름)
        String model = getFallbackScenarioModel(creatorId);
        log.info("[v2.9.72] 나레이션 확장 모델: creatorId={}, model={}", creatorId, model);
        return model;
    }

    /**
     * v2.9.4: 크리에이터별 TTS 음성 조회 (오디오북 스타일 최적화)
     * v2.9.128: tts_instruction 프롬프트에서 [VOICE] 섹션 파싱
     * 참조: https://ai.google.dev/gemini-api/docs/speech-generation
     *
     * tts_instruction 프롬프트 포맷:
     * [VOICE]
     * Sulafat
     *
     * [PERSONA]
     * 따뜻하고 감성적인 40대 여성 나레이터
     *
     * [INSTRUCTION]
     * ...
     *
     * @return tts_instruction 프롬프트의 [VOICE] 섹션에서 파싱한 음성 이름
     * @throws ApiException tts_instruction이 없거나 [VOICE] 섹션이 없으면 예외 발생
     */
    public String getTtsVoice(Long creatorId) {
        String ttsInstruction = getPrompt(creatorId, PROMPT_TYPE_TTS_INSTRUCTION);
        if (ttsInstruction == null || ttsInstruction.isBlank()) {
            log.error("[v2.9.128] TTS instruction 프롬프트 없음: creatorId={}", creatorId);
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 TTS instruction 프롬프트가 DB에 없습니다. 관리자에게 문의하세요.");
        }

        // [VOICE] 섹션에서 음성 이름 파싱
        String voice = parseVoiceFromInstruction(ttsInstruction);
        if (voice == null || voice.isBlank()) {
            log.error("[v2.9.128] TTS instruction에 [VOICE] 섹션 없음: creatorId={}", creatorId);
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 TTS instruction에 [VOICE] 섹션이 없습니다. 관리자에게 문의하세요.");
        }

        log.info("[v2.9.128] TTS 음성 파싱 완료: creatorId={}, voice={}", creatorId, voice);
        return voice;
    }

    /**
     * v2.9.129: scenario_prompt에서 특정 섹션 파싱
     * @param scenarioPrompt 시나리오 프롬프트 전체 내용 ([SYSTEM] + [USER_TEMPLATE] 섹션 포함)
     * @param sectionName 섹션 이름 (SYSTEM 또는 USER_TEMPLATE)
     * @return 해당 섹션의 내용 또는 null
     */
    /**
     * v2.9.138: 버그 수정 - [필수] 같은 일반 텍스트가 섹션 구분자로 인식되는 문제 해결
     * 이제 알려진 섹션 헤더([SYSTEM], [USER_TEMPLATE])만 구분자로 인식
     */
    private String parseScenarioSection(String scenarioPrompt, String sectionName) {
        if (scenarioPrompt == null) {
            return null;
        }

        String sectionHeader = "[" + sectionName + "]";
        int sectionStart = scenarioPrompt.indexOf(sectionHeader);
        if (sectionStart == -1) {
            return null;
        }

        // 섹션 헤더 다음부터 다음 알려진 섹션 또는 끝까지
        int contentStart = sectionStart + sectionHeader.length();

        // v2.9.138: 알려진 섹션 헤더만 구분자로 인식 (일반 [텍스트] 무시)
        String[] knownSections = {"[SYSTEM]", "[USER_TEMPLATE]"};
        int nextSection = -1;
        for (String known : knownSections) {
            if (known.equals(sectionHeader)) {
                continue; // 현재 섹션은 건너뜀
            }
            int pos = scenarioPrompt.indexOf("\n" + known, contentStart);
            if (pos != -1 && (nextSection == -1 || pos < nextSection)) {
                nextSection = pos;
            }
        }

        String sectionContent;
        if (nextSection == -1) {
            sectionContent = scenarioPrompt.substring(contentStart);
        } else {
            sectionContent = scenarioPrompt.substring(contentStart, nextSection);
        }

        // 앞뒤 공백 제거
        return sectionContent.trim();
    }

    /**
     * v2.9.130: safety_prompt에서 특정 섹션 파싱
     * @param safetyPrompt 안전 프롬프트 전체 내용 ([FILTER_INSTRUCTION] + [FALLBACK] 섹션 포함)
     * @param sectionName 섹션 이름 (FILTER_INSTRUCTION 또는 FALLBACK)
     * @return 해당 섹션의 내용 또는 null
     */
    private String parseSafetySection(String safetyPrompt, String sectionName) {
        if (safetyPrompt == null) {
            return null;
        }

        String sectionHeader = "[" + sectionName + "]";
        int sectionStart = safetyPrompt.indexOf(sectionHeader);
        if (sectionStart == -1) {
            return null;
        }

        // 섹션 헤더 다음부터 다음 섹션([로 시작하는 줄) 또는 끝까지
        int contentStart = sectionStart + sectionHeader.length();
        int nextSection = safetyPrompt.indexOf("\n[", contentStart);
        String sectionContent;
        if (nextSection == -1) {
            sectionContent = safetyPrompt.substring(contentStart);
        } else {
            sectionContent = safetyPrompt.substring(contentStart, nextSection);
        }

        // 앞뒤 공백 제거
        return sectionContent.trim();
    }

    /**
     * v2.9.128: tts_instruction 프롬프트에서 음성 이름 파싱
     * v2.9.158: XML 형식 (<voice>...</voice>) 지원 추가
     * @param ttsInstruction TTS instruction 프롬프트 전체 내용
     * @return 음성 이름 (예: Sulafat, Charon) 또는 null
     */
    private String parseVoiceFromInstruction(String ttsInstruction) {
        if (ttsInstruction == null) {
            return null;
        }

        // v2.9.158: XML 형식 먼저 시도 (<voice>...</voice>)
        String voice = parseXmlTag(ttsInstruction, "voice");
        if (voice != null && !voice.isBlank()) {
            log.debug("[v2.9.158] XML 형식에서 음성 파싱: {}", voice);
            return voice.trim();
        }

        // 기존 [VOICE] 섹션 형식도 지원 (하위 호환)
        int voiceStart = ttsInstruction.indexOf("[VOICE]");
        if (voiceStart == -1) {
            return null;
        }

        // [VOICE] 다음 줄부터 다음 섹션([로 시작) 또는 끝까지
        int contentStart = voiceStart + "[VOICE]".length();
        int nextSection = ttsInstruction.indexOf("\n[", contentStart);
        String voiceSection;
        if (nextSection == -1) {
            voiceSection = ttsInstruction.substring(contentStart);
        } else {
            voiceSection = ttsInstruction.substring(contentStart, nextSection);
        }

        // 줄바꿈으로 분리하고 첫 번째 비어있지 않은 줄 반환
        String[] lines = voiceSection.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }

        return null;
    }

    /**
     * v2.9.158: XML 태그에서 내용 추출
     * @param content 전체 내용
     * @param tagName 태그 이름 (예: "voice")
     * @return 태그 내용 또는 null
     */
    private String parseXmlTag(String content, String tagName) {
        if (content == null || tagName == null) {
            return null;
        }

        String openTag = "<" + tagName + ">";
        String closeTag = "</" + tagName + ">";

        int startIdx = content.indexOf(openTag);
        if (startIdx == -1) {
            return null;
        }

        int contentStart = startIdx + openTag.length();
        int endIdx = content.indexOf(closeTag, contentStart);
        if (endIdx == -1) {
            return null;
        }

        return content.substring(contentStart, endIdx).trim();
    }

    // ========== 길이 설정 조회 (v2.9.155: creator_prompts_length 테이블에서 조회) ==========

    /**
     * v2.9.170: 크리에이터별 오프닝 나레이션 글자 수 조회
     * creator_prompts_length 테이블에서 조회 (하드코딩 폴백 제거)
     */
    public int getOpeningNarrationLength(Long creatorId) {
        CreatorPromptLength lengthSettings = getCreatorPromptLength(getSafeCreatorId(creatorId));
        if (lengthSettings == null || lengthSettings.getOpeningNarrationLength() == null || lengthSettings.getOpeningNarrationLength() <= 0) {
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 오프닝 나레이션 길이 설정이 DB에 없습니다. creator_prompts_length 테이블을 확인하세요.");
        }
        return lengthSettings.getOpeningNarrationLength();
    }

    /**
     * v2.9.170: 크리에이터별 슬라이드 나레이션 글자 수 조회
     * creator_prompts_length 테이블에서 조회 (하드코딩 폴백 제거)
     */
    public int getSlideNarrationLength(Long creatorId) {
        CreatorPromptLength lengthSettings = getCreatorPromptLength(getSafeCreatorId(creatorId));
        if (lengthSettings == null || lengthSettings.getSlideNarrationLength() == null || lengthSettings.getSlideNarrationLength() <= 0) {
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 슬라이드 나레이션 길이 설정이 DB에 없습니다. creator_prompts_length 테이블을 확인하세요.");
        }
        return lengthSettings.getSlideNarrationLength();
    }

    /**
     * v2.9.170: 크리에이터별 나레이션 확장 시 요청 글자 수 조회
     * creator_prompts_length 테이블에서 조회 (하드코딩 폴백 제거)
     */
    public int getNarrationExpandLength(Long creatorId) {
        CreatorPromptLength lengthSettings = getCreatorPromptLength(getSafeCreatorId(creatorId));
        if (lengthSettings == null || lengthSettings.getNarrationExpandLength() == null || lengthSettings.getNarrationExpandLength() <= 0) {
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 나레이션 확장 길이 설정이 DB에 없습니다. creator_prompts_length 테이블을 확인하세요.");
        }
        return lengthSettings.getNarrationExpandLength();
    }

    /**
     * v2.9.170: 크리에이터별 썸네일 후킹문구 글자 수 조회
     * creator_prompts_length 테이블에서 조회 (하드코딩 폴백 제거)
     */
    public int getThumbnailHookLength(Long creatorId) {
        CreatorPromptLength lengthSettings = getCreatorPromptLength(getSafeCreatorId(creatorId));
        if (lengthSettings == null || lengthSettings.getThumbnailHookLength() == null || lengthSettings.getThumbnailHookLength() <= 0) {
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 썸네일 후킹문구 길이 설정이 DB에 없습니다. creator_prompts_length 테이블을 확인하세요.");
        }
        return lengthSettings.getThumbnailHookLength();
    }

    /**
     * v2.9.170: 크리에이터별 유튜브 제목 최소/최대 글자 수 조회
     * creator_prompts_length 테이블에서 조회 (하드코딩 폴백 제거)
     */
    public int[] getYoutubeTitleLength(Long creatorId) {
        CreatorPromptLength lengthSettings = getCreatorPromptLength(getSafeCreatorId(creatorId));
        if (lengthSettings == null
            || lengthSettings.getYoutubeTitleMinLength() == null || lengthSettings.getYoutubeTitleMinLength() <= 0
            || lengthSettings.getYoutubeTitleMaxLength() == null || lengthSettings.getYoutubeTitleMaxLength() <= 0) {
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 유튜브 제목 길이 설정이 DB에 없습니다. creator_prompts_length 테이블을 확인하세요.");
        }
        return new int[] { lengthSettings.getYoutubeTitleMinLength(), lengthSettings.getYoutubeTitleMaxLength() };
    }

    /**
     * v2.9.170: 크리에이터별 유튜브 설명 최소/최대 글자 수 조회
     * creator_prompts_length 테이블에서 조회 (하드코딩 폴백 제거)
     */
    public int[] getYoutubeDescriptionLength(Long creatorId) {
        CreatorPromptLength lengthSettings = getCreatorPromptLength(getSafeCreatorId(creatorId));
        if (lengthSettings == null
            || lengthSettings.getYoutubeDescriptionMinLength() == null || lengthSettings.getYoutubeDescriptionMinLength() <= 0
            || lengthSettings.getYoutubeDescriptionMaxLength() == null || lengthSettings.getYoutubeDescriptionMaxLength() <= 0) {
            throw new ApiException(ErrorCode.NOT_FOUND,
                "크리에이터 " + creatorId + "의 유튜브 설명 길이 설정이 DB에 없습니다. creator_prompts_length 테이블을 확인하세요.");
        }
        return new int[] { lengthSettings.getYoutubeDescriptionMinLength(), lengthSettings.getYoutubeDescriptionMaxLength() };
    }

    /**
     * 크리에이터에 DB 프롬프트가 있는지 확인
     */
    public boolean hasCustomPrompt(Long creatorId, String promptType) {
        String prompt = getPrompt(creatorId, promptType);
        return prompt != null && !prompt.isBlank();
    }

    // ========== 캐시 관리 ==========

    /**
     * 캐시 수동 초기화 (필요 시 호출)
     */
    public void clearCache() {
        creatorCache.invalidateAll();
        promptCache.invalidateAll();
        baseTemplateCache.invalidateAll();  // v2.9.152: Base 템플릿 캐시 추가
        lengthCache.invalidateAll();  // v2.9.155: 길이 설정 캐시 추가
        allCreatorsCache.invalidateAll();
        aiModelCache.invalidateAll();  // v2.9.120: AI 모델 캐시 추가
        log.info("크리에이터 설정 캐시 수동 초기화됨 (Base 템플릿, 길이 설정, AI 모델 캐시 포함)");
    }

    /**
     * 캐시 통계 조회 (디버깅용)
     */
    public String getCacheStats() {
        return String.format("creatorCache: %d, promptCache: %d, baseTemplateCache: %d, lengthCache: %d, allCreatorsCache: %d, aiModelCache: %d",
            creatorCache.estimatedSize(), promptCache.estimatedSize(),
            baseTemplateCache.estimatedSize(), lengthCache.estimatedSize(),
            allCreatorsCache.estimatedSize(), aiModelCache.estimatedSize());
    }
}
