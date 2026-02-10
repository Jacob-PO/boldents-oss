'use client';

import { MarkdownText } from './MarkdownText';

interface ChatMessageProps {
  role: 'user' | 'assistant';
  content: string;
  onSelectChoice?: (choice: string) => void;
}

interface ParsedContent {
  text: string;
  choices: { number: string; text: string }[];
}

/**
 * AI 메시지에서 선택지 파싱
 * 예: "1) 옵션1\n2) 옵션2" -> { text: "...", choices: [{number: "1", text: "옵션1"}, ...] }
 */
function parseAiMessage(content: string): ParsedContent {
  const lines = content.split('\n');
  const choices: { number: string; text: string }[] = [];
  const textLines: string[] = [];

  // 선택지 패턴: 숫자) 텍스트 또는 숫자. 텍스트
  const choicePattern = /^(\d+)[).]\s*(.+)$/;

  let inChoiceSection = false;

  for (const line of lines) {
    const trimmedLine = line.trim();
    const match = trimmedLine.match(choicePattern);

    if (match) {
      inChoiceSection = true;
      choices.push({
        number: match[1],
        text: match[2].trim(),
      });
    } else if (trimmedLine === '' && inChoiceSection) {
      // 빈 줄은 선택지 섹션 끝을 의미할 수 있음
      continue;
    } else if (inChoiceSection && choices.length > 0 && !trimmedLine.match(/번호|선택|골라/)) {
      // 선택지 이후의 안내 텍스트는 제외
      if (trimmedLine.includes('번호') || trimmedLine.includes('선택') || trimmedLine.includes('골라')) {
        continue;
      }
      inChoiceSection = false;
      textLines.push(line);
    } else {
      textLines.push(line);
    }
  }

  return {
    text: textLines.join('\n').trim(),
    choices,
  };
}

export function ChatMessage({ role, content, onSelectChoice }: ChatMessageProps) {
  const isUser = role === 'user';

  if (isUser) {
    return (
      <div className="flex justify-end mb-4">
        <div className="max-w-[80%] md:max-w-[75%] px-4 py-3 bg-black text-white rounded-2xl rounded-tr-md">
          <p className="text-sm leading-relaxed whitespace-pre-wrap">{content}</p>
        </div>
      </div>
    );
  }

  // AI 메시지 파싱
  const parsed = parseAiMessage(content);

  return (
    <div className="flex justify-start mb-4">
      <div className="max-w-[90%] md:max-w-[85%]">
        {/* AI 아이콘 */}
        <div className="flex items-start gap-3">
          <div className="flex-shrink-0 w-8 h-8 bg-gray-900 rounded-full flex items-center justify-center">
            <svg className="w-4 h-4 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" />
            </svg>
          </div>

          <div className="flex-1 min-w-0">
            {/* 텍스트 메시지 */}
            {parsed.text && (
              <div className="bg-gray-50 border border-gray-200 rounded-2xl rounded-tl-md px-4 py-3 mb-3">
                <div className="text-sm leading-relaxed text-gray-800">
                  <MarkdownText content={parsed.text} />
                </div>
              </div>
            )}

            {/* 선택지 버튼 */}
            {parsed.choices.length > 0 && (
              <div className="space-y-2">
                <p className="text-xs text-gray-500 mb-2 ml-1">아래에서 선택해주세요</p>
                {parsed.choices.map((choice, index) => (
                  <button
                    key={`${choice.number}-${index}`}
                    onClick={() => onSelectChoice?.(choice.number)}
                    className="w-full text-left px-4 py-3 bg-white border border-gray-200 rounded-xl hover:border-gray-400 hover:bg-gray-50 transition-all group touch-target btn-haptic"
                  >
                    <div className="flex items-center gap-3">
                      <span className="flex-shrink-0 w-7 h-7 bg-gray-100 group-hover:bg-gray-200 rounded-full flex items-center justify-center text-sm font-medium text-gray-600 group-hover:text-black transition-colors">
                        {choice.number}
                      </span>
                      <span className="text-sm text-gray-700 group-hover:text-black transition-colors">
                        <MarkdownText content={choice.text} />
                      </span>
                    </div>
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
