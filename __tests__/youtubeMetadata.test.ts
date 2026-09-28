import { describe, expect, it } from '@jest/globals';
import {
  fetchYoutubeMetadata,
  formatDurationSeconds,
  normalizeYoutubeUrl,
  parseAudiobookTitle,
  youtubeUrlsMatch,
} from '@/lib/youtubeMetadata';

describe('YouTube metadata helpers', () => {
  it('normalises watch and playlist URLs', () => {
    expect(normalizeYoutubeUrl('https://youtu.be/abc123_XYZ')).toBe(
      'https://www.youtube.com/watch?v=abc123_XYZ'
    );
    expect(
      normalizeYoutubeUrl('https://www.youtube.com/watch?v=abc123_XYZ&pp=ygUtest')
    ).toBe('https://www.youtube.com/watch?v=abc123_XYZ');
    expect(
      normalizeYoutubeUrl('https://www.youtube.com/playlist?list=PL123abc')
    ).toBe('https://www.youtube.com/playlist?list=PL123abc');
  });

  it('formats durations', () => {
    expect(formatDurationSeconds(4523)).toBe('1:15:23');
    expect(formatDurationSeconds(845)).toBe('14:05');
  });

  it('parses common audiobook title patterns', () => {
    expect(
      parseAudiobookTitle(
        'The Phoenix Project by Gene Kim | Full Audiobook',
        'Example Channel'
      )
    ).toEqual({ title: 'The Phoenix Project', author: 'Gene Kim' });

    expect(
      parseAudiobookTitle(
        'Passages from the Life of a Philosopher by Charles BABBAGE Part 1/3 | Full Audio Book',
        'LibriVox Audiobooks'
      )
    ).toEqual({
      title: 'Passages from the Life of a Philosopher',
      author: 'Charles BABBAGE',
    });
  });

  it('matches equivalent YouTube URLs', () => {
    expect(
      youtubeUrlsMatch(
        'https://www.youtube.com/watch?v=abc123_XYZ',
        'https://youtu.be/abc123_XYZ'
      )
    ).toBe(true);
    expect(
      youtubeUrlsMatch(
        'https://www.youtube.com/playlist?list=PL123abc',
        'https://www.youtube.com/playlist?list=PL123abc'
      )
    ).toBe(true);
  });


  it('does not match invalid or different YouTube resources', () => {
    expect(youtubeUrlsMatch('not youtube', 'https://youtu.be/abc123_XYZ')).toBe(false);
    expect(
      youtubeUrlsMatch(
        'https://youtu.be/abc123_XYZ',
        'https://youtu.be/def456_XYZ'
      )
    ).toBe(false);
    expect(
      youtubeUrlsMatch(
        'https://www.youtube.com/playlist?list=PL123abc',
        'https://www.youtube.com/playlist?list=PL999xyz'
      )
    ).toBe(false);
  });

  it('fetches oEmbed metadata and optional video duration', async () => {
    const originalFetch = globalThis.fetch;
    const fetchMock = jest
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          title: 'Clean Code by Robert C. Martin | Full Audiobook',
          author_name: 'Books Channel',
          thumbnail_url: 'https://img.example/cover.jpg',
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        text: async () => '{"lengthSeconds":"4523"}',
      });
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    try {
      await expect(
        fetchYoutubeMetadata('https://youtu.be/abc123_XYZ')
      ).resolves.toMatchObject({
        youtubeUrl: 'https://www.youtube.com/watch?v=abc123_XYZ',
        title: 'Clean Code',
        author: 'Robert C. Martin',
        durationSeconds: 4523,
        durationLabel: '1:15:23',
        source: 'Books Channel',
        isPlaylist: false,
      });
      expect(fetchMock).toHaveBeenCalledTimes(2);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('fetches playlist metadata without requesting a watch page', async () => {
    const originalFetch = globalThis.fetch;
    const fetchMock = jest.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        title: 'Classic Audiobooks',
        author_name: 'Library',
      }),
    });
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    try {
      const result = await fetchYoutubeMetadata(
        'https://www.youtube.com/playlist?list=PL123abc'
      );
      expect(result).toMatchObject({
        title: 'Classic Audiobooks',
        source: 'Library',
        isPlaylist: true,
      });
      expect(result.durationSeconds).toBeUndefined();
      expect(fetchMock).toHaveBeenCalledTimes(1);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('rejects invalid or unavailable YouTube resources', async () => {
    await expect(fetchYoutubeMetadata('https://example.com/nope')).rejects.toThrow(
      'Enter a valid YouTube'
    );

    const originalFetch = globalThis.fetch;
    globalThis.fetch = jest.fn().mockResolvedValue({
      ok: false,
      status: 404,
    }) as unknown as typeof fetch;
    try {
      await expect(
        fetchYoutubeMetadata('https://youtu.be/abc123_XYZ')
      ).rejects.toThrow('could not be found');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('keeps metadata when optional duration lookup fails', async () => {
    const originalFetch = globalThis.fetch;
    const fetchMock = jest
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          title: 'Simple Book',
          author_name: 'Reader',
        }),
      })
      .mockRejectedValueOnce(new Error('network'));
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    try {
      const result = await fetchYoutubeMetadata('https://youtu.be/abc123_XYZ');
      expect(result.title).toBe('Simple Book');
      expect(result.durationSeconds).toBeUndefined();
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

});
