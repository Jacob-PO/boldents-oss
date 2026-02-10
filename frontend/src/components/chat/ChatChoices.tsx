'use client';

import { useState } from 'react';

export interface ChatChoice {
  id: string;
  label: string;
  description?: string;
  icon?: 'check' | 'edit' | 'video' | 'image' | 'skip' | 'clock';
  variant?: 'primary' | 'secondary' | 'outline';
  disabled?: boolean;  // v2.9.75: 개별 선택지 비활성화
}

interface ChatChoicesProps {
  choices: ChatChoice[];
  onSelect: (choice: ChatChoice) => void;
  disabled?: boolean;
  title?: string;
  columns?: 1 | 2 | 3;
}

const icons = {
  check: (
    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
    </svg>
  ),
  edit: (
    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
    </svg>
  ),
  video: (
    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z" />
    </svg>
  ),
  image: (
    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
    </svg>
  ),
  skip: (
    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M13 5l7 7-7 7M5 5l7 7-7 7" />
    </svg>
  ),
  clock: (
    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
    </svg>
  ),
};

export default function ChatChoices({
  choices,
  onSelect,
  disabled = false,
  title,
  columns = 2,
}: ChatChoicesProps) {
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const handleSelect = (choice: ChatChoice) => {
    if (disabled || selectedId || choice.disabled) return;  // v2.9.75: 개별 비활성화 체크
    setSelectedId(choice.id);
    onSelect(choice);
  };

  const getButtonClasses = (choice: ChatChoice, isSelected: boolean) => {
    const base = 'touch-target flex items-center gap-3 p-4 rounded-xl text-left transition-all';

    if (isSelected) {
      return `${base} bg-neutral-900 text-white border-2 border-neutral-900`;
    }

    // v2.9.75: 개별 비활성화 스타일
    if (choice.disabled) {
      return `${base} opacity-40 cursor-not-allowed border border-[var(--color-border)] bg-[var(--color-background-secondary)]`;
    }

    if (selectedId && !isSelected) {
      return `${base} opacity-40 cursor-not-allowed border border-[var(--color-border)] bg-[var(--color-background-secondary)]`;
    }

    switch (choice.variant) {
      case 'primary':
        return `${base} bg-neutral-900 text-white hover:bg-neutral-800 active:scale-[0.98] border-2 border-neutral-900`;
      case 'outline':
        return `${base} border border-[var(--color-border)] text-[var(--color-foreground-secondary)] hover:bg-[var(--color-background-secondary)] hover:text-[var(--color-foreground)] active:scale-[0.98]`;
      case 'secondary':
      default:
        return `${base} border border-[var(--color-border)] bg-[var(--color-background)] text-[var(--color-foreground)] hover:bg-[var(--color-background-secondary)] active:scale-[0.98]`;
    }
  };

  const gridClass = columns === 1
    ? 'grid-cols-1'
    : columns === 3
      ? 'grid-cols-1 sm:grid-cols-3'
      : 'grid-cols-1 sm:grid-cols-2';

  return (
    <div className="animate-slideUp">
      {title && (
        <p className="text-sm text-[var(--color-foreground-muted)] mb-3">{title}</p>
      )}
      <div className={`grid ${gridClass} gap-2`}>
        {choices.map((choice) => {
          const isSelected = selectedId === choice.id;
          return (
            <button
              key={choice.id}
              onClick={() => handleSelect(choice)}
              disabled={disabled || !!selectedId || choice.disabled}
              className={getButtonClasses(choice, isSelected)}
            >
              {choice.icon && (
                <span className={`flex-shrink-0 ${isSelected ? 'text-white' : 'text-[var(--color-foreground-muted)]'}`}>
                  {icons[choice.icon]}
                </span>
              )}
              <div className="flex-1 min-w-0">
                <span className="font-medium text-sm block">{choice.label}</span>
                {choice.description && (
                  <span className={`text-xs mt-0.5 block ${isSelected ? 'text-neutral-300' : 'text-[var(--color-foreground-muted)]'}`}>
                    {choice.description}
                  </span>
                )}
              </div>
              {isSelected && (
                <svg className="w-5 h-5 flex-shrink-0 animate-scaleIn" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                </svg>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}
