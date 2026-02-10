/**
 * API 클라이언트 및 타입 정의
 * 단순화된 Chat/Content API
 */

import { getAccessToken, clearAuth, LoginResponse, User } from './auth';

// v2.9.9: S3 URL 검증 유틸리티 (XSS/Injection 방지)
const ALLOWED_URL_PATTERNS = [
  /^https:\/\/[a-z0-9-]+\.s3\.[a-z0-9-]+\.amazonaws\.com\//i,  // S3 presigned URL
  /^https:\/\/s3\.[a-z0-9-]+\.amazonaws\.com\/[a-z0-9-]+\//i,   // S3 path-style URL
  /^\/api\//,  // 로컬 API 경로
  /^\/tmp\//,  // 로컬 임시 파일 경로
];

function isValidDownloadUrl(url: string | null | undefined): boolean {
  if (!url || typeof url !== 'string') return false;

  // javascript:, data: 등 위험한 프로토콜 차단
  const lowerUrl = url.toLowerCase().trim();
  if (lowerUrl.startsWith('javascript:') ||
      lowerUrl.startsWith('data:') ||
      lowerUrl.startsWith('vbscript:')) {
    console.warn('[Security] Blocked dangerous URL protocol:', url.substring(0, 50));
    return false;
  }

  // 허용된 패턴 검사
  return ALLOWED_URL_PATTERNS.some(pattern => pattern.test(url));
}

// API Base URL 설정
// - 프로덕션: 환경변수 또는 상대경로 (같은 도메인에서 Nginx가 프록시)
// - 로컬: localhost:8080
const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL ||
  (typeof window !== 'undefined' && window.location.hostname !== 'localhost' ? '' : 'http://localhost:8080');

// ============ 고객 친화적 에러 메시지 (v2.9.95) ============
// 기술적 에러를 사용자가 이해하기 쉬운 메시지로 변환

const FRIENDLY_ERROR_MESSAGES: Record<string, string> = {
  // 네트워크/서버 에러
  'Failed to fetch': '인터넷 연결이 불안정해요. 연결 상태를 확인해주세요.',
  'Network Error': '인터넷 연결이 불안정해요. 연결 상태를 확인해주세요.',
  'NetworkError': '인터넷 연결이 불안정해요. 연결 상태를 확인해주세요.',
  'Load failed': '인터넷 연결이 불안정해요. 연결 상태를 확인해주세요.',

  // API 사용량 관련
  'API 사용한도 초과': '잠시 휴식이 필요해요. 1분 후에 다시 시도해주세요.',
  '사용한도 초과': '잠시 휴식이 필요해요. 1분 후에 다시 시도해주세요.',
  'rate limit': '잠시 휴식이 필요해요. 1분 후에 다시 시도해주세요.',
  'quota exceeded': '오늘 사용량이 많았네요. 내일 다시 이용해주세요.',

  // 인증 관련
  '인증이 만료': '로그인 세션이 만료됐어요. 다시 로그인해주세요.',
  'Unauthorized': '로그인이 필요해요. 로그인 후 이용해주세요.',
  'Forbidden': '접근 권한이 없어요. 로그인 상태를 확인해주세요.',

  // 서버 에러
  'Internal Server Error': '서버가 잠시 쉬고 있어요. 잠시 후 다시 시도해주세요.',
  'Service Unavailable': '서버 점검 중이에요. 잠시만 기다려주세요.',
  'Bad Gateway': '서버 연결에 문제가 생겼어요. 잠시 후 다시 시도해주세요.',
  'Gateway Timeout': '서버 응답이 늦어지고 있어요. 잠시 후 다시 시도해주세요.',

  // 콘텐츠 생성 관련
  '시나리오 생성 실패': '시나리오 생성에 문제가 생겼어요. 다시 시도해볼까요?',
  '이미지 생성 실패': '이미지 생성 중 문제가 생겼어요. 다시 시도해볼까요?',
  '영상 생성 실패': '영상 생성 중 문제가 생겼어요. 다시 시도해볼까요?',
  'TTS 생성 실패': '음성 생성 중 문제가 생겼어요. 다시 시도해볼까요?',

  // 파일 관련
  'Download failed': '다운로드가 잘 안 됐어요. 다시 시도해주세요.',
  '다운로드 실패': '다운로드가 잘 안 됐어요. 다시 시도해주세요.',
  '파일을 찾을 수 없': '파일이 아직 준비되지 않았어요. 잠시 후 다시 시도해주세요.',

  // 입력 관련
  '유효하지 않은': '입력 내용을 다시 확인해주세요.',
  'Invalid': '입력 내용을 다시 확인해주세요.',
};

/**
 * 기술적 에러 메시지를 고객 친화적 메시지로 변환
 */
function getFriendlyErrorMessage(technicalMessage: string): string {
  // 먼저 정확히 일치하는 메시지 찾기
  if (FRIENDLY_ERROR_MESSAGES[technicalMessage]) {
    return FRIENDLY_ERROR_MESSAGES[technicalMessage];
  }

  // 부분 일치로 찾기
  const lowerMessage = technicalMessage.toLowerCase();
  for (const [key, friendlyMessage] of Object.entries(FRIENDLY_ERROR_MESSAGES)) {
    if (lowerMessage.includes(key.toLowerCase())) {
      return friendlyMessage;
    }
  }

  // HTTP 상태 코드 기반 처리
  if (technicalMessage.includes('400')) {
    return '요청 내용에 문제가 있어요. 입력을 확인해주세요.';
  }
  if (technicalMessage.includes('401') || technicalMessage.includes('403')) {
    return '로그인이 필요해요. 다시 로그인해주세요.';
  }
  if (technicalMessage.includes('404')) {
    return '요청하신 내용을 찾을 수 없어요.';
  }
  if (technicalMessage.includes('429')) {
    return '잠시 휴식이 필요해요. 1분 후에 다시 시도해주세요.';
  }
  if (technicalMessage.includes('500') || technicalMessage.includes('502') || technicalMessage.includes('503')) {
    return '서버가 잠시 쉬고 있어요. 잠시 후 다시 시도해주세요.';
  }

  // 기본 메시지 (그래도 기술 용어는 숨기기)
  if (technicalMessage.includes('Error') || technicalMessage.includes('error') ||
      technicalMessage.includes('fail') || technicalMessage.includes('Fail')) {
    return '문제가 생겼어요. 다시 시도해주세요.';
  }

  // 이미 친절한 메시지인 경우 그대로 반환
  return technicalMessage;
}

// ============ 공통 타입 ============

export interface ApiResponse<T> {
  success: boolean;
  code: string;
  message: string;
  data: T;
}

