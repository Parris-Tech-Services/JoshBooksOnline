'use client';

import { useEffect, useState } from 'react';
import { usePathname } from 'next/navigation';

const VISIBILITY_KEY = 'joshbooks-podcast-button-hidden-v1';
const VISIBILITY_EVENT = 'joshbooks:podcast-visibility';
const VISIBILITY_CHANGED_EVENT = 'joshbooks:podcast-visibility-changed';

export default function PodcastVisibilitySettings() {
  const pathname = usePathname();
  const isReader = pathname?.startsWith('/reader/');
  const [open, setOpen] = useState(false);
  const [podcastHidden, setPodcastHidden] = useState(false);

  useEffect(() => {
    if (!isReader) return;

    const readStoredState = () => {
      try {
        setPodcastHidden(window.localStorage.getItem(VISIBILITY_KEY) === '1');
      } catch {
        setPodcastHidden(false);
      }
    };

    const handleVisibilityChanged = (event: Event) => {
      const customEvent = event as CustomEvent<{ hidden?: boolean }>;
      if (typeof customEvent.detail?.hidden === 'boolean') {
        setPodcastHidden(customEvent.detail.hidden);
      } else {
        readStoredState();
      }
    };

    readStoredState();
    window.addEventListener(VISIBILITY_CHANGED_EVENT, handleVisibilityChanged);
    return () => window.removeEventListener(VISIBILITY_CHANGED_EVENT, handleVisibilityChanged);
  }, [isReader]);

  if (!isReader) return null;

  const setPodcastVisibility = (hidden: boolean) => {
    setPodcastHidden(hidden);
    try {
      window.localStorage.setItem(VISIBILITY_KEY, hidden ? '1' : '0');
    } catch {
      // The custom element still receives the in-page event if storage is unavailable.
    }
    window.dispatchEvent(new CustomEvent(VISIBILITY_EVENT, { detail: { hidden } }));
  };

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        aria-label="Reader settings"
        title="Reader settings"
        className="fixed right-[9.25rem] top-4 z-[70] flex h-10 w-10 items-center justify-center rounded-full bg-slate-800/95 text-lg text-slate-100 shadow-lg backdrop-blur transition hover:bg-slate-700"
      >
        ⚙
      </button>

      {open && (
        <div
          className="fixed inset-0 flex items-center justify-center bg-black/70 p-4"
          style={{ zIndex: 2147483646 }}
          role="dialog"
          aria-modal="true"
          aria-label="Reader settings"
          onClick={() => setOpen(false)}
        >
          <div
            className="w-full max-w-sm rounded-3xl border border-slate-700 bg-slate-900 p-5 text-slate-100 shadow-2xl"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="flex items-center justify-between gap-4">
              <div>
                <p className="text-xs font-semibold uppercase tracking-[0.16em] text-slate-400">Reader</p>
                <h2 className="mt-1 text-xl font-semibold">Settings</h2>
              </div>
              <button
                type="button"
                onClick={() => setOpen(false)}
                aria-label="Close settings"
                className="flex h-10 w-10 items-center justify-center rounded-full bg-slate-800 text-xl hover:bg-slate-700"
              >
                ×
              </button>
            </div>

            <div className="mt-5 rounded-2xl border border-slate-700 bg-slate-950/70 p-4">
              <div className="flex items-start justify-between gap-3">
                <div>
                  <h3 className="font-semibold">Podcast button</h3>
                  <p className="mt-1 text-sm text-slate-400">
                    {podcastHidden
                      ? 'Hidden while you read.'
                      : 'Visible at the bottom of the reader.'}
                  </p>
                </div>
                <span className={`rounded-full px-2.5 py-1 text-xs font-semibold ${podcastHidden ? 'bg-slate-700 text-slate-200' : 'bg-emerald-900/70 text-emerald-200'}`}>
                  {podcastHidden ? 'Hidden' : 'Visible'}
                </span>
              </div>

              <button
                type="button"
                onClick={() => setPodcastVisibility(!podcastHidden)}
                className="mt-4 w-full rounded-xl bg-sky-600 px-4 py-3 font-semibold text-white transition hover:bg-sky-500"
              >
                {podcastHidden ? 'Show podcast button' : 'Hide podcast button'}
              </button>

              <p className="mt-3 text-xs leading-5 text-slate-500">
                You can also hide it instantly with the × beside the floating Podcasts button.
              </p>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
