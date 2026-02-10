'use client';

import { useState, useEffect, useRef } from 'react';
import { useRouter } from 'next/navigation';
import Image from 'next/image';
import { api, LinkedCreatorInfo } from '@/lib/api';


// v2.9.103: 하드코딩 제거 - 모든 값은 DB에서 동적으로 로드
const DEFAULT_PLACEHOLDER = '콘텐츠 아이디어를 입력해주세요';

export default function HomePage() {
  const router = useRouter();
  const [prompt, setPrompt] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  // v2.9.150: 연결된 크리에이터 정보 (계정-크리에이터 1:1 매핑)
  const [linkedCreator, setLinkedCreator] = useState<LinkedCreatorInfo | null>(null);
  const [creatorLoading, setCreatorLoading] = useState(true);
  const [noLinkedCreator, setNoLinkedCreator] = useState(false);

  // v2.9.162: 참조 이미지 업로드 상태 (최대 1장, ULTRA 티어 크리에이터 전용)
  const [selectedImages, setSelectedImages] = useState<File[]>([]);
  const [imagePreviews, setImagePreviews] = useState<string[]>([]);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const MAX_IMAGES = 1;

  // v2.9.150: 연결된 크리에이터의 플레이스홀더 (DB 기반)
  const currentPlaceholder = linkedCreator?.placeholderText || DEFAULT_PLACEHOLDER;

  // v2.9.150: 이미지 업로드 가능 여부 (ULTRA 티어만 허용)
  const canUploadImage = linkedCreator?.tierCode === 'ULTRA';

  useEffect(() => {
    const initialize = async () => {
      // v2.9.150: 연결된 크리에이터 정보 로드 (계정-크리에이터 1:1 매핑)
      try {
        const creator = await api.getMyLinkedCreator();
        if (creator) {
          setLinkedCreator(creator);
          setNoLinkedCreator(false);
        } else {
          setNoLinkedCreator(true);
        }
      } catch (error) {
        console.error('Failed to load linked creator:', error);
        setNoLinkedCreator(true);
      } finally {
        setCreatorLoading(false);
      }
    };

    initialize();
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!prompt.trim() || isLoading || !linkedCreator) return;

    setIsLoading(true);
    try {
      // v2.9.150: 연결된 크리에이터 ID 전달, ULTRA 티어만 이미지 업로드 (최대 5장)
      const creatorId = linkedCreator!.creatorId;
      const response = selectedImages.length > 0 && canUploadImage
        ? await api.startChatWithImages(prompt.trim(), selectedImages, creatorId)
        : await api.startChat(prompt.trim(), creatorId);
      router.push(`/chat/${response.chatId}`);
    } catch (error) {
      setIsLoading(false);
      const errorMessage = error instanceof Error ? error.message : '';

      // v2.9.30: 진행 중인 콘텐츠 생성 에러 처리
      const chatIdMatch = errorMessage.match(/채팅 #(\d+)/);
      if (chatIdMatch && (errorMessage.includes('다른 영상이 생성 중') || errorMessage.includes('CV005'))) {
        const inProgressChatId = chatIdMatch[1];
        const shouldNavigate = window.confirm(
          `이미 영상을 만들고 있어요!\n\n진행 중인 영상으로 이동할까요?`
        );
        if (shouldNavigate) {
          router.push(`/chat/${inProgressChatId}`);
        }
        return;
      }

      // v2.9.95: 고객 친화적 에러 메시지 (api.ts에서 이미 변환됨)
      alert(errorMessage || '문제가 생겼어요. 다시 시도해주세요.');
    }
  };


  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit(e);
    }
  };

  // v2.9.86: 참조 이미지 선택 핸들러 (최대 5장)
  const handleImageSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files || files.length === 0) return;

    const validTypes = ['image/jpeg', 'image/png', 'image/webp', 'image/gif'];
    const newFiles: File[] = [];

    // 현재 이미지 수 + 새로 추가할 이미지 수 체크
    const remainingSlots = MAX_IMAGES - selectedImages.length;
    if (remainingSlots <= 0) {
      alert(`이미지는 ${MAX_IMAGES}장까지만 올릴 수 있어요.`);
      return;
    }

    const filesToProcess = Array.from(files).slice(0, remainingSlots);

    for (const file of filesToProcess) {
      // 이미지 타입 검증
      if (!validTypes.includes(file.type)) {
        alert(`${file.name}은(는) 지원하지 않는 형식이에요.\nJPEG, PNG, WebP, GIF만 사용해주세요.`);
        continue;
      }

      // 파일 크기 검증 (10MB)
      if (file.size > 10 * 1024 * 1024) {
        alert(`${file.name}이(가) 너무 커요.\n10MB 이하 이미지를 사용해주세요.`);
        continue;
      }

      newFiles.push(file);
    }

    if (newFiles.length === 0) return;

    // 미리보기 생성
    Promise.all(
      newFiles.map(
        (file) =>
          new Promise<string>((resolve) => {
            const reader = new FileReader();
            reader.onloadend = () => resolve(reader.result as string);
            reader.readAsDataURL(file);
          })
      )
    ).then((previews) => {
      setSelectedImages((prev) => [...prev, ...newFiles]);
      setImagePreviews((prev) => [...prev, ...previews]);
    });

    // 파일 입력 초기화 (같은 파일 다시 선택 가능하도록)
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

  // v2.9.86: 특정 이미지 제거 핸들러
  const handleRemoveImage = (index: number) => {
    setSelectedImages((prev) => prev.filter((_, i) => i !== index));
    setImagePreviews((prev) => prev.filter((_, i) => i !== index));
  };

  // v2.9.86: 모든 이미지 제거 핸들러
  const handleRemoveAllImages = () => {
    setSelectedImages([]);
    setImagePreviews([]);
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

  return (
    <div className="flex-1 flex flex-col items-center justify-center px-4 py-8 md:px-8 safe-area-bottom bg-[var(--color-background)] overflow-y-auto">
      {/* Logo & Title */}
      <div className="mb-6 md:mb-10 text-center animate-fadeIn">
        <Image
          src="/images/logo.png"
          alt="AI Video"
          width={64}
          height={64}
          className="w-16 h-16 mx-auto mb-3"
          priority
        />
        <p className="text-[var(--color-foreground-muted)] text-sm md:text-base">
          영상 콘텐츠 자동 생성
        </p>
      </div>

      {/* v2.9.150: 연결된 크리에이터 정보 표시 (선택 UI 제거 - 1:1 매핑) */}
      <div className="w-full max-w-xl mb-6 animate-slideUp">
        {creatorLoading ? (
          <div className="flex justify-center">
            <div className="h-14 w-48 bg-neutral-200 rounded-xl animate-pulse" />
          </div>
        ) : noLinkedCreator ? (
          <div className="text-center p-4 bg-yellow-50 rounded-xl border border-yellow-200">
            <p className="text-sm text-yellow-800">
              연결된 크리에이터가 없습니다.
            </p>
            <p className="text-xs text-yellow-600 mt-1">
              관리자에게 문의하세요.
            </p>
          </div>
        ) : linkedCreator && (
          <div className="text-center p-4 bg-[var(--color-background-secondary)] rounded-xl border border-[var(--color-border)]">
            <p className="text-lg font-semibold text-[var(--color-foreground)]">
              {linkedCreator.creatorName}
            </p>
            {linkedCreator.description && (
              <p className="text-sm text-[var(--color-foreground-muted)] mt-1">
                {linkedCreator.description}
              </p>
            )}
          </div>
        )}
      </div>

      {/* Chat Input - Glassmorphism Card */}
      <form onSubmit={handleSubmit} className="w-full max-w-xl animate-slideUp" style={{ animationDelay: '50ms' }}>
        {/* v2.9.135: ULTRA 티어 이미지 미리보기 (최대 5장) */}
        {canUploadImage && imagePreviews.length > 0 && (
          <div className="relative mb-4">
            <div className="flex flex-wrap justify-center gap-2">
              {imagePreviews.map((preview, index) => (
                <div key={index} className="relative">
                  <Image
                    src={preview}
                    alt={`상품 이미지 ${index + 1}`}
                    width={120}
                    height={90}
                    className="h-24 w-auto rounded-lg object-contain border border-[var(--color-border)]"
                  />
                  <button
                    type="button"
                    onClick={() => handleRemoveImage(index)}
                    className="absolute -top-1.5 -right-1.5 w-5 h-5 flex items-center justify-center bg-neutral-900 text-white rounded-full hover:bg-neutral-700 transition-all"
                    aria-label={`이미지 ${index + 1} 제거`}
                  >
                    <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                      <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                    </svg>
                  </button>
                </div>
              ))}
            </div>
            {imagePreviews.length > 1 && (
              <button
                type="button"
                onClick={handleRemoveAllImages}
                className="mt-2 mx-auto block text-xs text-[var(--color-foreground-muted)] hover:text-[var(--color-foreground)] underline"
              >
                전체 삭제
              </button>
            )}
          </div>
        )}

        <div className="relative glass rounded-2xl p-1">
          <textarea
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={currentPlaceholder}
            rows={4}
            disabled={isLoading || !linkedCreator}
            className="w-full px-5 py-4 pr-28 text-base bg-[var(--color-background)] text-[var(--color-foreground)] border-0 rounded-xl resize-none focus:outline-none focus:ring-2 focus:ring-neutral-300 placeholder:text-[var(--color-foreground-muted)] disabled:opacity-50 disabled:cursor-not-allowed transition-all"
          />

          {/* v2.9.135: ULTRA 티어만 이미지 업로드 버튼 표시 (최대 5장) */}
          {canUploadImage && (
            <>
              <input
                ref={fileInputRef}
                type="file"
                accept="image/jpeg,image/png,image/webp,image/gif"
                multiple
                onChange={handleImageSelect}
                className="hidden"
              />
              <button
                type="button"
                onClick={() => fileInputRef.current?.click()}
                disabled={isLoading || selectedImages.length >= MAX_IMAGES}
                className={`
                  absolute right-16 bottom-4 touch-target w-11 h-11 flex items-center justify-center rounded-xl
                  disabled:opacity-50 disabled:cursor-not-allowed active:scale-95 transition-all
                  ${selectedImages.length > 0
                    ? 'bg-green-100 text-green-600 hover:bg-green-200'
                    : 'bg-neutral-100 text-neutral-600 hover:bg-neutral-200'
                  }
                `}
                aria-label="상품 이미지 업로드"
                title={`상품 이미지 업로드 (${selectedImages.length}/${MAX_IMAGES})`}
              >
                {selectedImages.length > 0 ? (
                  <span className="text-sm font-medium">{selectedImages.length}</span>
                ) : (
                  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
                  </svg>
                )}
              </button>
            </>
          )}

          <button
            type="submit"
            disabled={!prompt.trim() || isLoading || !linkedCreator}
            className={`
              absolute right-4 bottom-4 touch-target w-11 h-11 flex items-center justify-center rounded-xl
              disabled:bg-neutral-300 disabled:cursor-not-allowed active:scale-95 transition-all
              ${canUploadImage ? '' : 'right-4'}
              bg-neutral-900 text-white hover:bg-neutral-800
            `}
            aria-label="전송"
          >
            {isLoading ? (
              <div className="w-5 h-5 border-2 border-white border-t-transparent rounded-full animate-spin" />
            ) : (
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M5 10l7-7m0 0l7 7m-7-7v18" />
              </svg>
            )}
          </button>
        </div>

        {/* v2.9.135: ULTRA 티어 안내 문구 */}
        {canUploadImage && (
          <p className="mt-2 text-xs text-center text-[var(--color-foreground-muted)]">
            상품 이미지를 업로드하면 해당 상품을 리뷰하는 영상이 생성됩니다 (최대 {MAX_IMAGES}장)
          </p>
        )}
      </form>

    </div>
  );
}
