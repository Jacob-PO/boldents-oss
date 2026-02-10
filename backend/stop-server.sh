#!/bin/bash

# AI Video Platform - Local server stop script

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo ""
echo "=========================================="
echo "  AI Video - Local Server Stop"
echo "=========================================="

# 1. Backend 중지
echo ""
echo -e "${YELLOW}[1/2] Backend 서버 중지...${NC}"
if pgrep -f "bootRun\|spring-boot" > /dev/null 2>&1; then
    pkill -f "bootRun\|spring-boot" 2>/dev/null
    sleep 2
    echo -e "${GREEN}  ✓ Backend 서버 중지됨${NC}"
else
    echo "  - Backend 서버가 실행 중이 아닙니다."
fi

# Java 프로세스 추가 정리
if pgrep -f "java.*aivideo" > /dev/null 2>&1; then
    pkill -f "java.*aivideo" 2>/dev/null
    echo -e "${GREEN}  ✓ 남은 Java 프로세스 정리됨${NC}"
fi

# 2. Docker 컨테이너 (선택적)
echo ""
echo -e "${YELLOW}[2/2] Docker 컨테이너...${NC}"
if docker compose ps 2>/dev/null | grep -q "running"; then
    echo -n "  Docker 컨테이너(MariaDB, Redis)도 중지하시겠습니까? [y/N]: "
    read -r REPLY
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        cd "$(dirname "$0")"
        docker compose down
        echo -e "${GREEN}  ✓ Docker 컨테이너 중지됨${NC}"
    else
        echo "  - Docker 컨테이너는 계속 실행됩니다."
    fi
else
    echo "  - Docker 컨테이너가 실행 중이 아닙니다."
fi

# 완료 메시지
echo ""
echo "=========================================="
echo -e "${GREEN}  서버 중지 완료!${NC}"
echo "=========================================="
echo ""
echo "  상태 확인:"
echo "    docker compose ps"
echo "    lsof -i :8080"
echo ""
