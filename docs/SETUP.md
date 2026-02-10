# Setup Guide

## Prerequisites

| Requirement | Version |
|------------|---------|
| Java | 17+ |
| Node.js | 18+ |
| MariaDB | 10.11+ |
| FFmpeg | 6.0+ (with libsoxr) |
| Gradle | 8.x (wrapper included) |

## 1. Database Setup

```bash
# Create database
mysql -u root -e "CREATE DATABASE aivideo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# Run schema
mysql -u root aivideo < backend/sql/001-schema.sql

# Run seed data
mysql -u root aivideo < backend/sql/002-seed-data.sql
```

## 2. Backend Setup

```bash
cd backend

# Copy and edit environment variables
cp .env.example .env
```

Edit `.env` with your settings:

```env
# Database
DB_HOST=localhost
DB_PORT=3306
DB_NAME=aivideo
DB_USERNAME=root
DB_PASSWORD=your_password

# Google Gemini API Key (required for AI features)
# Get one at https://aistudio.google.com/apikey
GEMINI_API_KEY=your_gemini_api_key

# JWT Secret (change this)
JWT_SECRET=your-jwt-secret-key-at-least-32-characters

# AWS S3 (optional - falls back to local storage)
AWS_ACCESS_KEY_ID=your_access_key
AWS_SECRET_ACCESS_KEY=your_secret_key
S3_BUCKET_NAME=your-bucket-name
S3_REGION=us-east-1

# Encryption key for stored API keys (exactly 32 chars)
ENCRYPTION_KEY=change-this-encryption-key-32chars
```

Run the backend:

```bash
./gradlew :api:bootRun
```

The API will be available at `http://localhost:8080`.

## 3. Frontend Setup

```bash
cd frontend
npm install
npm run dev
```

The frontend will be available at `http://localhost:3000`.

## 4. Google Gemini API Key

1. Go to [Google AI Studio](https://aistudio.google.com/apikey)
2. Create a new API key
3. Add it to your `.env` file as `GEMINI_API_KEY`
4. Or enter it in the app's API key settings page

## 5. Docker (Optional)

```bash
cd backend
docker compose up -d
```

This starts MariaDB and the backend. Edit `docker-compose.yml` for your configuration.

## 6. Fonts

The platform uses custom fonts for subtitles and thumbnails. Included fonts:

- **SUIT-Bold** (Korean) — SIL Open Font License 1.1
- **Montserrat-Bold** (Latin) — Apache License 2.0

Font files are in `backend/api/src/main/resources/fonts/`. For Docker deployments, fonts are automatically copied to the system font directory.

## Verification

```bash
# Backend health check
curl http://localhost:8080/api/health

# Frontend
open http://localhost:3000
```
