/**
 * 인증 관련 유틸리티
 */

const TOKEN_KEY = 'aivideo_access_token';
const REFRESH_TOKEN_KEY = 'aivideo_refresh_token';
const USER_KEY = 'aivideo_user';

export interface User {
  userNo: number;
  loginId: string;
  name: string;
  email: string | null;
  phone: string | null;
  role: string;
  tier: string | null;
  hasGoogleApiKey: boolean;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  user: User;
}

// 토큰 저장
export function setTokens(accessToken: string, refreshToken: string) {
  if (typeof window !== 'undefined') {
    localStorage.setItem(TOKEN_KEY, accessToken);
    localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
  }
}

// 액세스 토큰 가져오기
export function getAccessToken(): string | null {
  if (typeof window !== 'undefined') {
    return localStorage.getItem(TOKEN_KEY);
  }
  return null;
}

// 리프레시 토큰 가져오기
export function getRefreshToken(): string | null {
  if (typeof window !== 'undefined') {
    return localStorage.getItem(REFRESH_TOKEN_KEY);
  }
  return null;
}

// 사용자 정보 저장
export function setUser(user: User) {
  if (typeof window !== 'undefined') {
    localStorage.setItem(USER_KEY, JSON.stringify(user));
  }
}

// 사용자 정보 가져오기
export function getUser(): User | null {
  if (typeof window !== 'undefined') {
    const userStr = localStorage.getItem(USER_KEY);
    if (userStr) {
      try {
        return JSON.parse(userStr);
      } catch {
        return null;
      }
    }
  }
  return null;
}

// 로그아웃
export function clearAuth() {
  if (typeof window !== 'undefined') {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
  }
}

// 로그인 여부 확인
export function isAuthenticated(): boolean {
  return !!getAccessToken();
}
