import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { AuthProvider } from "./providers";
import OfflineBanner from "@/components/OfflineBanner";
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
        </AuthProvider>
        <script
          src="https://cdn.jsdelivr.net/gh/joshualparris/JoshHub@main/public/podcast-dock-universal.js"
          data-bank="books"
          data-label="📚 Listen to a different books podcast"
          data-quiet-on-input="true"
          defer
        />
      </body>
    </html>
  );
}
