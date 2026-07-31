'use client';

import { signIn } from 'next-auth/react';

export default function Home() {
  return (
    <div className="flex items-center justify-center min-h-screen bg-gradient-to-br from-slate-900 to-slate-800">
      <div className="text-center">
        <h1 className="text-5xl font-bold text-white mb-4">BookShelf</h1>
        <p className="text-xl text-slate-300 mb-12">
          Your personal ebook reader for Drive and rescued local books
        </p>
        <a
          href="/library"
          className="mr-3 inline-flex px-8 py-3 bg-slate-700 hover:bg-slate-600 text-white font-semibold rounded-lg transition-colors"
        >
          Open local library
        </a>
        <button
          onClick={() => signIn('google', { callbackUrl: '/library' })}
          className="px-8 py-3 bg-blue-600 hover:bg-blue-700 text-white font-semibold rounded-lg transition-colors"
        >
          Sign in with Google
        </button>
      </div>
    </div>
  );
}
