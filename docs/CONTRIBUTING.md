# Contributing

## Reporting Issues

- Search existing issues before creating a new one
- Include steps to reproduce, expected vs actual behavior
- Include relevant logs or screenshots

## Pull Requests

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Make your changes
4. Run tests: `cd backend && ./gradlew build` and `cd frontend && npm run build`
5. Submit a pull request

## Adding a New Creator

1. Insert into `creators` table (set `model_tier_id`, `nation_code`)
2. Insert into `creator_prompts` table (31 prompt columns)
3. Insert into `creator_prompts_length` table (8 length settings)
4. Link a test user: `UPDATE users SET creator_id = ? WHERE login_id = ?`

## Adding a New Nation

1. Insert into `creator_nation` (language code, TTS chars/sec, max chars/line)
2. Insert into `video_font` (font file, nation code)
3. Add font TTF file to `backend/api/src/main/resources/fonts/`
4. Rebuild Docker image to include the new font

## Code Style

- Backend: Standard Java conventions, no unnecessary abstractions
- Frontend: TypeScript strict mode, Tailwind CSS
- All AI prompts must be in the database (no hardcoded prompts)
