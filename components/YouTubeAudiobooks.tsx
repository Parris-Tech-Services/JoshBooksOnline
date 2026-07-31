'use client';

import { useState } from 'react';

const YOUTUBE_AUDIOBOOKS = [
  { title: 'The Way of Kings', author: 'Brandon Sanderson', videoId: 'ImJfoAtViWQ' },
  { title: 'Mistborn: The Final Empire (Part 1)', author: 'Brandon Sanderson', videoId: '9ROVxlGaV6c' },
  { title: 'The Art of War', author: 'Sun Tzu', videoId: 'jxcMRkqaQdw' },
  { title: 'Sherlock Holmes', author: 'Arthur Conan Doyle', videoId: 'A4TU2h_rDlM' },
  { title: "Harry Potter and the Philosopher's Stone", author: 'J.K. Rowling', videoId: '6XIPkMFZf-0' },
  { title: 'Pride and Prejudice', author: 'Jane Austen', videoId: 'ZhxqauL9WbM' },
  { title: 'Magician', author: 'Raymond E. Feist', videoId: 'AfmVKN-RwAQ' },
  { title: 'The Door in the Wall', author: 'H.G. Wells', videoId: 'Huam5sSRjwc' },
];

function getThumbnailUrl(videoId: string) {
  return `https://img.youtube.com/vi/${videoId}/mqdefault.jpg`;
}

export function YouTubeAudiobooks() {
  const [selectedVideo, setSelectedVideo] = useState<null | typeof YOUTUBE_AUDIOBOOKS[number]>(null);

  const toggleSelection = (video: typeof YOUTUBE_AUDIOBOOKS[number]) => {
    if (selectedVideo?.videoId === video.videoId) {
      setSelectedVideo(null);
    } else {
      setSelectedVideo(video);
    }
  };

  return (
    <section className="rounded-3xl border border-white/10 bg-slate-900/80 p-6 shadow-xl shadow-black/10 transition">
      <div className="mb-6 flex items-center justify-between gap-4">
        <div>
          <h2 className="text-2xl font-semibold text-white">YouTube Audiobooks</h2>
          <p className="mt-2 text-sm text-slate-400">
            Browse a curated YouTube audiobook list and play episodes without leaving the library.
          </p>
        </div>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {YOUTUBE_AUDIOBOOKS.map((book) => {
          const isSelected = selectedVideo?.videoId === book.videoId;
          return (
            <button
              key={book.videoId}
              type="button"
              onClick={() => toggleSelection(book)}
              className={`group flex flex-col overflow-hidden rounded-3xl border p-0 text-left transition ${
                isSelected ? 'border-sky-500 bg-slate-800 shadow-[0_0_0_1px_rgba(56,189,248,0.5)]' : 'border-white/10 bg-slate-950 hover:border-slate-500/40 hover:bg-slate-900'
              }`}
            >
              <div className="relative overflow-hidden bg-slate-800">
                <img
                  src={getThumbnailUrl(book.videoId)}
                  alt={`${book.title} thumbnail`}
                  className="h-40 w-full object-cover transition duration-300 group-hover:scale-105"
                />
                <div className="absolute inset-x-0 bottom-0 flex items-center justify-center bg-black/40 p-2 text-xs font-semibold uppercase tracking-[0.12em] text-white">
                  {isSelected ? 'Playing' : 'Play'}
                </div>
              </div>
              <div className="space-y-2 p-4">
                <h3 className="text-sm font-semibold text-white">{book.title}</h3>
                <p className="text-sm text-slate-400">{book.author}</p>
              </div>
            </button>
          );
        })}
      </div>

      {selectedVideo ? (
        <div className="mt-6 rounded-3xl border border-white/10 bg-slate-950 p-6">
          <div className="mb-4 flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
            <div>
              <p className="text-sm uppercase tracking-[0.24em] text-sky-300">Now playing</p>
              <h3 className="mt-2 text-xl font-semibold text-white">{selectedVideo.title}</h3>
              <p className="mt-1 text-sm text-slate-400">{selectedVideo.author}</p>
            </div>
            <button
              type="button"
              onClick={() => setSelectedVideo(null)}
              className="inline-flex items-center justify-center rounded-full border border-white/10 bg-slate-900 px-4 py-2 text-sm font-semibold text-white transition hover:border-slate-300/30 hover:bg-slate-800"
            >
              Close
            </button>
          </div>
          <div className="aspect-[16/9] overflow-hidden rounded-3xl border border-white/10 bg-slate-950">
            <iframe
              className="h-full w-full"
              src={`https://www.youtube.com/embed/${selectedVideo.videoId}?autoplay=1`}
              title={selectedVideo.title}
              allow="autoplay; encrypted-media"
              allowFullScreen
            />
          </div>
        </div>
      ) : null}
    </section>
  );
}
