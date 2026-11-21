import fs from 'node:fs';
import path from 'node:path';

describe('independent podcast topic recommendations', () => {
  beforeAll(() => {
    window.eval(fs.readFileSync(path.join(process.cwd(), 'public/book-podcast-player.js'), 'utf8'));
  });
  beforeEach(() => {
    document.body.innerHTML = '';
    localStorage.clear();
    jest.spyOn(Math, 'random').mockReturnValue(0);
  });
  afterEach(() => { document.body.innerHTML = ''; jest.restoreAllMocks(); });

  function player() {
    const element = document.createElement('book-podcast-player');
    document.body.appendChild(element);
    element.shadowRoot!.querySelector<HTMLButtonElement>('.launcher')!.click();
    return element;
  }
  function chooseTopic(element: HTMLElement, value: string) {
    const select = element.shadowRoot!.querySelector<HTMLSelectElement>('.topic')!;
    select.value = value;
    select.dispatchEvent(new Event('change'));
  }
  function different(element: HTMLElement) {
    element.shadowRoot!.querySelector<HTMLButtonElement>('.different')!.click();
  }
  function title(element: HTMLElement) { return element.shadowRoot!.querySelector('.title')!.textContent; }

  it('uses catalogue tags and preserves the currently playing media element when preferences change', () => {
    const element = player();
    const media = element.shadowRoot!.querySelector('audio,iframe');
    chooseTopic(element, 'crime fiction');
    expect(element.shadowRoot!.querySelector('audio,iframe')).toBe(media);
    different(element);
    expect(element.shadowRoot!.querySelector('.meta')!.textContent).toContain('crime fiction');
    expect(element.shadowRoot!.querySelector('.recommendation')!.textContent).toContain('Matched topic');
  });
  it('avoids current/recent episodes and falls back honestly after fresh matches are exhausted', () => {
    const element = player();
    chooseTopic(element, 'crime fiction');
    const seen = new Set<string | null>();
    for (let i = 0; i < 3; i++) {
      different(element);
      expect(element.shadowRoot!.querySelector('.meta')!.textContent).toContain('crime fiction');
      expect(seen.has(title(element))).toBe(false);
      seen.add(title(element));
    }
    different(element);
    expect(seen.has(title(element))).toBe(false);
    expect(element.shadowRoot!.querySelector('.recommendation')!.textContent).toContain('No fresh matches');
  });
  it('remembers the selected topic across reconnection', () => {
    const element = player();
    chooseTopic(element, 'literary fiction');
    element.remove();
    const restored = player();
    expect(restored.shadowRoot!.querySelector<HTMLSelectElement>('.topic')!.value).toBe('literary fiction');
  });
  it('handles invalid stored preferences and blocked storage without breaking selection', () => {
    localStorage.setItem('joshbooks-independent-podcast-v1', JSON.stringify({ topic: '<script>', recent: ['bad', -1] }));
    jest.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    const element = player();
    expect(element.shadowRoot!.querySelector<HTMLSelectElement>('.topic')!.value).toBe('');
    const previous = title(element);
    different(element);
    expect(title(element)).not.toBe(previous);
  });
});