// ============ 채팅 API 타입 ============

// v2.9.75: 모든 진행/실패/재생성 상태 추가 (새로고침 시 상태 복원용)
export type ChatStage =
  | 'CHATTING'
  | 'SCENARIO_READY'
  | 'SCENARIO_GENERATING'  // v2.9.75: 시나리오 생성 중
  | 'SCENARIO_DONE'
  | 'PREVIEWS_GENERATING'  // 씬 프리뷰 생성 중
  | 'PREVIEWS_DONE'        // 씬 프리뷰 완료
  | 'SCENES_GENERATING'    // 씬 생성 중 (레거시)
  | 'SCENES_REVIEW'        // 씬 검토 중
  | 'SCENE_REGENERATING'   // v2.9.0: 개별 씬 재생성 중
  | 'TTS_GENERATING'       // TTS 생성 중
  | 'TTS_DONE'             // TTS/자막 완료
  | 'TTS_PARTIAL_FAILED'   // TTS 일부 실패 (재시도 필요)
  | 'IMAGES_GENERATING'    // 이미지 생성 중
  | 'IMAGES_DONE'
  | 'AUDIO_GENERATING'     // 오디오 생성 중
  | 'AUDIO_DONE'
  | 'VIDEO_GENERATING'     // 영상 합성 중
  | 'VIDEO_FAILED'         // v2.9.0: 영상 합성 실패 (재시도 가능)
  | 'VIDEO_DONE';

export interface ChatStartRequest {
  prompt: string;
}

export interface ChatMessageRequest {
  message: string;
}

export interface ChatResponse {
  chatId: number;
  aiMessage: string;
  stage: ChatStage;
  canGenerateScenario: boolean;
}

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  messageType?: string;  // v2.9.27: VIDEO_RESULT, THUMBNAIL_RESULT 등
  metadata?: string;     // v2.9.27: JSON 메타데이터 (URL, 제목 등)
  createdAt: string;
}

export interface ContentStatus {
  scenarioReady: boolean;
  imagesReady: boolean;
  audioReady: boolean;
  videoReady: boolean;
}

// v2.9.121: 백엔드 응답 타입 (creator* 필드 사용)
interface ChatDetailBackend {
  chatId: number;
  stage: ChatStage;
  canGenerateScenario: boolean;
  messages: ChatMessage[];
  contentStatus: ContentStatus;
  creatorId?: number;
  creatorName?: string;
  referenceImageUrl?: string;
}

// v2.9.121: 프론트엔드 사용 타입 (genre* 필드로 변환)
export interface ChatDetail {
  chatId: number;
  stage: ChatStage;
  canGenerateScenario: boolean;
  messages: ChatMessage[];
  contentStatus: ContentStatus;
  creatorId?: number;       // v2.9.134: 크리에이터 ID
  creatorName?: string;     // v2.9.134: 크리에이터 이름
  referenceImageUrl?: string;  // v2.9.84: 참조 이미지 URL (S3 presigned URL)
}

export interface ChatSummary {
  chatId: number;
  initialPrompt: string;
  stage: ChatStage;
  messageCount: number;
  createdAt: string;
}

// ============ 콘텐츠 API 타입 ============

export interface SlideInfo {
  order: number;
  narration: string;
  imagePrompt: string;
  durationSeconds: number;
}

export interface ScenarioRequest {
  slideCount?: number;      // v2.9.73: 슬라이드 수 직접 선택 (1-10장, 슬라이드당 ~5분)
  creatorId?: number;       // v2.9.134: 크리에이터 ID
  formatId?: number;        // v2.9.25: 영상 포맷 ID (기본값: 1 = 유튜브 일반)
  videoSubtitleId?: number; // v2.9.161: 자막 템플릿 ID (기본값: 1 = 기본 자막)
  fontSizeLevel?: number;   // v2.9.161: 자막 글자 크기 (1=small, 2=medium, 3=large, 기본값: 3)
  subtitlePosition?: number; // v2.9.167: 자막 위치 (1=하단, 2=중앙, 3=상단, 기본값: 1)
  thumbnailId?: number;      // v2.9.168: 썸네일 디자인 ID (null이면 기본 CLASSIC)
  fontId?: number;           // v2.9.174: 폰트 ID (null이면 기본 폰트=1 사용)
}

// v2.9.25: 영상 포맷 관련 타입
export interface VideoFormat {
  formatId: number;
  formatCode: string;       // YOUTUBE_STANDARD, YOUTUBE_SHORTS, INSTAGRAM_REELS, TIKTOK
  formatName: string;       // 유튜브 일반영상, 유튜브 쇼츠, ...
  formatNameEn: string;     // YouTube Standard, YouTube Shorts, ...
  width: number;            // 1920 또는 1080
  height: number;           // 1080 또는 1920
  aspectRatio: string;      // "16:9" 또는 "9:16"
  icon?: string | null;     // 아이콘 (nullable)
  description: string;
  platform: string;         // YouTube, Instagram, TikTok
  isDefault: boolean;
}

export interface VideoFormatsResponse {
  formats: VideoFormat[];
}

// v2.9.161: 영상 자막 템플릿 관련 타입
export interface VideoSubtitle {
  videoSubtitleId: number;
  subtitleCode: string;       // DEFAULT_OUTLINE, BACKGROUND_BOX
  subtitleName: string;       // 기본 자막, 배경 자막
  subtitleNameEn: string;     // Default Outline, Background Box
  description: string;
  isDefault: boolean;
  displayOrder: number;
}

export interface VideoSubtitlesResponse {
  subtitles: VideoSubtitle[];
  totalCount: number;
}

// v2.9.174: 비디오 폰트 관련 타입
export interface FontInfo {
  fontId: number;
  fontCode: string;           // SUIT_BOLD
  fontName: string;           // SUIT-Bold (ASS + Java2D)
  fontNameDisplay: string;    // 수트 볼드 (UI 표시)
  nationCode: string;         // KR, JP, US
  description?: string;
  isDefault: boolean;
  displayOrder: number;
}

export interface FontsResponse {
  fonts: FontInfo[];
  totalCount: number;
}

export interface OpeningInfo {
  narration: string;         // 오프닝 나레이션 (한국어)
  videoPrompt: string;       // Veo 3.1용 영상 생성 프롬프트 (영어)
  durationSeconds: number;   // 길이 (8초 - Veo API 고정)
}

export interface ScenarioResponse {
  chatId: number;
  title: string;
  description: string;
  summary: string;           // 고객용 시나리오 요약
  hook: string;              // 후킹 멘트
  slides: SlideInfo[];
  estimatedDuration: number;
  downloadReady: boolean;
  hasOpening: boolean;       // 오프닝 영상 프롬프트 포함 여부
  opening?: OpeningInfo;     // 오프닝 영상 상세 정보
}

