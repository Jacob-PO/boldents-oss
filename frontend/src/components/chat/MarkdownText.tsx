'use client';

import React from 'react';

interface MarkdownTextProps {
  content: string;
  className?: string;
}

/**
 * 간단한 마크다운 텍스트 렌더링 컴포넌트
 * 코드블록 없이 자연스러운 메시지처럼 보이게 함
 */
export function MarkdownText({ content, className = '' }: MarkdownTextProps) {
  // 코드 블록 (```) 제거 - 내용만 표시
  let cleanContent = content.replace(/```[\s\S]*?```/g, (match) => {
    // 코드 블록 내용만 추출 (``` 제거)
    return match.slice(3, -3).trim();
  });

  // 인라인 코드 (`) 제거 - 내용만 표시
  cleanContent = cleanContent.replace(/`([^`]+)`/g, '$1');

  // 라인별로 처리
  const lines = cleanContent.split('\n');

  const renderLine = (line: string, lineIndex: number) => {
    const parts: React.ReactNode[] = [];
    let remaining = line;
    let keyIndex = 0;

    while (remaining.length > 0) {
      // **bold** 패턴 찾기
      const boldMatch = remaining.match(/\*\*(.+?)\*\*/);

      if (boldMatch && boldMatch.index !== undefined) {
        // 볼드 전 텍스트
        if (boldMatch.index > 0) {
          parts.push(remaining.slice(0, boldMatch.index));
        }
        // 볼드 텍스트
        parts.push(
          <strong key={`bold-${lineIndex}-${keyIndex++}`} className="font-semibold">
            {boldMatch[1]}
          </strong>
        );
        remaining = remaining.slice(boldMatch.index + boldMatch[0].length);
      } else {
        // 매치 없으면 나머지 텍스트 추가
        parts.push(remaining);
        break;
      }
    }

    return (
      <span key={`line-${lineIndex}`}>
        {parts}
        {lineIndex < lines.length - 1 && <br />}
      </span>
    );
  };

  return (
    <span className={className}>
      {lines.map((line, index) => renderLine(line, index))}
    </span>
  );
}
