#!/bin/bash

# AI Video Generator - Local Server Start Script

set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="/tmp/aivideo"

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 로그 디렉토리 생성
mkdir -p "$LOG_DIR"

echo ""
echo "=========================================="
echo "  AI Video Generator - Local Server Start"
echo "=========================================="

# 1. Docker 컨테이너 시작 (MariaDB, Redis, Adminer)
echo ""
echo -e "${YELLOW}[1/2] Docker 컨테이너 시작...${NC}"
cd "$PROJECT_DIR"

# Docker Desktop 실행 확인
if ! docker info > /dev/null 2>&1; then
    echo -e "${RED}  ✗ Docker가 실행 중이 아닙니다. Docker Desktop을 먼저 시작하세요.${NC}"
    exit 1
fi

if docker compose ps 2>/dev/null | grep -q "running"; then
    echo -e "${GREEN}  ✓ Docker 컨테이너가 이미 실행 중입니다.${NC}"
else
    docker compose up -d
    echo "  DB 초기화 대기 중..."
    sleep 10  # DB 초기화 대기
    echo -e "${GREEN}  ✓ Docker 컨테이너 시작됨 (MariaDB, Redis, Adminer)${NC}"
fi

# DB 연결 확인
echo "  DB 연결 확인 중..."
for i in {1..10}; do
    if docker exec aivideo-mariadb mysql -u aivideo -p"${DB_PASSWORD:-changeme}" -e "SELECT 1" > /dev/null 2>&1; then
        echo -e "${GREEN}  ✓ MariaDB 연결 성공${NC}"
        break
    fi
    sleep 2
    if [ $i -eq 10 ]; then
        echo -e "${RED}  ✗ MariaDB 연결 실패${NC}"
        exit 1
    fi
done

# 2. Backend (Spring Boot) 시작
echo ""
echo -e "${YELLOW}[2/2] Backend 서버 시작...${NC}"
cd "$PROJECT_DIR"

# 기존 프로세스 종료
if pgrep -f "bootRun\|spring-boot" > /dev/null 2>&1; then
    echo "  기존 Backend 프로세스 종료 중..."
    pkill -f "bootRun\|spring-boot" 2>/dev/null || true
    sleep 3
fi

# 백그라운드로 실행
./gradlew :api:bootRun --no-daemon > "$LOG_DIR/backend.log" 2>&1 &
BACKEND_PID=$!
echo "  Backend PID: $BACKEND_PID"

# 서버 시작 대기
echo "  Backend 서버 시작 대기 중..."
for i in {1..60}; do
    if curl -s http://localhost:8080/actuator/health > /dev/null 2>&1; then
        echo -e "${GREEN}  ✓ Backend 서버 시작 완료 (포트: 8080)${NC}"
        break
    fi
    sleep 2
    if [ $i -eq 60 ]; then
        echo -e "${RED}  ✗ Backend 서버 시작 타임아웃${NC}"
        echo "  로그 확인: tail -f $LOG_DIR/backend.log"
        exit 1
    fi
done

# 완료 메시지
echo ""
echo "=========================================="
echo -e "${GREEN}  로컬 서버 시작 완료!${NC}"
echo "=========================================="
echo ""
echo "  접속 URL:"
echo "    Frontend:   http://localhost:3000 (cd ../frontend && npm run dev)"
echo "    Backend:    http://localhost:8080"
echo "    Swagger:    http://localhost:8080/swagger-ui.html"
echo "    Adminer:    http://localhost:8081"
echo ""
echo "  로그 위치:"
echo "    Backend:    $LOG_DIR/backend.log"
echo ""
echo "  중지: ./stop-server.sh"
echo ""
