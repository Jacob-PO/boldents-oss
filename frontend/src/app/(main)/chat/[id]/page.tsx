'use client';

import { useEffect, useState, useRef } from 'react';
import { useParams, useRouter } from 'next/navigation';
import Image from 'next/image';
import {
  api,
  type ScenarioResponse,
  type ScenarioProgressResponse,
  type ImagesResponse,
  type AudioResponse,
  type VideoResponse,
  type ScenePreviewResponse,
  type ScenePreviewInfo,
  type SceneAudioGenerateResponse,
  type VideoThumbnailStyle,
  type VideoSubtitle  // v2.9.176: 자막 템플릿 동적 로딩
} from '@/lib/api';

import ChatChoices, { type ChatChoice } from '@/components/chat/ChatChoices';
import GenreSelector from '@/components/chat/GenreSelector';
import type { GenreItem } from '@/lib/api';
import { ScenePreviewList } from '@/components/chat/ScenePreviewCard';
import { VideoResultMessage } from '@/components/chat/VideoResultMessage';  // v2.9.27
import { ThumbnailResultMessage } from '@/components/chat/ThumbnailResultMessage';  // v2.9.27

// 플로우 상태 타입
type FlowStep =
  | 'INITIAL'           // 첫 입력 대기
  | 'GENRE_SELECT'      // v2.8.0: 장르 선택
  | 'SLIDE_COUNT_SELECT'   // v2.9.73: 슬라이드 수 선택
  | 'FORMAT_SELECT'     // v2.9.25: 영상 포맷 선택
  | 'SUBTITLE_SELECT'   // v2.9.161: 자막 템플릿 선택
  | 'FONT_SELECT'       // v2.9.174: 폰트 선택
  | 'FONT_SIZE_SELECT'  // v2.9.161: 자막 글자 크기 선택
  | 'POSITION_SELECT'   // v2.9.167: 자막 위치 선택
  | 'THUMBNAIL_SELECT'  // v2.9.168: 썸네일 디자인 선택
  | 'SCENARIO_GENERATING' // v2.9.75: 시나리오 생성 중 (프로그레스바)
  | 'GENERATING'        // 시나리오 생성 중 (레거시)
  | 'SCENARIO_REVIEW'   // 시나리오 확인/수정
  | 'OPENING_REVIEW'    // 오프닝 내용 확인/수정
  | 'READY_TO_GENERATE' // 이미지 생성 준비
  | 'PREVIEWS_GENERATING' // v2.5.0: 씬 프리뷰 생성 중 (이미지/영상만)
  | 'PREVIEWS_DONE'       // v2.5.0: 씬 프리뷰 완료 (나레이션 편집 가능)
  | 'TTS_GENERATING'      // v2.5.0: TTS/자막 생성 중
  | 'IMAGES_GENERATING' // 이미지 생성 중
  | 'IMAGES_DONE'       // 이미지 완료
  | 'AUDIO_GENERATING'  // 오디오 생성 중
  | 'AUDIO_DONE'        // 오디오 완료
  | 'VIDEO_GENERATING'  // 영상 합성 중
  | 'VIDEO_DONE';       // 완료

// 채팅 메시지 타입
interface ChatItem {
  id: string;
  type: 'user' | 'assistant' | 'choices' | 'progress' | 'summary' | 'opening' | 'scene_previews' | 'genre_selector' | 'video_result' | 'thumbnail_result' | 'slide_count_selector';
  content?: string;
  choices?: ChatChoice[];
  choiceType?: string;
  progress?: { current: number; total: number; message: string };
  summary?: ScenarioResponse;
  opening?: { narration: string; videoPrompt: string };  // 오프닝 영상 정보
  videoResult?: {  // v2.9.27: 최종 영상 결과
    videoUrl: string;
    title: string;
  };
  thumbnailResult?: {  // v2.9.27: 썸네일 결과
    thumbnailUrl: string;
    youtubeTitle: string;
    youtubeDescription: string;
    catchphrase: string;
  };
  selected?: string;  // 선택된 항목 ID
  genreConfirmed?: boolean;  // v2.8.0: 장르 선택 완료 여부
  scenePreviews?: ScenePreviewInfo[];  // v2.5.0: 씬 프리뷰 목록
  aspectRatio?: string;  // v2.9.25: 영상 포맷 비율 ("16:9" 또는 "9:16")
  slideCountConfirmed?: boolean;  // v2.9.73: 슬라이드 수 선택 완료 여부
}

