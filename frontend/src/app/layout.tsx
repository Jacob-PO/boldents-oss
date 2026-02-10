import type { Metadata, Viewport } from 'next';
import './globals.css';

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  maximumScale: 1,
  // 라이트 모드 고정 (다크모드 제거)
  themeColor: '#ffffff',
};

export const metadata: Metadata = {
  title: {
    default: 'AI Video',
    template: '%s | AI Video',
  },
  description: 'AI 기반 영상 제작 플랫폼. 프롬프트 입력만으로 전문가급 영상을 생성하세요.',
  keywords: ['AI', '영상 제작', 'Video Generation', '자동 영상 생성'],
  authors: [{ name: 'AI Video' }],
  creator: 'AI Video',
  openGraph: {
    type: 'website',
    locale: 'ko_KR',
    siteName: 'AI Video',
    title: 'AI Video',
    description: 'AI 기반 영상 제작 플랫폼',
  },
  twitter: {
    card: 'summary_large_image',
    title: 'AI Video',
    description: 'AI 기반 영상 제작 플랫폼',
  },
  robots: {
    index: true,
    follow: true,
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ko" suppressHydrationWarning>
      <head>
        <link
          rel="preconnect"
          href="https://cdn.jsdelivr.net"
          crossOrigin="anonymous"
        />
      </head>
      <body className="antialiased custom-scrollbar">
        {children}
      </body>
    </html>
  );
}
