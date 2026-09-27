import { createHash, timingSafeEqual } from 'node:crypto';
import { type NextAuthOptions, type DefaultSession } from 'next-auth';
import GoogleProvider from 'next-auth/providers/google';

/**
 * Module augmentation to add accessToken and error to session
 */
declare module 'next-auth' {
  interface Session extends DefaultSession {
    accessToken?: string;
    error?: string;
  }
}

declare module 'next-auth/jwt' {
  interface JWT {
    accessToken?: string;
    refreshToken?: string;
    expiresAt?: number;
    error?: string;
  }
}

const LEGACY_ALLOWED_EMAIL_SHA256 = 'dcf7a9679f97f67fa02fb0f540bfa869b070c33161e5fc15fbf0e6adb056cbfb';

function allowedEmail(email: string | null | undefined): boolean {
  const normalized = email?.trim().toLowerCase();
  if (!normalized) return false;

  const configured = (process.env.ALLOWED_GOOGLE_EMAILS || '')
    .split(',')
    .map((value) => value.trim().toLowerCase())
    .filter(Boolean);
  if (configured.length > 0) return configured.includes(normalized);

  const digest = createHash('sha256').update(normalized).digest();
  const expected = Buffer.from(LEGACY_ALLOWED_EMAIL_SHA256, 'hex');
  return digest.length === expected.length && timingSafeEqual(digest, expected);
}

export const authOptions: NextAuthOptions = {
  providers: [
    GoogleProvider({
      clientId: process.env.GOOGLE_CLIENT_ID!,
      clientSecret: process.env.GOOGLE_CLIENT_SECRET!,
      authorization: {
        params: {
          access_type: 'offline',
          prompt: 'consent',
          scope: 'openid email profile https://www.googleapis.com/auth/drive',
        },
      },
    }),
  ],
  session: {
    strategy: 'jwt',
  },
  callbacks: {
    async signIn({ user }) {
      return allowedEmail(user.email);
    },
    async jwt({ token, account }) {
      if (account) {
        token.accessToken = account.access_token;
        token.refreshToken = account.refresh_token;
        token.expiresAt = (account.expires_at ?? 0) * 1000;
      }

      if (token.expiresAt && Date.now() >= token.expiresAt) {
        if (token.refreshToken) {
          try {
            const response = await fetch('https://oauth2.googleapis.com/token', {
              method: 'POST',
              headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
              body: new URLSearchParams({
                client_id: process.env.GOOGLE_CLIENT_ID!,
                client_secret: process.env.GOOGLE_CLIENT_SECRET!,
                grant_type: 'refresh_token',
                refresh_token: token.refreshToken,
              }).toString(),
            });

            if (response.ok) {
              const refreshedTokens = await response.json();
              token.accessToken = refreshedTokens.access_token;
              token.expiresAt = refreshedTokens.expires_in
                ? Date.now() + refreshedTokens.expires_in * 1000
                : token.expiresAt;
              if (refreshedTokens.refresh_token) {
                token.refreshToken = refreshedTokens.refresh_token;
              }
              token.error = undefined;
            } else {
              token.error = 'RefreshAccessTokenError';
            }
          } catch {
            token.error = 'RefreshAccessTokenError';
          }
        }
      }

      return token;
    },
    async session({ session, token }) {
      session.accessToken = token.accessToken;
      session.error = token.error;
      return session;
    },
  },
  pages: {
    signIn: '/',
  },
};

export default authOptions;
