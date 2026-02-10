package com.aivideo.api.controller;

import com.aivideo.api.dto.ChatDto;
import com.aivideo.api.security.UserPrincipal;
import com.aivideo.api.service.ChatService;
import com.aivideo.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat")
@Tag(name = "Chat", description = "채팅 API")
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/start")
    @Operation(summary = "채팅 시작", description = "새로운 채팅을 시작합니다.")
    public ApiResponse<ChatDto.ChatResponse> startChat(
            @AuthenticationPrincipal UserPrincipal user,
            @RequestBody(required = false) ChatDto.StartRequest request) {
        log.info("[Chat] Start - userNo: {}", user.getUserNo());
        ChatDto.ChatResponse response = chatService.startChat(user.getUserNo(), request);
        return ApiResponse.success("채팅이 시작되었습니다.", response);
    }

    /**
     * v2.9.86: 참조 이미지와 함께 채팅 시작 (최대 5장 지원)
     * 이미지를 S3에 업로드하고 Gemini로 분석하여 콘텐츠 생성에 활용
     */
    @PostMapping(value = "/start-with-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "참조 이미지와 함께 채팅 시작",
            description = "v2.9.86: 참조 이미지를 최대 5장까지 업로드하여 새로운 채팅을 시작합니다. 이미지는 S3에 저장되고 AI 분석을 통해 시나리오/이미지/영상 생성에 활용됩니다.")
    public ApiResponse<ChatDto.ChatResponse> startChatWithImage(
            @AuthenticationPrincipal UserPrincipal user,
            @RequestPart(value = "prompt", required = false) String prompt,
            @RequestPart(value = "images", required = false) List<MultipartFile> images,
            @RequestPart(value = "creatorId", required = false) String creatorIdStr) {

        // v2.9.86: 이미지 개수 로깅
        int imageCount = (images != null) ? (int) images.stream().filter(img -> img != null && !img.isEmpty()).count() : 0;
        log.info("[Chat] v2.9.86 Start with images - userNo: {}, imageCount: {}, promptLength: {}",
                user.getUserNo(),
                imageCount,
                prompt != null ? prompt.length() : 0);

        // creatorId 파싱 (문자열로 받아서 Long으로 변환)
        Long creatorId = null;
        if (creatorIdStr != null && !creatorIdStr.isEmpty()) {
            try {
                creatorId = Long.parseLong(creatorIdStr);
            } catch (NumberFormatException e) {
                log.warn("[Chat] Invalid creatorId format: {}", creatorIdStr);
            }
        }

        ChatDto.ChatResponse response = chatService.startChatWithImages(
                user.getUserNo(), prompt, images, creatorId);
        return ApiResponse.success("채팅이 시작되었습니다.", response);
    }

    @PostMapping("/{chatId}/message")
    @Operation(summary = "메시지 전송", description = "채팅에 메시지를 전송합니다.")
    public ApiResponse<ChatDto.ChatResponse> sendMessage(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId,
            @RequestBody ChatDto.MessageRequest request) {
        log.info("[Chat] Message - chatId: {}, message: {}", chatId,
                request.getMessage().substring(0, Math.min(50, request.getMessage().length())));
        ChatDto.ChatResponse response = chatService.sendMessage(user.getUserNo(), chatId, request);
        return ApiResponse.success(response);
    }

    @GetMapping("/{chatId}")
    @Operation(summary = "채팅 상세 조회", description = "채팅 상세 정보와 생성된 콘텐츠 상태를 조회합니다.")
    public ApiResponse<ChatDto.ChatDetail> getChatDetail(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        ChatDto.ChatDetail response = chatService.getChatDetail(user.getUserNo(), chatId);
        return ApiResponse.success(response);
    }

    @GetMapping
    @Operation(summary = "채팅 목록 조회", description = "사용자의 채팅 목록을 조회합니다.")
    public ApiResponse<List<ChatDto.ChatSummary>> getChatList(
            @AuthenticationPrincipal UserPrincipal user) {
        List<ChatDto.ChatSummary> response = chatService.getChatList(user.getUserNo());
        return ApiResponse.success(response);
    }

    @DeleteMapping("/{chatId}")
    @Operation(summary = "채팅 삭제", description = "채팅을 삭제합니다.")
    public ApiResponse<Void> deleteChat(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        chatService.deleteChat(user.getUserNo(), chatId);
        return ApiResponse.success("채팅이 삭제되었습니다.", null);
    }

    /**
     * v2.9.150: 사용자에게 연결된 크리에이터 정보 조회
     * 계정에 1:1로 매핑된 크리에이터 정보를 반환합니다.
     */
    @GetMapping("/my-creator")
    @Operation(summary = "연결된 크리에이터 조회", description = "v2.9.150: 계정에 연결된 크리에이터 정보를 조회합니다.")
    public ApiResponse<ChatDto.LinkedCreatorInfo> getMyLinkedCreator(
            @AuthenticationPrincipal UserPrincipal user) {
        log.info("[Chat] Get linked creator - userNo: {}", user.getUserNo());
        ChatDto.LinkedCreatorInfo creator = chatService.getLinkedCreator(user.getUserNo());
        if (creator == null) {
            log.warn("[Chat] User {} has no linked creator", user.getUserNo());
            return ApiResponse.success("연결된 크리에이터가 없습니다.", null);
        }
        return ApiResponse.success(creator);
    }
}
