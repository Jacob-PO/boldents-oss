'use client';

import { useEffect, useState, useCallback } from 'react';
import { useRouter, usePathname } from 'next/navigation';
import Image from 'next/image';
import { api, type ChatSummary, type ChatStage } from '@/lib/api';
import { getUser, clearAuth } from '@/lib/auth';

// Stage display config (B&W design) - v2.9.5 모든 상태 포함
const STAGE_CONFIG: Record<ChatStage, { label: string }> = {
  'CHATTING': { label: '대화중' },
  'SCENARIO_READY': { label: '시나리오 준비' },
  'SCENARIO_GENERATING': { label: '시나리오 생성중' },  // v2.9.75 추가
  'SCENARIO_DONE': { label: '시나리오 완료' },
  'PREVIEWS_GENERATING': { label: '프리뷰 생성중' },
  'PREVIEWS_DONE': { label: '프리뷰 완료' },
  'SCENES_GENERATING': { label: '씬 생성중' },
  'SCENES_REVIEW': { label: '씬 검토' },
  'SCENE_REGENERATING': { label: '씬 재생성중' },  // v2.9.5 추가
  'TTS_GENERATING': { label: 'TTS 생성중' },
  'TTS_DONE': { label: 'TTS 완료' },
  'TTS_PARTIAL_FAILED': { label: 'TTS 일부 실패' },
  'IMAGES_GENERATING': { label: '이미지 생성중' },
  'IMAGES_DONE': { label: '이미지 완료' },
  'AUDIO_GENERATING': { label: '오디오 생성중' },
  'AUDIO_DONE': { label: '오디오 완료' },
  'VIDEO_GENERATING': { label: '영상 생성중' },
  'VIDEO_DONE': { label: '완료' },
  'VIDEO_FAILED': { label: '영상 실패' },  // v2.9.5 추가
};


interface SidebarProps {
  onClose?: () => void;
}

