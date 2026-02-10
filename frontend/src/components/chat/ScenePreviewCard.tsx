'use client';

import { ScenePreviewInfo } from '@/lib/api';

interface ScenePreviewCardProps {
  preview: ScenePreviewInfo;
  aspectRatio?: string;  // v2.9.25: "16:9" 또는 "9:16"
}

/**
 * 씬 프리뷰 카드 컴포넌트 (v2.9.11 - 완전 자동화)
 * - 이미지/영상 표시
 * - 나레이션 텍스트 읽기 전용
 * - 재생성 기능 제거 (자동 재시도로 대체)
 * - v2.9.25: 동적 aspect ratio 지원
 */
export function ScenePreviewCard({ preview, aspectRatio = '16:9' }: ScenePreviewCardProps) {
  const getStatusBadge = () => {
    switch (preview.previewStatus) {
      case 'PENDING':
        return (
          <span className="inline-flex items-center gap-1 px-2 py-1 text-xs bg-gray-100 text-gray-600 rounded-full">
            <svg className="w-3 h-3" viewBox="0 0 20 20" fill="currentColor"><circle cx="10" cy="10" r="4" /></svg>
            대기
          </span>
        );
      case 'GENERATING':
        return (
          <span className="inline-flex items-center gap-1 px-2 py-1 text-xs bg-gray-200 text-gray-800 rounded-full animate-pulse">
            <svg className="w-3 h-3 animate-spin" viewBox="0 0 20 20" fill="none" stroke="currentColor"><path d="M10 3v4M10 13v4M3 10h4M13 10h4" strokeWidth="2" strokeLinecap="round"/></svg>
            생성 중
          </span>
        );
      case 'MEDIA_READY':
        return (
          <span className="inline-flex items-center gap-1 px-2 py-1 text-xs bg-gray-200 text-gray-700 rounded-full">
            <svg className="w-3 h-3" viewBox="0 0 20 20" fill="currentColor"><path fillRule="evenodd" d="M4 3a2 2 0 00-2 2v10a2 2 0 002 2h12a2 2 0 002-2V5a2 2 0 00-2-2H4zm12 12H4l4-8 3 6 2-4 3 6z" clipRule="evenodd" /></svg>
            미디어
          </span>
        );
      case 'TTS_READY':
        return (
          <span className="inline-flex items-center gap-1 px-2 py-1 text-xs bg-gray-300 text-gray-800 rounded-full">
            <svg className="w-3 h-3" viewBox="0 0 20 20" fill="currentColor"><path fillRule="evenodd" d="M9.383 3.076A1 1 0 0110 4v12a1 1 0 01-1.707.707L4.586 13H2a1 1 0 01-1-1V8a1 1 0 011-1h2.586l3.707-3.707a1 1 0 011.09-.217zM14.657 2.929a1 1 0 011.414 0A9.972 9.972 0 0119 10a9.972 9.972 0 01-2.929 7.071 1 1 0 01-1.414-1.414A7.971 7.971 0 0017 10a7.971 7.971 0 00-2.343-5.657 1 1 0 010-1.414z" clipRule="evenodd" /></svg>
            TTS
          </span>
        );
      case 'COMPLETED':
        return (
          <span className="inline-flex items-center gap-1 px-2 py-1 text-xs bg-gray-900 text-white rounded-full animate-scaleIn">
            <svg className="w-3 h-3" viewBox="0 0 20 20" fill="currentColor"><path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" /></svg>
            완료
          </span>
        );
      case 'FAILED':
        return (
          <span className="inline-flex items-center gap-1 px-2 py-1 text-xs bg-gray-200 text-gray-800 rounded-full">
            <svg className="w-3 h-3" viewBox="0 0 20 20" fill="currentColor"><path fillRule="evenodd" d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" clipRule="evenodd" /></svg>
            실패
          </span>
        );
      default:
        return null;
    }
  };

  return (
    <div className="border border-gray-200 rounded-xl overflow-hidden bg-white shadow-sm">
      {/* 헤더 */}
      <div className="flex items-center justify-between px-4 py-3 bg-gray-50 border-b border-gray-200">
        <div className="flex items-center gap-2">
          <span className="w-6 h-6 flex items-center justify-center bg-black text-white text-xs font-medium rounded-full">
            {preview.sceneOrder}
          </span>
          <span className="text-sm font-medium text-gray-800">
            {preview.sceneType === 'OPENING' ? '오프닝' : `슬라이드 ${preview.sceneOrder}`}
          </span>
          {preview.title && (
            <span className="text-xs text-gray-500 ml-2">{preview.title}</span>
          )}
        </div>
        {getStatusBadge()}
      </div>

      {/* 미디어 영역 - v2.9.25: 동적 aspect ratio */}
      <div className={`${aspectRatio === '9:16' ? 'aspect-[9/16]' : 'aspect-video'} bg-gray-100 relative`}>
        {/* COMPLETED 상태이고 합성된 영상이 있으면 영상 표시 */}
        {preview.previewStatus === 'COMPLETED' && preview.sceneVideoUrl ? (
          <video
            src={preview.sceneVideoUrl}
            className="w-full h-full object-cover"
            controls
            playsInline
          />
        ) : preview.mediaUrl ? (
          preview.mediaType === 'video' ? (
            <video
              src={preview.mediaUrl}
              className="w-full h-full object-cover"
              controls
              muted
              playsInline
            />
          ) : (
            <img
              src={preview.mediaUrl}
              alt={`Scene ${preview.sceneOrder}`}
              className="w-full h-full object-cover"
            />
          )
        ) : preview.previewStatus === 'GENERATING' ? (
          <div className="w-full h-full flex items-center justify-center">
            <div className="text-center">
              <div className="w-8 h-8 border-2 border-gray-300 border-t-black rounded-full animate-spin mx-auto mb-2" />
              <p className="text-sm text-gray-500">생성 중...</p>
            </div>
          </div>
        ) : preview.previewStatus === 'FAILED' ? (
          <div className="w-full h-full flex items-center justify-center">
            <div className="text-center text-[var(--color-gray-700)]">
              <svg className="w-8 h-8 mx-auto mb-2" viewBox="0 0 20 20" fill="currentColor">
                <path fillRule="evenodd" d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" clipRule="evenodd" />
              </svg>
              <p className="text-sm">{preview.errorMessage || '생성 실패'}</p>
            </div>
          </div>
        ) : (
          <div className="w-full h-full flex items-center justify-center">
            <p className="text-sm text-gray-400">대기 중</p>
          </div>
        )}
      </div>

      {/* 나레이션 영역 - v2.9.11: 읽기 전용 */}
      <div className="p-4">
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs font-medium text-gray-500">나레이션</span>
        </div>
        <p className="text-sm text-gray-700 leading-relaxed whitespace-pre-wrap">
          {preview.narration}
        </p>
      </div>
    </div>
  );
}

