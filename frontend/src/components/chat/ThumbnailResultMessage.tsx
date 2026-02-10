'use client';

interface ThumbnailResultMessageProps {
  metadata: {
    thumbnailUrl: string;
    youtubeTitle: string;
    youtubeDescription: string;
    catchphrase: string;
  };
}

/**
 * 유튜브 썸네일 결과 메시지 컴포넌트 (v2.9.27)
 * - 썸네일 이미지 표시
 * - 유튜브 제목, 설명, 후킹 문구 정보
 * - 복사 기능 (제목, 설명)
 * - 다운로드 링크
 */
export function ThumbnailResultMessage({ metadata }: ThumbnailResultMessageProps) {
  const { thumbnailUrl, youtubeTitle, youtubeDescription, catchphrase } = metadata;

  return (
    <div className="animate-slideUp">
      <div className="glass border border-[var(--color-border)] rounded-2xl p-4 overflow-hidden">
        <h3 className="font-bold text-lg text-[var(--color-foreground)] mb-3">
          🎨 유튜브 썸네일
        </h3>

        {/* Thumbnail Preview - v2.9.41: 반응형 (이미지 원본 비율 유지) */}
        <div className="relative rounded-lg overflow-hidden bg-gray-100 mb-4">
          <img
            src={thumbnailUrl}
            alt="YouTube Thumbnail"
            className="w-full h-auto object-contain"
          />
        </div>

        {/* Catchphrase */}
        <div className="mb-4">
          <p className="text-sm text-[var(--color-foreground-secondary)]">
            <span className="font-semibold">캐치프레이즈:</span> {catchphrase}
          </p>
        </div>

        {/* YouTube Title with Copy */}
        <div className="space-y-3 mb-4">
          <div className="bg-neutral-50 p-3 rounded-lg border border-gray-100">
            <div className="flex justify-between items-center mb-1">
              <span className="text-xs font-semibold text-gray-500">유튜브 제목</span>
              <button
                onClick={() => navigator.clipboard.writeText(youtubeTitle)}
                className="text-xs text-blue-600 hover:underline flex items-center gap-1"
              >
                <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
                </svg>
                복사
              </button>
            </div>
            <p className="text-sm font-medium text-gray-900 break-keep">{youtubeTitle}</p>
          </div>

          {/* YouTube Description with Copy */}
          <div className="bg-neutral-50 p-3 rounded-lg border border-gray-100">
            <div className="flex justify-between items-center mb-1">
              <span className="text-xs font-semibold text-gray-500">설명 (해시태그 포함)</span>
              <button
                onClick={() => navigator.clipboard.writeText(youtubeDescription)}
                className="text-xs text-blue-600 hover:underline flex items-center gap-1"
              >
                <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
                </svg>
                복사
              </button>
            </div>
            <p className="text-sm text-gray-700 whitespace-pre-wrap leading-relaxed">
              {youtubeDescription}
            </p>
          </div>
        </div>

        {/* Download Button */}
        <a
          href={thumbnailUrl}
          download="youtube_thumbnail.png"
          target="_blank"
          rel="noopener noreferrer"
          className="w-full py-3 bg-black text-white text-center rounded-xl font-medium hover:bg-gray-800 transition-colors flex items-center justify-center gap-2"
        >
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
          </svg>
          썸네일 다운로드
        </a>
      </div>
    </div>
  );
}
