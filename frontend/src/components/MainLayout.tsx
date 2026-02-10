'use client';

import { useEffect, useState } from 'react';
import { useRouter, usePathname } from 'next/navigation';
import { isAuthenticated } from '@/lib/auth';
import { Sidebar } from './Sidebar';

interface MainLayoutProps {
  children: React.ReactNode;
}

export function MainLayout({ children }: MainLayoutProps) {
  const router = useRouter();
  const pathname = usePathname();
  const [isReady, setIsReady] = useState(false);
  const [isSidebarOpen, setIsSidebarOpen] = useState(false);

  useEffect(() => {
    if (!isAuthenticated()) {
      router.push('/login');
    } else {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setIsReady(true);
    }
  }, [router]);

  // Close sidebar on route change (mobile)
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setIsSidebarOpen(false);
  }, [pathname]);

  if (!isReady) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-white">
        <div className="flex flex-col items-center gap-3">
          <div className="w-8 h-8 border-2 border-black border-t-transparent rounded-full animate-spin" />
          <span className="text-sm text-gray-500">로딩 중...</span>
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-dvh bg-white overflow-hidden relative">
      {/* Desktop Sidebar - Fixed position */}
      <div className="hidden md:flex md:flex-shrink-0 h-full overflow-hidden">
        <Sidebar />
      </div>

      {/* Mobile Sidebar Overlay */}
      {isSidebarOpen && (
        <div
          className="fixed inset-0 z-40 bg-black/50 md:hidden"
          onClick={() => setIsSidebarOpen(false)}
        />
      )}

      {/* Mobile Sidebar Drawer */}
      <div
        className={`fixed inset-y-0 left-0 z-50 h-full transform transition-transform duration-300 ease-out md:hidden ${
          isSidebarOpen ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
        <Sidebar onClose={() => setIsSidebarOpen(false)} />
      </div>

      {/* Main Content - 모바일에서 하단 탭바 높이만큼 여백 */}
      <main className="flex-1 flex flex-col min-h-0 has-bottom-nav md:pb-0">
        {children}
      </main>

      {/* Mobile Bottom Navigation - v2.9.12: 사이드바 열릴 때 숨김 */}
      <nav className={`bottom-nav flex items-center justify-around md:hidden safe-area-bottom ${isSidebarOpen ? 'hidden' : ''}`}>
        <button
          onClick={() => router.push('/')}
          className={`relative flex flex-col items-center justify-center gap-1 py-2 px-4 touch-target press-scale transition-colors duration-200 ${
            pathname === '/' ? 'text-black nav-item-active' : 'text-gray-400'
          }`}
        >
          <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={pathname === '/' ? 2.5 : 1.5}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6" />
          </svg>
          <span className="text-[10px] font-medium">홈</span>
        </button>

        <button
          onClick={() => setIsSidebarOpen(true)}
          className={`relative flex flex-col items-center justify-center gap-1 py-2 px-4 touch-target press-scale transition-colors duration-200 ${
            isSidebarOpen ? 'text-black nav-item-active' : 'text-gray-400'
          }`}
        >
          <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
          </svg>
          <span className="text-[10px] font-medium">대화</span>
        </button>

        <button
          onClick={() => router.push('/mypage')}
          className={`relative flex flex-col items-center justify-center gap-1 py-2 px-4 touch-target press-scale transition-colors duration-200 ${
            pathname === '/mypage' ? 'text-black nav-item-active' : 'text-gray-400'
          }`}
        >
          <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={pathname === '/mypage' ? 2.5 : 1.5}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
          </svg>
          <span className="text-[10px] font-medium">마이</span>
        </button>
      </nav>
    </div>
  );
}
