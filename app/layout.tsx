import type { Metadata } from "next";
import Script from "next/script";
import { Geist, Geist_Mono } from "next/font/google";
import { AuthProvider } from "./providers";
import OfflineBanner from "@/components/OfflineBanner";
import PodcastVisibilitySettings from "@/components/PodcastVisibilitySettings";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "BookShelf",
  description: "Personal ebook reader powered by Google Drive",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">
        <AuthProvider>
          <OfflineBanner />
          {children}
          <PodcastVisibilitySettings />
        </AuthProvider>
        <Script
          id="joshbooks-podcast-player"
          src="/book-podcast-player.js?v=3"
          strategy="afterInteractive"
        />
        <Script
          src="https://cloud.umami.is/script.js"
          strategy="afterInteractive"
          data-website-id="__UMAMI_WEBSITE_ID__"
          data-do-not-track="true"
          data-performance="true"
          data-exclude-hash="true"
          data-domains="josh-books-online.vercel.app"
          data-tag="JoshBooksOnline"
        />
      </body>
    </html>
  );
}
