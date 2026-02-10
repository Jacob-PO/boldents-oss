package com.aivideo.api.util;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 외부 프로세스 실행 유틸리티 (v2.9.13)
 * - 표준화된 프로세스 실행 패턴
 * - 버퍼 오버플로우 방지 (출력 읽기)
 * - 타임아웃 처리
 * - 보안 검증 (PathValidator 연동)
 */
@Slf4j
public class ProcessExecutor {

    /**
     * 명령어 실행 결과
     */
    public static class Result {
        private final int exitCode;
        private final String output;

        public Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getOutput() {
            return output;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    /**
     * 명령어 실행 (기본 타임아웃: 30분)
     * @param command 실행할 명령어 리스트
     * @param taskName 작업 이름 (로깅용)
     * @return 실행 결과
     */
    public static Result execute(List<String> command, String taskName)
            throws IOException, InterruptedException, TimeoutException {
        return execute(command, taskName, 30, TimeUnit.MINUTES);
    }

    /**
     * 명령어 실행 (타임아웃 지정)
     * @param command 실행할 명령어 리스트
     * @param taskName 작업 이름 (로깅용)
     * @param timeout 타임아웃 값
     * @param unit 타임아웃 단위
     * @return 실행 결과
     */
    public static Result execute(List<String> command, String taskName, long timeout, TimeUnit unit)
            throws IOException, InterruptedException, TimeoutException {

        log.debug("[ProcessExecutor] Starting {}: {}", taskName,
                String.join(" ", command).substring(0, Math.min(200, String.join(" ", command).length())));

        // 보안 검증: 경로에 위험한 패턴이 있는지 확인
        PathValidator.validateCommandArgs(command);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // 출력 읽기 (버퍼 오버플로우 방지)
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null) {
                // 최대 1000줄까지만 저장 (메모리 보호)
                if (lineCount < 1000) {
                    output.append(line).append("\n");
                    lineCount++;
                }
                // 디버그 로깅은 처음 10줄만
                if (lineCount <= 10) {
                    log.trace("[ProcessExecutor] {}: {}", taskName, line);
                }
            }
        }

        boolean completed = process.waitFor(timeout, unit);

        if (!completed) {
            process.destroyForcibly();
            log.error("[ProcessExecutor] {} TIMEOUT after {} {}", taskName, timeout, unit);
            throw new TimeoutException("Process timeout: " + taskName + " (" + timeout + " " + unit + ")");
        }

        int exitCode = process.exitValue();

        if (exitCode != 0) {
            log.warn("[ProcessExecutor] {} failed with exit code {}", taskName, exitCode);
            log.debug("[ProcessExecutor] {} output: {}", taskName,
                    output.toString().substring(0, Math.min(500, output.length())));
        } else {
            log.debug("[ProcessExecutor] {} completed successfully", taskName);
        }

        return new Result(exitCode, output.toString());
    }

    /**
     * 명령어 실행 (성공 필수 - 실패 시 예외)
     * @param command 실행할 명령어 리스트
     * @param taskName 작업 이름 (로깅용)
     */
    public static void executeOrThrow(List<String> command, String taskName)
            throws IOException, InterruptedException, TimeoutException {
        Result result = execute(command, taskName);
        if (!result.isSuccess()) {
            throw new IOException(taskName + " failed with exit code " + result.getExitCode() +
                    ": " + result.getOutput().substring(0, Math.min(200, result.getOutput().length())));
        }
    }

    /**
     * 명령어 실행 (성공 필수, 타임아웃 지정)
     * @param command 실행할 명령어 리스트
     * @param taskName 작업 이름 (로깅용)
     * @param timeout 타임아웃 값
     * @param unit 타임아웃 단위
     */
    public static void executeOrThrow(List<String> command, String taskName, long timeout, TimeUnit unit)
            throws IOException, InterruptedException, TimeoutException {
        Result result = execute(command, taskName, timeout, unit);
        if (!result.isSuccess()) {
            throw new IOException(taskName + " failed with exit code " + result.getExitCode() +
                    ": " + result.getOutput().substring(0, Math.min(200, result.getOutput().length())));
        }
    }
}