// v2.9.30: 진행 중인 콘텐츠 생성 chatId 추출
const extractInProgressChatId = (error: unknown): number | null => {
  const errorMsg = error instanceof Error ? error.message : String(error);
  // "다른 영상이 생성 중입니다 (채팅 #123). 완료 후 다시 시도해주세요."
  const match = errorMsg.match(/채팅 #(\d+)/);
  return match ? parseInt(match[1], 10) : null;
};

// v2.9.95: 에러 메시지 파싱 (고객 친화적 메시지)
const parseErrorMessage = (error: unknown): string => {
  const errorMsg = error instanceof Error ? error.message : String(error);
  const lowerMsg = errorMsg.toLowerCase();

  // 콘솔에 실제 에러 로그 출력 (디버깅용)
  console.error('[Error Details]', errorMsg);

  // v2.9.30: 진행 중인 콘텐츠 생성 에러 (CV005) - 예외 처리
  if (lowerMsg.includes('다른 영상이 생성 중') || lowerMsg.includes('cv005') || lowerMsg.includes('content_generation_in_progress')) {
    return '이미 영상을 만들고 있어요. 진행 중인 영상이 완료되면 새로운 영상을 만들 수 있어요.';
  }

  // v2.9.95: 고객 친화적 에러 메시지 (api.ts에서 이미 변환된 경우 그대로 사용)
  // 이미 친절한 메시지면 그대로 반환
  if (errorMsg.includes('휴식이') || errorMsg.includes('문제가') || errorMsg.includes('다시 시도') ||
      errorMsg.includes('연결이') || errorMsg.includes('만료됐어요') || errorMsg.includes('준비')) {
    return errorMsg;
  }

  // Rate limit / quota 관련
  if (lowerMsg.includes('rate') || lowerMsg.includes('limit') || lowerMsg.includes('quota') ||
      lowerMsg.includes('사용한도') || lowerMsg.includes('초과')) {
    return '잠시 휴식이 필요해요. 1분 후에 다시 시도해주세요.';
  }

  // 네트워크 관련
  if (lowerMsg.includes('network') || lowerMsg.includes('fetch') || lowerMsg.includes('connection')) {
    return '인터넷 연결이 불안정해요. 연결 상태를 확인해주세요.';
  }

  // 서버 에러
  if (lowerMsg.includes('500') || lowerMsg.includes('502') || lowerMsg.includes('503') || lowerMsg.includes('server')) {
    return '서버가 잠시 쉬고 있어요. 잠시 후 다시 시도해주세요.';
  }

  // 기본 메시지
  return '문제가 생겼어요. 다시 시도해주세요.';
};

// v2.9.119: 영상 비율 선택지 (슬라이드 수에 따라 쇼츠 활성화/비활성화)
// - 1장만: 일반/쇼츠 둘 다 선택 가능
// - 2장 이상: 일반만 선택 가능 (쇼츠 비활성화)
const getFormatChoices = (slideCount: number): ChatChoice[] => [
  { id: '1', label: '일반 영상', description: '16:9 가로형 (유튜브)', icon: 'video', variant: 'primary' },
  {
    id: '2',
    label: '쇼츠',
    description: slideCount <= 1 ? '9:16 세로형 (쇼츠/릴스/틱톡)' : '1장만 쇼츠 제작 가능',
    icon: 'video',
    variant: 'secondary',
    disabled: slideCount > 1
  },
];

// v2.9.176: 자막 템플릿을 DB에서 동적 로딩 (하드코딩 제거)
const getSubtitleChoicesFromTemplates = (templates: VideoSubtitle[]): ChatChoice[] => {
  return templates.map(t => ({
    id: String(t.videoSubtitleId),
    label: t.subtitleName,
    description: t.description || t.subtitleNameEn,
    icon: 'edit' as const,
    variant: t.isDefault ? 'primary' : 'secondary' as const,
  }));
};

// v2.9.161: 자막 글자 크기 선택지
const FONT_SIZE_CHOICES: ChatChoice[] = [
  { id: '3', label: '큰 글자', description: '기본 크기 (추천)', icon: 'edit', variant: 'primary' },
  { id: '2', label: '중간 글자', description: '80% 크기', icon: 'edit', variant: 'secondary' },
  { id: '1', label: '작은 글자', description: '60% 크기', icon: 'edit', variant: 'secondary' },
];

// v2.9.167: 자막 위치 선택지
const POSITION_CHOICES: ChatChoice[] = [
  { id: '1', label: '하단', description: '기본 위치 (추천)', icon: 'edit', variant: 'primary' },
  { id: '2', label: '중앙', description: '화면 가운데', icon: 'edit', variant: 'secondary' },
  { id: '3', label: '상단', description: '화면 위쪽', icon: 'edit', variant: 'secondary' },
];

// v2.9.119: 슬라이드 수에 따른 예상 시간 계산 (분)
// - 이미지 한 장당 2분
const calculateEstimatedMinutes = (slideCount: number): number => {
  return slideCount * 2;
};

// v2.9.11: 시나리오 확인 선택지 제거됨 - 자동 진행으로 대체

// 오프닝 확인 선택지 (오프닝 필수 - 재생성 옵션 제거, 바로 이미지 생성)
const OPENING_CONFIRM_CHOICES: ChatChoice[] = [
  { id: 'confirm', label: '이미지 생성하기', description: '오프닝 영상과 슬라이드 이미지를 만듭니다', icon: 'video', variant: 'primary' },
];

// v2.9.165: 썸네일 스타일 선택지 (API에서 동적으로 로드, 폴백용 기본 선택지)
const THUMBNAIL_CHOICES_FALLBACK: ChatChoice[] = [
  { id: 'generate_thumbnail_default', label: '클래식', description: '노란색 텍스트, 심플한 스타일', icon: 'image', variant: 'primary' },
];

export default function ChatRoomPage() {
  const params = useParams();
  const router = useRouter();
  const chatId = Number(params.id);

  // 플로우 상태
  const [flowStep, setFlowStep] = useState<FlowStep>('INITIAL');
  const [chatItems, setChatItems] = useState<ChatItem[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [isInitialLoading, setIsInitialLoading] = useState(true);
  const [isRetrying, setIsRetrying] = useState(false);  // v2.6.0: 실패 씬 재시도 중

  // 선택된 값들
  const [selectedCreatorId, setSelectedCreatorId] = useState<number | null>(null);  // v2.9.134: 선택된 크리에이터 ID
  const [selectedSlideCount, setSelectedSlideCount] = useState<number>(1);  // v2.9.99: 슬라이드 수 (기본값 1장, 최대 10장)
  const [selectedFormatId, setSelectedFormatId] = useState<number>(1);  // v2.9.161: 선택된 포맷 ID
  const [selectedVideoSubtitleId, setSelectedVideoSubtitleId] = useState<number>(1);  // v2.9.161: 선택된 자막 템플릿 ID
  const [selectedFontId, setSelectedFontId] = useState<number>(1);  // v2.9.174: 선택된 폰트 ID (기본값: SUIT-Bold)
  const [creatorNationCode, setCreatorNationCode] = useState<string>('KR');  // v2.9.174: 크리에이터 국가 코드
  const [includeOpening, setIncludeOpening] = useState(true);  // 오프닝 필수

  // 콘텐츠 상태
  const [scenario, setScenario] = useState<ScenarioResponse | null>(null);
  const [scenarioProgress, setScenarioProgress] = useState<ScenarioProgressResponse | null>(null);  // v2.9.75
  const [imagesProgress, setImagesProgress] = useState<ImagesResponse | null>(null);
  const [audioProgress, setAudioProgress] = useState<AudioResponse | null>(null);
  const [videoProgress, setVideoProgress] = useState<VideoResponse | null>(null);

  // v2.5.0: 씬 프리뷰 상태
  const [scenePreviewProgress, setScenePreviewProgress] = useState<ScenePreviewResponse | null>(null);
  const [scenePreviews, setScenePreviews] = useState<ScenePreviewInfo[]>([]);
  const [ttsProgress, setTtsProgress] = useState<SceneAudioGenerateResponse | null>(null);

  // v2.9.12: 최종 영상 프리뷰 URL
  const [, setFinalVideoUrl] = useState<string | null>(null);

  // v2.9.38: presigned URL 만료 관리
  const [isDownloadExpired, setIsDownloadExpired] = useState(false);

  // v2.9.84: 참조 이미지 URL
  const [referenceImageUrl, setReferenceImageUrl] = useState<string | null>(null);

  // v2.9.168: 썸네일 디자인 스타일 + 선택된 ID
  const [thumbnailStyles, setThumbnailStyles] = useState<VideoThumbnailStyle[]>([]);
  const [selectedThumbnailId, setSelectedThumbnailId] = useState<number | undefined>(undefined);

  // 폴링 ref
  const pollingRef = useRef<NodeJS.Timeout | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  // v2.9.1: 현재 폴링 중인 chatId 추적 (다른 채팅으로 전환 시 폴링 중지)
  const currentPollingChatIdRef = useRef<number | null>(null);
  // v2.9.75: 자동 실행 중복 방지 (PREVIEWS_DONE에서 TTS 자동 시작)
  const autoTtsExecutedRef = useRef<boolean>(false);
  // v2.9.75: 상태 복원 중인지 여부 (새로고침 시 자동 실행 방지)
  const isRestoringStateRef = useRef<boolean>(false);

  // 폴링 에러 상태 (503 등 일시적 에러 추적)
  const [pollingErrorCount, setPollingErrorCount] = useState(0);
  const [pollingErrorMessage, setPollingErrorMessage] = useState<string | null>(null);
  // v2.9.171: stale closure 해결 - setInterval 콜백에서 ref 사용
  const pollingErrorCountRef = useRef(0);

  // v2.9.171: 폴링 실패 시 백엔드 상태 확인하여 자동 복구
  const MAX_POLLING_ERRORS = 15; // ~45초(3초 간격) 후 복구 시도

  // v2.9.1: 폴링 안전 종료 헬퍼
  const stopCurrentPolling = () => {
    if (pollingRef.current) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }
    currentPollingChatIdRef.current = null;
    pollingErrorCountRef.current = 0;
    setPollingErrorCount(0);
    setPollingErrorMessage(null);
  };

  // v2.9.171: 폴링 연속 실패 시 백엔드 실제 상태 확인하여 자동 복구
  const recoverFromPollingFailure = async () => {
    console.log('[v2.9.171] Attempting recovery via getChatDetail...');
    try {
      const detail = await api.getChatDetail(chatId);
      const stage = detail.stage;
      console.log('[v2.9.171] Backend stage:', stage);

      // 백엔드가 아직 작업 중 → 에러 카운트 리셋, 폴링 계속
      if (stage.endsWith('_GENERATING')) {
        pollingErrorCountRef.current = 0;
        setPollingErrorCount(0);
        setPollingErrorMessage('서버에서 작업 중이에요. 잠시만 기다려주세요...');
        return; // 폴링 계속
      }

      // 백엔드가 완료됨 → 폴링 중지, 전체 상태 복원
      if (stage.endsWith('_DONE') || stage === 'SCENARIO_DONE' || stage === 'SCENARIO_READY') {
        console.log('[v2.9.171] Backend completed! Restoring state via loadChat...');
        stopCurrentPolling();
        setIsLoading(false);
        setPollingErrorMessage(null);
        await loadChat();
        return;
      }

      // 백엔드가 실패함 → 폴링 중지, 에러 메시지 + 새로고침 안내
      if (stage.endsWith('_FAILED') || stage === 'VIDEO_FAILED') {
        stopCurrentPolling();
        setIsLoading(false);
        addMessage({
          type: 'assistant',
          content: '작업 중 문제가 발생했어요. 페이지를 새로고침한 후 다시 시도해주세요.'
        });
        return;
      }

      // 기타 상태 → 폴링 중지, 상태 복원
      stopCurrentPolling();
      setIsLoading(false);
      await loadChat();
    } catch (recoveryErr) {
      console.error('[v2.9.171] Recovery failed:', recoveryErr);
      stopCurrentPolling();
      setIsLoading(false);
      addMessage({
        type: 'assistant',
        content: '연결에 문제가 생겼어요. 페이지를 새로고침해주세요.'
      });
    }
  };

  // 스크롤
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [chatItems]);

  // 초기 로드
  useEffect(() => {
    if (chatId) {
      loadChat();
    }
    return () => {
      if (pollingRef.current) clearInterval(pollingRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chatId]);

  // v2.9.134: 크리에이터 선택 완료 처리
  const handleGenreSelect = (creatorId: number, genre: GenreItem) => {
    setSelectedCreatorId(creatorId);
    // v2.9.174: 국가 코드 저장 (폰트 필터링용)
    if (genre.nationCode) {
      setCreatorNationCode(genre.nationCode);
    }

    // 장르 선택 UI 완료 표시
    setChatItems(prev => prev.map(item =>
      item.type === 'genre_selector'
        ? { ...item, genreConfirmed: true }
        : item
    ));

    // 선택한 장르 표시
    addMessage({
      type: 'user',
      content: `선택한 장르: ${genre.creatorName}`
    });

    addMessage({
      type: 'assistant',
      content: `${genre.creatorName} 콘텐츠를 만들어 드릴게요!\n\n슬라이드 수를 선택해주세요. (이미지 한 장당 약 2분)`
    });

    // 슬라이드 수 선택 단계로 이동
    addMessage({
      type: 'slide_count_selector'
    });

    setFlowStep('SLIDE_COUNT_SELECT');
  };

  const loadChat = async () => {
    // v2.9.75: 상태 복원 중 플래그 설정 (자동 실행 방지)
    isRestoringStateRef.current = true;
    // v2.9.75: autoTtsExecutedRef는 TTS 완료 후 상태(AUDIO_DONE, VIDEO_*) 복원 시에만 설정
    // PREVIEWS_DONE 복원 시에는 설정하지 않아 자동 TTS가 실행될 수 있도록 함

    try {
      const data = await api.getChatDetail(chatId);

      // 기존 메시지에서 원본 프롬프트 추출
      const firstUserMessage = data.messages?.find(m => m.role === 'user');

      // v2.8.0: 장르 정보 복원 (페이지 새로고침 시)
      if (data.creatorId) {
        setSelectedCreatorId(data.creatorId);
      }

      // v2.9.84: 참조 이미지 URL 복원
      if (data.referenceImageUrl) {
        setReferenceImageUrl(data.referenceImageUrl);
      }

      // stage에 따라 상태 복원
      const stage = data.stage;
      const contentStatus = data.contentStatus;

      // 메시지 히스토리 복원 및 현재 stage에 따른 UI 구성
      const items: ChatItem[] = [];

      if (firstUserMessage) {
        items.push({
          id: 'user-initial',
          type: 'user',
          content: firstUserMessage.content
        });
      }

      // v2.9.27: VIDEO_RESULT, THUMBNAIL_RESULT 메시지 복원
      data.messages?.forEach((msg, idx) => {
        if (msg.messageType === 'VIDEO_RESULT' && msg.metadata) {
          try {
            const metadata = JSON.parse(msg.metadata);
            items.push({
              id: `video-result-${idx}`,
              type: 'video_result',
              videoResult: {
                videoUrl: metadata.videoUrl,
                title: metadata.title
              }
            });
          } catch (e) {
            console.error('Failed to parse VIDEO_RESULT metadata:', e);
          }
        } else if (msg.messageType === 'THUMBNAIL_RESULT' && msg.metadata) {
          try {
            const metadata = JSON.parse(msg.metadata);
            items.push({
              id: `thumbnail-result-${idx}`,
              type: 'thumbnail_result',
              thumbnailResult: {
                thumbnailUrl: metadata.thumbnailUrl,
                youtubeTitle: metadata.youtubeTitle,
                youtubeDescription: metadata.youtubeDescription,
                catchphrase: metadata.catchphrase
              }
            });
          } catch (e) {
            console.error('Failed to parse THUMBNAIL_RESULT metadata:', e);
          }
        }
      });

      // 시나리오 데이터 임시 저장 (위에서 로드한 데이터)
      let loadedScenario: ScenarioResponse | null = null;
      if (contentStatus?.scenarioReady) {
        try {
          loadedScenario = await api.getScenario(chatId);
          if (loadedScenario) {
            setScenario(loadedScenario);
          }
        } catch (err) {
          console.error('Failed to load scenario:', err);
        }
      }

      // 진행 중인 작업이 있는지 확인
      const progressInfo = await api.getProgress(chatId).catch(() => null);
      const isProcessing = progressInfo?.status === 'processing';

      // 진행 중이면 해당 상태로 복원하고 폴링 시작
      if (isProcessing && progressInfo) {
        if (loadedScenario) {
          items.push({
            id: 'summary-restored',
            type: 'summary',
            summary: loadedScenario
          });
        }

        if (progressInfo.processType === 'scene_preview') {
          // v2.5.0: 씬 프리뷰 생성 중
          items.push({
            id: 'assistant-generating',
            type: 'assistant',
            content: '씬 프리뷰를 만들고 있어요 ✨\n\n멋진 장면들이 곧 나타날 거예요!'
          });
          setFlowStep('PREVIEWS_GENERATING');
          setIsLoading(true);
          startScenePreviewPolling();
        } else if (progressInfo.processType === 'scene_audio') {
          // v2.5.0: TTS/자막 생성 중
          items.push({
            id: 'assistant-generating',
            type: 'assistant',
            content: '음성과 자막을 만들고 있어요 🎤\n\n영상에 생동감을 불어넣는 중이에요!'
          });
          setFlowStep('TTS_GENERATING');
          setIsLoading(true);
          startTtsPolling();
        } else if (progressInfo.processType === 'images') {
          items.push({
            id: 'assistant-generating',
            type: 'assistant',
            content: '이미지를 만들고 있어요 🎨\n\n아름다운 장면들이 곧 완성돼요!'
          });
          setFlowStep('IMAGES_GENERATING');
          setIsLoading(true);
          startImagePolling();
        } else if (progressInfo.processType === 'audio') {
          items.push({
            id: 'assistant-generating',
            type: 'assistant',
            content: '나레이션을 만들고 있어요 🎙️\n\n목소리에 감정을 담는 중이에요!'
          });
          setFlowStep('AUDIO_GENERATING');
          setIsLoading(true);
          startAudioPolling();
        } else if (progressInfo.processType === 'video' || progressInfo.processType === 'final_video') {
          items.push({
            id: 'assistant-generating',
            type: 'assistant',
            content: '최종 영상을 합성하고 있어요...\n\n오프닝 영상 + 슬라이드 영상들 + 썸네일 2초 영상을 하나로 합칩니다.\n시간이 조금 걸릴 수 있어요.'
          });
          setFlowStep('VIDEO_GENERATING');
          setIsLoading(true);
          startVideoPolling();
        } else if (progressInfo.processType === 'retry_failed') {
          // v2.9.0: 실패 씬 재시도 중
          // 씬 프리뷰 데이터 로드
          try {
            const previewData = await api.getScenePreview(chatId);
            if (previewData.previews && previewData.previews.length > 0) {
              setScenePreviews(previewData.previews);
              items.push({
                id: 'scene-previews-retrying',
                type: 'scene_previews',
                scenePreviews: previewData.previews,
                aspectRatio: previewData.aspectRatio
              });
            }
          } catch (err) {
            console.error('Failed to load scene previews:', err);
          }
          items.push({
            id: 'assistant-retrying',
            type: 'assistant',
            content: `문제가 있던 씬들을 다시 만들고 있어요.\n\n${progressInfo.message || '잠시만 기다려주세요...'}`
          });
          setFlowStep('TTS_GENERATING');
          setIsLoading(true);
          setIsRetrying(true);
          startRetryPolling();
        }

        setChatItems(items);
        setIsInitialLoading(false);
        return;
      }

      // stage에 따라 적절한 UI 구성
      if (stage === 'CHATTING' || stage === 'SCENARIO_READY') {
        // v2.9.107: 홈페이지에서 이미 장르 선택한 경우 → 슬라이드 수 선택으로 바로 이동
        if (data.creatorId) {
          items.push({
            id: 'assistant-slide-count',
            type: 'assistant',
            content: `${data.creatorName || '선택한 장르'} 콘텐츠를 만들어 드릴게요!\n\n슬라이드 수를 선택해주세요. (이미지 한 장당 약 2분)`
          });

          items.push({
            id: 'slide-count-selector',
            type: 'slide_count_selector',
            slideCountConfirmed: false
          });

          setFlowStep('SLIDE_COUNT_SELECT');
        } else {
          // v2.8.0: 장르가 없는 경우 - 장르 선택부터 시작 (레거시 대응)
          items.push({
            id: 'assistant-genre',
            type: 'assistant',
            content: `"${firstUserMessage?.content || ''}" 주제로 영상을 만들게요.\n\n먼저 어떤 장르의 콘텐츠를 만들지 선택해주세요.`
          });

          // 장르 선택 UI 추가
          items.push({
            id: 'genre-selector',
            type: 'genre_selector',
            genreConfirmed: false
          });

          setFlowStep('GENRE_SELECT');
        }
      } else if (stage === 'SCENARIO_GENERATING') {
        // v2.9.75: 시나리오 생성 중 - 프로그레스 폴링 시작
        items.push({
          id: 'scenario_progress',
          type: 'progress',
          progress: {
            current: 0,
            total: selectedSlideCount || 1,
            message: '시나리오 생성이 진행 중입니다...'
          }
        });

        setFlowStep('SCENARIO_GENERATING');
        setIsLoading(true);

        // 시나리오 진행 상황 폴링 시작
        pollScenarioProgress();
      } else if (stage === 'SCENARIO_DONE') {
        // 시나리오 생성 완료 - 오프닝 필수로 바로 오프닝 확인 단계로
        if (loadedScenario) {
          items.push({
            id: 'summary-restored',
            type: 'summary',
            summary: loadedScenario
          });

          // 오프닝이 있으면 바로 표시
          if (loadedScenario.opening) {
            items.push({
              id: 'assistant-restored',
              type: 'assistant',
              content: '이전에 생성한 시나리오입니다.\n\n8초 오프닝 영상을 확인해주세요.'
            });
            items.push({
              id: 'opening-restored',
              type: 'opening',
              opening: {
                narration: loadedScenario.opening.narration,
                videoPrompt: loadedScenario.opening.videoPrompt
              }
            });
            items.push({
              id: 'choices-opening-confirm',
              type: 'choices',
              choices: OPENING_CONFIRM_CHOICES,
              choiceType: 'opening_confirm'
            });
            setFlowStep('OPENING_REVIEW');
          } else {
            // 오프닝이 없는 예외 케이스
            items.push({
              id: 'assistant-restored',
              type: 'assistant',
              content: '이전에 생성한 시나리오입니다.\n\n이미지 생성을 시작해주세요.'
            });
            setFlowStep('READY_TO_GENERATE');
          }
        }
      } else if (stage === 'PREVIEWS_DONE') {
        // v2.5.0: 씬 프리뷰 완료 - 나레이션 편집 가능
        if (loadedScenario) {
          items.push({
            id: 'summary-restored',
            type: 'summary',
            summary: loadedScenario
          });
        }

        // 씬 프리뷰 데이터 로드
        try {
          const previewData = await api.getScenePreview(chatId);
          if (previewData.previews && previewData.previews.length > 0) {
            setScenePreviews(previewData.previews);
            items.push({
              id: 'assistant-previews-done',
              type: 'assistant',
              content: '씬 프리뷰가 생성되었습니다.\n\n아래에서 나레이션을 확인하고 수정하세요.\n수정이 완료되면 TTS를 생성해주세요.'
            });

            // v2.9.49: 썸네일 메시지 복원
            const thumbnailMessage = data.messages?.find(m => m.messageType === 'THUMBNAIL_RESULT');
            if (thumbnailMessage && thumbnailMessage.metadata) {
              try {
                const metadata = JSON.parse(thumbnailMessage.metadata);
                items.push({
                  id: 'thumbnail-result-restored',
                  type: 'thumbnail_result',
                  thumbnailResult: {
                    thumbnailUrl: metadata.thumbnailUrl,
                    youtubeTitle: metadata.youtubeTitle,
                    youtubeDescription: metadata.youtubeDescription,
                    catchphrase: metadata.catchphrase
                  }
                });
              } catch (e) {
                console.error('Failed to parse THUMBNAIL_RESULT:', e);
              }
            }

            items.push({
              id: 'scene-previews-restored',
              type: 'scene_previews',
              scenePreviews: previewData.previews,
              aspectRatio: previewData.aspectRatio
            });
          }
        } catch (err) {
          console.error('Failed to load scene previews:', err);
          items.push({
            id: 'assistant-previews-error',
            type: 'assistant',
            content: '씬 프리뷰를 불러오는 중 문제가 생겼어요. 새로고침해주세요.'
          });
        }
        setFlowStep('PREVIEWS_DONE');
      } else if (stage === 'TTS_PARTIAL_FAILED') {
        // v2.8.3: TTS 일부 실패 - 재시도 필요
        if (loadedScenario) {
          items.push({
            id: 'summary-restored',
            type: 'summary',
            summary: loadedScenario
          });
        }

        // 씬 프리뷰 데이터 로드 (실패한 씬 확인용)
        try {
          const previewData = await api.getScenePreview(chatId);
          if (previewData.previews && previewData.previews.length > 0) {
            setScenePreviews(previewData.previews);
            const failedScenes = previewData.previews.filter(p => p.previewStatus === 'FAILED');
            items.push({
              id: 'scene-previews-restored',
              type: 'scene_previews',
              scenePreviews: previewData.previews,
              aspectRatio: previewData.aspectRatio
            });
            items.push({
              id: 'assistant-tts-partial-failed',
              type: 'assistant',
              content: `${failedScenes.length}개 씬의 음성 생성이 잘 안 됐어요.\n\n아래 "재시도" 버튼을 눌러주세요.`
            });
          }
        } catch (err) {
          console.error('Failed to load scene previews:', err);
          items.push({
            id: 'assistant-tts-partial-failed',
            type: 'assistant',
            content: '일부 음성 생성이 잘 안 됐어요.\n\n아래 "재시도" 버튼을 눌러주세요.'
          });
        }
        setFlowStep('PREVIEWS_DONE'); // 프리뷰 단계로 돌아가서 재시도 가능하게
      } else if (stage === 'TTS_DONE' || stage === 'AUDIO_DONE') {
        // v2.5.0: TTS/자막 완료 또는 레거시 오디오 완료 - 영상 합성 대기
        // v2.9.75: TTS 완료 상태이므로 자동 TTS 실행 방지
        autoTtsExecutedRef.current = true;
        if (loadedScenario) {
          items.push({
            id: 'summary-restored',
            type: 'summary',
            summary: loadedScenario
          });
        }

        // 씬 프리뷰 데이터 로드 (TTS_DONE인 경우)
        if (stage === 'TTS_DONE') {
          try {
            const previewData = await api.getScenePreview(chatId);
            if (previewData.previews && previewData.previews.length > 0) {
              setScenePreviews(previewData.previews);
              items.push({
                id: 'scene-previews-restored',
                type: 'scene_previews',
                scenePreviews: previewData.previews,
                aspectRatio: previewData.aspectRatio
              });
            }
          } catch (err) {
            console.error('Failed to load scene previews:', err);
          }
        }

        items.push({
          id: 'assistant-audio-done',
          type: 'assistant',
          content: '나레이션과 자막이 생성되었습니다.\n\n영상을 합성할 준비가 되었어요.'
        });
        setFlowStep('AUDIO_DONE');
      } else if (stage === 'IMAGES_DONE') {
        // 레거시: 이미지 완료 - 시나리오 표시 후 오디오 생성 대기
        if (loadedScenario) {
          items.push({
            id: 'summary-restored',
            type: 'summary',
            summary: loadedScenario
          });
        }
        items.push({
          id: 'assistant-images-done',
          type: 'assistant',
          content: '이미지 생성이 완료되었습니다.\n\n나레이션을 생성할 준비가 되었어요.'
        });
        setFlowStep('IMAGES_DONE');
      } else if (stage === 'VIDEO_DONE') {
        // v2.9.12: 영상 완료 - 프리뷰 URL 가져오기
        // v2.9.38: 만료 시간 체크 추가
        // v2.9.75: 영상 완료 상태이므로 자동 TTS 실행 방지
        autoTtsExecutedRef.current = true;
        try {
          const videoInfo = await api.getVideoDownloadInfo(chatId);
          if (videoInfo.downloadUrl) {
            setFinalVideoUrl(videoInfo.downloadUrl);
          }

          // v2.9.38: 만료 시간 확인
          if (videoInfo.presignedUrlExpiresAt) {
            const expiresAt = new Date(videoInfo.presignedUrlExpiresAt);
            const now = new Date();
            const expired = now > expiresAt;
            setIsDownloadExpired(expired);
          }
        } catch (urlErr) {
          console.warn('Failed to get video URL for preview:', urlErr);
        }

        if (isDownloadExpired) {
          // 만료된 경우 메시지
          items.push({
            id: 'assistant-video-expired',
            type: 'assistant',
            content: '⏰ 다운로드 기간이 만료되었습니다.\n\n새로운 영상을 생성해주세요.'
          });
          items.push({
            id: 'choices-expired-home',
            type: 'choices',
            choices: [
              { id: 'new_video', label: '새 콘텐츠 생성하러 가기' }
            ],
            choiceType: 'navigate_home'
          });
        } else {
          // 정상인 경우 기존 메시지
          items.push({
            id: 'assistant-video-done',
            type: 'assistant',
            content: '🎬 영상이 완성되었습니다!\n\n⏰ 다운로드 링크는 3시간 동안 유효합니다.\n3시간 이내에 다운로드해주세요.'
          });

          // v2.9.56: 최종 영상 완료 후 썸네일 버튼 제거
          // 썸네일은 최종 영상 합성 전(PREVIEWS_DONE)에만 생성 가능
          // 영상 완료 후에는 썸네일을 추가할 수 없으므로 버튼을 표시하지 않음
        }

        setFlowStep('VIDEO_DONE');
      } else if (stage === 'PREVIEWS_GENERATING' || stage === 'SCENES_GENERATING') {
        // v2.7.2: 씬 프리뷰/이미지 생성 중 - 폴링 시작
        if (loadedScenario) {
          items.push({
            id: 'summary-restored',
            type: 'summary',
            summary: loadedScenario
          });
        }
        items.push({
          id: 'assistant-generating',
          type: 'assistant',
          content: '씬 프리뷰를 만들고 있어요 ✨\n\n멋진 장면들이 곧 나타날 거예요!'
        });
        setFlowStep('PREVIEWS_GENERATING');
        setIsLoading(true);
        startScenePreviewPolling();
      } else if (stage === 'TTS_GENERATING') {
        // v2.7.2: TTS 생성 중 - 폴링 시작
        if (loadedScenario) {
          items.push({
            id: 'summary-restored',
            type: 'summary',
            summary: loadedScenario
          });
        }
        items.push({
          id: 'assistant-generating',
          type: 'assistant',
          content: '음성과 자막을 만들고 있어요 🎤\n\n영상에 생동감을 불어넣는 중이에요!'
        });
        setFlowStep('TTS_GENERATING');
        setIsLoading(true);
        startTtsPolling();
      } else if (stage === 'VIDEO_GENERATING') {
        // v2.7.2: 영상 합성 중 - 폴링 시작
        // v2.9.75: 영상 합성 중 상태이므로 자동 TTS 실행 방지
        autoTtsExecutedRef.current = true;
        if (loadedScenario) {
          items.push({
            id: 'summary-restored',
            type: 'summary',
            summary: loadedScenario
          });
        }
        items.push({
          id: 'assistant-generating',
          type: 'assistant',
          content: '영상을 합성하고 있어요 🎬\n\n모든 장면을 하나로 엮는 중이에요!'
        });
        setFlowStep('VIDEO_GENERATING');
        setIsLoading(true);
        startVideoPolling();
      } else if (stage === 'VIDEO_FAILED') {
        // v2.9.0: 영상 합성 실패 - 재시도 버튼 표시
        // v2.9.75: 영상 합성 실패 상태 (TTS는 완료)이므로 자동 TTS 실행 방지
        autoTtsExecutedRef.current = true;
        if (loadedScenario) {
          items.push({
            id: 'summary-restored',
            type: 'summary',
            summary: loadedScenario
          });
        }
        // 씬 프리뷰 데이터 로드
        try {
          const previewData = await api.getScenePreview(chatId);
          if (previewData.previews && previewData.previews.length > 0) {
            setScenePreviews(previewData.previews);
            items.push({
              id: 'scene-previews-restored',
              type: 'scene_previews',
              scenePreviews: previewData.previews,
              aspectRatio: previewData.aspectRatio
            });
          }
        } catch (err) {
          console.error('Failed to load scene previews:', err);
        }
        items.push({
          id: 'assistant-video-failed',
          type: 'assistant',
          content: '영상 합성이 잘 안 됐어요.\n\n아래 "영상 합성하기" 버튼을 눌러주세요.'
        });
        setFlowStep('AUDIO_DONE');  // AUDIO_DONE 상태로 돌아가서 영상 합성 재시도 가능
      } else if (stage === 'SCENE_REGENERATING') {
        // v2.9.0: 씬 재생성 중 - 진행 상황 표시 및 폴링 시작
        if (loadedScenario) {
          items.push({
            id: 'summary-restored',
            type: 'summary',
            summary: loadedScenario
          });
        }
        // 씬 프리뷰 데이터 로드
        try {
          const previewData = await api.getScenePreview(chatId);
          if (previewData.previews && previewData.previews.length > 0) {
            setScenePreviews(previewData.previews);
            items.push({
              id: 'scene-previews-restored',
              type: 'scene_previews',
              scenePreviews: previewData.previews,
              aspectRatio: previewData.aspectRatio
            });
          }
        } catch (err) {
          console.error('Failed to load scene previews:', err);
        }
        items.push({
          id: 'assistant-scene-regenerating',
          type: 'assistant',
          content: '씬을 다시 만들고 있어요 🔄\n\n더 좋은 결과물이 나올 거예요!'
        });
        setFlowStep('TTS_GENERATING');
        setIsLoading(true);
        startTtsPolling();
      } else if (stage === 'SCENES_REVIEW') {
        // v2.7.2: 씬 검토 중 - 씬 프리뷰 표시
        if (loadedScenario) {
          items.push({
            id: 'summary-restored',
            type: 'summary',
            summary: loadedScenario
          });
        }
        try {
          const previewData = await api.getScenePreview(chatId);
          if (previewData.previews && previewData.previews.length > 0) {
            setScenePreviews(previewData.previews);
            items.push({
              id: 'assistant-scenes-review',
              type: 'assistant',
              content: '씬을 검토하고 있습니다.\n\n문제가 있는 씬을 수정하거나 다시 생성해주세요.'
            });
            items.push({
              id: 'scene-previews-restored',
              type: 'scene_previews',
              scenePreviews: previewData.previews,
              aspectRatio: previewData.aspectRatio
            });
          }
        } catch (err) {
          console.error('Failed to load scene previews:', err);
        }
        setFlowStep('PREVIEWS_DONE');
      } else if (stage === 'IMAGES_GENERATING') {
        // v2.9.2: 이미지 생성 중 - 새로고침 시 폴링 재시작
        if (loadedScenario) {
          items.push({
            id: 'summary-restored',
            type: 'summary',
            summary: loadedScenario
          });
        }
        items.push({
          id: 'assistant-images-generating',
          type: 'assistant',
          content: '이미지를 만들고 있어요 🎨\n\n아름다운 장면들이 곧 완성돼요!'
        });
        setFlowStep('IMAGES_GENERATING');
        setIsLoading(true);
        startImagePolling();
      } else if (stage === 'AUDIO_GENERATING') {
        // v2.9.2: 오디오 생성 중 - 새로고침 시 폴링 재시작
        if (loadedScenario) {
          items.push({
            id: 'summary-restored',
            type: 'summary',
            summary: loadedScenario
          });
        }
        items.push({
          id: 'assistant-audio-generating',
          type: 'assistant',
          content: '나레이션을 만들고 있어요 🎙️\n\n목소리에 감정을 담는 중이에요!'
        });
        setFlowStep('AUDIO_GENERATING');
        setIsLoading(true);
        startAudioPolling();
      }

      setChatItems(items);

    } catch (err) {
      console.error('Failed to load chat:', err);
      router.push('/');
    } finally {
      setIsInitialLoading(false);
      // v2.9.75: 상태 복원 완료 후 플래그 해제
      isRestoringStateRef.current = false;
    }
  };

  // ID 생성 헬퍼
  // v2.9.2: substr() deprecated - slice() 사용
  const generateId = () => `item-${Date.now()}-${Math.random().toString(36).slice(2, 11)}`;

  // 메시지 추가 헬퍼
  const addMessage = (item: Omit<ChatItem, 'id'>) => {
    setChatItems(prev => [...prev, { ...item, id: generateId() }]);
  };

  // 선택지 선택 처리
  const handleChoiceSelect = async (choice: ChatChoice, choiceType: string) => {
    // 선택된 항목 표시를 위해 해당 choices 아이템 업데이트
    setChatItems(prev => prev.map(item =>
      item.choiceType === choiceType
        ? { ...item, selected: choice.id }
        : item
    ));

    switch (choiceType) {
      // v2.9.73: 'duration' case 제거됨 - 슬라이드 수 선택 UI로 대체
      case 'format':
        await handleFormatSelect(choice);
        break;
      case 'subtitle':
        await handleVideoSubtitleSelect(choice);
        break;
      case 'font':
        await handleFontSelect(choice);
        break;
      case 'font_size':
        await handleFontSizeSelect(choice);
        break;
      case 'position':
        await handlePositionSelect(choice);
        break;
      case 'thumbnail_select':
        await handleThumbnailStyleSelect(choice);
        break;
      case 'scenario_confirm':
        await handleScenarioConfirm();
        break;
      case 'opening':
        await handleOpeningSelect(choice);
        break;
      case 'opening_confirm':
        await handleOpeningConfirm(choice);
        break;
      case 'thumbnail_generate':
        await handleThumbnailGenerate(choice);
        break;
      case 'in_progress_navigate':
        handleInProgressNavigate(choice);
        break;
      case 'navigate_home':
        // v2.9.38: 만료된 다운로드 - 홈으로 이동
        addMessage({
          type: 'user',
          content: choice.label
        });
        addMessage({
          type: 'assistant',
          content: '홈페이지로 이동합니다...'
        });
        setTimeout(() => {
          router.push('/');
        }, 500);
        break;
    }
  };

  // v2.9.30: 진행 중인 채팅 네비게이션 처리
  const handleInProgressNavigate = (choice: ChatChoice) => {
    addMessage({
      type: 'user',
      content: choice.label
    });

    if (choice.id === 'navigate') {
      // 진행 중인 채팅으로 이동
      const inProgressChatId = choice.description?.match(/#(\d+)/)?.[1];
      if (inProgressChatId) {
        addMessage({
          type: 'assistant',
          content: '진행 중인 채팅으로 이동합니다...'
        });
        setTimeout(() => {
          router.push(`/chat/${inProgressChatId}`);
        }, 500);
      }
    } else {
      // 현재 채팅 유지
      addMessage({
        type: 'assistant',
        content: '진행 중인 영상이 완료되면 다시 시도해주세요.'
      });
    }
  };

  // v2.9.165: 썸네일 스타일 선택지를 API에서 로드하여 표시
  const showThumbnailStyleChoices = async () => {
    try {
      let styles = thumbnailStyles;
      if (styles.length === 0) {
        styles = await api.getVideoThumbnailStyles();
        setThumbnailStyles(styles);
      }

      if (styles.length > 0) {
        const choices: ChatChoice[] = styles.map(style => ({
          id: `generate_thumbnail_${style.thumbnailId}`,
          label: style.styleName,
          description: style.description,
          icon: 'image' as const,
          variant: style.isDefault ? 'primary' as const : 'outline' as const,
        }));
        addMessage({
          type: 'choices',
          choices,
          choiceType: 'thumbnail_generate'
        });
      } else {
        // 스타일 없으면 폴백
        addMessage({
          type: 'choices',
          choices: THUMBNAIL_CHOICES_FALLBACK,
          choiceType: 'thumbnail_generate'
        });
      }
    } catch {
      addMessage({
        type: 'choices',
        choices: THUMBNAIL_CHOICES_FALLBACK,
        choiceType: 'thumbnail_generate'
      });
    }
  };

  // v2.9.165: TTS 자동 진행 헬퍼
  const proceedToTtsGeneration = async () => {
    try {
      addMessage({
        type: 'assistant',
        content: '🎙️ TTS 음성과 자막을 자동으로 생성합니다...\n\n마지막에 썸네일 2초 영상도 추가됩니다.'
      });
      setFlowStep('TTS_GENERATING');
      setIsLoading(true);
      await api.generateSceneAudio(chatId, { includeSubtitle: true });
      startTtsPolling();
    } catch (ttsErr) {
      console.error('[v2.9.171] Auto TTS generation error:', ttsErr);
      // v2.9.171: 에러 발생 시 백엔드가 실제로 시작했는지 확인
      try {
        const detail = await api.getChatDetail(chatId);
        if (detail.stage === 'TTS_GENERATING' || detail.stage === 'TTS_DONE') {
          console.log('[v2.9.171] Backend is processing TTS, starting polling...');
          startTtsPolling();
          return;
        }
      } catch { /* 복구 실패 무시 */ }
      setIsLoading(false);
      setFlowStep('PREVIEWS_DONE');
      addMessage({
        type: 'assistant',
        content: `TTS 생성 중 문제가 발생했어요.\n\n${parseErrorMessage(ttsErr)}\n\n아래 버튼을 눌러 다시 시도해주세요.`
      });
    }
  };

  // 썸네일 생성 처리 (v2.9.165: 스타일 선택 후 호출)
  const handleThumbnailGenerate = async (choice?: ChatChoice) => {
    // v2.9.165: choice.id에서 thumbnailId 추출 (generate_thumbnail_{id} 형식)
    let thumbnailId: number | undefined;
    if (choice) {
      addMessage({
        type: 'user',
        content: choice.label
      });
      const idMatch = choice.id.match(/generate_thumbnail_(\d+)/);
      if (idMatch) {
        thumbnailId = Number(idMatch[1]);
      }
    }

    addMessage({
      type: 'assistant',
      content: '선택한 스타일로 썸네일을 만들고 있어요...\n\n잠시만 기다려주세요.'
    });

    setIsLoading(true);

    try {
      const response = await api.generateThumbnail(chatId, thumbnailId);

      setIsLoading(false);

      // v2.9.27: THUMBNAIL_RESULT 메시지 추가 (채팅에 표시)
      addMessage({
        type: 'thumbnail_result',
        thumbnailResult: {
          thumbnailUrl: response.thumbnailUrl,
          youtubeTitle: response.youtubeTitle,
          youtubeDescription: response.youtubeDescription,
          catchphrase: response.catchphrase
        }
      });

      // v2.9.165: 썸네일 완료 후 TTS 자동 시작
      addMessage({
        type: 'assistant',
        content: '썸네일이 완성되었어요!\n\n이제 TTS 음성과 자막을 생성합니다...'
      });

      setTimeout(async () => {
        await proceedToTtsGeneration();
      }, 1000);

    } catch (err) {
      setIsLoading(false);
      const errorMsg = parseErrorMessage(err);
      addMessage({
        type: 'assistant',
        content: `썸네일 생성 중 문제가 발생했어요.\n\n${errorMsg}`
      });
      // 다시 시도할 수 있도록 버튼 다시 표시
      showThumbnailStyleChoices();
    }
  };

  // v2.9.73: 슬라이드 수 확정 처리
  const handleSlideCountConfirm = async (slideCount: number) => {
    setSelectedSlideCount(slideCount);
    const estimatedMinutes = calculateEstimatedMinutes(slideCount);

    // 슬라이드 수 선택 메시지를 확정으로 마킹
    setChatItems(prev => prev.map(item =>
      item.type === 'slide_count_selector' ? { ...item, slideCountConfirmed: true } : item
    ));

    addMessage({
      type: 'user',
      content: `슬라이드 ${slideCount}장 (예상 ${estimatedMinutes}분)`
    });

    // 포맷 선택 단계로 이동
    addMessage({
      type: 'assistant',
      content: '어떤 영상 비율로 만들까요?'
    });

    addMessage({
      type: 'choices',
      choices: getFormatChoices(selectedSlideCount),
      choiceType: 'format'
    });

    setFlowStep('FORMAT_SELECT');
  };

  // v2.9.73: 포맷 선택 처리 → v2.9.176: 자막 템플릿 API 동적 로딩
  const handleFormatSelect = async (choice: ChatChoice) => {
    const formatId = parseInt(choice.id, 10);
    if (isNaN(formatId)) {
      console.error('Invalid format choice ID:', choice.id);
      return;
    }

    setSelectedFormatId(formatId);

    addMessage({
      type: 'user',
      content: choice.label
    });

    // v2.9.176: 자막 템플릿 API에서 동적 로딩
    addMessage({
      type: 'assistant',
      content: '자막 스타일을 불러오는 중...'
    });

    try {
      const res = await api.getVideoSubtitles();
      const templates = res.subtitles || [];

      // 마지막 로딩 메시지를 실제 안내로 교체
      setChatItems(prev => {
        const newItems = [...prev];
        if (newItems.length > 0 && newItems[newItems.length - 1].content === '자막 스타일을 불러오는 중...') {
          newItems[newItems.length - 1] = {
            ...newItems[newItems.length - 1],
            content: '자막 스타일을 선택해주세요.'
          };
        }
        return newItems;
      });

      if (templates.length === 0) {
        // 템플릿이 없으면 기본값으로 진행
        console.warn('[v2.9.176] No subtitle templates found, using default');
        setSelectedVideoSubtitleId(1);
        // 바로 글자 크기 선택으로 이동
        addMessage({
          type: 'assistant',
          content: '자막 글자 크기를 선택해주세요.'
        });
        addMessage({
          type: 'choices',
          choices: FONT_SIZE_CHOICES,
          choiceType: 'font_size'
        });
        setFlowStep('FONT_SIZE_SELECT');
        return;
      }

      addMessage({
        type: 'choices',
        choices: getSubtitleChoicesFromTemplates(templates),
        choiceType: 'subtitle'
      });

      setFlowStep('SUBTITLE_SELECT');
    } catch (error) {
      console.error('[v2.9.176] Failed to load subtitle templates:', error);
      // 폴백: 기본 템플릿으로 진행
      setSelectedVideoSubtitleId(1);
      setChatItems(prev => {
        const newItems = [...prev];
        if (newItems.length > 0 && newItems[newItems.length - 1].content === '자막 스타일을 불러오는 중...') {
          newItems[newItems.length - 1] = {
            ...newItems[newItems.length - 1],
            content: '자막 글자 크기를 선택해주세요.'
          };
        }
        return newItems;
      });
      addMessage({
        type: 'choices',
        choices: FONT_SIZE_CHOICES,
        choiceType: 'font_size'
      });
      setFlowStep('FONT_SIZE_SELECT');
    }
  };

  // v2.9.161: 자막 템플릿 선택 처리 → 글자 크기 선택으로 이동
  const handleVideoSubtitleSelect = async (choice: ChatChoice) => {
    const videoSubtitleId = parseInt(choice.id, 10);
    if (isNaN(videoSubtitleId)) {
      console.error('Invalid subtitle choice ID:', choice.id);
      return;
    }

    setSelectedVideoSubtitleId(videoSubtitleId);

    addMessage({
      type: 'user',
      content: choice.label
    });

    // v2.9.174: 국가별 폰트 조회 → 1개면 자동선택+스킵, 2개 이상이면 선택 UI
    try {
      const nationCode = creatorNationCode || 'KR';
      const res = await api.getFontsByNation(nationCode);
      const fonts = res.fonts || [];

      if (fonts.length === 0) {
        // 폰트가 없으면 기본 폰트(1)로 자동 선택, 바로 글자 크기 선택
        setSelectedFontId(1);
        addMessage({
          type: 'assistant',
          content: '자막 글자 크기를 선택해주세요.'
        });
        addMessage({
          type: 'choices',
          choices: FONT_SIZE_CHOICES,
          choiceType: 'font_size'
        });
        setFlowStep('FONT_SIZE_SELECT');
      } else if (fonts.length === 1) {
        // 폰트 1개 → 자동 선택, 바로 글자 크기 선택
        setSelectedFontId(fonts[0].fontId);
        addMessage({
          type: 'assistant',
          content: '자막 글자 크기를 선택해주세요.'
        });
        addMessage({
          type: 'choices',
          choices: FONT_SIZE_CHOICES,
          choiceType: 'font_size'
        });
        setFlowStep('FONT_SIZE_SELECT');
      } else {
        // 폰트 2개 이상 → 선택 UI 표시
        const fontChoices: ChatChoice[] = fonts.map((f) => ({
          id: String(f.fontId),
          label: f.fontNameDisplay,
          description: f.description || f.fontName,
          icon: 'edit' as const,
          variant: (f.isDefault ? 'primary' : 'secondary') as 'primary' | 'secondary',
        }));
        addMessage({
          type: 'assistant',
          content: '자막 폰트를 선택해주세요.'
        });
        addMessage({
          type: 'choices',
          choices: fontChoices,
          choiceType: 'font'
        });
        setFlowStep('FONT_SELECT');
      }
    } catch (err) {
      console.error('[Font Load Error]', err);
      // 폰트 로드 실패 시 기본값으로 진행
      setSelectedFontId(1);
      addMessage({
        type: 'assistant',
        content: '자막 글자 크기를 선택해주세요.'
      });
      addMessage({
        type: 'choices',
        choices: FONT_SIZE_CHOICES,
        choiceType: 'font_size'
      });
      setFlowStep('FONT_SIZE_SELECT');
    }
  };

  // v2.9.174: 폰트 선택 처리 → 글자 크기 선택으로 이동
  const handleFontSelect = async (choice: ChatChoice) => {
    const fontId = parseInt(choice.id, 10);
    if (isNaN(fontId)) {
      console.error('Invalid font choice ID:', choice.id);
      return;
    }

    addMessage({
      type: 'user',
      content: choice.label
    });

    setSelectedFontId(fontId);

    addMessage({
      type: 'assistant',
      content: '자막 글자 크기를 선택해주세요.'
    });

    addMessage({
      type: 'choices',
      choices: FONT_SIZE_CHOICES,
      choiceType: 'font_size'
    });

    setFlowStep('FONT_SIZE_SELECT');
  };

  // v2.9.161: 자막 글자 크기 선택 처리 → v2.9.167: 자막 위치 선택으로 이동
  const [selectedFontSizeLevel, setSelectedFontSizeLevel] = useState<number>(3);

  const handleFontSizeSelect = async (choice: ChatChoice) => {
    const fontSizeLevel = parseInt(choice.id, 10);
    if (isNaN(fontSizeLevel)) {
      console.error('Invalid font size choice ID:', choice.id);
      return;
    }

    setSelectedFontSizeLevel(fontSizeLevel);

    addMessage({
      type: 'user',
      content: choice.label
    });

    addMessage({
      type: 'assistant',
      content: '자막 위치를 선택해주세요.'
    });

    addMessage({
      type: 'choices',
      choices: POSITION_CHOICES,
      choiceType: 'position'
    });

    setFlowStep('POSITION_SELECT');
  };

  // v2.9.167: 자막 위치 선택 처리 → v2.9.168: 썸네일 스타일 선택으로 이동
  const handlePositionSelect = async (choice: ChatChoice) => {
    const subtitlePosition = parseInt(choice.id, 10);
    if (isNaN(subtitlePosition)) {
      console.error('Invalid position choice ID:', choice.id);
      return;
    }

    addMessage({
      type: 'user',
      content: choice.label
    });

    // v2.9.168: 자막 위치 저장 후 썸네일 스타일 선택으로 이동
    setSelectedSubtitlePosition(subtitlePosition);

    addMessage({
      type: 'assistant',
      content: '썸네일 디자인 스타일을 선택해주세요.'
    });

    // 썸네일 스타일 로드 및 표시
    try {
      let styles = thumbnailStyles;
      if (styles.length === 0) {
        styles = await api.getVideoThumbnailStyles();
        setThumbnailStyles(styles);
      }

      if (styles.length > 0) {
        const choices: ChatChoice[] = styles.map(style => ({
          id: `select_thumbnail_${style.thumbnailId}`,
          label: style.styleName,
          description: style.description,
          icon: 'image' as const,
          variant: style.isDefault ? 'primary' as const : 'outline' as const,
        }));
        addMessage({
          type: 'choices',
          choices,
          choiceType: 'thumbnail_select'
        });
      } else {
        addMessage({
          type: 'choices',
          choices: [{ id: 'select_thumbnail_default', label: '클래식', description: '노란색 텍스트, 심플한 스타일', icon: 'image', variant: 'primary' }],
          choiceType: 'thumbnail_select'
        });
      }
    } catch {
      addMessage({
        type: 'choices',
        choices: [{ id: 'select_thumbnail_default', label: '클래식', description: '노란색 텍스트, 심플한 스타일', icon: 'image', variant: 'primary' }],
        choiceType: 'thumbnail_select'
      });
    }

    setFlowStep('THUMBNAIL_SELECT');
  };

  // v2.9.168: 자막 위치 상태 저장용 (handlePositionSelect에서 설정, handleThumbnailStyleSelect에서 사용)
  const [selectedSubtitlePosition, setSelectedSubtitlePosition] = useState<number>(1);

  // v2.9.168: 썸네일 스타일 선택 처리 → 시나리오 생성
  const handleThumbnailStyleSelect = async (choice: ChatChoice) => {
    addMessage({
      type: 'user',
      content: choice.label
    });

    // choice.id에서 thumbnailId 추출 (select_thumbnail_{id} 형식)
    let thumbnailId: number | undefined;
    const idMatch = choice.id.match(/select_thumbnail_(\d+)/);
    if (idMatch) {
      thumbnailId = Number(idMatch[1]);
    }
    setSelectedThumbnailId(thumbnailId);

    await generateScenarioWithSlideCount(selectedSlideCount, selectedFormatId, selectedVideoSubtitleId, selectedFontSizeLevel, selectedSubtitlePosition, thumbnailId, selectedFontId);
  };

  // v2.9.75: 시나리오 진행 상황 폴링
  const pollScenarioProgress = () => {
    stopCurrentPolling();
    currentPollingChatIdRef.current = chatId;

    const poll = async () => {
      if (currentPollingChatIdRef.current !== chatId) {
        stopCurrentPolling();
        return;
      }

      try {
        const progress = await api.getScenarioProgress(chatId);
        setScenarioProgress(progress);

        // 채팅 메시지 업데이트 (프로그레스 메시지)
        setChatItems(prev => {
          const items = [...prev];
          const progressIdx = items.findIndex(item => item.id === 'scenario_progress');
          if (progressIdx >= 0) {
            items[progressIdx] = {
              ...items[progressIdx],
              progress: {
                current: progress.completedSlides,
                total: progress.totalSlides,
                message: progress.message
              }
            };
          }
          return items;
        });

        if (progress.status === 'completed') {
          stopCurrentPolling();
          // 시나리오 완료 - 결과 가져오기
          const result = await api.getScenario(chatId);
          if (result) {
            setScenario(result);
            setIsLoading(false);

            // 프로그레스 메시지 제거
            setChatItems(prev => prev.filter(item => item.id !== 'scenario_progress'));

            // 시나리오 요약 표시
            addMessage({
              type: 'summary',
              summary: result
            });

            // v2.9.75: 자동 진행 - 바로 다음 단계로
            setFlowStep('SCENARIO_REVIEW');

            // 자동으로 다음 단계 진행 (오프닝 확인 → 이미지 생성)
            setTimeout(() => {
              autoProcessScenarioConfirm(result);
            }, 500);
          }
        } else if (progress.status === 'failed') {
          stopCurrentPolling();
          setIsLoading(false);

          // 프로그레스 메시지 제거 후 에러 표시
          setChatItems(prev => prev.filter(item => item.id !== 'scenario_progress'));

          addMessage({
            type: 'assistant',
            content: `시나리오 생성 중 문제가 발생했어요.\n\n${progress.message}\n\n다시 시도해주세요.`
          });
          addMessage({
            type: 'slide_count_selector'
          });
          setFlowStep('SLIDE_COUNT_SELECT');
        }
      } catch (err) {
        console.error('[Scenario Progress Poll Error]', err);
        pollingErrorCountRef.current += 1;
        setPollingErrorCount(pollingErrorCountRef.current);

        if (pollingErrorCountRef.current >= MAX_POLLING_ERRORS) {
          await recoverFromPollingFailure();
        }
      }
    };

    // 즉시 한 번 실행 후 3초 간격으로 폴링
    poll();
    pollingRef.current = setInterval(poll, 3000);
  };

  // v2.9.75: 시나리오 확인 자동 처리 (버튼 자동 클릭)
  const autoProcessScenarioConfirm = (scenarioResult: ScenarioResponse) => {
    // 오프닝 정보 표시
    if (scenarioResult?.opening) {
      addMessage({
        type: 'assistant',
        content: '시나리오가 완성되었어요! 8초 오프닝 영상과 함께 이미지 생성을 시작합니다.'
      });

      addMessage({
        type: 'opening',
        opening: {
          narration: scenarioResult.opening.narration,
          videoPrompt: scenarioResult.opening.videoPrompt
        }
      });

      setFlowStep('OPENING_REVIEW');

      // 자동으로 이미지 생성 시작
      setTimeout(() => {
        autoProcessOpeningConfirm();
      }, 500);
    } else {
      // 오프닝 없으면 바로 이미지 생성
      autoProcessOpeningConfirm();
    }
  };

  // v2.9.75: 오프닝 확인 자동 처리 (이미지 생성 시작)
  const autoProcessOpeningConfirm = () => {
    setIncludeOpening(true);

    // 이미지 생성 시작 (API 키 체크 건너뛰기 - 시나리오 생성 시 이미 인증됨)
    handleStartImageGeneration(true);
  };

  // v2.9.75: 시나리오 생성 (슬라이드 수 + 포맷 + 자막) - 프로그레스바 지원
  const generateScenarioWithSlideCount = async (slideCount: number, formatId: number, videoSubtitleId?: number, fontSizeLevel?: number, subtitlePosition?: number, thumbnailId?: number, fontId?: number) => {
    // creatorId가 없으면 에러 (크리에이터 선택 필수)
    if (!selectedCreatorId) {
      addMessage({
        type: 'assistant',
        content: '장르를 먼저 선택해주세요.'
      });
      setFlowStep('GENRE_SELECT');
      return;
    }

    // 프로그레스 메시지 추가
    setChatItems(prev => [...prev, {
      id: 'scenario_progress',
      type: 'progress',
      progress: {
        current: 0,
        total: slideCount,
        message: '시나리오 생성 준비 중...'
      }
    }]);

    setFlowStep('SCENARIO_GENERATING');
    setIsLoading(true);
    setScenarioProgress(null);

    try {
      // 시나리오 생성 API 호출 (백그라운드에서 진행)
      api.generateScenario(chatId, {
        slideCount: slideCount,
        creatorId: selectedCreatorId,
        formatId: formatId,
        videoSubtitleId: videoSubtitleId || 1,
        fontSizeLevel: fontSizeLevel || 3,
        subtitlePosition: subtitlePosition || 1,
        thumbnailId: thumbnailId,  // v2.9.168: 사용자 선택 썸네일 디자인
        fontId: fontId || 1,       // v2.9.174: 사용자 선택 폰트
      }).catch(err => {
        console.error('[Scenario Generation Error]', err);
        // 에러는 폴링에서 처리됨
      });

      // 진행 상황 폴링 시작
      pollScenarioProgress();
    } catch (err) {
      setIsLoading(false);
      const errorMsg = parseErrorMessage(err);

      // 프로그레스 메시지 제거
      setChatItems(prev => prev.filter(item => item.id !== 'scenario_progress'));

      // v2.9.30: 진행 중인 콘텐츠 생성 에러 처리
      const inProgressChatId = extractInProgressChatId(err);
      if (inProgressChatId) {
        addMessage({
          type: 'assistant',
          content: `${errorMsg}\n\n진행 중인 영상 생성을 먼저 완료하거나 해당 채팅을 삭제해주세요.`
        });
        addMessage({
          type: 'choices',
          choices: [
            { id: 'navigate', label: '진행 중인 채팅으로 이동', description: `채팅 #${inProgressChatId}`, icon: 'video', variant: 'primary' },
            { id: 'stay', label: '현재 채팅 유지', description: '나중에 다시 시도', icon: 'clock', variant: 'secondary' }
          ],
          choiceType: 'in_progress_navigate'
        });
        return;
      }

      addMessage({
        type: 'assistant',
        content: `시나리오 생성 중 문제가 발생했어요.\n\n${errorMsg}\n\n다시 시도해주세요.`
      });
      addMessage({
        type: 'slide_count_selector'
      });
      setFlowStep('SLIDE_COUNT_SELECT');
    }
  };

  // 시나리오 확인 처리 (v2.9.11: 다시 만들기 제거 - 자동화)
  const handleScenarioConfirm = async () => {
    // 확인 선택 - 오프닝 필수로 바로 오프닝 내용 표시
    addMessage({
      type: 'user',
      content: '이 시나리오로 진행'
    });

    // 오프닝이 있으면 표시하고 이미지 생성 버튼 제공
    if (scenario?.opening) {
      addMessage({
        type: 'assistant',
        content: '8초 오프닝 영상 정보입니다.'
      });

      addMessage({
        type: 'opening',
        opening: {
          narration: scenario.opening.narration,
          videoPrompt: scenario.opening.videoPrompt
        }
      });

      addMessage({
        type: 'assistant',
        content: '확인 후 이미지 생성을 시작해주세요.'
      });

      addMessage({
        type: 'choices',
        choices: OPENING_CONFIRM_CHOICES,
        choiceType: 'opening_confirm'
      });

      setFlowStep('OPENING_REVIEW');
    } else {
      // 오프닝이 없으면 바로 이미지 생성 (예외 케이스)
      addMessage({
        type: 'assistant',
        content: '슬라이드 이미지를 만들게요.\n\n아래 버튼을 눌러 시작해주세요.'
      });
      setFlowStep('READY_TO_GENERATE');
    }
  };

  // 오프닝 선택 처리 (오프닝 필수 - 항상 오프닝 포함)
  const handleOpeningSelect = async (choice: ChatChoice) => {
    // 오프닝은 필수이므로 항상 true
    setIncludeOpening(true);

    addMessage({
      type: 'user',
      content: choice.label
    });

    if (scenario?.opening) {
      // 오프닝 내용 표시
      addMessage({
        type: 'assistant',
        content: '8초 오프닝 영상을 이렇게 만들 예정이에요.\n확인하시고 진행해주세요.'
      });

      addMessage({
        type: 'opening',
        opening: {
          narration: scenario.opening.narration,
          videoPrompt: scenario.opening.videoPrompt
        }
      });

      addMessage({
        type: 'choices',
        choices: OPENING_CONFIRM_CHOICES,
        choiceType: 'opening_confirm'
      });

      setFlowStep('OPENING_REVIEW');
    } else {
      // 오프닝이 없는 예외 케이스
      addMessage({
        type: 'assistant',
        content: '슬라이드 이미지를 만들게요.\n\n아래 버튼을 눌러 이미지 생성을 시작해주세요.'
      });

      setFlowStep('READY_TO_GENERATE');
    }
  };

  // 오프닝 확인 처리 - 바로 이미지 생성 시작 (재생성 옵션 제거)
  const handleOpeningConfirm = async (choice: ChatChoice) => {
    addMessage({
      type: 'user',
      content: choice.label
    });

    // 오프닝 영상 포함하여 바로 이미지 생성 시작
    setIncludeOpening(true);

    // 바로 이미지 생성 시작
    await handleStartImageGeneration();
  };

  // v2.5.0: 씬 프리뷰 생성 시작 (Nginx 타임아웃 대응)
  const handleStartImageGeneration = async (skipApiKeyCheck = false) => {
    // v2.9.172: 중복 호출 방지 (썸네일 중복 생성 원인)
    if (flowStep === 'PREVIEWS_GENERATING') {
      console.log('[v2.9.172] Already generating previews, ignoring duplicate call');
      return;
    }

    addMessage({
      type: 'assistant',
      content: '씬 프리뷰와 유튜브 썸네일을 생성하고 있어요...\n\n오프닝 영상, 슬라이드 이미지, 썸네일이 완성되면 나레이션을 확인할 수 있어요.\n시간이 조금 걸릴 수 있어요.'
    });

    setFlowStep('PREVIEWS_GENERATING');
    setIsLoading(true);

    try {
      await api.generateScenePreview(chatId);
      startScenePreviewPolling();
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : String(err);
      console.log('Scene preview generation request error (may be timeout):', errorMsg);

      // v2.9.30: 진행 중인 콘텐츠 생성 에러 처리
      const inProgressChatId = extractInProgressChatId(err);
      if (inProgressChatId) {
        setIsLoading(false);
        const parsedError = parseErrorMessage(err);
        addMessage({
          type: 'assistant',
          content: `${parsedError}\n\n진행 중인 영상 생성을 먼저 완료하거나 해당 채팅을 삭제해주세요.`
        });
        addMessage({
          type: 'choices',
          choices: [
            { id: 'navigate', label: '진행 중인 채팅으로 이동', description: `채팅 #${inProgressChatId}`, icon: 'video', variant: 'primary' },
            { id: 'stay', label: '현재 채팅 유지', description: '나중에 다시 시도', icon: 'clock', variant: 'secondary' }
          ],
          choiceType: 'in_progress_navigate'
        });
        setFlowStep('READY_TO_GENERATE');
        return;
      }

      // 503/타임아웃 에러는 백엔드에서 작업이 시작되었을 수 있음 - 폴링으로 확인
      if (errorMsg.includes('503') || errorMsg.includes('504') ||
          errorMsg.includes('timeout') || errorMsg.includes('Timeout') ||
          errorMsg.includes('overload') || errorMsg.includes('Gateway')) {
        setPollingErrorMessage('서버가 바빠서 응답이 지연되고 있어요. 확인 중...');
        startScenePreviewPolling();
      } else {
        // v2.9.171: 에러 발생 시 백엔드가 실제로 시작했는지 확인
        try {
          const detail = await api.getChatDetail(chatId);
          if (detail.stage === 'PREVIEWS_GENERATING' || detail.stage === 'PREVIEWS_DONE') {
            console.log('[v2.9.171] Backend is processing previews, starting polling...');
            startScenePreviewPolling();
            return;
          }
        } catch { /* 복구 실패 무시 */ }
        setIsLoading(false);
        const parsedError = parseErrorMessage(err);
        addMessage({
          type: 'assistant',
          content: `프리뷰 생성 중 문제가 발생했어요.\n\n${parsedError}`
        });
        setFlowStep('READY_TO_GENERATE');
      }
    }
  };

  // v2.5.0: 씬 프리뷰 폴링
  const startScenePreviewPolling = () => {
    // v2.9.1: 기존 폴링 정리 후 시작
    stopCurrentPolling();
    currentPollingChatIdRef.current = chatId;

    pollingRef.current = setInterval(async () => {
      // v2.9.1: chatId가 변경되었으면 폴링 중지
      if (currentPollingChatIdRef.current !== chatId) {
        stopCurrentPolling();
        return;
      }
      try {
        const progress = await api.getScenePreview(chatId);
        setScenePreviewProgress(progress);

        // 에러 상태 초기화 (성공 시)
        if (pollingErrorCountRef.current > 0) {
          pollingErrorCountRef.current = 0;
          setPollingErrorCount(0);
          setPollingErrorMessage(null);
        }

        if (progress.status === 'completed' && progress.previews) {
          if (pollingRef.current) clearInterval(pollingRef.current);
          setIsLoading(false);
          setPollingErrorCount(0);
          setPollingErrorMessage(null);
          setScenePreviews(progress.previews);

          addMessage({
            type: 'assistant',
            content: `모든 씬의 이미지/영상과 유튜브 썸네일이 완성되었어요!\n\n아래에서 나레이션을 확인하고 수정하세요.\n수정이 완료되면 TTS를 생성합니다.`
          });

          // 씬 프리뷰 목록 표시
          addMessage({
            type: 'scene_previews',
            scenePreviews: progress.previews,
            aspectRatio: progress.aspectRatio
          });

          setFlowStep('PREVIEWS_DONE');

          // v2.9.168: 썸네일 스타일은 시나리오 생성 전에 선택 완료됨 → 바로 TTS 자동 시작
          // 중복 실행 방지 + 상태 복원 시 실행 방지
          if (autoTtsExecutedRef.current || isRestoringStateRef.current) {
            console.log('[v2.9.168] Skipping auto TTS - already executed or restoring state');
          } else {
            autoTtsExecutedRef.current = true;
            setTimeout(() => {
              (async () => {
                try {
                  await proceedToTtsGeneration();
                } catch (ttsErr) {
                  console.error('[v2.9.168] Auto TTS generation error:', ttsErr);
                }
              })();
            }, 1500);
          }
        }
      } catch (err) {
        console.error('Scene preview polling error:', err);
        const errorMsg = err instanceof Error ? err.message : String(err);

        pollingErrorCountRef.current += 1;
        setPollingErrorCount(pollingErrorCountRef.current);

        if (errorMsg.includes('503') || errorMsg.includes('overload') || errorMsg.includes('busy')) {
          setPollingErrorMessage('서버가 잠시 바빠요. 자동으로 다시 시도하고 있어요...');
        } else {
          setPollingErrorMessage('잠시 문제가 생겼어요. 다시 시도하고 있어요...');
        }

        if (pollingErrorCountRef.current >= MAX_POLLING_ERRORS) {
          await recoverFromPollingFailure();
        }
      }
    }, 3000);
  };

  // v2.6.0: 실패 씬 재시도 핸들러
  // AUDIO_DONE 상태에서는 TTS도 재시도해야 하므로 retryMediaOnly: false
  const handleRetryFailed = async () => {
    if (isRetrying) return;

    setIsRetrying(true);

    try {
      // AUDIO_DONE 상태면 TTS도 재시도 (retryMediaOnly: false)
      // PREVIEWS_DONE 상태면 미디어만 재시도 (retryMediaOnly: true)
      const retryMediaOnly = flowStep !== 'AUDIO_DONE';
      const response = await api.retryFailedScenes(chatId, { retryMediaOnly });

      if (response.status === 'no_failed_scenes') {
        addMessage({
          type: 'assistant',
          content: '다시 만들 씬이 없어요. 모두 정상이에요!'
        });
        setIsRetrying(false);
        return;
      }

      addMessage({
        type: 'assistant',
        content: `${response.retryingCount || 0}개 씬을 다시 생성하고 있어요...`
      });

      // 재시도 폴링 시작
      startRetryPolling();
    } catch (err) {
      console.error('Failed to retry scenes:', err);
      addMessage({
        type: 'assistant',
        content: `다시 만드는 중에 문제가 생겼어요: ${parseErrorMessage(err)}`
      });
      setIsRetrying(false);
    }
  };

  // v2.6.0: 재시도 폴링
  const startRetryPolling = () => {
    // v2.9.1: 기존 폴링 정리 후 시작
    stopCurrentPolling();
    currentPollingChatIdRef.current = chatId;

    pollingRef.current = setInterval(async () => {
      // v2.9.1: chatId가 변경되었으면 폴링 중지
      if (currentPollingChatIdRef.current !== chatId) {
        stopCurrentPolling();
        return;
      }
      try {
        const response = await api.getFailedScenes(chatId);

        // 재시도 중인 씬 상태 업데이트
        if (response.failedScenes) {
          // 씬 프리뷰 상태 업데이트
          setScenePreviews(prev =>
            prev.map(p => {
              const failedScene = response.failedScenes?.find(f => f.sceneId === p.sceneId);
              if (failedScene) {
                return {
                  ...p,
                  previewStatus: failedScene.isRetrying ? 'GENERATING' : 'FAILED',
                  errorMessage: failedScene.errorMessage || p.errorMessage
                };
              }
              return p;
            })
          );

          // 채팅 아이템도 업데이트
          setChatItems(prev =>
            prev.map(item =>
              item.type === 'scene_previews' && item.scenePreviews
                ? {
                    ...item,
                    scenePreviews: item.scenePreviews.map(p => {
                      const failedScene = response.failedScenes?.find(f => f.sceneId === p.sceneId);
                      if (failedScene) {
                        return {
                          ...p,
                          previewStatus: failedScene.isRetrying ? 'GENERATING' : 'FAILED',
                          errorMessage: failedScene.errorMessage || p.errorMessage
                        };
                      }
                      return p;
                    })
                  }
                : item
            )
          );
        }

        // 모든 재시도 완료 확인
        if (response.status === 'completed' || response.status === 'no_failed_scenes') {
          if (pollingRef.current) {
            clearInterval(pollingRef.current);
            pollingRef.current = null;
          }
          setIsRetrying(false);

          // 프리뷰 상태 새로고침
          const updatedPreviews = await api.getScenePreview(chatId);
          if (updatedPreviews.previews) {
            setScenePreviews(updatedPreviews.previews);
            setChatItems(prev =>
              prev.map(item =>
                item.type === 'scene_previews'
                  ? { ...item, scenePreviews: updatedPreviews.previews, aspectRatio: updatedPreviews.aspectRatio }
                  : item
              )
            );
          }

          const stillFailed = updatedPreviews.previews?.filter(s => s.previewStatus === 'FAILED').length || 0;
          if (stillFailed === 0) {
            addMessage({
              type: 'assistant',
              content: '모든 씬 생성이 완료되었어요! 나레이션을 확인하고 TTS를 생성해주세요.'
            });
          } else {
            addMessage({
              type: 'assistant',
              content: `다시 만들기 완료! ${stillFailed}개 씬이 아직 문제가 있어요. 다시 시도해주세요.`
            });
          }
        }

        // 재시도 중인 씬이 없으면 폴링 중지
        const retryingCount = response.failedScenes?.filter(f => f.isRetrying).length || 0;
        if (retryingCount === 0 && response.status !== 'processing') {
          if (pollingRef.current) {
            clearInterval(pollingRef.current);
            pollingRef.current = null;
          }
          setIsRetrying(false);
        }
      } catch (err) {
        console.error('Retry polling error:', err);
      }
    }, 3000);
  };

  // v2.5.0: TTS/자막 생성 시작 (Nginx 타임아웃 대응)
  const handleStartTtsGeneration = async () => {
    addMessage({
      type: 'assistant',
      content: '나레이션 TTS와 자막을 생성하고 있어요...\n\n마지막에 썸네일 2초 영상도 추가됩니다.\n시간이 조금 걸릴 수 있어요.'
    });

    setFlowStep('TTS_GENERATING');
    setIsLoading(true);

    try {
      await api.generateSceneAudio(chatId, { includeSubtitle: true });
      startTtsPolling();
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : String(err);
      console.log('TTS generation request error (may be timeout):', errorMsg);

      // 503/타임아웃 에러는 백엔드에서 작업이 시작되었을 수 있음 - 폴링으로 확인
      if (errorMsg.includes('503') || errorMsg.includes('504') ||
          errorMsg.includes('timeout') || errorMsg.includes('Timeout') ||
          errorMsg.includes('overload') || errorMsg.includes('Gateway')) {
        setPollingErrorMessage('서버가 바빠서 응답이 지연되고 있어요. 확인 중...');
        startTtsPolling();
      } else {
        // v2.9.171: 에러 발생 시 백엔드가 실제로 시작했는지 확인
        try {
          const detail = await api.getChatDetail(chatId);
          if (detail.stage === 'TTS_GENERATING' || detail.stage === 'TTS_DONE') {
            console.log('[v2.9.171] Backend is processing TTS, starting polling...');
            startTtsPolling();
            return;
          }
        } catch { /* 복구 실패 무시 */ }
        setIsLoading(false);
        const parsedError = parseErrorMessage(err);
        addMessage({
          type: 'assistant',
          content: `TTS 생성 중 문제가 발생했어요.\n\n${parsedError}`
        });
        setFlowStep('PREVIEWS_DONE');
      }
    }
  };

  // v2.5.0: TTS 폴링 (503 에러 대응 개선)
  const startTtsPolling = () => {
    // v2.9.1: 기존 폴링 정리 후 시작
    stopCurrentPolling();
    currentPollingChatIdRef.current = chatId;

    pollingRef.current = setInterval(async () => {
      // v2.9.1: chatId가 변경되었으면 폴링 중지
      if (currentPollingChatIdRef.current !== chatId) {
        stopCurrentPolling();
        return;
      }
      try {
        const progress = await api.getSceneAudioProgress(chatId);
        setTtsProgress(progress);

        // 에러 상태 초기화 (성공 시)
        if (pollingErrorCountRef.current > 0) {
          pollingErrorCountRef.current = 0;
          setPollingErrorCount(0);
          setPollingErrorMessage(null);
        }

        if (progress.status === 'completed') {
          if (pollingRef.current) clearInterval(pollingRef.current);
          setIsLoading(false);
          setPollingErrorCount(0);
          setPollingErrorMessage(null);

          // v2.6.0: TTS 완료 후 씬 프리뷰 데이터 다시 로드 (개별 씬 영상 URL 포함)
          try {
            const updatedPreviews = await api.getScenePreview(chatId);
            if (updatedPreviews.previews) {
              setScenePreviews(updatedPreviews.previews);
            }
          } catch (err) {
            console.error('Failed to load updated scene previews:', err);
          }

          addMessage({
            type: 'assistant',
            content: '모든 씬의 TTS와 자막이 완성되었어요!\n\n이제 최종 영상 합성을 시작할 준비가 되었습니다.'
          });

          setFlowStep('AUDIO_DONE');

          // v2.9.49: 썸네일은 PREVIEWS_DONE에서 이미 생성됨. 중복 생성 제거.

          // v2.9.75: auto-proceed - 자동으로 최종 영상 합성 시작
          setTimeout(() => {
            console.log('[v2.9.75] Auto-proceeding to video generation...');
            (async () => {
              try {
                addMessage({
                  type: 'assistant',
                  content: '🎬 최종 영상을 자동으로 합성합니다...\n\n잠시만 기다려주세요.'
                });
                setFlowStep('VIDEO_GENERATING');
                setIsLoading(true);
                await api.generateFinalVideo(chatId);
                startVideoPolling();
              } catch (videoErr) {
                console.error('[v2.9.171] Auto video generation error:', videoErr);
                // v2.9.171: 에러 발생 시 백엔드가 실제로 시작했는지 확인
                try {
                  const detail = await api.getChatDetail(chatId);
                  if (detail.stage === 'VIDEO_GENERATING' || detail.stage === 'VIDEO_DONE') {
                    console.log('[v2.9.171] Backend is processing video, starting polling...');
                    startVideoPolling();
                    return;
                  }
                } catch { /* 복구 실패 무시 */ }
                setIsLoading(false);
                setFlowStep('AUDIO_DONE');
                addMessage({
                  type: 'assistant',
                  content: `영상 합성 중 문제가 발생했어요.\n\n${parseErrorMessage(videoErr)}\n\n아래 버튼을 눌러 다시 시도해주세요.`
                });
              }
            })();
          }, 1500);
        }
      } catch (err) {
        console.error('TTS polling error:', err);
        const errorMsg = err instanceof Error ? err.message : String(err);

        pollingErrorCountRef.current += 1;
        setPollingErrorCount(pollingErrorCountRef.current);

        if (errorMsg.includes('503') || errorMsg.includes('overload') || errorMsg.includes('busy')) {
          setPollingErrorMessage('서버가 잠시 바빠요. 자동으로 다시 시도하고 있어요...');
        } else {
          setPollingErrorMessage('잠시 문제가 생겼어요. 다시 시도하고 있어요...');
        }

        if (pollingErrorCountRef.current >= MAX_POLLING_ERRORS) {
          await recoverFromPollingFailure();
        }
      }
    }, 3000);
  };

  // 이미지 폴링 (레거시)
  const startImagePolling = () => {
    // v2.9.1: 기존 폴링 정리 후 시작
    stopCurrentPolling();
    currentPollingChatIdRef.current = chatId;

    pollingRef.current = setInterval(async () => {
      // v2.9.1: chatId가 변경되었으면 폴링 중지
      if (currentPollingChatIdRef.current !== chatId) {
        stopCurrentPolling();
        return;
      }
      try {
        const progress = await api.getImagesProgress(chatId);
        setImagesProgress(progress);

        if (progress.downloadReady) {
          if (pollingRef.current) clearInterval(pollingRef.current);
          setIsLoading(false);

          addMessage({
            type: 'assistant',
            content: `이미지 ${progress.totalCount}장이 모두 완성되었어요!\n\n다음으로 나레이션을 생성할까요?`
          });

          setFlowStep('IMAGES_DONE');
        }
      } catch (err) {
        console.error('Image polling error:', err);
        pollingErrorCountRef.current += 1;
        setPollingErrorCount(pollingErrorCountRef.current);

        if (pollingErrorCountRef.current >= MAX_POLLING_ERRORS) {
          await recoverFromPollingFailure();
        }
      }
    }, 3000);
  };

  // 오디오 생성 시작
  const handleStartAudioGeneration = async () => {
    addMessage({
      type: 'assistant',
      content: '나레이션을 생성하고 있어요...'
    });

    setFlowStep('AUDIO_GENERATING');
    setIsLoading(true);

    try {
      await api.generateAudio(chatId);
      startAudioPolling();
    } catch (err) {
      setIsLoading(false);
      const errorMsg = parseErrorMessage(err);
      addMessage({
        type: 'assistant',
        content: `나레이션 생성 중 문제가 발생했어요.\n\n${errorMsg}`
      });
      setFlowStep('IMAGES_DONE');
    }
  };

  // 오디오 폴링
  const startAudioPolling = () => {
    // v2.9.1: 기존 폴링 정리 후 시작
    stopCurrentPolling();
    currentPollingChatIdRef.current = chatId;

    pollingRef.current = setInterval(async () => {
      // v2.9.1: chatId가 변경되었으면 폴링 중지
      if (currentPollingChatIdRef.current !== chatId) {
        stopCurrentPolling();
        return;
      }

      try {
        const progress = await api.getAudioProgress(chatId);
        setAudioProgress(progress);

        if (progress.downloadReady) {
          stopCurrentPolling();
          setIsLoading(false);

          addMessage({
            type: 'assistant',
            content: '나레이션이 모두 완성되었어요!\n\n이제 영상으로 합성할까요?'
          });

          setFlowStep('AUDIO_DONE');
        }
      } catch (err) {
        console.error('Audio polling error:', err);
        pollingErrorCountRef.current += 1;
        setPollingErrorCount(pollingErrorCountRef.current);

        if (pollingErrorCountRef.current >= MAX_POLLING_ERRORS) {
          await recoverFromPollingFailure();
        }
      }
    }, 3000);
  };

  // 영상 합성 시작 (Nginx 타임아웃 대응)
  const handleStartVideoGeneration = async (skipApiKeyCheck = false) => {
    addMessage({
      type: 'assistant',
      content: '최종 영상을 합성하고 있어요...\n\n오프닝 영상 + 슬라이드 영상들 + 썸네일 2초 영상을 하나로 합칩니다.\n시간이 조금 걸릴 수 있어요.'
    });

    setFlowStep('VIDEO_GENERATING');
    setIsLoading(true);

    try {
      await api.generateVideo(chatId, { includeSubtitle: true, includeOpening });
      startVideoPolling();
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : String(err);
      console.log('Video generation request error (may be timeout):', errorMsg);

      // 503/타임아웃 에러는 백엔드에서 작업이 시작되었을 수 있음 - 폴링으로 확인
      if (errorMsg.includes('503') || errorMsg.includes('504') ||
          errorMsg.includes('timeout') || errorMsg.includes('Timeout') ||
          errorMsg.includes('overload') || errorMsg.includes('Gateway')) {
        setPollingErrorMessage('서버가 바빠서 응답이 지연되고 있어요. 확인 중...');
        startVideoPolling();
      } else {
        // v2.9.171: 에러 발생 시 백엔드가 실제로 시작했는지 확인
        try {
          const detail = await api.getChatDetail(chatId);
          if (detail.stage === 'VIDEO_GENERATING' || detail.stage === 'VIDEO_DONE') {
            console.log('[v2.9.171] Backend is processing video, starting polling...');
            startVideoPolling();
            return;
          }
        } catch { /* 복구 실패 무시 */ }
        setIsLoading(false);
        const parsedError = parseErrorMessage(err);
        addMessage({
          type: 'assistant',
          content: `영상 합성 중 문제가 발생했어요.\n\n${parsedError}`
        });
        setFlowStep('AUDIO_DONE');
      }
    }
  };

  // 영상 폴링 (에러 대응 개선)
  const startVideoPolling = () => {
    // v2.9.1: 기존 폴링 정리 후 시작
    stopCurrentPolling();
    currentPollingChatIdRef.current = chatId;
    setPollingErrorCount(0);
    setPollingErrorMessage(null);

    pollingRef.current = setInterval(async () => {
      // v2.9.1: chatId가 변경되었으면 폴링 중지
      if (currentPollingChatIdRef.current !== chatId) {
        stopCurrentPolling();
        return;
      }

      try {
        const progress = await api.getVideoProgress(chatId);
        setVideoProgress(progress);

        // 에러 상태 초기화 (성공 시)
        if (pollingErrorCountRef.current > 0) {
          pollingErrorCountRef.current = 0;
          setPollingErrorCount(0);
          setPollingErrorMessage(null);
        }

        if (progress.downloadReady) {
          stopCurrentPolling();
          setIsLoading(false);

          // v2.9.27: 영상 URL 가져오기 및 VIDEO_RESULT 메시지 추가
          try {
            const videoInfo = await api.getVideoDownloadInfo(chatId);
            if (videoInfo.downloadUrl) {
              setFinalVideoUrl(videoInfo.downloadUrl);

              // v2.9.27: VIDEO_RESULT 메시지 추가 (채팅에 표시)
              addMessage({
                type: 'video_result',
                videoResult: {
                  videoUrl: videoInfo.downloadUrl,
                  title: scenario?.title || 'Untitled'
                }
              });
            }
          } catch (urlErr) {
            console.warn('Failed to get video URL for preview:', urlErr);
            // 에러 시에도 완성 메시지는 표시 (v2.9.38: 3시간 만료 안내 추가)
            addMessage({
              type: 'assistant',
              content: '🎬 영상이 완성되었습니다!\n\n⏰ 다운로드 링크는 3시간 동안 유효합니다.\n3시간 이내에 다운로드해주세요.'
            });
          }

          // v2.9.75: VIDEO_DONE 상태에서 썸네일 버튼 제거 (v2.9.56 정책)
          // 썸네일은 PREVIEWS_DONE에서 이미 생성되어 최종 영상에 포함됨
          // 영상 완료 후에는 썸네일을 추가할 수 없으므로 버튼 표시하지 않음

          setFlowStep('VIDEO_DONE');
        }
      } catch (err) {
        console.error('Video polling error:', err);
        const errorMsg = err instanceof Error ? err.message : String(err);

        pollingErrorCountRef.current += 1;
        setPollingErrorCount(pollingErrorCountRef.current);

        if (errorMsg.includes('503') || errorMsg.includes('overload') || errorMsg.includes('busy')) {
          setPollingErrorMessage('서버가 잠시 바빠요. 자동으로 다시 시도하고 있어요...');
        } else {
          setPollingErrorMessage('잠시 문제가 생겼어요. 다시 시도하고 있어요...');
        }

        if (pollingErrorCountRef.current >= MAX_POLLING_ERRORS) {
          await recoverFromPollingFailure();
        }
      }
    }, 5000);
  };

  // 다운로드 처리
  // v2.9.8: S3 presigned URL 방식 - 인증된 API로 URL 조회 후 다운로드
  const handleDownload = async (type: 'scenario' | 'images' | 'video') => {
    try {
      switch (type) {
        case 'scenario':
          // 시나리오는 작은 파일이므로 기존 blob 방식 유지
          const blob = await api.downloadScenario(chatId);
          api.downloadBlob(blob, `scenario_${chatId}.txt`);
          break;
        case 'images':
          await api.downloadImages(chatId);
          break;
        case 'video':
          await api.downloadVideo(chatId);
          break;
      }
    } catch (err) {
      console.error('Download failed:', err);
      // 다운로드 실패 시 사용자에게 알림
      addMessage({
        type: 'assistant',
        content: '다운로드 기간이 지났어요.\n\n새로운 영상을 만들어주세요!'
      });
    }
  };

  // 로딩 화면
  if (isInitialLoading) {
    return (
      <div className="flex-1 flex items-center justify-center bg-[var(--color-background)]">
        <div className="flex flex-col items-center gap-4">
          <div className="w-10 h-10 border-2 border-neutral-200 rounded-full animate-spin border-t-neutral-900" />
          <span className="text-sm text-[var(--color-foreground-muted)]">잠시만요...</span>
        </div>
      </div>
    );
  }

  // 현재 진행률 계산
  // v2.9.172: flowStep이 *_GENERATING이면 progress가 null이어도 초기값으로 프로그레스바 즉시 표시
  const getProgressInfo = () => {
    if (flowStep === 'SCENARIO_GENERATING') {
      return scenarioProgress
        ? { current: scenarioProgress.completedSlides || 0, total: scenarioProgress.totalSlides || 1, message: scenarioProgress.message || '이야기를 만들고 있어요...' }
        : { current: 0, total: 1, message: '이야기를 만들고 있어요...' };
    }
    if (flowStep === 'PREVIEWS_GENERATING') {
      return scenePreviewProgress
        ? { current: scenePreviewProgress.completedCount || 0, total: scenePreviewProgress.totalCount || 1, message: scenePreviewProgress.progressMessage || '멋진 장면을 그리고 있어요...' }
        : { current: 0, total: 1, message: '멋진 장면을 그리고 있어요...' };
    }
    if (flowStep === 'TTS_GENERATING') {
      return ttsProgress
        ? { current: ttsProgress.completedCount || 0, total: ttsProgress.totalCount || 1, message: ttsProgress.progressMessage || '음성을 녹음하고 있어요...' }
        : { current: 0, total: 1, message: '음성과 자막을 준비하고 있어요...' };
    }
    if (flowStep === 'IMAGES_GENERATING') {
      return imagesProgress
        ? { current: imagesProgress.completedCount || 0, total: imagesProgress.totalCount || 1, message: imagesProgress.progressMessage || '이미지를 만들고 있어요...' }
        : { current: 0, total: 1, message: '이미지를 만들고 있어요...' };
    }
    if (flowStep === 'AUDIO_GENERATING') {
      return audioProgress
        ? { current: audioProgress.completedCount || 0, total: audioProgress.totalCount || 1, message: audioProgress.progressMessage || '나레이션을 만들고 있어요...' }
        : { current: 0, total: 1, message: '나레이션을 만들고 있어요...' };
    }
    if (flowStep === 'VIDEO_GENERATING') {
      return videoProgress
        ? { current: videoProgress.progress || 0, total: 100, message: videoProgress.progressMessage || '영상을 합성하고 있어요...', isPercent: true }
        : { current: 0, total: 100, message: '영상 합성을 준비하고 있어요...', isPercent: true };
    }
    return null;
  };

  const progressInfo = getProgressInfo();

  return (
    <div className="flex-1 flex flex-col min-h-0 overflow-hidden bg-[var(--color-background-secondary)]">
      {/* Header */}
      <header className="flex-shrink-0 glass border-b border-[var(--color-border)] safe-area-top">
        <div className="px-4 py-3">
          <div className="max-w-3xl mx-auto flex items-center justify-between">
            <div className="flex items-center gap-3">
              <h1 className="text-lg font-semibold text-[var(--color-foreground)]">AI 영상 제작</h1>
            </div>
            <button
              onClick={() => router.push('/')}
              className="touch-target flex items-center justify-center text-sm text-[var(--color-foreground-secondary)] hover:text-[var(--color-foreground)] transition-colors"
            >
              나가기
            </button>
          </div>
        </div>
      </header>

      {/* v2.9.86: 참조 이미지 표시 (여러 이미지 지원) */}
      {referenceImageUrl && (
        <div className="flex-shrink-0 px-4 py-3 border-b border-[var(--color-border)] bg-[var(--color-background)]">
          <div className="max-w-3xl mx-auto flex items-center gap-3">
            <div className="flex gap-2 flex-shrink-0">
              {referenceImageUrl.split(',').map((url, index) => (
                <div key={index} className="relative flex-shrink-0">
                  <Image
                    src={url.trim()}
                    alt={`참조 이미지 ${index + 1}`}
                    width={60}
                    height={60}
                    className="w-15 h-15 rounded-lg object-cover border border-[var(--color-border)]"
                    unoptimized
                  />
                </div>
              ))}
            </div>
            <div className="flex-1 min-w-0">
              <p className="text-xs text-[var(--color-foreground-muted)]">
                참조 이미지 ({referenceImageUrl.split(',').length}장)
              </p>
              <p className="text-sm text-[var(--color-foreground)] truncate">이 스타일로 콘텐츠가 생성됩니다</p>
            </div>
          </div>
        </div>
      )}

      {/* Chat Messages */}
      <div className="flex-1 min-h-0 overflow-y-auto momentum-scroll custom-scrollbar">
        <div className="max-w-3xl mx-auto px-4 py-6 space-y-4">
          {chatItems.map((item) => {
            // 사용자 메시지
            if (item.type === 'user') {
              return (
                <div key={item.id} className="flex justify-end animate-slideUp">
                  <div className="max-w-[85%] bg-neutral-900 text-white rounded-2xl rounded-br-sm px-4 py-3">
                    <p className="text-sm">{item.content}</p>
                  </div>
                </div>
              );
            }

            // AI 메시지
            if (item.type === 'assistant') {
              return (
                <div key={item.id} className="flex justify-start animate-slideUp">
                  <div className="max-w-[85%] glass border border-[var(--color-border)] rounded-2xl rounded-bl-sm px-4 py-3">
                    <p className="text-sm whitespace-pre-wrap text-[var(--color-foreground)]">{item.content}</p>
                  </div>
                </div>
              );
            }

            // 선택지
            if (item.type === 'choices' && item.choices) {
              return (
                <div key={item.id} className="animate-slideUp">
                  <ChatChoices
                    choices={item.choices}
                    onSelect={(choice) => handleChoiceSelect(choice, item.choiceType || '')}
                    disabled={!!item.selected || isLoading}
                    columns={item.choices.length <= 2 ? 2 : 2}
                  />
                </div>
              );
            }

            // v2.8.0: 장르 선택 UI
            if (item.type === 'genre_selector') {
              return (
                <div key={item.id} className="animate-slideUp">
                  <GenreSelector
                    onSelect={handleGenreSelect}
                    disabled={item.genreConfirmed || isLoading}
                    selectedCreatorId={selectedCreatorId}
                  />
                </div>
              );
            }

            // v2.9.73: 슬라이드 수 선택 UI
            if (item.type === 'slide_count_selector') {
              return (
                <div key={item.id} className="animate-slideUp">
                  <div className="glass border border-[var(--color-border)] rounded-2xl p-5">
                    <div className="mb-4">
                      <h3 className="font-bold text-base text-[var(--color-foreground)]">
                        슬라이드 수 선택
                      </h3>
                      <p className="text-xs text-[var(--color-foreground-muted)] mt-1">
                        이미지 한 장당 약 2분
                      </p>
                    </div>

                    <div className="flex items-center justify-center gap-4 py-4">
                      <button
                        onClick={() => setSelectedSlideCount(prev => Math.max(1, prev - 1))}
                        disabled={item.slideCountConfirmed || isLoading || selectedSlideCount <= 1}
                        className="w-12 h-12 rounded-full border-2 border-[var(--color-border)] flex items-center justify-center text-xl font-bold hover:bg-[var(--color-background-secondary)] disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                      >
                        −
                      </button>
                      <div className="text-center min-w-[120px]">
                        <div className="text-4xl font-bold text-[var(--color-foreground)]">
                          {selectedSlideCount}
                        </div>
                        <div className="text-sm text-[var(--color-foreground-muted)]">
                          장
                        </div>
                      </div>
                      <button
                        onClick={() => setSelectedSlideCount(prev => Math.min(5, prev + 1))}
                        disabled={item.slideCountConfirmed || isLoading || selectedSlideCount >= 5}
                        className="w-12 h-12 rounded-full border-2 border-[var(--color-border)] flex items-center justify-center text-xl font-bold hover:bg-[var(--color-background-secondary)] disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                      >
                        +
                      </button>
                    </div>

                    <div className="text-center py-3 bg-[var(--color-background-secondary)] rounded-xl mb-4">
                      <span className="text-sm text-[var(--color-foreground-muted)]">예상 영상 길이: </span>
                      <span className="text-lg font-bold text-[var(--color-foreground)]">
                        약 {calculateEstimatedMinutes(selectedSlideCount)}분
                      </span>
                    </div>

                    {/* v2.9.119: 2장 이상 쇼츠 불가 안내 */}
                    {selectedSlideCount >= 2 && (
                      <div className="text-center py-2 px-3 bg-blue-50 border border-blue-200 rounded-xl mb-4">
                        <span className="text-sm text-blue-700">
                          ℹ️ 2장 이상은 쇼츠(9:16) 비율 제작이 불가합니다
                        </span>
                      </div>
                    )}

                    {/* v2.9.119: 5장 이상 생성 시간 경고 */}
                    {selectedSlideCount >= 5 && (
                      <div className="text-center py-2 px-3 bg-amber-50 border border-amber-200 rounded-xl mb-4">
                        <span className="text-sm text-amber-700">
                          ⚠️ 콘텐츠 생성에 30~60분 정도 소요됩니다
                        </span>
                      </div>
                    )}

                    <button
                      onClick={() => handleSlideCountConfirm(selectedSlideCount)}
                      disabled={item.slideCountConfirmed || isLoading}
                      className="w-full py-3 px-4 bg-neutral-900 text-white rounded-xl font-medium hover:bg-neutral-800 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                    >
                      {item.slideCountConfirmed ? '✓ 선택 완료' : '확정하기'}
                    </button>
                  </div>
                </div>
              );
            }

            // 시나리오 요약
            if (item.type === 'summary' && item.summary) {
              return (
                <div key={item.id} className="animate-slideUp">
                  <div className="glass border border-[var(--color-border)] rounded-2xl p-5">
                    <div className="mb-4">
                      <h3 className="font-bold text-lg text-[var(--color-foreground)]">
                        {item.summary.title}
                      </h3>
                    </div>

                    {item.summary.hook && (
                      <p className="text-sm text-[var(--color-foreground-secondary)] italic mb-4">
                        &ldquo;{item.summary.hook}&rdquo;
                      </p>
                    )}

                    <div className="border-t border-[var(--color-border)] pt-4">
                      <p className="text-xs text-[var(--color-foreground-muted)] mb-2">스토리 미리보기</p>
                      <div className="space-y-2">
                        {/* v2.9.2: null 체크 강화 */}
                        {item.summary.slides && item.summary.slides.length > 0 && item.summary.slides.slice(0, 3).map((slide, idx) => (
                          <div key={`slide-${idx}-${slide.narration.substring(0, 10)}`} className="flex gap-2 text-sm">
                            <span className="text-[var(--color-foreground-muted)] flex-shrink-0">{idx + 1}.</span>
                            <span className="text-[var(--color-foreground-secondary)]">
                              {slide.narration.length > 60
                                ? slide.narration.substring(0, 60) + '...'
                                : slide.narration}
                            </span>
                          </div>
                        ))}
                        {item.summary.slides && item.summary.slides.length > 3 && (
                          <p className="text-xs text-[var(--color-foreground-muted)]">
                            ... 외 {item.summary.slides.length - 3}개 슬라이드
                          </p>
                        )}
                      </div>
                    </div>

                    <div className="flex items-center gap-4 mt-4 pt-4 border-t border-[var(--color-border)]">
                      <span className="text-xs text-[var(--color-foreground-muted)]">
                        총 {item.summary.slides?.length || 0}장
                      </span>
                      <span className="text-xs text-[var(--color-foreground-muted)]">
                        예상 {Math.floor((item.summary.estimatedDuration || 0) / 60)}분 {(item.summary.estimatedDuration || 0) % 60}초
                      </span>
                    </div>
                  </div>
                </div>
              );
            }

            // 오프닝 영상 내용
            if (item.type === 'opening' && item.opening) {
              return (
                <div key={item.id} className="animate-slideUp">
                  <div className="glass border border-[var(--color-border)] rounded-2xl p-5">
                    <div className="flex items-center gap-2 mb-4">
                      <div className="w-8 h-8 bg-neutral-900 rounded-full flex items-center justify-center">
                        <svg className="w-4 h-4 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z" />
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                        </svg>
                      </div>
                      <h3 className="font-bold text-lg text-[var(--color-foreground)]">
                        오프닝 영상 (8초)
                      </h3>
                    </div>

                    <div className="space-y-4">
                      {/* 나레이션 */}
                      <div className="bg-neutral-50 rounded-xl p-4">
                        <p className="text-xs text-[var(--color-foreground-muted)] mb-2 font-medium">나레이션</p>
                        <p className="text-sm text-[var(--color-foreground-secondary)] leading-relaxed">
                          &ldquo;{item.opening.narration}&rdquo;
                        </p>
                      </div>

                      {/* 영상 프롬프트 */}
                      <div className="bg-neutral-50 rounded-xl p-4">
                        <p className="text-xs text-[var(--color-foreground-muted)] mb-2 font-medium">영상 생성 프롬프트</p>
                        <p className="text-xs text-[var(--color-foreground-muted)] leading-relaxed font-mono">
                          {item.opening.videoPrompt.length > 200
                            ? item.opening.videoPrompt.substring(0, 200) + '...'
                            : item.opening.videoPrompt}
                        </p>
                      </div>
                    </div>

                    <div className="mt-4 pt-4 border-t border-[var(--color-border)]">
                      <p className="text-xs text-[var(--color-foreground-muted)]">
                        Veo 3.1로 8초 오프닝 영상을 생성합니다
                      </p>
                    </div>
                  </div>
                </div>
              );
            }

            // v2.9.27: 최종 영상 결과 메시지
            if (item.type === 'video_result' && item.videoResult) {
              return (
                <div key={item.id}>
                  <VideoResultMessage metadata={item.videoResult} />
                </div>
              );
            }

            // v2.9.27: 썸네일 결과 메시지
            if (item.type === 'thumbnail_result' && item.thumbnailResult) {
              return (
                <div key={item.id}>
                  <ThumbnailResultMessage metadata={item.thumbnailResult} />
                </div>
              );
            }

            // v2.5.0: 씬 프리뷰 목록 (v2.9.11: 재생성 UI 제거 - 완전 자동화)
            // v2.9.25: aspectRatio 전달 (Shorts 등 세로형 영상 지원)
            if (item.type === 'scene_previews' && item.scenePreviews) {
              return (
                <div key={item.id} className="animate-slideUp">
                  <ScenePreviewList
                    previews={item.scenePreviews}
                    onGenerateAudio={handleStartTtsGeneration}
                    canGenerateAudio={flowStep === 'PREVIEWS_DONE'}
                    onRetryFailed={handleRetryFailed}
                    isRetrying={isRetrying}
                    aspectRatio={item.aspectRatio}
                  />
                </div>
              );
            }

            return null;
          })}

          {/* 진행률 표시 */}
          {progressInfo && (
            <div className="glass border border-[var(--color-border)] rounded-2xl p-4 animate-fadeIn">
              <div className="flex items-center justify-between mb-3">
                <span className="font-medium text-sm text-[var(--color-foreground)]">
                  {flowStep === 'SCENARIO_GENERATING' && '시나리오 생성 중'}
                  {flowStep === 'PREVIEWS_GENERATING' && '씬 프리뷰 생성 중'}
                  {flowStep === 'TTS_GENERATING' && 'TTS/자막 생성 중'}
                  {flowStep === 'IMAGES_GENERATING' && '이미지 생성 중'}
                  {flowStep === 'AUDIO_GENERATING' && '나레이션 생성 중'}
                  {flowStep === 'VIDEO_GENERATING' && '영상 합성 중'}
                </span>
                <span className="text-sm font-mono text-[var(--color-foreground-secondary)]">
                  {progressInfo.current}/{progressInfo.total}
                </span>
              </div>
              <div className="h-2 bg-neutral-200 rounded-full overflow-hidden">
                <div
                  className="h-full bg-neutral-900 transition-all duration-500 relative overflow-hidden"
                  style={{ width: `${(progressInfo.current / progressInfo.total) * 100}%` }}
                >
                  <div className="absolute inset-0 bg-gradient-to-r from-transparent via-white/30 to-transparent animate-shimmer" />
                </div>
              </div>
              <p className="text-xs text-[var(--color-foreground-muted)] mt-2">
                {pollingErrorMessage || progressInfo.message}
              </p>
              {/* 서버 응답 지연 안내 */}
              {pollingErrorMessage && (
                <p className="text-xs text-amber-600 mt-1">
                  백그라운드에서 작업이 계속 진행 중입니다. 페이지를 닫지 마세요.
                </p>
              )}
            </div>
          )}

          {/* 로딩 (시나리오 생성 중) */}
          {isLoading && flowStep === 'GENERATING' && (
            <div className="flex justify-start animate-fadeIn">
              <div className="glass border border-[var(--color-border)] rounded-2xl rounded-bl-sm px-4 py-3">
                <div className="flex items-center gap-3">
                  <div className="w-5 h-5 border-2 border-neutral-300 border-t-neutral-900 rounded-full animate-spin" />
                  <span className="text-sm text-[var(--color-foreground-muted)]">이야기를 만들고 있어요...</span>
                </div>
              </div>
            </div>
          )}

          <div ref={messagesEndRef} />
        </div>
      </div>

      {/* Bottom Action Panel - v2.9.10: safe-area-bottom 제거 (MainLayout has-bottom-nav에서 이미 처리) */}
      <div className="flex-shrink-0 glass border-t border-[var(--color-border)]">
        <div className="max-w-3xl mx-auto p-4">
          {/* 이미지 생성 준비 */}
          {flowStep === 'READY_TO_GENERATE' && (
            <button
              onClick={() => handleStartImageGeneration()}
              disabled={isLoading}
              className="w-full touch-target py-4 bg-neutral-900 text-white rounded-xl font-medium hover:bg-neutral-800 active:scale-[0.99] transition-all disabled:opacity-50"
            >
              이미지 생성 시작
            </button>
          )}

          {/* 이미지 완료 후 오디오 생성 */}
          {flowStep === 'IMAGES_DONE' && (
            <div className="flex gap-3">
              <button
                onClick={() => handleDownload('images')}
                className="flex-1 touch-target py-3.5 border border-[var(--color-border)] text-[var(--color-foreground-secondary)] rounded-xl font-medium hover:bg-[var(--color-background-secondary)] active:scale-[0.99] transition-all"
              >
                이미지 다운로드
              </button>
              <button
                onClick={handleStartAudioGeneration}
                disabled={isLoading}
                className="flex-1 touch-target py-3.5 bg-neutral-900 text-white rounded-xl font-medium hover:bg-neutral-800 active:scale-[0.99] transition-all disabled:opacity-50"
              >
                나레이션 생성
              </button>
            </div>
          )}

          {/* 오디오 완료 후 영상 합성 - 버튼만 표시 (ScenePreviewList는 메시지 영역에서 렌더링) */}
          {flowStep === 'AUDIO_DONE' && (
            <div className="flex flex-col gap-3">
              {/* 실패한 씬이 있을 경우 안내 메시지와 재시도 버튼 */}
              {scenePreviews.some(p => p.previewStatus === 'FAILED') && (
                <div className="p-3 bg-red-50 border border-red-200 rounded-xl">
                  <p className="text-sm text-red-700 mb-2">
                    {scenePreviews.filter(p => p.previewStatus === 'FAILED').length}개 씬 음성 생성에 문제가 생겼어요.
                  </p>
                  <button
                    onClick={handleRetryFailed}
                    disabled={isRetrying}
                    className="w-full py-2 bg-red-600 text-white rounded-lg font-medium hover:bg-red-700 disabled:opacity-50"
                  >
                    {isRetrying ? '다시 만드는 중...' : '다시 만들기'}
                  </button>
                </div>
              )}
              {scenePreviews.every(p => p.previewStatus === 'COMPLETED') && (
                <button
                  onClick={() => handleStartVideoGeneration()}
                  disabled={isLoading}
                  className="w-full touch-target py-3.5 bg-neutral-900 text-white rounded-xl font-medium hover:bg-neutral-800 active:scale-[0.99] transition-all disabled:opacity-50"
                >
                  최종 영상 합성
                </button>
              )}
            </div>
          )}

          {/* 생성 중 안내 */}
          {(flowStep === 'PREVIEWS_GENERATING' || flowStep === 'TTS_GENERATING' || flowStep === 'IMAGES_GENERATING' || flowStep === 'AUDIO_GENERATING' || flowStep === 'VIDEO_GENERATING') && (
            <div className="text-center py-3 text-[var(--color-foreground-muted)] text-sm">
              열심히 만들고 있어요 ✨
            </div>
          )}
        </div>
      </div>

    </div>
  );
}
