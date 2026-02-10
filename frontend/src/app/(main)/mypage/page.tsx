'use client';

import { useState, useEffect, useCallback } from 'react';
import { useRouter } from 'next/navigation';
import { getUser, clearAuth, setUser, User } from '@/lib/auth';
import { api } from '@/lib/api';


export default function MyPage() {
  const router = useRouter();
  const [user, setUserState] = useState<User | null>(() => getUser());

  // API Key 관리 상태
  const [apiKeyInput, setApiKeyInput] = useState('');
  const [showApiKey, setShowApiKey] = useState(false);
  const [apiKeyLoading, setApiKeyLoading] = useState(false);
  const [apiKeyMessage, setApiKeyMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [isEditing, setIsEditing] = useState(false);
  const [maskedKey, setMaskedKey] = useState<string | null>(null);

  // 최신 사용자 정보 로드
  const refreshUser = useCallback(async () => {
    try {
      const freshUser = await api.getCurrentUser();
      setUserState(freshUser);
      setUser(freshUser);
    } catch {
      // 로드 실패 시 localStorage 캐시 사용
    }
  }, []);

  useEffect(() => {
    refreshUser();
  }, [refreshUser]);

  const handleLogout = () => {
    clearAuth();
    router.push('/login');
  };

  const isCustomTier = user?.tier?.toUpperCase() === 'CUSTOM';

  const handleSaveApiKey = async () => {
    if (!apiKeyInput.trim()) return;

    setApiKeyLoading(true);
    setApiKeyMessage(null);

    try {
      const result = await api.saveApiKey(apiKeyInput.trim());
      setApiKeyMessage({ type: 'success', text: 'API 키가 정상적으로 등록되었습니다.' });
      setMaskedKey(result.maskedKey);
      setApiKeyInput('');
      setIsEditing(false);
      await refreshUser();
    } catch (err) {
      const message = err instanceof Error ? err.message : 'API 키 저장에 실패했습니다.';
      setApiKeyMessage({ type: 'error', text: message });
    } finally {
      setApiKeyLoading(false);
    }
  };

  const handleDeleteApiKey = async () => {
    if (!window.confirm('API 키를 삭제하시겠습니까?')) return;

    setApiKeyLoading(true);
    setApiKeyMessage(null);

    try {
      await api.deleteApiKey();
      setApiKeyMessage({ type: 'success', text: 'API 키가 삭제되었습니다.' });
      setMaskedKey(null);
      setIsEditing(false);
      await refreshUser();
    } catch (err) {
      const message = err instanceof Error ? err.message : 'API 키 삭제에 실패했습니다.';
      setApiKeyMessage({ type: 'error', text: message });
    } finally {
      setApiKeyLoading(false);
    }
  };

  const getTierLabel = (tier: string | null) => {
    if (!tier) return '-';
    switch (tier.toUpperCase()) {
      case 'FREE': return 'Free';
      case 'PREMIUM': return 'Premium';
      case 'CUSTOM': return 'Custom';
      default: return tier;
    }
  };

  return (
    <div className="flex-1 overflow-y-auto p-4 md:p-8 pb-24 md:pb-8 bg-[var(--color-background)]">
      <div className="max-w-2xl mx-auto">
        {/* Header */}
        <div className="mb-6 md:mb-8 animate-fadeIn">
          <button
            onClick={() => router.back()}
            className="touch-target flex items-center gap-2 text-[var(--color-foreground-secondary)] hover:text-[var(--color-foreground)] transition-colors mb-4"
          >
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
            </svg>
            뒤로가기
          </button>
          <h1 className="text-2xl font-bold text-[var(--color-foreground)]">마이페이지</h1>
        </div>

        {/* Profile Card - Glassmorphism */}
        <div className="glass border border-[var(--color-border)] rounded-2xl p-5 md:p-6 mb-4 md:mb-6 animate-slideUp">
          <div className="flex items-center gap-4 mb-6">
            <div className="w-14 h-14 md:w-16 md:h-16 bg-neutral-900 text-white rounded-full flex items-center justify-center text-xl md:text-2xl font-bold">
              {user?.name?.charAt(0) || 'U'}
            </div>
            <div>
              <h2 className="text-lg md:text-xl font-semibold text-[var(--color-foreground)]">{user?.name || '사용자'}</h2>
              <p className="text-sm text-[var(--color-foreground-muted)]">{user?.email || '-'}</p>
            </div>
          </div>
        </div>

        {/* Info Card - Glassmorphism */}
        <div className="glass border border-[var(--color-border)] rounded-2xl p-5 md:p-6 mb-4 md:mb-6 animate-slideUp" style={{ animationDelay: '50ms' }}>
          <h3 className="text-base md:text-lg font-semibold text-[var(--color-foreground)] mb-4">계정 정보</h3>
          <div className="space-y-0">
            <div className="flex justify-between py-3 border-b border-[var(--color-border-subtle)]">
              <span className="text-sm text-[var(--color-foreground-muted)]">아이디</span>
              <span className="text-sm text-[var(--color-foreground)] font-medium">{user?.loginId || '-'}</span>
            </div>
            <div className="flex justify-between py-3 border-b border-[var(--color-border-subtle)]">
              <span className="text-sm text-[var(--color-foreground-muted)]">이름</span>
              <span className="text-sm text-[var(--color-foreground)] font-medium">{user?.name || '-'}</span>
            </div>
            <div className="flex justify-between py-3 border-b border-[var(--color-border-subtle)]">
              <span className="text-sm text-[var(--color-foreground-muted)]">이메일</span>
              <span className="text-sm text-[var(--color-foreground)] font-medium">{user?.email || '-'}</span>
            </div>
            <div className="flex justify-between py-3 border-b border-[var(--color-border-subtle)]">
              <span className="text-sm text-[var(--color-foreground-muted)]">연락처</span>
              <span className="text-sm text-[var(--color-foreground)] font-medium">{user?.phone || '-'}</span>
            </div>
            <div className="flex justify-between py-3 border-b border-[var(--color-border-subtle)]">
              <span className="text-sm text-[var(--color-foreground-muted)]">역할</span>
              <span className="text-sm text-[var(--color-foreground)] font-medium">{user?.role || '-'}</span>
            </div>
            <div className="flex justify-between py-3">
              <span className="text-sm text-[var(--color-foreground-muted)]">등급</span>
              <span className="text-sm text-[var(--color-foreground)] font-medium">{getTierLabel(user?.tier ?? null)}</span>
            </div>
          </div>
        </div>

        {/* API Key 관리 카드 - CUSTOM 티어만 표시 */}
        {isCustomTier && (
          <div className="glass border border-[var(--color-border)] rounded-2xl p-5 md:p-6 mb-4 md:mb-6 animate-slideUp" style={{ animationDelay: '75ms' }}>
            <h3 className="text-base md:text-lg font-semibold text-[var(--color-foreground)] mb-2">Google API Key</h3>
            <p className="text-xs text-[var(--color-foreground-muted)] mb-4">
              Custom 티어 사용자는 자신의 Google API Key를 등록하여 사용할 수 있습니다.
            </p>

            {/* 메시지 표시 */}
            {apiKeyMessage && (
              <div className={`mb-4 p-3 rounded-lg text-sm ${
                apiKeyMessage.type === 'success'
                  ? 'bg-green-50 text-green-700 border border-green-200'
                  : 'bg-red-50 text-red-700 border border-red-200'
              }`}>
                {apiKeyMessage.text}
              </div>
            )}

            {/* 키가 등록된 상태 & 편집 모드가 아닌 경우 */}
            {user?.hasGoogleApiKey && !isEditing ? (
              <div>
                <div className="flex items-center justify-between py-3 mb-3">
                  <span className="text-sm text-[var(--color-foreground-muted)]">등록된 키</span>
                  <span className="text-sm text-[var(--color-foreground)] font-mono">
                    {maskedKey || 'AIza****'}
                  </span>
                </div>
                <div className="flex gap-2">
                  <button
                    onClick={() => { setIsEditing(true); setApiKeyMessage(null); }}
                    className="flex-1 py-2.5 bg-[var(--color-background-secondary)] text-[var(--color-foreground)] border border-[var(--color-border)] rounded-xl text-sm font-medium hover:bg-[var(--color-background-tertiary)] active:scale-[0.99] transition-all"
                  >
                    수정
                  </button>
                  <button
                    onClick={handleDeleteApiKey}
                    disabled={apiKeyLoading}
                    className="flex-1 py-2.5 bg-red-50 text-red-600 border border-red-200 rounded-xl text-sm font-medium hover:bg-red-100 active:scale-[0.99] transition-all disabled:opacity-50"
                  >
                    {apiKeyLoading ? '처리 중...' : '삭제'}
                  </button>
                </div>
              </div>
            ) : (
              /* 키가 없거나 편집 모드인 경우 */
              <div>
                <div className="relative mb-3">
                  <input
                    type={showApiKey ? 'text' : 'password'}
                    value={apiKeyInput}
                    onChange={(e) => setApiKeyInput(e.target.value)}
                    placeholder="AIza로 시작하는 API 키를 입력하세요"
                    className="w-full px-4 py-3 pr-12 bg-[var(--color-background)] border border-[var(--color-border)] rounded-xl text-sm text-[var(--color-foreground)] placeholder-[var(--color-foreground-muted)] focus:outline-none focus:border-neutral-400 transition-colors font-mono"
                    disabled={apiKeyLoading}
                  />
                  <button
                    type="button"
                    onClick={() => setShowApiKey(!showApiKey)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-[var(--color-foreground-muted)] hover:text-[var(--color-foreground)] transition-colors"
                  >
                    {showApiKey ? (
                      <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21" />
                      </svg>
                    ) : (
                      <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                      </svg>
                    )}
                  </button>
                </div>
                <div className="flex gap-2">
                  <button
                    onClick={handleSaveApiKey}
                    disabled={apiKeyLoading || !apiKeyInput.trim()}
                    className="flex-1 py-2.5 bg-neutral-900 text-white rounded-xl text-sm font-medium hover:bg-neutral-800 active:scale-[0.99] transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {apiKeyLoading ? '검증 중...' : (isEditing ? '수정' : '등록')}
                  </button>
                  {isEditing && (
                    <button
                      onClick={() => { setIsEditing(false); setApiKeyInput(''); setApiKeyMessage(null); }}
                      className="py-2.5 px-4 bg-[var(--color-background-secondary)] text-[var(--color-foreground-secondary)] border border-[var(--color-border)] rounded-xl text-sm font-medium hover:bg-[var(--color-background-tertiary)] active:scale-[0.99] transition-all"
                    >
                      취소
                    </button>
                  )}
                </div>
              </div>
            )}
          </div>
        )}

        {/* Actions */}
        <div className="space-y-3 animate-slideUp" style={{ animationDelay: '100ms' }}>
          <button
            onClick={handleLogout}
            className="w-full touch-target py-3.5 bg-[var(--color-background-secondary)] text-[var(--color-foreground-secondary)] border border-[var(--color-border)] rounded-xl font-medium hover:bg-[var(--color-background-tertiary)] hover:text-[var(--color-foreground)] active:scale-[0.99] transition-all"
          >
            로그아웃
          </button>
        </div>
      </div>
    </div>
  );
}
