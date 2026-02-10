'use client';

import { useState, useEffect } from 'react';
import { api, GenreItem } from '@/lib/api';

interface GenreSelectorProps {
  onSelect: (creatorId: number, genre: GenreItem) => void;  // v2.9.134: genreId → creatorId
  disabled?: boolean;
  selectedCreatorId?: number | null;  // v2.9.134: selectedCreatorId → selectedCreatorId
}

export default function GenreSelector({
  onSelect,
  disabled = false,
  selectedCreatorId = null,
}: GenreSelectorProps) {
  const [genres, setGenres] = useState<GenreItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    loadGenres();
  }, []);

  const loadGenres = async () => {
    try {
      setIsLoading(true);
      setError(null);

      const response = await api.getGenres();
      if (response.genres && response.genres.length > 0) {
        setGenres(response.genres);
      } else {
        setError('사용 가능한 장르가 없습니다.');
      }
    } catch (err) {
      console.error('Failed to load genres:', err);
      setError('장르를 불러오는데 실패했습니다.');
    } finally {
      setIsLoading(false);
    }
  };

  const handleSelect = (genre: GenreItem) => {
    if (disabled) return;
    onSelect(genre.creatorId, genre);  // v2.9.134: genreId → creatorId
  };

  if (isLoading) {
    return (
      <div className="glass border border-[var(--color-border)] rounded-2xl p-6 animate-fadeIn">
        <div className="flex flex-col items-center justify-center gap-3">
          <div className="w-5 h-5 border-2 border-neutral-300 border-t-neutral-900 rounded-full animate-spin" />
          <span className="text-sm text-[var(--color-foreground-muted)]">
            콘텐츠 장르 불러오는 중...
          </span>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="glass border border-[var(--color-border)] rounded-2xl p-6 animate-fadeIn">
        <div className="flex items-start gap-2">
          <svg className="w-4 h-4 flex-shrink-0 mt-0.5 text-[var(--color-gray-700)]" viewBox="0 0 20 20" fill="currentColor">
            <path fillRule="evenodd" d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" clipRule="evenodd" />
          </svg>
          <p className="text-sm text-[var(--color-foreground)]">{error}</p>
        </div>
        <button
          onClick={loadGenres}
          className="mt-3 text-sm text-[var(--color-foreground-secondary)] underline hover:text-[var(--color-foreground)]"
        >
          다시 시도
        </button>
      </div>
    );
  }

  return (
    <div className="glass border border-[var(--color-border)] rounded-2xl p-5 animate-slideUp">
      {/* 헤더 */}
      <div className="mb-4">
        <h3 className="font-bold text-base text-[var(--color-foreground)]">
          어떤 콘텐츠를 만들까요?
        </h3>
        <p className="text-xs text-[var(--color-foreground-muted)] mt-1">
          장르를 선택하면 해당 스타일에 맞는 영상이 제작됩니다
        </p>
      </div>

      {/* 장르 카드 목록 */}
      <div className="space-y-3">
        {genres.map(genre => {
          const isSelected = selectedCreatorId === genre.creatorId;

          return (
            <button
              key={genre.creatorId}
              onClick={() => handleSelect(genre)}
              disabled={disabled}
              className={`
                w-full p-4 rounded-xl text-left transition-all
                ${isSelected
                  ? 'bg-neutral-900 text-white ring-2 ring-neutral-900 ring-offset-2'
                  : 'bg-neutral-50 hover:bg-neutral-100 active:scale-[0.99]'
                }
                ${disabled ? 'opacity-50 cursor-not-allowed' : ''}
              `}
            >
              <div className="flex items-start gap-3">
                {/* 장르 아이콘 */}
                <div className={`
                  w-10 h-10 rounded-lg flex items-center justify-center shrink-0
                  ${isSelected ? 'bg-white/20' : 'bg-neutral-200'}
                `}>
                  <svg className={`w-5 h-5 ${isSelected ? 'text-white' : 'text-neutral-600'}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z" />
                  </svg>
                </div>

                {/* 장르 정보 */}
                {/* v2.9.126: description, targetAudience 삭제 */}
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className={`font-semibold ${isSelected ? 'text-white' : 'text-neutral-900'}`}>
                      {genre.creatorName}
                    </span>
                    {isSelected && (
                      <svg className="w-4 h-4 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                        <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                      </svg>
                    )}
                  </div>
                </div>
              </div>
            </button>
          );
        })}
      </div>

      {/* 선택 완료 표시 */}
      {selectedCreatorId && (
        <div className="mt-4 flex items-center justify-center gap-2 text-sm text-green-600">
          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
          </svg>
          <span>장르 선택 완료</span>
        </div>
      )}
    </div>
  );
}
