'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import Image from 'next/image';
import { api } from '@/lib/api';
import { setTokens, setUser } from '@/lib/auth';

export default function LoginPage() {
  const router = useRouter();
  const [isSignup, setIsSignup] = useState(false);

  const [loginId, setLoginId] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');

  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  // Validation
  const validateLoginId = (value: string) => /^[a-zA-Z]{1,10}$/.test(value);
  const validatePassword = (value: string) => /^[a-zA-Z0-9]{1,10}$/.test(value);
  const validateEmail = (value: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);
  const validatePhone = (value: string) => /^010-\d{4}-\d{4}$/.test(value);
  const validateName = (value: string) => /^[가-힣a-zA-Z]{1,20}$/.test(value);

  // Input filtering
  const handleLoginIdChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value.replace(/[^a-zA-Z]/g, '').slice(0, 10);
    setLoginId(value);
  };

  const handlePasswordChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value.replace(/[^a-zA-Z0-9]/g, '').slice(0, 10);
    setPassword(value);
  };

  const handleNameChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value.replace(/[^가-힣a-zA-Z]/g, '').slice(0, 20);
    setName(value);
  };

  const handlePhoneChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    let value = e.target.value.replace(/[^\d-]/g, '');
    // Auto-hyphen
    if (value.length <= 3) {
      value = value;
    } else if (value.length <= 8) {
      value = value.slice(0, 3) + '-' + value.slice(3).replace(/-/g, '');
    } else {
      value = value.slice(0, 3) + '-' + value.slice(3, 7).replace(/-/g, '') + '-' + value.slice(7, 11).replace(/-/g, '');
    }
    if (value.length <= 13) setPhone(value);
  };

  const isSignupValid = () => {
    return validateLoginId(loginId) &&
           validatePassword(password) &&
           validateName(name) &&
           validateEmail(email) &&
           validatePhone(phone);
  };

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);

    try {
      const response = await api.login({ loginId, password });
      setTokens(response.accessToken, response.refreshToken);
      setUser(response.user);
      router.push('/');
    } catch {
      setError('아이디 또는 비밀번호가 올바르지 않습니다.');
    } finally {
      setLoading(false);
    }
  };

  const handleSignup = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    if (!isSignupValid()) {
      setError('입력 형식을 확인해주세요.');
      return;
    }

    setLoading(true);

    try {
      const response = await api.signup({ loginId, password, name, email, phone });
      setTokens(response.accessToken, response.refreshToken);
      setUser(response.user);
      router.push('/');
    } catch (err: unknown) {
      const error = err as { message?: string };
      setError(error.message || '회원가입에 실패했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const resetForm = () => {
    setLoginId('');
    setPassword('');
    setName('');
    setEmail('');
    setPhone('');
    setError('');
  };

  const toggleMode = () => {
    resetForm();
    setIsSignup(!isSignup);
  };

  return (
    <div className="min-h-screen flex flex-col bg-white safe-area-top safe-area-bottom">
      {/* Mobile-first full screen layout */}
      <div className="flex-1 flex flex-col justify-center px-6 py-12 md:px-8">
        <div className="w-full max-w-sm mx-auto">
          {/* Logo */}
          <div className="text-center mb-10">
            <Image
              src="/images/logo.png"
              alt="AI Video"
              width={56}
              height={56}
              className="w-14 h-14 mx-auto mb-3"
              priority
            />
            <p className="text-gray-500 text-sm">
              영상 제작 플랫폼
            </p>
          </div>

          {/* Form Card */}
          <div className="bg-white">
            <h2 className="text-xl font-semibold text-black mb-6">
              {isSignup ? '회원가입' : '로그인'}
            </h2>

            <form onSubmit={isSignup ? handleSignup : handleLogin} className="space-y-4">
              {isSignup && (
                <>
                  {/* Name */}
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-2">
                      이름
                      <span className="text-gray-400 font-normal ml-1">(한글/영어)</span>
                    </label>
                    <input
                      type="text"
                      placeholder="홍길동"
                      value={name}
                      onChange={handleNameChange}
                      required
                      className={`w-full px-4 py-3.5 text-base bg-gray-50 border rounded-xl focus:outline-none transition-colors placeholder:text-gray-400 touch-target ${
                        name && !validateName(name)
                          ? 'border-gray-900 bg-gray-100'
                          : 'border-gray-200 focus:border-black focus:bg-white'
                      }`}
                    />
                  </div>

                  {/* Email */}
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-2">
                      이메일
                    </label>
                    <input
                      type="email"
                      placeholder="example@email.com"
                      value={email}
                      onChange={(e) => setEmail(e.target.value)}
                      required
                      className={`w-full px-4 py-3.5 text-base bg-gray-50 border rounded-xl focus:outline-none transition-colors placeholder:text-gray-400 touch-target ${
                        email && !validateEmail(email)
                          ? 'border-gray-900 bg-gray-100'
                          : 'border-gray-200 focus:border-black focus:bg-white'
                      }`}
                    />
                  </div>

                  {/* Phone */}
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-2">
                      전화번호
                    </label>
                    <input
                      type="tel"
                      placeholder="010-0000-0000"
                      value={phone}
                      onChange={handlePhoneChange}
                      required
                      className={`w-full px-4 py-3.5 text-base bg-gray-50 border rounded-xl focus:outline-none transition-colors placeholder:text-gray-400 touch-target ${
                        phone && !validatePhone(phone)
                          ? 'border-gray-900 bg-gray-100'
                          : 'border-gray-200 focus:border-black focus:bg-white'
                      }`}
                    />
                  </div>
                </>
              )}

              {/* Login ID */}
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  아이디
                  {isSignup && <span className="text-gray-400 font-normal ml-1">(영어만, 최대 10자)</span>}
                </label>
                <input
                  type="text"
                  placeholder="아이디를 입력하세요"
                  value={loginId}
                  onChange={isSignup ? handleLoginIdChange : (e) => setLoginId(e.target.value)}
                  required
                  className={`w-full px-4 py-3.5 text-base bg-gray-50 border rounded-xl focus:outline-none transition-colors placeholder:text-gray-400 touch-target ${
                    isSignup && loginId && !validateLoginId(loginId)
                      ? 'border-gray-900 bg-gray-100'
                      : 'border-gray-200 focus:border-black focus:bg-white'
                  }`}
                />
              </div>

              {/* Password */}
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  비밀번호
                  {isSignup && <span className="text-gray-400 font-normal ml-1">(영어/숫자, 최대 10자)</span>}
                </label>
                <input
                  type="password"
                  placeholder="비밀번호를 입력하세요"
                  value={password}
                  onChange={isSignup ? handlePasswordChange : (e) => setPassword(e.target.value)}
                  required
                  className={`w-full px-4 py-3.5 text-base bg-gray-50 border rounded-xl focus:outline-none transition-colors placeholder:text-gray-400 touch-target ${
                    isSignup && password && !validatePassword(password)
                      ? 'border-gray-900 bg-gray-100'
                      : 'border-gray-200 focus:border-black focus:bg-white'
                  }`}
                />
              </div>

              {/* Error Message */}
              {error && (
                <div className="py-3 px-4 bg-gray-100 rounded-xl flex items-center justify-center gap-2">
                  <svg className="w-4 h-4 flex-shrink-0 text-gray-700" viewBox="0 0 20 20" fill="currentColor">
                    <path fillRule="evenodd" d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" clipRule="evenodd" />
                  </svg>
                  <p className="text-sm text-gray-700">{error}</p>
                </div>
              )}

              {/* Submit Button */}
              <button
                type="submit"
                disabled={loading || !loginId || !password || (isSignup && !isSignupValid())}
                className="w-full py-4 bg-black text-white rounded-xl font-semibold hover:bg-gray-800 disabled:bg-gray-300 disabled:cursor-not-allowed transition-colors touch-target btn-haptic"
              >
                {loading ? (
                  <span className="flex items-center justify-center gap-2">
                    <div className="w-5 h-5 border-2 border-white border-t-transparent rounded-full animate-spin" />
                    {isSignup ? '가입 중...' : '로그인 중...'}
                  </span>
                ) : (
                  isSignup ? '회원가입' : '로그인'
                )}
              </button>
            </form>

            {/* Toggle Mode */}
            <div className="mt-8 pt-6 border-t border-gray-100 text-center">
              <button
                onClick={toggleMode}
                className="text-sm text-gray-500 hover:text-black transition-colors touch-target"
              >
                {isSignup ? (
                  <>이미 계정이 있으신가요? <span className="font-semibold text-black">로그인</span></>
                ) : (
                  <>계정이 없으신가요? <span className="font-semibold text-black">회원가입</span></>
                )}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
