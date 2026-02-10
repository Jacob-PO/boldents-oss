'use client';

interface VideoResultMessageProps {
  metadata: {
    videoUrl: string;
    title: string;
  };
}

/**
 * 최종 영상 결과 메시지 컴포넌트 (v2.9.27)
 * - 영상 프리뷰 표시
 * - 제목 정보
 * - 다운로드 링크
 */
export function VideoResultMessage({ metadata }: VideoResultMessageProps) {
  const { videoUrl, title } = metadata;

  return (
    <div className="animate-slideUp">
      <div className="glass border border-[var(--color-border)] rounded-2xl p-4 overflow-hidden">
        <div className="flex items-center justify-between mb-3">
          <h3 className="font-bold text-lg text-[var(--color-foreground)]">
            ✅ 최종 영상 완성!
          </h3>
        </div>

        {/* Video Preview */}
        <div className="aspect-video relative rounded-lg overflow-hidden bg-gray-100 mb-4">
          <video
            src={videoUrl}
            controls
            className="w-full h-full object-contain"
            preload="metadata"
          >
            Your browser does not support the video tag.
          </video>
        </div>

        {/* Video Info */}
        <div className="space-y-2 mb-4">
          <div>
            <h4 className="text-sm font-semibold text-[var(--color-foreground-muted)] mb-1">
              제목
            </h4>
            <p className="text-sm text-[var(--color-foreground)]">{title}</p>
          </div>
        </div>

        {/* Download Button */}
        <a
          href={videoUrl}
          download={`${title}_final.mp4`}
          className="inline-flex items-center gap-2 px-4 py-2 bg-gray-900 text-white rounded-lg hover:bg-gray-800 transition-colors"
        >
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
          </svg>
          영상 다운로드
        </a>
      </div>
    </div>
  );
}