// v2.9.75: 시나리오 생성 진행 상황
export interface ScenarioProgressResponse {
  chatId: number;
  status: 'idle' | 'generating' | 'expanding' | 'completed' | 'failed';
  phase: 'INIT' | 'BASE_SCENARIO' | 'NARRATION_EXPAND';
  totalSlides: number;
  completedSlides: number;
  progress: number;  // 0-100
  message: string;
}

export interface ImageInfo {
  slideOrder: number;
  status: 'completed' | 'pending' | 'failed';
  filePath: string | null;
  errorMessage: string | null;
}

export interface ImagesResponse {
  chatId: number;
  totalCount: number;
  completedCount: number;
  images?: ImageInfo[];
  downloadReady: boolean;
  progressMessage: string;
}

export interface AudioInfo {
  slideOrder: number;
  status: string;
  filePath: string | null;
  durationSeconds: number;
  errorMessage: string | null;
}

export interface AudioResponse {
  chatId: number;
  totalCount: number;
  completedCount: number;
  audios?: AudioInfo[];
  downloadReady: boolean;
  totalDuration: number;
  progressMessage: string;
}

export interface VideoRequest {
  includeSubtitle?: boolean;
  includeOpening?: boolean;
}

export interface VideoResponse {
  chatId: number;
  status: 'processing' | 'completed' | 'failed';
  progress: number;
  progressMessage: string;
  downloadReady: boolean;
  durationSeconds?: number;
  filePath?: string;
}

export interface ThumbnailResponse {
  chatId: number;
  thumbnailUrl: string;
  title: string;
  catchphrase: string;
  youtubeTitle: string;
  youtubeDescription: string;
}

// v2.9.165: 썸네일 디자인 스타일
export interface VideoThumbnailStyle {
  thumbnailId: number;
  styleCode: string;
  styleName: string;
  description: string;
  borderEnabled: boolean;
  borderColor: string;
  gradientEnabled: boolean;
  textLine1Color: string;
  textLine2Color: string;
  isDefault: boolean;
}

export interface ProgressResponse {
  chatId: number;
  processType: 'scenario' | 'images' | 'audio' | 'video' | 'scene_preview' | 'scene_audio' | 'retry_failed' | 'final_video';  // v2.9.0
  status: 'idle' | 'processing' | 'completed' | 'failed';
  progress: number;
  message: string;
  currentIndex: number;
  totalCount: number;
}

export interface DownloadInfo {
  filename: string;
  contentType: string;
  fileSize: number;
  downloadUrl?: string;
  durationSeconds?: number;
  presignedUrlExpiresAt?: string;  // v2.9.38: S3 presigned URL 만료 시간
}

// ============ v2.4.0 씬 기반 파이프라인 타입 ============

export interface ScenesGenerateRequest {
  includeSubtitle?: boolean;
}

export interface SceneInfo {
  sceneId: number;
  sceneOrder: number;
  sceneType: 'OPENING' | 'SLIDE';
  title: string | null;
  narration: string;
  prompt: string;
  imageUrl: string | null;
  audioUrl: string | null;
  subtitleUrl: string | null;
  sceneVideoUrl: string | null;
  sceneStatus: 'PENDING' | 'GENERATING' | 'COMPLETED' | 'FAILED' | 'REGENERATING';
  regenerateCount: number;
  userFeedback: string | null;
  durationSeconds: number;
  errorMessage: string | null;
}

export interface ScenesGenerateResponse {
  chatId: number;
  status: 'processing' | 'completed' | 'failed' | 'idle';
  totalCount: number;
  completedCount: number;
  progressMessage: string;
  scenes: SceneInfo[];
}

export interface ScenesReviewResponse {
  chatId: number;
  status: 'pending' | 'all_completed' | 'has_failed';
  totalCount: number;
  completedCount: number;
  failedCount: number;
  scenes: SceneInfo[];
  canProceedToFinal: boolean;
  message: string;
}

export interface SceneRegenerateRequest {
  sceneId: number;
  userFeedback?: string;
  newPrompt?: string;
  mediaOnly?: boolean;  // v2.6.1: true면 이미지/영상만 재생성
}

export interface SceneRegenerateResponse {
  chatId: number;
  sceneId: number;
  status: 'processing' | 'completed' | 'failed';
  message: string;
  scene?: SceneInfo;
}

export interface ScenesZipInfo {
  chatId: number;
  filename: string;
  fileSize: number | null;
  sceneCount: number;
  includedFiles: string[];
  downloadUrl: string | null;
}

export interface FinalVideoRequest {
  includeTransitions?: boolean;
}

export interface FinalVideoResponse {
  chatId: number;
  status: 'processing' | 'completed' | 'failed' | 'idle';
  progress: number;
  progressMessage: string;
  downloadReady: boolean;
  durationSeconds: number | null;
  filePath: string | null;
  sceneCount: number | null;
}

// ============ v2.5.0 씬 프리뷰 타입 ============

// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export interface ScenePreviewRequest {
  // 현재는 추가 옵션 없음
}

export interface ScenePreviewInfo {
  sceneId: number;
  sceneOrder: number;
  sceneType: 'OPENING' | 'SLIDE';
  title: string | null;
  mediaUrl: string | null;      // 이미지 또는 영상 URL
  mediaType: 'image' | 'video';
  sceneVideoUrl: string | null; // v2.6.0: 합성된 씬 영상 URL (COMPLETED 상태일 때)
  narration: string;
  isEdited: boolean;
  previewStatus: 'PENDING' | 'GENERATING' | 'MEDIA_READY' | 'TTS_READY' | 'COMPLETED' | 'FAILED';
  errorMessage: string | null;
}

export interface ScenePreviewResponse {
  chatId: number;
  status: 'processing' | 'completed' | 'failed' | 'idle';
  totalCount: number;
  completedCount: number;
  progressMessage: string;
  previews: ScenePreviewInfo[];
  aspectRatio?: string;  // v2.9.25: 영상 포맷 비율 ("16:9" 또는 "9:16")
}

export interface SceneNarrationEditRequest {
  sceneId: number;
  newNarration: string;
}

export interface SceneNarrationEditResponse {
  chatId: number;
  sceneId: number;
  status: 'success' | 'failed';
  oldNarration: string;
  newNarration: string;
  message: string;
}

export interface SceneAudioGenerateRequest {
  sceneIds?: number[];         // 특정 씬만 (null이면 전체)
  includeSubtitle?: boolean;
}

