'use client';

import { MainLayout } from '@/components/MainLayout';

export default function MainAppLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return <MainLayout>{children}</MainLayout>;
}