interface ScenePreviewListProps {
  previews: ScenePreviewInfo[];
  onGenerateAudio?: () => void;
  canGenerateAudio?: boolean;
  onRetryFailed?: () => void;  // 실패 씬 자동 재시도
  isRetrying?: boolean;        // 재시도 중 상태
  aspectRatio?: string;        // v2.9.25: "16:9" 또는 "9:16"
}

/**
 * 씬 프리뷰 목록 컴포넌트 (v2.9.11 - 완전 자동화)
 * - 수동 재생성 기능 제거
 * - 실패 시 자동 재시도만 지원
 */
export function ScenePreviewList({
  previews,
  onGenerateAudio,
  canGenerateAudio = false,
  onRetryFailed,
  isRetrying = false,
  aspectRatio = '16:9',
}: ScenePreviewListProps) {
  const allMediaReady = previews.every(p =>
    p.previewStatus === 'MEDIA_READY' || p.previewStatus === 'TTS_READY' || p.previewStatus === 'COMPLETED'
  );

  const failedPreviews = previews.filter(p => p.previewStatus === 'FAILED');
  const hasFailedScenes = failedPreviews.length > 0;

  return (
    <div className="space-y-4">
      {/* 실패 씬 안내 및 자동 재시도 */}
      {hasFailedScenes && onRetryFailed && (
        <div className="bg-gray-100 border border-gray-300 rounded-lg p-4">
          <div className="flex items-start gap-3">
            <svg className="w-5 h-5 text-gray-700 flex-shrink-0 mt-0.5" viewBox="0 0 20 20" fill="currentColor">
              <path fillRule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clipRule="evenodd" />
            </svg>
            <div className="flex-1">
              <p className="text-sm font-medium text-gray-900">
                {failedPreviews.length}개 씬 생성 실패
              </p>
              <p className="text-xs text-gray-600 mt-1">
                일부 씬 생성에 실패했습니다. 자동으로 재시도합니다.
              </p>
              <button
                onClick={onRetryFailed}
                disabled={isRetrying}
                className="mt-3 px-4 py-2 bg-gray-900 text-white text-sm rounded-lg hover:bg-gray-800 disabled:opacity-50 flex items-center gap-2"
              >
                {isRetrying ? (
                  <>
                    <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
                    재시도 중...
                  </>
                ) : (
                  <>
                    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                    </svg>
                    실패한 씬 재시도
                  </>
                )}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 안내 메시지 - 자동화 */}
      {allMediaReady && !hasFailedScenes && (
        <div className="bg-gray-50 border border-gray-200 rounded-lg p-4">
          <div className="flex items-start gap-3">
            <svg className="w-5 h-5 text-gray-900 flex-shrink-0 mt-0.5" viewBox="0 0 20 20" fill="currentColor">
              <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
            </svg>
            <div>
              <p className="text-sm font-medium text-gray-900">모든 씬 생성 완료</p>
              <p className="text-xs text-gray-600 mt-1">
                이미지와 영상이 준비되었습니다. TTS와 자막을 생성하여 최종 영상을 만듭니다.
              </p>
            </div>
          </div>
        </div>
      )}

      {/* 씬 목록 */}
      <div className="grid gap-4">
        {previews.map((preview) => (
          <ScenePreviewCard
            key={preview.sceneId}
            preview={preview}
            aspectRatio={aspectRatio}
          />
        ))}
      </div>

      {/* TTS 생성 버튼 - 실패 씬이 없을 때만 표시 */}
      {canGenerateAudio && onGenerateAudio && !hasFailedScenes && (
        <div className="pt-4">
          <button
            onClick={onGenerateAudio}
            className="w-full py-3 bg-black text-white rounded-xl font-medium hover:bg-gray-800 transition-colors flex items-center justify-center gap-2"
          >
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 11a7 7 0 01-7 7m0 0a7 7 0 01-7-7m7 7v4m0 0H8m4 0h4m-4-8a3 3 0 01-3-3V5a3 3 0 116 0v6a3 3 0 01-3 3z" />
            </svg>
            TTS 음성 및 자막 생성
          </button>
        </div>
      )}
    </div>
  );
}