export interface SceneAudioInfo {
  sceneId: number;
  sceneOrder: number;
  audioUrl: string | null;
  subtitleUrl: string | null;
  durationSeconds: number | null;
  status: 'pending' | 'completed' | 'failed';
  errorMessage: string | null;
}

export interface SceneAudioGenerateResponse {
  chatId: number;
  status: 'processing' | 'completed' | 'failed' | 'idle';
  totalCount: number;
  completedCount: number;
  progressMessage: string;
  audioInfos: SceneAudioInfo[];
}

// ============ v2.6.0 부분 실패 복구 타입 ============

export interface FailedScenesRetryRequest {
  sceneIds?: number[];         // 재시도할 씬 ID 목록 (null이면 모든 실패 씬)
  retryMediaOnly?: boolean;    // 미디어만 재시도 (TTS 제외)
}

export interface FailedSceneInfo {
  sceneId: number;
  sceneOrder: number;
  sceneType: 'OPENING' | 'SLIDE';
  failedAt: 'MEDIA' | 'TTS' | 'SUBTITLE' | 'VIDEO';
  errorMessage: string | null;
  retryCount: number;
  isRetrying: boolean;
}

export interface FailedScenesRetryResponse {
  chatId: number;
  status: 'processing' | 'completed' | 'no_failed_scenes' | 'has_failed';
  totalFailedCount: number;
  retryingCount: number;
  failedScenes: FailedSceneInfo[];
  message: string;
}

export interface ProcessCheckpoint {
  chatId: number;
  processType: string;
  status: 'processing' | 'completed' | 'failed' | 'paused' | 'idle';
  totalCount: number;
  completedCount: number;
  failedCount: number;
  completedSceneIds: number[];
  failedSceneIds: number[];
  lastUpdated: string;
  canResume: boolean;
}

export interface ProcessResumeRequest {
  skipFailed?: boolean;        // 실패 씬 스킵하고 계속 진행
}

export interface ProcessResumeResponse {
  chatId: number;
  status: 'resuming' | 'already_completed' | 'no_pending';
  resumedFromIndex: number;
  remainingCount: number;
  message: string;
}


// ============ 크리에이터 시스템 (v2.9.121: Genre → Creator 리네이밍) ============

// v2.9.150: 사용자에게 연결된 크리에이터 정보
export interface LinkedCreatorInfo {
  creatorId: number;
  creatorCode: string;
  creatorName: string;
  nationCode?: string;       // v2.9.174: 국가 코드 (KR, JP, US 등)
  description?: string;
  placeholderText?: string;
  tierCode?: string;
  allowImageUpload?: boolean;
}

// v2.9.126: icon, description, targetAudience 삭제
// v2.9.127: showOnHome → isActive 통합, homeDescription → description 변경
export interface CreatorItem {
  // v2.9.121: 새 필드명
  creatorId: number;
  creatorCode: string;      // Unique creator code (e.g. "REVIEW", "FINANCE")
  creatorName: string;      // Creator display name
  nationCode?: string;          // v2.9.174: 국가 코드 (KR, JP, US 등)
  // v2.9.127: showOnHome 삭제 (isActive로 기능 통합)
  placeholderText?: string;     // v2.9.103: 입력창 플레이스홀더
  description?: string;         // v2.9.127: homeDescription에서 변경
  allowImageUpload?: boolean;   // v2.9.120: ULTRA 티어만 허용 (백엔드에서 동적 결정)
  tierCode?: string;            // v2.9.120: AI 모델 티어 (BASIC, PRO, ULTRA)
}

// v2.9.134: 크리에이터 정보 (genre→creator 통합)
// v2.9.126: icon, description, targetAudience 삭제
// v2.9.127: showOnHome → isActive 통합, homeDescription → description 변경
export interface GenreItem {
  creatorId: number;            // v2.9.134: genreId → creatorId
  creatorCode: string;          // v2.9.134: genreCode → creatorCode
  creatorName: string;          // v2.9.134: genreName → creatorName
  // v2.9.127: showOnHome 삭제 (isActive로 기능 통합)
  placeholderText?: string;
  description?: string;         // v2.9.127: homeDescription에서 변경
  allowImageUpload?: boolean;
  tierCode?: string;
  nationCode?: string;          // v2.9.174: 국가 코드 (KR, JP, US 등)
}

// ============ 인증 타입 ============

export interface LoginRequest {
  loginId: string;
  password: string;
}

export interface SignupRequest {
  loginId: string;
  password: string;
  name: string;
  email: string;
  phone: string;
}

// ============ API 클라이언트 ============

// v2.9.171: timeout + retry 상수
const DEFAULT_TIMEOUT_MS = 30_000;   // 일반 요청 (폴링 등) 30초
export const LONG_TIMEOUT_MS = 120_000;     // 생성 시작 요청 (POST) 120초
const MAX_RETRIES = 2;               // 재시도 2회 (총 3회 시도)
const RETRY_DELAY_MS = 2_000;        // 백오프 기본값 2초

// 재시도 가능한 에러 판별
function isRetryableError(err: unknown): boolean {
  if (err instanceof TypeError) {
    const msg = (err as TypeError).message.toLowerCase();
    return msg.includes('failed to fetch') ||
           msg.includes('networkerror') ||
           msg.includes('load failed') ||
           msg.includes('network request failed');
  }
  return false;
}

function isRetryableStatus(status: number): boolean {
  return status === 429 || (status >= 500 && status <= 599);
}

