import { afterEach, beforeEach, describe, expect, it, jest } from '@jest/globals';
import { fetchYoutubeMetadata, youtubeUrlsMatch } from '@/lib/youtubeMetadata';

describe('youtubeUrlsMatch', () => {
  it.each([
    ['same video, mobile vs short link', 'https://m.youtube.com/watch?v=abc', 'https://youtu.be/abc', true],
    ['same video, extra tracking params', 'https://www.youtube.com/watch?v=abc&t=10s', 'https://youtube.com/watch?v=abc', true],
    ['different videos', 'https://youtu.be/abc', 'https://youtu.be/xyz', false],
    ['video vs playlist', 'https://youtu.be/abc', 'https://www.youtube.com/playlist?list=PL1', false],
    ['different playlists', 'https://www.youtube.com/playlist?list=PL1', 'https://www.youtube.com/playlist?list=PL2', false],
    ['not a YouTube URL', 'https://vimeo.com/123', 'https://vimeo.com/123', false],
    ['empty input', '', 'https://youtu.be/abc', false],
    ['garbage input', 'not a url', 'https://youtu.be/abc', false],
  ])('%s -> %s', (_label, a, b, expected) => {
    expect(youtubeUrlsMatch(a, b)).toBe(expected);
    expect(youtubeUrlsMatch(b, a)).toBe(expected);
  });
});

describe('fetchYoutubeMetadata', () => {
  const fetchMock = jest.fn<typeof fetch>();
  const json = (body: object, ok = true) => ({ ok, json: async () => body }) as unknown as Response;
  const text = (body: string, ok = true) => ({ ok, text: async () => body }) as unknown as Response;

  beforeEach(() => {
    fetchMock.mockReset();
    global.fetch = fetchMock;
  });

  afterEach(() => {
    // @ts-expect-error restore the test environment's missing fetch
    delete global.fetch;
  });

  it('rejects links that are not YouTube videos or playlists, without any request', async () => {
    await expect(fetchYoutubeMetadata('https://example.com/video')).rejects.toThrow('valid YouTube');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('reports a video YouTube cannot find', async () => {
    fetchMock.mockResolvedValueOnce(json({}, false));
    await expect(fetchYoutubeMetadata('https://youtu.be/gone')).rejects.toThrow('could not be found');
  });

  it('returns the parsed title, author and duration for a video', async () => {
    fetchMock
      .mockResolvedValueOnce(json({ title: 'Dune by Frank Herbert | Full Audiobook', author_name: ' Book Channel ', thumbnail_url: 'https://thumb' }))
      .mockResolvedValueOnce(text('..."lengthSeconds":"4523"...'));

    await expect(fetchYoutubeMetadata(' https://youtu.be/abc ')).resolves.toEqual({
      youtubeUrl: 'https://www.youtube.com/watch?v=abc',
      title: 'Dune',
      author: 'Frank Herbert',
      durationLabel: '1:15:23',
      durationSeconds: 4523,
      thumbnailUrl: 'https://thumb',
      source: 'Book Channel',
      isPlaylist: false,
    });
    expect(fetchMock.mock.calls[0][0]).toContain(encodeURIComponent('https://www.youtube.com/watch?v=abc'));
  });

  it('falls back to the approximate duration in milliseconds', async () => {
    fetchMock.mockResolvedValueOnce(json({ title: 'T' })).mockResolvedValueOnce(text('"approxDurationMs":"845400"'));
    const result = await fetchYoutubeMetadata('https://youtu.be/abc');
    expect(result.durationSeconds).toBe(845);
    expect(result.durationLabel).toBe('14:05');
  });

  it('still succeeds without a duration when the page cannot be fetched', async () => {
    fetchMock.mockResolvedValueOnce(json({ title: 'T', author_name: 'A' })).mockRejectedValueOnce(new Error('network'));
    const result = await fetchYoutubeMetadata('https://youtu.be/abc');
    expect(result.durationSeconds).toBeUndefined();
    expect(result.durationLabel).toBeUndefined();

    fetchMock.mockResolvedValueOnce(json({ title: 'T' })).mockResolvedValueOnce(text('', false));
    expect((await fetchYoutubeMetadata('https://youtu.be/abc')).durationSeconds).toBeUndefined();
  });

  it('does not fetch the page for a playlist', async () => {
    fetchMock.mockResolvedValueOnce(json({ title: 'Series', author_name: 'Chan' }));
    const result = await fetchYoutubeMetadata('https://www.youtube.com/playlist?list=PL1');
    expect(result.isPlaylist).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('uses placeholders when YouTube omits the title or channel', async () => {
    fetchMock.mockResolvedValueOnce(json({})).mockResolvedValueOnce(text(''));
    const result = await fetchYoutubeMetadata('https://youtu.be/abc');
    expect(result.source).toBe('Unknown');
    expect(result.title).toBe('Untitled');
  });
});