export function Sidebar({ onClose }: SidebarProps) {
  const router = useRouter();
  const pathname = usePathname();
  const [chats, setChats] = useState<ChatSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [user, setUser] = useState<{ name: string } | null>(null);

  const loadChats = useCallback(async () => {
    try {
      const data = await api.getChatList();
      setChats(data || []);
    } catch {
      setChats([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const userData = getUser();
    if (userData) {
      setUser({ name: userData.name });
    }
    loadChats();
  }, [loadChats]);

  const handleLogout = () => {
    clearAuth();
    router.push('/login');
  };

  const handleNewChat = () => {
    router.push('/');
    onClose?.();
  };

  const handleSelectChat = (chat: ChatSummary) => {
    router.push(`/chat/${chat.chatId}`);
    onClose?.();
  };

  const handleDeleteChat = async (e: React.MouseEvent, chatId: number) => {
    e.stopPropagation();
    if (confirm('이 대화를 삭제할까요?\n삭제된 내용은 복구할 수 없어요.')) {
      try {
        await api.deleteChat(chatId);
        loadChats();
      } catch (error) {
        console.error('Failed to delete chat:', error);
        alert('삭제가 잘 안 됐어요. 다시 시도해주세요.');
      }
    }
  };

  const handleMyPage = () => {
    router.push('/mypage');
    onClose?.();
  };

  const isActiveChat = (chatId: number) => {
    return pathname === `/chat/${chatId}`;
  };

  const getStageLabel = (stage: ChatStage) => {
    return STAGE_CONFIG[stage]?.label || stage;
  };

  return (
    <aside className="w-72 md:w-64 h-full max-h-screen flex flex-col border-r border-gray-200 bg-white overflow-hidden">
      {/* Header */}
      <div className="flex-shrink-0 p-4 border-b border-gray-200">
        <div className="flex items-center justify-between mb-4">
          <Image src="/images/logo.png" alt="AI" width={32} height={32} className="w-8 h-8" />
          {onClose && (
            <button
              onClick={onClose}
              className="p-2 -mr-2 text-gray-500 hover:text-black transition-colors touch-target btn-haptic md:hidden"
              aria-label="닫기"
            >
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          )}
        </div>
        <button
          onClick={handleNewChat}
          className="w-full flex items-center justify-center gap-2 px-4 py-3 bg-black text-white rounded-xl font-medium hover:bg-gray-800 transition-colors touch-target btn-haptic"
        >
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
          </svg>
          새 대화
        </button>
      </div>

      {/* Chat List */}
      <div className="flex-1 min-h-0 overflow-y-auto p-2 momentum-scroll custom-scrollbar">
        <div className="text-xs font-medium text-gray-400 px-3 py-2 uppercase tracking-wider">
          대화 목록
        </div>
        {loading ? (
          <div className="space-y-2 px-2">
            {[1, 2, 3].map((i) => (
              <div key={i} className="h-16 bg-gray-100 rounded-xl animate-pulse" />
            ))}
          </div>
        ) : chats.length === 0 ? (
          <div className="text-center py-12">
            <div className="w-12 h-12 mx-auto mb-3 rounded-full bg-gray-100 flex items-center justify-center">
              <svg className="w-6 h-6 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
              </svg>
            </div>
            <p className="text-sm text-gray-400">대화 내역이 없습니다</p>
            <p className="text-xs text-gray-300 mt-1">새 대화를 시작해보세요</p>
          </div>
        ) : (
          <div className="space-y-1">
            {chats.map((chat) => {
              const isSelected = isActiveChat(chat.chatId);

              return (
                <div
                  key={chat.chatId}
                  onClick={() => handleSelectChat(chat)}
                  className={`group w-full text-left px-3 py-3 rounded-xl transition-all cursor-pointer touch-target btn-haptic ${
                    isSelected
                      ? 'bg-black text-white'
                      : 'hover:bg-gray-100 text-gray-700'
                  }`}
                >
                  <div className="flex items-start justify-between gap-2">
                    <div className="flex-1 min-w-0">
                      <div className="text-sm font-medium truncate leading-snug">
                        {chat.initialPrompt.slice(0, 30)}
                        {chat.initialPrompt.length > 30 && '...'}
                      </div>
                      <div className="flex items-center gap-2 mt-1.5">
                        <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${
                          isSelected
                            ? 'bg-white/20 text-white/80'
                            : 'bg-gray-100 text-gray-500'
                        }`}>
                          {getStageLabel(chat.stage)}
                        </span>
                        <span className={`text-[10px] ${isSelected ? 'text-white/60' : 'text-gray-400'}`}>
                          {chat.messageCount}개
                        </span>
                      </div>
                    </div>
                    <button
                      onClick={(e) => handleDeleteChat(e, chat.chatId)}
                      className={`opacity-50 group-hover:opacity-100 p-1.5 rounded-lg transition-all touch-target ${
                        isSelected
                          ? 'hover:bg-white/10 text-white/80'
                          : 'hover:bg-gray-200 text-gray-500'
                      }`}
                      title="대화 삭제"
                      aria-label="대화 삭제"
                    >
                      <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                        <path strokeLinecap="round" strokeLinejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                      </svg>
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* User Info & Actions */}
      <div className="flex-shrink-0 p-3 border-t border-gray-200 space-y-2 safe-area-bottom">
        <button
          onClick={handleMyPage}
          className="w-full flex items-center gap-3 px-3 py-3 rounded-xl hover:bg-gray-100 transition-colors touch-target btn-haptic"
        >
          <div className="w-10 h-10 bg-black text-white rounded-full flex items-center justify-center text-sm font-semibold">
            {user?.name?.charAt(0) || 'U'}
          </div>
          <div className="flex-1 text-left">
            <div className="text-sm font-semibold text-black">
              {user?.name || '사용자'}
            </div>
          </div>
          <svg className="w-4 h-4 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
          </svg>
        </button>

        <button
          onClick={handleLogout}
          className="w-full flex items-center justify-center gap-2 px-3 py-2.5 text-sm text-gray-500 hover:text-black hover:bg-gray-100 rounded-xl transition-colors touch-target btn-haptic"
        >
          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
          </svg>
          로그아웃
        </button>
      </div>
    </aside>
  );
}