class ApiClient {
  private baseUrl: string;
  private isHandlingUnauthorized = false;  // v2.9.93: 중복 세션 만료 다이얼로그 방지

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl;
  }

  private async request<T>(
    endpoint: string,
    options: RequestInit & { timeout?: number } = {},
    requireAuth = true
  ): Promise<T> {
    const { timeout, ...fetchOptions } = options;
    const timeoutMs = timeout ?? DEFAULT_TIMEOUT_MS;
    const url = `${this.baseUrl}${endpoint}`;

    const headers: HeadersInit = {
      'Content-Type': 'application/json',
      ...fetchOptions.headers,
    };

    // JWT 토큰 추가
    if (requireAuth) {
      const token = getAccessToken();
      if (token) {
        (headers as Record<string, string>)['Authorization'] = `Bearer ${token}`;
      }
    }

    let lastError: unknown = null;

    for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      // 재시도 시 지수 백오프 대기
      if (attempt > 0) {
        const delay = RETRY_DELAY_MS * Math.pow(2, attempt - 1);
        console.log(`[API Retry] ${endpoint} attempt ${attempt + 1}/${MAX_RETRIES + 1}, waiting ${delay}ms`);
        await new Promise(r => setTimeout(r, delay));
      }

      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);

      try {
        const response = await fetch(url, {
          ...fetchOptions,
          headers,
          signal: controller.signal,
        });
        clearTimeout(timer);

        // 401/403 인증 에러: 재시도하지 않음
        if (response.status === 401 || response.status === 403) {
          this.handleUnauthorized();
          throw new Error('로그인 세션이 만료됐어요. 다시 로그인해주세요.');
        }

        // 재시도 가능한 서버 에러 (5xx, 429)
        if (isRetryableStatus(response.status) && attempt < MAX_RETRIES) {
          console.warn(`[API Retry] ${endpoint} got ${response.status}, will retry`);
          lastError = new Error(`API Error: ${response.status} ${response.statusText}`);
          continue;
        }

        if (!response.ok) {
          const errorBody = await response.json().catch(() => ({}));
          const technicalMessage = errorBody.message || `API Error: ${response.status} ${response.statusText}`;
          throw new Error(getFriendlyErrorMessage(technicalMessage));
        }

        const result: ApiResponse<T> = await response.json();

        if (!result.success) {
          throw new Error(getFriendlyErrorMessage(result.message || '요청 처리 중 문제가 생겼어요.'));
        }

        return result.data;
      } catch (err) {
        clearTimeout(timer);

        // AbortController timeout
        if (err instanceof DOMException && err.name === 'AbortError') {
          lastError = new Error(`요청 시간이 초과됐어요. (${timeoutMs / 1000}초)`);
          if (attempt < MAX_RETRIES) {
            console.warn(`[API Retry] ${endpoint} timeout after ${timeoutMs}ms, will retry`);
            continue;
          }
          throw lastError;
        }

        // 인증 에러는 재시도하지 않음
        if (err instanceof Error && err.message.includes('로그인 세션이 만료')) {
          throw err;
        }

        // 네트워크 에러 재시도
        if (isRetryableError(err) && attempt < MAX_RETRIES) {
          console.warn(`[API Retry] ${endpoint} network error, will retry:`, (err as Error).message);
          lastError = err;
          continue;
        }

        throw err;
      }
    }

    // 모든 재시도 실패
    throw lastError || new Error('요청에 실패했어요. 잠시 후 다시 시도해주세요.');
  }

  // Blob 다운로드용 메서드
  private async downloadFile(endpoint: string): Promise<Blob> {
    const url = `${this.baseUrl}${endpoint}`;

    const headers: HeadersInit = {};
    const token = getAccessToken();
    if (token) {
      (headers as Record<string, string>)['Authorization'] = `Bearer ${token}`;
    }

    const response = await fetch(url, { headers });

    // 토큰 만료 시 자동 로그아웃 (401 또는 403)
    if (response.status === 401 || response.status === 403) {
      this.handleUnauthorized();
      throw new Error('로그인 세션이 만료됐어요. 다시 로그인해주세요.');
    }

    if (!response.ok) {
      throw new Error(getFriendlyErrorMessage(`Download failed: ${response.status}`));
    }

    return response.blob();
  }

  // v2.9.8: S3 presigned URL 직접 다운로드 (CORS 우회)
  // fetch() 대신 anchor 태그로 브라우저 네이티브 다운로드 트리거
  // v2.9.9: URL 검증 추가 (XSS/Injection 방지)
  private downloadViaAnchor(url: string, filename?: string): void {
    if (!isValidDownloadUrl(url)) {
      console.error('[Security] Invalid download URL blocked:', url.substring(0, 100));
      throw new Error('다운로드 준비 중 문제가 생겼어요. 다시 시도해주세요.');
    }

    const link = document.createElement('a');
    link.href = url;
    link.download = filename || '';
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  }

  // 401 Unauthorized 또는 403 Forbidden 공통 처리
  // v2.9.93: 중복 다이얼로그 방지 플래그 추가
  private handleUnauthorized() {
    if (typeof window !== 'undefined') {
      // 이미 처리 중이면 무시 (여러 API 호출이 동시에 401 받을 때 중복 방지)
      if (this.isHandlingUnauthorized) {
        return;
      }

      // 현재 페이지가 로그인 페이지가 아닐 때만 처리
      if (!window.location.pathname.startsWith('/login')) {
        this.isHandlingUnauthorized = true;  // 플래그 설정

        // 세션 만료 확인 다이얼로그
        const shouldLogout = window.confirm(
          '로그인 세션이 만료됐어요.\n\n다시 로그인하시겠어요?'
        );

        if (shouldLogout) {
          clearAuth();
          window.location.href = '/login';
        } else {
          // 취소 시 플래그 리셋 (다음 번 만료 시 다시 표시 가능)
          this.isHandlingUnauthorized = false;
        }
      }
    }
  }

  // ============ 인증 API ============

  async login(data: LoginRequest): Promise<LoginResponse> {
    return this.request<LoginResponse>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify(data),
    }, false);
  }

  async getCurrentUser(): Promise<User> {
    return this.request<User>('/api/v1/auth/me');
  }

  async signup(data: SignupRequest): Promise<LoginResponse> {
    return this.request<LoginResponse>('/api/v1/auth/signup', {
      method: 'POST',
      body: JSON.stringify(data),
    }, false);
  }

  // v2.9.169: Google API Key 저장/수정 (CUSTOM 티어 전용)
  async saveApiKey(apiKey: string): Promise<{ hasGoogleApiKey: boolean; maskedKey: string }> {
    return this.request('/api/v1/auth/api-key', {
      method: 'PUT',
      body: JSON.stringify({ apiKey }),
    });
  }

  // v2.9.169: Google API Key 삭제 (CUSTOM 티어 전용)
  async deleteApiKey(): Promise<{ hasGoogleApiKey: boolean; maskedKey: string | null }> {
    return this.request('/api/v1/auth/api-key', {
      method: 'DELETE',
    });
  }

  // ============ 채팅 API ============

  /**
   * 새 채팅 시작
   * v2.9.134: creatorId 파라미터 (genreId에서 통합)
   */
  async startChat(prompt: string, creatorId?: number): Promise<ChatResponse> {
    return this.request<ChatResponse>('/api/chat/start', {
      method: 'POST',
      body: JSON.stringify({ prompt, creatorId }),
    });
  }

  /**
   * v2.9.86: 참조 이미지와 함께 새 채팅 시작 (최대 5장)
   * v2.9.134: creatorId 파라미터 (genreId에서 통합)
   * FormData를 사용하여 이미지 파일 업로드
   */
  async startChatWithImages(prompt: string, images: File[], creatorId?: number): Promise<ChatResponse> {
    const url = `${this.baseUrl}/api/chat/start-with-image`;
    const token = getAccessToken();

    const formData = new FormData();
    formData.append('prompt', prompt);
    // v2.9.86: 여러 이미지를 'images' 파라미터로 전송
    images.forEach((image) => {
      formData.append('images', image);
    });
    if (creatorId !== undefined) {
      formData.append('creatorId', creatorId.toString());
    }

    const headers: HeadersInit = {};
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    // Note: Content-Type은 FormData 사용 시 자동 설정 (boundary 포함)

    const response = await fetch(url, {
      method: 'POST',
      headers,
      body: formData,
    });

    if (!response.ok) {
      if (response.status === 401) {
        clearAuth();
        throw new Error('로그인 세션이 만료됐어요. 다시 로그인해주세요.');
      }
      const errorData = await response.json().catch(() => ({}));
      const technicalMessage = errorData.message || `API Error: ${response.status}`;
      throw new Error(getFriendlyErrorMessage(technicalMessage));
    }

    const result: ApiResponse<ChatResponse> = await response.json();
    if (!result.success) {
      throw new Error(getFriendlyErrorMessage(result.message || '요청 처리 중 문제가 생겼어요.'));
    }

    return result.data;
  }

  /**
   * @deprecated v2.9.86에서 startChatWithImages로 대체됨
   * 하위 호환성을 위해 유지
   */
  async startChatWithImage(prompt: string, image: File, creatorId?: number): Promise<ChatResponse> {
    return this.startChatWithImages(prompt, [image], creatorId);
  }

  /**
   * 채팅 메시지 전송
   */
  async sendMessage(chatId: number, message: string): Promise<ChatResponse> {
    return this.request<ChatResponse>(`/api/chat/${chatId}/message`, {
      method: 'POST',
      body: JSON.stringify({ message }),
    });
  }

  /**
   * 채팅 상세 조회
   * v2.9.134: 백엔드 응답 직접 사용 (genreId → creatorId 통합)
   */
  async getChatDetail(chatId: number): Promise<ChatDetail> {
    return this.request<ChatDetail>(`/api/chat/${chatId}`);
  }

  /**
   * 채팅 목록 조회
   */
  async getChatList(): Promise<ChatSummary[]> {
    return this.request<ChatSummary[]>('/api/chat');
  }

  /**
   * 채팅 삭제
   */
  async deleteChat(chatId: number): Promise<void> {
    return this.request<void>(`/api/chat/${chatId}`, {
      method: 'DELETE',
    });
  }

  // ============ 콘텐츠 생성 API ============

  /**
   * 시나리오 조회 (기존 생성된 시나리오)
   */
  async getScenario(chatId: number): Promise<ScenarioResponse | null> {
    return this.request<ScenarioResponse | null>(`/api/content/${chatId}/scenario`);
  }

  /**
   * 시나리오 생성
   */
  async generateScenario(chatId: number, options?: ScenarioRequest): Promise<ScenarioResponse> {
    return this.request<ScenarioResponse>(`/api/content/${chatId}/scenario`, {
      method: 'POST',
      body: JSON.stringify(options || {}),
      timeout: LONG_TIMEOUT_MS,
    });
  }

  /**
   * v2.9.75: 시나리오 생성 진행 상황 조회
   */
  async getScenarioProgress(chatId: number): Promise<ScenarioProgressResponse> {
    return this.request<ScenarioProgressResponse>(`/api/content/${chatId}/scenario/progress`);
  }

  /**
   * 시나리오 다운로드 정보 조회
   */
  async getScenarioDownloadInfo(chatId: number): Promise<DownloadInfo> {
    return this.request<DownloadInfo>(`/api/content/${chatId}/scenario/download`);
  }

  /**
   * 시나리오 TXT 파일 다운로드
   */
  async downloadScenario(chatId: number): Promise<Blob> {
    return this.downloadFile(`/api/content/${chatId}/scenario/file`);
  }

  /**
   * 이미지 생성 시작
   */
  async generateImages(chatId: number): Promise<ImagesResponse> {
    return this.request<ImagesResponse>(`/api/content/${chatId}/images`, {
      method: 'POST',
    });
  }

  /**
   * 이미지 생성 진행 상황 조회
   */
  async getImagesProgress(chatId: number): Promise<ImagesResponse> {
    return this.request<ImagesResponse>(`/api/content/${chatId}/images/progress`);
  }

  /**
   * 이미지 다운로드 정보 조회 (v2.9.8: 별도 info 엔드포인트 사용)
   */
  async getImagesDownloadInfo(chatId: number): Promise<DownloadInfo> {
    return this.request<DownloadInfo>(`/api/content/${chatId}/images/info`);
  }

  /**
   * 이미지 ZIP 파일 다운로드
   * v2.9.8: S3 presigned URL을 먼저 가져온 후 직접 다운로드 (인증 우회)
   */
  async downloadImages(chatId: number): Promise<void> {
    const info = await this.getImagesDownloadInfo(chatId);
    if (info.downloadUrl) {
      this.downloadViaAnchor(info.downloadUrl, `images_${chatId}.zip`);
    } else {
      const blob = await this.downloadFile(`/api/content/${chatId}/images/download`);
      this.downloadBlob(blob, `images_${chatId}.zip`);
    }
  }

  /**
   * 오디오 생성 시작
   */
  async generateAudio(chatId: number): Promise<AudioResponse> {
    return this.request<AudioResponse>(`/api/content/${chatId}/audio`, {
      method: 'POST',
    });
  }

  /**
   * 오디오 생성 진행 상황 조회
   */
  async getAudioProgress(chatId: number): Promise<AudioResponse> {
    return this.request<AudioResponse>(`/api/content/${chatId}/audio/progress`);
  }

  /**
   * 오디오 다운로드 정보 조회 (v2.9.8: 별도 info 엔드포인트 사용)
   */
  async getAudioDownloadInfo(chatId: number): Promise<DownloadInfo> {
    return this.request<DownloadInfo>(`/api/content/${chatId}/audio/info`);
  }

  /**
   * 오디오 MP3 파일 다운로드
   * v2.9.8: S3 presigned URL을 먼저 가져온 후 직접 다운로드 (인증 우회)
   */
  async downloadAudio(chatId: number): Promise<void> {
    const info = await this.getAudioDownloadInfo(chatId);
    if (info.downloadUrl) {
      this.downloadViaAnchor(info.downloadUrl, `audio_${chatId}.mp3`);
    } else {
      const blob = await this.downloadFile(`/api/content/${chatId}/audio/download`);
      this.downloadBlob(blob, `audio_${chatId}.mp3`);
    }
  }

  /**
   * 영상 생성 시작
   */
  async generateVideo(chatId: number, options?: VideoRequest): Promise<VideoResponse> {
    return this.request<VideoResponse>(`/api/content/${chatId}/video`, {
      method: 'POST',
      body: JSON.stringify(options || {}),
      timeout: LONG_TIMEOUT_MS,
    });
  }

  /**
   * 영상 생성 진행 상황 조회
   */
  async getVideoProgress(chatId: number): Promise<VideoResponse> {
    return this.request<VideoResponse>(`/api/content/${chatId}/video/progress`);
  }

  /**
   * 영상 다운로드 정보 조회 (v2.9.8: 별도 info 엔드포인트 사용)
   */
  async getVideoDownloadInfo(chatId: number): Promise<DownloadInfo> {
    return this.request<DownloadInfo>(`/api/content/${chatId}/video/info`);
  }

  /**
   * 썸네일 생성 (v2.9.165: thumbnailId로 디자인 스타일 선택 가능)
   */
  async generateThumbnail(chatId: number, thumbnailId?: number): Promise<ThumbnailResponse> {
    const params = thumbnailId ? `?thumbnailId=${thumbnailId}` : '';
    return this.request<ThumbnailResponse>(`/api/content/${chatId}/thumbnail${params}`, {
      method: 'POST',
    });
  }

  /**
   * v2.9.165: 썸네일 디자인 스타일 목록 조회
   */
  async getVideoThumbnailStyles(): Promise<VideoThumbnailStyle[]> {
    return this.request<VideoThumbnailStyle[]>('/api/content/thumbnail-styles');
  }

  /**
   * 영상 MP4 파일 다운로드
   * v2.9.8: S3 presigned URL을 먼저 가져온 후 직접 다운로드 (인증 우회)
   */
  async downloadVideo(chatId: number): Promise<void> {
    // 1. presigned URL 가져오기 (인증된 API 호출)
    const info = await this.getVideoDownloadInfo(chatId);

    // 2. S3 presigned URL이 있으면 직접 다운로드 (인증 불필요)
    if (info.downloadUrl) {
      this.downloadViaAnchor(info.downloadUrl, `video_${chatId}.mp4`);
    } else {
      // 로컬 파일 폴백
      const blob = await this.downloadFile(`/api/content/${chatId}/video/download`);
      this.downloadBlob(blob, `video_${chatId}.mp4`);
    }
  }

  /**
   * 전체 진행 상황 조회
   */
  async getProgress(chatId: number): Promise<ProgressResponse> {
    return this.request<ProgressResponse>(`/api/content/${chatId}/progress`);
  }

  // ============ v2.4.0 씬 기반 파이프라인 API ============

  /**
   * 씬 생성 시작 (이미지/오프닝 영상 + TTS + 자막 + 개별 씬 영상)
   */
  async generateScenes(chatId: number, options?: ScenesGenerateRequest): Promise<ScenesGenerateResponse> {
    return this.request<ScenesGenerateResponse>(`/api/content/${chatId}/scenes`, {
      method: 'POST',
      body: JSON.stringify(options || {}),
    });
  }

  /**
   * 씬 생성 진행 상황 조회
   */
  async getScenesProgress(chatId: number): Promise<ScenesGenerateResponse> {
    return this.request<ScenesGenerateResponse>(`/api/content/${chatId}/scenes/progress`);
  }

  /**
   * 씬 검토 (모든 씬 상태 조회)
   */
  async getScenesReview(chatId: number): Promise<ScenesReviewResponse> {
    return this.request<ScenesReviewResponse>(`/api/content/${chatId}/scenes/review`);
  }

  /**
   * 특정 씬 재생성
   */
  async regenerateScene(chatId: number, request: SceneRegenerateRequest): Promise<SceneRegenerateResponse> {
    return this.request<SceneRegenerateResponse>(`/api/content/${chatId}/scenes/regenerate`, {
      method: 'POST',
      body: JSON.stringify(request),
    });
  }

  /**
   * 씬 ZIP 다운로드 정보 조회
   */
  async getScenesZipInfo(chatId: number): Promise<ScenesZipInfo> {
    return this.request<ScenesZipInfo>(`/api/content/${chatId}/scenes/zip`);
  }

  /**
   * 씬 ZIP 파일 다운로드
   * v2.9.8: S3 presigned URL 방식 - 인증된 API로 URL 조회 후 다운로드
   */
  async downloadScenes(chatId: number): Promise<void> {
    // 1. presigned URL 가져오기 (인증된 API 호출)
    const info = await this.getScenesZipInfo(chatId);

    // 2. S3 presigned URL이 있으면 직접 다운로드 (인증 불필요)
    if (info.downloadUrl) {
      this.downloadViaAnchor(info.downloadUrl, info.filename || `scenes_${chatId}.zip`);
    } else {
      // 로컬 파일 폴백
      const blob = await this.downloadFile(`/api/content/${chatId}/scenes/download`);
      this.downloadBlob(blob, info.filename || `scenes_${chatId}.zip`);
    }
  }

  /**
   * 최종 영상 생성 (모든 씬 합성)
   */
  async generateFinalVideo(chatId: number, options?: FinalVideoRequest): Promise<FinalVideoResponse> {
    return this.request<FinalVideoResponse>(`/api/content/${chatId}/final-video`, {
      method: 'POST',
      body: JSON.stringify(options || {}),
      timeout: LONG_TIMEOUT_MS,
    });
  }

  /**
   * 최종 영상 진행 상황 조회
   */
  async getFinalVideoProgress(chatId: number): Promise<FinalVideoResponse> {
    return this.request<FinalVideoResponse>(`/api/content/${chatId}/final-video/progress`);
  }

  // ============ v2.5.0 씬 프리뷰 및 나레이션 편집 API ============

  /**
   * 씬 프리뷰 생성 (이미지/영상만 먼저)
   */
  async generateScenePreview(chatId: number, options?: ScenePreviewRequest): Promise<ScenePreviewResponse> {
    return this.request<ScenePreviewResponse>(`/api/content/${chatId}/scenes/preview`, {
      method: 'POST',
      body: JSON.stringify(options || {}),
      timeout: LONG_TIMEOUT_MS,
    });
  }

  /**
   * 씬 프리뷰 조회
   */
  async getScenePreview(chatId: number): Promise<ScenePreviewResponse> {
    return this.request<ScenePreviewResponse>(`/api/content/${chatId}/scenes/preview`);
  }

  /**
   * 씬 나레이션 편집
   */
  async editSceneNarration(chatId: number, request: SceneNarrationEditRequest): Promise<SceneNarrationEditResponse> {
    return this.request<SceneNarrationEditResponse>(`/api/content/${chatId}/scenes/narration`, {
      method: 'PUT',
      body: JSON.stringify(request),
    });
  }

  /**
   * TTS/자막 생성 (나레이션 편집 완료 후)
   */
  async generateSceneAudio(chatId: number, options?: SceneAudioGenerateRequest): Promise<SceneAudioGenerateResponse> {
    return this.request<SceneAudioGenerateResponse>(`/api/content/${chatId}/scenes/audio`, {
      method: 'POST',
      body: JSON.stringify(options || {}),
      timeout: LONG_TIMEOUT_MS,
    });
  }

  /**
   * TTS/자막 생성 진행 상황 조회
   */
  async getSceneAudioProgress(chatId: number): Promise<SceneAudioGenerateResponse> {
    return this.request<SceneAudioGenerateResponse>(`/api/content/${chatId}/scenes/audio/progress`);
  }

  // ============ v2.6.0 부분 실패 복구 API ============

  /**
   * 실패한 씬 목록 조회
   */
  async getFailedScenes(chatId: number): Promise<FailedScenesRetryResponse> {
    return this.request<FailedScenesRetryResponse>(`/api/content/${chatId}/scenes/failed`);
  }

  /**
   * 실패한 씬 재시도
   */
  async retryFailedScenes(chatId: number, options?: FailedScenesRetryRequest): Promise<FailedScenesRetryResponse> {
    return this.request<FailedScenesRetryResponse>(`/api/content/${chatId}/scenes/retry`, {
      method: 'POST',
      body: JSON.stringify(options || {}),
    });
  }

  /**
   * 진행 상태 체크포인트 조회
   */
  async getProcessCheckpoint(chatId: number): Promise<ProcessCheckpoint> {
    return this.request<ProcessCheckpoint>(`/api/content/${chatId}/checkpoint`);
  }

  /**
   * 프로세스 재개 (중단된 작업 계속)
   */
  async resumeProcess(chatId: number, options?: ProcessResumeRequest): Promise<ProcessResumeResponse> {
    return this.request<ProcessResumeResponse>(`/api/content/${chatId}/resume`, {
      method: 'POST',
      body: JSON.stringify(options || {}),
    });
  }

  // ============ 크리에이터 API (v2.9.121: Genre → Creator 리네이밍) ============

  /**
   * v2.9.134: CreatorItem → GenreItem 변환 (동일 구조)
   * v2.9.126: icon, description, targetAudience 삭제
   * v2.9.127: showOnHome 삭제, homeDescription → description 변경
   */
  private mapCreatorToGenre(creator: CreatorItem): GenreItem {
    return {
      creatorId: creator.creatorId,
      creatorCode: creator.creatorCode,
      creatorName: creator.creatorName,
      // v2.9.127: showOnHome 삭제 (isActive로 기능 통합)
      placeholderText: creator.placeholderText,
      description: creator.description,
      allowImageUpload: creator.allowImageUpload,
      tierCode: creator.tierCode,
      nationCode: creator.nationCode,    // v2.9.174: 국가 코드 전파
    };
  }

  /**
   * 활성화된 크리에이터 목록 조회
   * 백엔드에서 CreatorItem[] 배열을 직접 반환
   */
  async getCreators(): Promise<{ creators: CreatorItem[]; totalCount: number }> {
    const creators = await this.request<CreatorItem[]>('/api/creators');
    return { creators, totalCount: creators.length };
  }

  /**
   * 크리에이터 상세 조회
   */
  async getCreator(creatorId: number): Promise<CreatorItem> {
    return this.request<CreatorItem>(`/api/creators/${creatorId}`);
  }

  /**
   * v2.9.103: 홈 화면에 표시되는 크리에이터 목록 조회
   */
  async getCreatorsForHome(): Promise<{ creators: CreatorItem[]; totalCount: number }> {
    const creators = await this.request<CreatorItem[]>('/api/creators/home');
    return { creators, totalCount: creators.length };
  }

  // v2.9.121: 하위 호환성을 위한 별칭 메서드 (기존 코드와 호환)
  async getGenres(): Promise<{ genres: GenreItem[]; totalCount: number }> {
    const { creators, totalCount } = await this.getCreators();
    const genres = creators.map(c => this.mapCreatorToGenre(c));
    return { genres, totalCount };
  }

  async getGenre(creatorId: number): Promise<GenreItem> {
    const creator = await this.getCreator(creatorId);
    return this.mapCreatorToGenre(creator);
  }

  async getGenresForHome(): Promise<{ genres: GenreItem[]; totalCount: number }> {
    const { creators, totalCount } = await this.getCreatorsForHome();
    const genres = creators.map(c => this.mapCreatorToGenre(c));
    return { genres, totalCount };
  }

  /**
   * v2.9.150: 사용자에게 연결된 크리에이터 정보 조회
   * 계정에 1:1로 매핑된 크리에이터 정보를 반환
   */
  async getMyLinkedCreator(): Promise<LinkedCreatorInfo | null> {
    return this.request<LinkedCreatorInfo | null>('/api/chat/my-creator');
  }

  // ============ 영상 포맷 API (v2.9.25) ============

  /**
   * 영상 포맷 목록 조회
   */
  async getFormats(): Promise<VideoFormatsResponse> {
    return this.request<VideoFormatsResponse>('/api/formats');
  }

  /**
   * 영상 포맷 상세 조회
   */
  async getFormat(formatId: number): Promise<VideoFormat> {
    return this.request<VideoFormat>(`/api/formats/${formatId}`);
  }


  // ============ 비디오 폰트 API (v2.9.174) ============

  /**
   * 국가별 폰트 목록 조회
   */
  async getFontsByNation(nationCode: string): Promise<FontsResponse> {
    return this.request<FontsResponse>(`/api/video-fonts/nation/${nationCode}`);
  }

  /**
   * 전체 폰트 목록 조회
   */
  async getFonts(): Promise<FontsResponse> {
    return this.request<FontsResponse>('/api/video-fonts');
  }

  // ============ 영상 자막 템플릿 API (v2.9.161) ============

  /**
   * 자막 템플릿 목록 조회
   */
  async getVideoSubtitles(): Promise<VideoSubtitlesResponse> {
    return this.request<VideoSubtitlesResponse>('/api/video-subtitles');
  }

  /**
   * 자막 템플릿 상세 조회
   */
  async getVideoSubtitle(videoSubtitleId: number): Promise<VideoSubtitle> {
    return this.request<VideoSubtitle>(`/api/video-subtitles/${videoSubtitleId}`);
  }

  // ============ 유틸리티 ============

  /**
   * 파일 다운로드 헬퍼
   */
  downloadBlob(blob: Blob, filename: string): void {
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    window.URL.revokeObjectURL(url);
  }
}

export const api = new ApiClient(API_BASE_URL);
