package com.aivideo.api.service.subtitle;

import com.aivideo.api.dto.VideoDto;
import com.aivideo.api.entity.VideoFont;
import com.aivideo.api.entity.VideoFormat;
import com.aivideo.api.entity.VideoSubtitle;
import com.aivideo.api.mapper.VideoFormatMapper;
import com.aivideo.api.mapper.VideoSubtitleMapper;
import com.aivideo.api.service.CreatorConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 자막 생성 서비스 구현
 * ASS subtitle generation service
 * v2.9.25: 영상 포맷별 동적 해상도 지원
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubtitleServiceImpl implements SubtitleService {

    private final VideoFormatMapper videoFormatMapper;
    private final VideoSubtitleMapper videoSubtitleMapper;
    private final CreatorConfigService creatorConfigService;

    // v2.9.25: 현재 영상 포맷 ID
    private static final ThreadLocal<Long> currentFormatId = new ThreadLocal<>();

    // ========== v2.9.25: 포맷 관련 메서드 ==========

    /**
     * 현재 영상 포맷 ID 설정 (ContentService에서 호출)
     */
    public void setCurrentFormatId(Long formatId) {
        currentFormatId.set(formatId);
        log.info("[SUBTITLE] formatId set: {}", formatId);
    }

    /**
     * 현재 포맷의 해상도 조회
     * @return int[] {width, height}
     */
    private int[] getCurrentResolution() {
        Long formatId = currentFormatId.get();
        if (formatId == null) {
            return new int[]{1920, 1080};  // 기본값: 16:9
        }
        return videoFormatMapper.findById(formatId)
            .map(f -> new int[]{f.getWidth(), f.getHeight()})
            .orElse(new int[]{1920, 1080});
    }

    /**
     * 세로형 영상 여부 확인
     */
    private boolean isVerticalFormat() {
        int[] resolution = getCurrentResolution();
        return resolution[1] > resolution[0];  // height > width
    }

    /**
     * 동적 ASS 헤더 생성 (포맷별 해상도/폰트 크기 조정)
     */
    private String generateAssHeader() {
        int[] resolution = getCurrentResolution();
        int width = resolution[0];
        int height = resolution[1];

        // v2.9.27: 세로형 영상(쇼츠)은 폰트 크기 확대 + 자막 위치 상향
        // - 화면이 좁아서 폰트를 더 크게 해야 잘 보임
        // - 하단 UI(좋아요, 댓글 등) 때문에 자막을 중앙 가까이 배치
        double fontScale = isVerticalFormat() ? 1.5 : 1.0;  // 쇼츠: 1.5배 크게
        int defaultFontSize = (int)(120 * fontScale);
        int openingFontSize = (int)(140 * fontScale);
        int emotionFontSize = (int)(130 * fontScale);

        // v2.9.27: 세로형은 자막을 훨씬 위로 (화면 중앙~중하단 지점)
        // MarginV가 클수록 화면 하단에서 더 멀리 = 위로 올라감
        // 쇼츠(1080x1920): 하단 300-400px는 UI로 가려짐 → 자막을 중하단(40-60% 지점)에 배치
        int marginV = isVerticalFormat() ? 700 : 80;  // 쇼츠: 700px 위로, 일반: 80px (유튜브 스타일)
        int marginVOpening = isVerticalFormat() ? 800 : 100;  // 쇼츠 오프닝: 800px 위로, 일반: 100px

        log.info("[SUBTITLE] Generating ASS header: {}x{}, fontScale: {}, vertical: {}",
                width, height, fontScale, isVerticalFormat());

        return String.format("""
            [Script Info]
            Title: AI Generated Video Subtitles
            ScriptType: v4.00+
            PlayResX: %d
            PlayResY: %d
            WrapStyle: 0
            ScaledBorderAndShadow: yes

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Noto Sans CJK KR,%d,&H00FFFFFF,&H000000FF,&H00000000,&HB0000000,1,0,0,0,100,100,3,0,1,6,4,2,10,10,%d,1
            Style: Opening,Noto Sans CJK KR,%d,&H00FFFFFF,&H000000FF,&H00000000,&HB0000000,1,0,0,0,100,100,3,0,1,7,5,2,10,10,%d,1
            Style: Emotion,Noto Sans CJK KR,%d,&H0000FFFF,&H000000FF,&H00000000,&HB0000000,1,0,0,0,100,100,3,0,1,6,4,2,10,10,%d,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            """, width, height, defaultFontSize, marginV, openingFontSize, marginVOpening, emotionFontSize, marginV);
    }

    // 유튜브 스타일 대형 폰트 크기
    private static final int FONT_SIZE_OPENING = 140;    // 오프닝 자막 (매우 크게)
    private static final int FONT_SIZE_DEFAULT = 120;    // 기본 자막 (크게)
    private static final int FONT_SIZE_EMOTION = 130;    // 감정 자막 (강조)

    // 한 줄당 최대 글자 수 - 더 많은 글자 허용 (좌우 꽉 참)
    private static final int MAX_CHARS_PER_LINE = 35;

    // 한국어 TTS 평균 발화 속도 (초당 글자 수) - v2.9.179: DB 기본값, 국가별로 creatorConfigService에서 조회
    // Gemini TTS 기준: 약 4-5자/초 (분당 240-300자)
    private static final double DEFAULT_TTS_CHARS_PER_SECOND = 4.5;

    // 문장 간 자연스러운 쉼 (초)
    private static final double PAUSE_BETWEEN_SENTENCES = 0.3;

    @Override
    public String generateSubtitles(List<VideoDto.SlideScene> slides, String openingText, int openingDuration) {
        log.info("Generating ASS subtitles for {} slides, opening: {}s (text-based timing)", slides.size(), openingDuration);

        StringBuilder ass = new StringBuilder();
        // v2.9.25: 포맷별 동적 ASS 헤더 사용
        ass.append(generateAssHeader());

        double currentTime = 0;

        // 1. 오프닝 자막 (텍스트 길이 기반 타이밍)
        if (openingText != null && !openingText.isEmpty()) {
            List<String> openingLines = splitIntoSubtitleLines(openingText);

            // 오프닝은 지정된 시간 내에서 텍스트 기반 타이밍
            double totalOpeningTextDuration = calculateTextDuration(openingText);
            double scaleFactor = totalOpeningTextDuration > openingDuration ?
                    (double) openingDuration / totalOpeningTextDuration : 1.0;

            double lineStartTime = 0;
            for (int i = 0; i < openingLines.size(); i++) {
                String line = openingLines.get(i);
                double lineDuration = calculateTextDuration(line) * scaleFactor;
                double startTime = lineStartTime;
                double endTime = startTime + lineDuration;

                ass.append(formatAssDialogue("Opening", startTime, endTime, line));
                lineStartTime = endTime + (PAUSE_BETWEEN_SENTENCES * scaleFactor);
            }

            // 오프닝 종료 시간은 지정된 시간으로 맞춤
            currentTime = openingDuration;
        }

        // 2. 슬라이드별 자막 (텍스트 길이 기반 타이밍)
        for (int i = 0; i < slides.size(); i++) {
            VideoDto.SlideScene slide = slides.get(i);
            String narration = slide.getNarration();
            int slotDuration = slide.getDurationSeconds() > 0 ? slide.getDurationSeconds() : 10;

            // 나레이션이 유효한지 확인 (null, 빈 문자열, 구두점만 있는 경우 제외)
            boolean hasValidNarration = narration != null
                    && !narration.trim().isEmpty()
                    && !narration.trim().matches("^[.!?…\\s]+$")
                    && narration.trim().length() >= 2;

            if (hasValidNarration) {
                List<String> lines = splitIntoSubtitleLines(narration);

                // 유효한 자막 라인이 있는 경우에만 처리
                if (!lines.isEmpty()) {
                    // 텍스트 기반 발화 시간 계산
                    double narrationDuration = calculateTextDuration(narration);

                    // 텍스트가 슬롯보다 길면 스케일 조정
                    double scaleFactor = narrationDuration > slotDuration ?
                            (double) slotDuration / narrationDuration : 1.0;

                    double lineStartTime = currentTime;

                    for (int j = 0; j < lines.size(); j++) {
                        String line = lines.get(j);
                        double lineDuration = calculateTextDuration(line) * scaleFactor;
                        double startTime = lineStartTime;
                        double endTime = startTime + lineDuration;

                        // 감정적 문장 감지 (느낌표, 물음표, 말줄임표)
                        String style = detectEmotionalStyle(line);
                        ass.append(formatAssDialogue(style, startTime, endTime, line));

                        lineStartTime = endTime + (PAUSE_BETWEEN_SENTENCES * scaleFactor);
                    }
                }
            }

            // 슬라이드 종료 시간은 항상 슬롯 시간만큼 증가
            currentTime += slotDuration;
        }

        String result = ass.toString();
        log.info("Generated ASS subtitle: {} characters, total duration: {}s", result.length(), currentTime);
        return result;
    }

    /**
     * 텍스트 길이 기반 발화 시간 계산 (한국어 TTS 기준)
     */
    private double calculateTextDuration(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        // 공백과 특수문자 제외한 실제 글자 수
        String cleanText = text.replaceAll("[\\s\\p{Punct}]", "");
        int charCount = cleanText.length();

        // TTS 발화 시간 계산 (최소 1초)
        double duration = (double) charCount / DEFAULT_TTS_CHARS_PER_SECOND;

        // 문장 구두점에 따른 쉼 추가
        int pauseCount = 0;
        pauseCount += text.split("[.!?]", -1).length - 1; // 문장 종료
        pauseCount += text.split("[,，、]", -1).length - 1; // 짧은 쉼

        duration += pauseCount * 0.2;

        return Math.max(duration, 1.0);
    }

    @Override
    public String generateSubtitleLine(String text, double startTime, double endTime) {
        return formatAssDialogue("Default", startTime, endTime, formatForDisplay(text));
    }

    @Override
    public String generateSrtSubtitles(List<VideoDto.SlideScene> slides, String openingText, int openingDuration) {
        log.info("Generating SRT subtitles for {} slides", slides.size());

        StringBuilder srt = new StringBuilder();
        int index = 1;
        double currentTime = 0;

        // 1. 오프닝 자막
        if (openingText != null && !openingText.isEmpty()) {
            List<String> openingLines = splitIntoSubtitleLines(openingText);
            double openingLineTime = (double) openingDuration / openingLines.size();

            for (int i = 0; i < openingLines.size(); i++) {
                double startTime = i * openingLineTime;
                double endTime = (i + 1) * openingLineTime;
                srt.append(formatSrtEntry(index++, startTime, endTime, openingLines.get(i)));
            }
            currentTime = openingDuration;
        }

        // 2. 슬라이드별 자막
        for (VideoDto.SlideScene slide : slides) {
            String narration = slide.getNarration();
            int duration = slide.getDurationSeconds() > 0 ? slide.getDurationSeconds() : 10;

            if (narration != null && !narration.isEmpty()) {
                List<String> lines = splitIntoSubtitleLines(narration);
                double lineTime = (double) duration / lines.size();

                for (int j = 0; j < lines.size(); j++) {
                    double startTime = currentTime + (j * lineTime);
                    double endTime = currentTime + ((j + 1) * lineTime);
                    srt.append(formatSrtEntry(index++, startTime, endTime, lines.get(j)));
                }
            }

            currentTime += duration;
        }

        return srt.toString();
    }

    /**
     * 텍스트를 자막 라인으로 분할 (자동 줄바꿈)
     * 빈 문장, 점만 있는 문장 필터링
     * v2.9.3: 소수점(15.5%) 분할 버그 수정
     */
    private List<String> splitIntoSubtitleLines(String text) {
        List<String> lines = new ArrayList<>();

        // null 또는 빈 텍스트 체크
        if (text == null || text.trim().isEmpty()) {
            return lines;
        }

        // 문장 단위로 먼저 분리 (. ! ? 기준, 소수점 보호)
        // (?![0-9]) - 마침표 뒤 숫자가 오면 분할 안함 (15.5% 보호)
        String[] sentences = text.split("(?<=[.!?])(?![0-9])\\s*");

        for (String sentence : sentences) {
            sentence = sentence.trim();

            // 빈 문장, 점/느낌표/물음표만 있는 문장 스킵
            if (sentence.isEmpty()) continue;
            if (sentence.matches("^[.!?…]+$")) continue;  // 구두점만 있는 경우 스킵
            if (sentence.length() < 2) continue;  // 너무 짧은 문장 스킵

            // 문장이 너무 길면 추가 분할
            if (sentence.length() > MAX_CHARS_PER_LINE * 2) {
                // 쉼표나 조사 기준으로 분할
                String[] parts = sentence.split("(?<=[,，、])\\s*|(?<=는|을|를|이|가|에|로|의|와|과|도)\\s+");
                StringBuilder current = new StringBuilder();

                for (String part : parts) {
                    if (current.length() + part.length() > MAX_CHARS_PER_LINE * 2) {
                        if (current.length() > 0) {
                            String trimmed = current.toString().trim();
                            if (trimmed.length() >= 2 && !trimmed.matches("^[.!?…]+$")) {
                                lines.add(formatForDisplay(trimmed));
                            }
                            current = new StringBuilder();
                        }
                    }
                    current.append(part);
                }

                if (current.length() > 0) {
                    String trimmed = current.toString().trim();
                    if (trimmed.length() >= 2 && !trimmed.matches("^[.!?…]+$")) {
                        lines.add(formatForDisplay(trimmed));
                    }
                }
            } else {
                lines.add(formatForDisplay(sentence));
            }
        }

        // 빈 결과면 원본 텍스트 반환 (단, 의미있는 내용이 있을 때만)
        if (lines.isEmpty() && text.length() >= 2 && !text.matches("^[.!?…\\s]+$")) {
            lines.add(formatForDisplay(text));
        }

        return lines;
    }

    /**
     * 자막 표시용 텍스트 포맷팅
     * 한 줄이 너무 길면 줄바꿈 추가
     */
    private String formatForDisplay(String text) {
        if (text.length() <= MAX_CHARS_PER_LINE) {
            return text;
        }

        // 중간 지점에서 줄바꿈 (띄어쓰기 기준)
        int midPoint = text.length() / 2;
        int breakPoint = text.lastIndexOf(' ', midPoint + 5);

        if (breakPoint < midPoint - 10) {
            breakPoint = text.indexOf(' ', midPoint - 5);
        }

        if (breakPoint > 0 && breakPoint < text.length() - 1) {
            return text.substring(0, breakPoint).trim() + "\\N" + text.substring(breakPoint).trim();
        }

        return text;
    }

    /**
     * 감정적 스타일 감지
     */
    private String detectEmotionalStyle(String text) {
        // 강한 감정 표현 감지
        if (text.contains("!") || text.contains("?!") || text.contains("!!")) {
            return "Emotion";
        }
        // 극적인 말줄임표
        if (text.contains("...") || text.contains("…")) {
            return "Emotion";
        }
        return "Default";
    }

    /**
     * ASS 다이얼로그 라인 포맷팅
     */
    private String formatAssDialogue(String style, double startTime, double endTime, String text) {
        return String.format("Dialogue: 0,%s,%s,%s,,0,0,0,,%s%n",
                formatAssTime(startTime),
                formatAssTime(endTime),
                style,
                text.replace("\n", "\\N")
        );
    }

    /**
     * ASS 시간 형식 변환 (H:MM:SS.CC)
     */
    private String formatAssTime(double seconds) {
        int hours = (int) (seconds / 3600);
        int minutes = (int) ((seconds % 3600) / 60);
        double secs = seconds % 60;

        return String.format("%d:%02d:%05.2f", hours, minutes, secs);
    }

    // ========== v2.9.160: 개별 씬용 ASS 자막 생성 (ContentService에서 분리) ==========

    // v2.9.170: SUBTITLE_DELAY_OFFSET 제거 - detectSentenceBoundaries()가 정확한 경계를 반환하므로 오프셋 불필요

    @Override
    public String generateSceneSubtitle(String narration, Integer duration, List<double[]> speechSegments, Long formatId, Long videoSubtitleId, Integer fontSizeLevel, Integer subtitlePosition, Long fontId, Long creatorId, Double actualAudioDuration) {
        // v2.9.179: creatorId를 ThreadLocal에 저장 (분할 로직에서 국가별 설정 사용)
        currentCreatorId.set(creatorId);

        try {
            return generateSceneSubtitleInternal(narration, duration, speechSegments, formatId, videoSubtitleId, fontSizeLevel, subtitlePosition, fontId, creatorId, actualAudioDuration);
        } finally {
            currentCreatorId.remove();  // ThreadLocal 정리
        }
    }

    /**
     * v2.9.179: 실제 자막 생성 로직 (ThreadLocal 정리를 위해 분리)
     */
    private String generateSceneSubtitleInternal(String narration, Integer duration, List<double[]> speechSegments, Long formatId, Long videoSubtitleId, Integer fontSizeLevel, Integer subtitlePosition, Long fontId, Long creatorId, Double actualAudioDuration) {
        // v2.9.161: 자막 템플릿 조회 (null이면 기본 템플릿)
        VideoSubtitle template = resolveTemplate(videoSubtitleId);

        // v2.9.174: fontId로 폰트명 오버라이드 (자막 + 썸네일 동일 폰트 사용)
        String fontName = resolveFontName(fontId, template);

        // v2.9.160: formatId로 직접 해상도 조회 (ThreadLocal 미사용)
        int[] resolution = resolveResolution(formatId);
        boolean isVertical = resolution[1] > resolution[0];

        // v2.9.161: 템플릿 기반 폰트 크기/위치 (세로형일 때 별도 값 사용)
        int defaultFontSize = isVertical ? template.getFontSizeVertical() : template.getFontSize();
        int emotionFontSize = isVertical ? template.getFontSizeEmotionVertical() : template.getFontSizeEmotion();

        // v2.9.161: fontSizeLevel에 따른 배율 적용 (1=small 0.6x, 2=medium 0.8x, 3=large 1.0x)
        double fontSizeMultiplier = resolveFontSizeMultiplier(fontSizeLevel);
        defaultFontSize = (int) Math.round(defaultFontSize * fontSizeMultiplier);
        emotionFontSize = (int) Math.round(emotionFontSize * fontSizeMultiplier);
        int marginV = isVertical ? template.getMarginVVertical() : template.getMarginV();

        // v2.9.167: 사용자 선택 자막 위치 적용 (1=하단, 2=중앙, 3=상단)
        int alignment = resolveAlignment(subtitlePosition, template.getAlignment());
        marginV = resolvePositionMarginV(subtitlePosition, marginV, isVertical);
        log.info("[v2.9.174] Subtitle: font='{}', fontId={}, creatorId={}, position={}, alignment={}, marginV={}, vertical={}",
                fontName, fontId, creatorId, subtitlePosition, alignment, marginV, isVertical);

        StringBuilder ass = new StringBuilder();
        ass.append("[Script Info]\n");
        ass.append("ScriptType: v4.00+\n");
        ass.append(String.format("PlayResX: %d\n", resolution[0]));
        ass.append(String.format("PlayResY: %d\n", resolution[1]));
        ass.append("WrapStyle: 0\n\n");
        ass.append("[V4+ Styles]\n");
        ass.append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n");
        // v2.9.174: fontName을 VideoFont에서 오버라이드 (fontId 기반)
        ass.append(String.format("Style: Default,%s,%d,%s,%s,%s,%s,%d,0,0,0,100,100,%d,0,%d,%d,%d,%d,%d,%d,%d,1\n",
                fontName, defaultFontSize,
                template.getPrimaryColour(), template.getSecondaryColour(),
                template.getOutlineColour(), template.getBackColour(),
                template.getBold() ? 1 : 0, template.getSpacing(),
                template.getBorderStyle(), template.getOutline(), template.getShadow(),
                alignment, template.getMarginL(), template.getMarginR(), marginV));
        ass.append(String.format("Style: Emotion,%s,%d,%s,%s,%s,%s,%d,0,0,0,100,100,%d,0,%d,%d,%d,%d,%d,%d,%d,1\n\n",
                fontName, emotionFontSize,
                template.getEmotionPrimaryColour(), template.getSecondaryColour(),
                template.getOutlineColour(), template.getBackColour(),
                template.getBold() ? 1 : 0, template.getSpacing(),
                template.getBorderStyle(), template.getOutline(), template.getShadow(),
                alignment, template.getMarginL(), template.getMarginR(), marginV));
        ass.append("[Events]\n");
        ass.append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");

        if (narration == null || narration.trim().isEmpty()) {
            return ass.toString();
        }

        // v2.9.175: 실제 TTS 오디오 길이가 있으면 사용 (Math.ceil 반올림으로 인한 점진적 지연 방지)
        double durationSec = (actualAudioDuration != null && actualAudioDuration > 0)
                ? actualAudioDuration
                : (duration != null ? duration.doubleValue() : 10.0);

        // 문장 단위로 분할
        List<String> sentences = splitNarrationIntoSentencesForScene(narration);

        if (sentences.isEmpty()) {
            double endTime = (speechSegments != null && !speechSegments.isEmpty())
                    ? speechSegments.get(speechSegments.size() - 1)[1]
                    : durationSec;
            ass.append("Dialogue: 0,0:00:00.00,").append(formatAssTime(endTime)).append(",Default,,0,0,0,,")
               .append(formatSubtitleTextForScene(narration)).append("\n");
            return ass.toString();
        }

        // v2.9.170: 음성 구간이 문장 수와 정확히 일치하면 직접 사용 (detectSentenceBoundaries 결과)
        if (speechSegments != null && !speechSegments.isEmpty() && speechSegments.size() == sentences.size()) {
            log.info("[v2.9.170] Using exact sentence boundaries: {} segments for {} sentences (no offset)",
                    speechSegments.size(), sentences.size());

            for (int i = 0; i < sentences.size(); i++) {
                String sentence = sentences.get(i);
                double[] segment = speechSegments.get(i);

                // v2.9.166: 문장을 짧은 디스플레이 청크로 분할 후 시간 비례 배분
                appendSubdividedDialogues(ass, sentence, segment[0], segment[1]);
            }

            return ass.toString();
        }

        // v2.9.170: 레거시 폴백 - 세그먼트 수 > 문장 수인 경우 (기존 detectSpeechSegments 결과)
        if (speechSegments != null && !speechSegments.isEmpty() && speechSegments.size() > sentences.size()) {
            List<double[]> mergedSegments = mergeSegmentsToSentencesForScene(speechSegments, sentences.size());

            log.info("[v2.9.170] Legacy fallback: merged {} segments -> {} for {} sentences",
                    speechSegments.size(), mergedSegments.size(), sentences.size());

            for (int i = 0; i < sentences.size(); i++) {
                String sentence = sentences.get(i);
                double[] segment = mergedSegments.get(i);
                appendSubdividedDialogues(ass, sentence, segment[0], segment[1]);
            }

            return ass.toString();
        }

        // v2.9.179: 음성 구간이 없거나 문장보다 적으면 글자 수 + 구두점 고정 시간 기반 폴백
        // - DB의 TTS 발화 속도 사용 (국가별 설정)
        // - 구두점에 고정 시간 추가 (마침표 +0.5초, 쉼표 +0.2초)
        log.info("[v2.9.179] Falling back to character-based timing: segments={}, sentences={}",
                speechSegments != null ? speechSegments.size() : 0, sentences.size());

        double ttsCharsPerSec = getTtsCharsPerSecond(creatorId);
        List<Double> estimatedDurations = new ArrayList<>();
        double totalEstimated = 0;

        // v2.9.179: 각 문장의 예상 발화 시간 계산 (글자 수 / TTS 속도 + 구두점 고정 시간)
        for (String sentence : sentences) {
            int charCount = sentence.replaceAll("[\\s]", "").length();
            charCount = Math.max(charCount, 1);

            // 기본 발화 시간 = 글자 수 / TTS 발화 속도
            double baseDuration = (double) charCount / ttsCharsPerSec;

            // 구두점에 고정 시간 추가 (실제 TTS 측정 기반)
            double pauseTime = 0;
            if (sentence.matches(".*[.!?:].*")) {
                pauseTime = 0.5;  // 문장 종료: +0.5초
            } else if (sentence.contains(",")) {
                pauseTime = 0.2;  // 쉼표: +0.2초
            }

            double estimated = baseDuration + pauseTime;
            estimatedDurations.add(estimated);
            totalEstimated += estimated;
        }

        // 예상 시간이 실제 duration보다 길면 스케일 조정
        double scaleFactor = (totalEstimated > durationSec) ? (durationSec / totalEstimated) : 1.0;
        double currentTime = 0.0;

        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            double estimated = estimatedDurations.get(i);
            double segmentDuration = estimated * scaleFactor;

            double sentenceStart = currentTime;
            double sentenceEnd = currentTime + segmentDuration;

            if (i == sentences.size() - 1) {
                sentenceEnd = durationSec;  // 마지막 문장은 끝까지
            }

            double displayEnd = Math.min(sentenceEnd - 0.05, durationSec);
            if (displayEnd <= sentenceStart) displayEnd = sentenceEnd;

            // v2.9.166: 문장을 짧은 디스플레이 청크로 분할 후 시간 비례 배분
            appendSubdividedDialogues(ass, sentence, sentenceStart, displayEnd);

            currentTime = sentenceEnd;
        }

        return ass.toString();
    }

    /**
     * v2.9.160: formatId로 해상도 조회 (ThreadLocal 미사용, 파라미터 직접 전달)
     */
    private int[] resolveResolution(Long formatId) {
        if (formatId == null) {
            return new int[]{1920, 1080};
        }
        return videoFormatMapper.findById(formatId)
            .map(f -> new int[]{f.getWidth(), f.getHeight()})
            .orElse(new int[]{1920, 1080});
    }

    /**
     * v2.9.161: 자막 템플릿 조회 (null이면 기본 템플릿 반환)
     */
    private VideoSubtitle resolveTemplate(Long videoSubtitleId) {
        if (videoSubtitleId != null) {
            Optional<VideoSubtitle> found = videoSubtitleMapper.findById(videoSubtitleId);
            if (found.isPresent()) {
                log.info("[v2.9.161] Using subtitle template: {} ({})", found.get().getSubtitleName(), found.get().getSubtitleCode());
                return found.get();
            }
            log.warn("[v2.9.161] Subtitle template not found: {}, falling back to default", videoSubtitleId);
        }
        // 기본 템플릿 (findDefault) 또는 하드코딩 폴백
        return videoSubtitleMapper.findDefault()
                .orElseGet(() -> {
                    log.warn("[v2.9.161] No default subtitle template in DB, using hardcoded fallback");
                    return VideoSubtitle.builder()
                            .videoSubtitleId(1L)
                            .subtitleCode("DEFAULT_OUTLINE")
                            .subtitleName("기본 자막")
                            .fontName("SUIT-Bold")
                            .fontSize(72)
                            .fontSizeVertical(100)
                            .fontSizeEmotion(80)
                            .fontSizeEmotionVertical(100)
                            .bold(true)
                            .spacing(2)
                            .primaryColour("&H00FFFFFF")
                            .secondaryColour("&H000000FF")
                            .outlineColour("&H00000000")
                            .backColour("&HB0000000")
                            .borderStyle(1)
                            .outline(4)
                            .shadow(2)
                            .alignment(2)
                            .marginL(20)
                            .marginR(20)
                            .marginV(80)
                            .marginVVertical(300)
                            .emotionPrimaryColour("&H0000FFFF")
                            .isDefault(true)
                            .build();
                });
    }

    /**
     * v2.9.161: fontSizeLevel에 따른 배율 반환
     * 1=small (0.6x), 2=medium (0.8x), 3=large (1.0x, 기본값)
     */
    private double resolveFontSizeMultiplier(Integer fontSizeLevel) {
        if (fontSizeLevel == null) {
            return 1.0;  // 기본값: large
        }
        return switch (fontSizeLevel) {
            case 1 -> 0.6;
            case 2 -> 0.8;
            default -> 1.0;
        };
    }

    /**
     * v2.9.167: 사용자 선택 위치를 ASS Alignment 값으로 변환
     * ASS Alignment (numpad 기반): 1-3=하단, 4-6=중앙, 7-9=상단
     * 사용자 선택: 1=하단, 2=중앙, 3=상단
     * 중앙 정렬(가로 기준)을 유지하면서 세로 위치만 변경
     */
    private int resolveAlignment(Integer subtitlePosition, int templateAlignment) {
        if (subtitlePosition == null || subtitlePosition == 1) {
            return templateAlignment;  // 기본값: 템플릿 원래 값 사용 (보통 2=하단 중앙)
        }
        return switch (subtitlePosition) {
            case 2 -> 5;  // 중앙 (ASS numpad 5 = 화면 중앙)
            case 3 -> 8;  // 상단 (ASS numpad 8 = 상단 중앙)
            default -> templateAlignment;
        };
    }

    /**
     * v2.9.172: 사용자 선택 위치에 따른 MarginV 조정 (유튜브 스타일)
     * 하단: 템플릿 원래 marginV 유지
     * 중앙: marginV=0 (ASS alignment 5가 정확히 중앙 배치)
     * 상단: 화면 가장자리에서 충분히 떨어진 위치 (가로 80px, 세로 200px)
     */
    private int resolvePositionMarginV(Integer subtitlePosition, int templateMarginV, boolean isVertical) {
        if (subtitlePosition == null || subtitlePosition == 1) {
            return templateMarginV;  // 하단: 템플릿 값 유지
        }
        return switch (subtitlePosition) {
            case 2 -> 0;  // 중앙: 여백 없음 (alignment 5가 중앙 배치)
            case 3 -> isVertical ? 200 : 80;  // 상단: 유튜브 스타일 여백 (16:9=7.4%, 9:16=10.4%)
            default -> templateMarginV;
        };
    }

    /**
     * v2.9.174: fontId로 VideoFont 조회하여 폰트명 오버라이드
     * fontId가 없으면 템플릿의 기본 폰트명 사용
     */
    private String resolveFontName(Long fontId, VideoSubtitle template) {
        if (fontId != null) {
            VideoFont font = creatorConfigService.getFont(fontId);
            if (font != null) {
                log.info("[v2.9.174] Font override: fontId={} -> fontName='{}'", fontId, font.getFontName());
                return font.getFontName();
            }
            log.warn("[v2.9.174] Font not found for fontId={}, using template font", fontId);
        }
        return template.getFontName();
    }

    // v2.9.179: 자막 한 세그먼트 최대 글자 수 (이 이상이면 추가 분할)
    // 25자 → 45자로 증가: 유튜브/넷플릭스 표준 40-50자/줄, 자막 변경 빈도 감소로 시청자 눈 피로 완화
    private static final int MAX_SUBTITLE_SEGMENT_LENGTH = 45;

    // v2.9.179: 현재 씬의 creatorId (generateSceneSubtitle에서 설정, 헬퍼 메서드에서 사용)
    private static final ThreadLocal<Long> currentCreatorId = new ThreadLocal<>();

    /**
     * v2.9.179: 국가별 TTS 발화 속도 조회 (DB 기반)
     * creatorId로 국가 설정 조회, 없으면 기본값 4.5자/초
     */
    private double getTtsCharsPerSecond(Long creatorId) {
        if (creatorId != null) {
            double ttsChars = creatorConfigService.getTtsCharsPerSecond(creatorId);
            if (ttsChars > 0) {
                return ttsChars;
            }
        }
        return DEFAULT_TTS_CHARS_PER_SECOND;
    }

    /**
     * v2.9.179: 국가별 자막 최대 글자 수 조회 (DB 기반)
     * creatorId로 국가 설정 조회, 없으면 기본값 45자
     */
    private int getMaxSubtitleSegmentLength(Long creatorId) {
        if (creatorId != null) {
            int maxChars = creatorConfigService.getMaxCharsPerLine(creatorId);
            if (maxChars > 0) {
                return maxChars;
            }
        }
        return MAX_SUBTITLE_SEGMENT_LENGTH;
    }

    /**
     * v2.9.166: 나레이션을 문장 단위로 분할 (타이밍 계산용)
     * 문장 종결 부호(.!?)로만 분할 — 음성 구간 매핑 정확도를 위해 원본 문장 유지
     * 짧은 디스플레이 분할은 generateSceneSubtitle()에서 타이밍 계산 후 수행
     */
    private List<String> splitNarrationIntoSentencesForScene(String narration) {
        List<String> result = new ArrayList<>();

        if (narration == null || narration.trim().isEmpty()) {
            return result;
        }

        // 문장 종결 부호로만 분할 (소수점 보호)
        String[] parts = narration.split("(?<=[.!?。！？])(?![0-9])\\s*");

        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty() || trimmed.matches("^[.!?…\\s]+$") || trimmed.length() < 2) {
                continue;
            }
            result.add(trimmed);
        }

        if (result.isEmpty() && narration.trim().length() >= 2) {
            result.add(narration.trim());
        }

        return result;
    }

    /**
     * v2.9.179: 긴 문장을 쉼표/조사 기준으로 짧은 세그먼트로 분할
     * - DB 국가별 maxCharsPerLine 설정 사용
     * - 짧은 세그먼트(15자 미만) 병합으로 자막 변경 빈도 감소
     */
    private List<String> splitLongSentence(String sentence) {
        List<String> segments = new ArrayList<>();
        int maxLength = getMaxSubtitleSegmentLength(currentCreatorId.get());

        if (sentence.length() <= maxLength) {
            segments.add(sentence);
            return segments;
        }

        // 쉼표로 먼저 분할 시도
        String[] commaParts = sentence.split("(?<=[,，、])\\s*");

        StringBuilder current = new StringBuilder();
        for (String commaPart : commaParts) {
            // 현재 버퍼 + 새 파트가 최대 길이 초과하면 버퍼 flush
            if (current.length() > 0 && current.length() + commaPart.length() > maxLength) {
                String seg = current.toString().trim();
                if (seg.length() >= 2) {
                    segments.add(seg);
                }
                current = new StringBuilder();
            }

            current.append(commaPart);

            // 단일 쉼표 파트가 이미 초과하면 조사 기준으로 추가 분할
            if (current.length() > maxLength) {
                List<String> subParts = splitByParticles(current.toString().trim(), maxLength);
                // 마지막 파트는 다음과 이어붙일 수 있으므로 남김
                for (int i = 0; i < subParts.size() - 1; i++) {
                    String seg = subParts.get(i).trim();
                    if (seg.length() >= 2) {
                        segments.add(seg);
                    }
                }
                current = new StringBuilder(subParts.get(subParts.size() - 1));
            }
        }

        // 남은 텍스트 처리
        if (current.length() > 0) {
            String remaining = current.toString().trim();
            if (remaining.length() > maxLength) {
                for (String sub : splitByParticles(remaining, maxLength)) {
                    String seg = sub.trim();
                    if (seg.length() >= 2) {
                        segments.add(seg);
                    }
                }
            } else if (remaining.length() >= 2) {
                segments.add(remaining);
            }
        }

        // v2.9.179: 짧은 세그먼트 병합 (15자 미만은 이전 세그먼트와 합침)
        segments = mergeShortSegments(segments, maxLength);

        // 빈 결과 방지
        if (segments.isEmpty()) {
            segments.add(sentence);
        }

        return segments;
    }

    /**
     * v2.9.179: 짧은 세그먼트 병합 (15자 미만은 이전/다음 세그먼트와 합침)
     */
    private List<String> mergeShortSegments(List<String> segments, int maxLength) {
        if (segments.size() <= 1) {
            return segments;
        }

        List<String> merged = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        final int MIN_SEGMENT_LENGTH = 15;

        for (int i = 0; i < segments.size(); i++) {
            String seg = segments.get(i);

            if (buffer.length() == 0) {
                buffer.append(seg);
            } else if (buffer.length() + seg.length() + 1 <= maxLength) {
                // 이전 버퍼와 합쳐도 최대 길이 이내면 합침
                buffer.append(" ").append(seg);
            } else {
                // 버퍼가 최소 길이 이상이면 flush
                if (buffer.length() >= MIN_SEGMENT_LENGTH) {
                    merged.add(buffer.toString().trim());
                    buffer = new StringBuilder(seg);
                } else {
                    // 최소 길이 미만이면 억지로 합침
                    buffer.append(" ").append(seg);
                }
            }
        }

        // 마지막 버퍼 처리
        if (buffer.length() > 0) {
            String remaining = buffer.toString().trim();
            // 마지막 세그먼트가 짧고, 이전에 세그먼트가 있으면 합침
            if (remaining.length() < MIN_SEGMENT_LENGTH && !merged.isEmpty()) {
                String last = merged.remove(merged.size() - 1);
                if (last.length() + remaining.length() + 1 <= maxLength * 1.2) {
                    merged.add(last + " " + remaining);
                } else {
                    merged.add(last);
                    merged.add(remaining);
                }
            } else {
                merged.add(remaining);
            }
        }

        return merged.isEmpty() ? segments : merged;
    }

    /**
     * v2.9.179: 한국어 조사/어미 뒤 띄어쓰기 기준으로 분할
     * @param maxLength DB 국가별 최대 글자 수
     */
    private List<String> splitByParticles(String text, int maxLength) {
        List<String> parts = new ArrayList<>();

        if (text.length() <= maxLength) {
            parts.add(text);
            return parts;
        }

        // 띄어쓰기 기준으로 단어 분할 후 적절한 길이로 묶기
        String[] words = text.split("\\s+");
        StringBuilder current = new StringBuilder();

        for (String word : words) {
            if (current.length() > 0 && current.length() + 1 + word.length() > maxLength) {
                String seg = current.toString().trim();
                if (seg.length() >= 2) {
                    parts.add(seg);
                }
                current = new StringBuilder();
            }
            if (current.length() > 0) {
                current.append(" ");
            }
            current.append(word);
        }

        if (current.length() > 0) {
            String seg = current.toString().trim();
            if (seg.length() >= 2) {
                parts.add(seg);
            }
        }

        if (parts.isEmpty()) {
            parts.add(text);
        }

        return parts;
    }

    /**
     * v2.9.179: 문장을 짧은 디스플레이 청크로 분할하고, 시간 슬롯을 글자 수 비례로 배분
     * - 타이밍은 문장 단위로 정확하게 계산된 상태 (음성 구간 기반)
     * - 디스플레이만 짧게 분할하여 한 번에 적은 글자가 보이도록 함
     * - DB 국가별 maxCharsPerLine 설정 사용
     */
    private void appendSubdividedDialogues(StringBuilder ass, String sentence, double startTime, double endTime) {
        int maxLength = getMaxSubtitleSegmentLength(currentCreatorId.get());

        // 짧은 문장은 분할 없이 그대로 표시
        if (sentence.length() <= maxLength) {
            String style = detectSubtitleStyleForScene(sentence);
            ass.append("Dialogue: 0,").append(formatAssTime(startTime)).append(",")
               .append(formatAssTime(endTime)).append(",").append(style).append(",,0,0,0,,")
               .append(formatSubtitleTextForScene(sentence)).append("\n");
            return;
        }

        // 긴 문장을 짧은 청크로 분할
        List<String> chunks = splitLongSentence(sentence);

        if (chunks.size() <= 1) {
            String style = detectSubtitleStyleForScene(sentence);
            ass.append("Dialogue: 0,").append(formatAssTime(startTime)).append(",")
               .append(formatAssTime(endTime)).append(",").append(style).append(",,0,0,0,,")
               .append(formatSubtitleTextForScene(sentence)).append("\n");
            return;
        }

        // 각 청크의 글자 수 비율로 시간 배분
        double totalChars = 0;
        for (String chunk : chunks) {
            totalChars += Math.max(chunk.replaceAll("[\\s]", "").length(), 1);
        }

        double totalDuration = endTime - startTime;
        double chunkStart = startTime;

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            double charCount = Math.max(chunk.replaceAll("[\\s]", "").length(), 1);
            double chunkDuration = (charCount / totalChars) * totalDuration;
            double chunkEnd = (i == chunks.size() - 1) ? endTime : chunkStart + chunkDuration;

            String style = detectSubtitleStyleForScene(chunk);
            ass.append("Dialogue: 0,").append(formatAssTime(chunkStart)).append(",")
               .append(formatAssTime(chunkEnd)).append(",").append(style).append(",,0,0,0,,")
               .append(formatSubtitleTextForScene(chunk)).append("\n");

            chunkStart = chunkEnd;
        }
    }

    /**
     * v2.9.165: 자막 텍스트 포맷팅 (짧은 세그먼트이므로 줄바꿈 불필요)
     */
    private String formatSubtitleTextForScene(String text) {
        return text.replace("\n", " ").trim();
    }

    /**
     * v2.9.160: 감정 스타일 감지 (ContentService 로직 이동)
     */
    private String detectSubtitleStyleForScene(String text) {
        if (text.contains("!") || text.contains("?!") || text.contains("!!") ||
            text.contains("...") || text.contains("…")) {
            return "Emotion";
        }
        return "Default";
    }

    /**
     * v2.9.160: 음성 구간을 문장 수에 맞게 병합 (ContentService 로직 이동)
     */
    private List<double[]> mergeSegmentsToSentencesForScene(List<double[]> segments, int sentenceCount) {
        List<double[]> merged = new ArrayList<>();

        if (segments.isEmpty() || sentenceCount <= 0) {
            return merged;
        }

        double segmentsPerSentence = (double) segments.size() / sentenceCount;

        for (int i = 0; i < sentenceCount; i++) {
            int startIdx = (int) Math.round(i * segmentsPerSentence);
            int endIdx = (int) Math.round((i + 1) * segmentsPerSentence) - 1;

            startIdx = Math.max(0, Math.min(startIdx, segments.size() - 1));
            endIdx = Math.max(startIdx, Math.min(endIdx, segments.size() - 1));

            double mergedStart = segments.get(startIdx)[0];
            double mergedEnd = segments.get(endIdx)[1];

            merged.add(new double[]{mergedStart, mergedEnd});
        }

        return merged;
    }

    // ========== 기존 메서드 ==========

    /**
     * SRT 엔트리 포맷팅
     */
    private String formatSrtEntry(int index, double startTime, double endTime, String text) {
        return String.format("%d%n%s --> %s%n%s%n%n",
                index,
                formatSrtTime(startTime),
                formatSrtTime(endTime),
                text.replace("\\N", "\n")
        );
    }

    /**
     * SRT 시간 형식 변환 (HH:MM:SS,mmm)
     */
    private String formatSrtTime(double seconds) {
        int hours = (int) (seconds / 3600);
        int minutes = (int) ((seconds % 3600) / 60);
        int secs = (int) (seconds % 60);
        int millis = (int) ((seconds % 1) * 1000);

        return String.format("%02d:%02d:%02d,%03d", hours, minutes, secs, millis);
    }
}
