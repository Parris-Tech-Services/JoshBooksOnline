(() => {
  const episodes = [
    { id: '42vmiZTrdB9cbDAKCQmBVv', title: 'Some people read the Great Books, so why not me?', show: 'What Should I Read Next?', tags: ['classics', 'reading life'] },
    { id: '7IAGrTkMf0ZcoIv0REqQap', title: "I can't read all the books, and that's okay", show: 'What Should I Read Next?', tags: ['reading life', 'bookshelves'] },
    { id: '5JzOAzU4kqIHybVsNJRGWF', title: 'Your favorite book discoveries from 10 years of WSIRN', show: 'What Should I Read Next?', tags: ['recommendations', 'reading life'] },
    { id: '1mW7Az5s0qMK1BrJYgeKF3', title: "Anne's Best Books of 2025", show: 'What Should I Read Next?', tags: ['recommendations', 'best books'] },
    { id: '2XOHCYRgNT65BekJqfU4wW', title: 'Get ready for a reading retreat', show: 'What Should I Read Next?', tags: ['reading habits', 'reading retreat'] },
    { id: '6te7r1uiMDnGvqtitHPBqp', title: 'Branching out with escapist, emotionally resonant novels', show: 'What Should I Read Next?', tags: ['fiction', 'recommendations'] },
    { id: '6DG2G9aOJSAZOckuAdL9cL', title: 'Books that take you on an epic journey', show: 'What Should I Read Next?', tags: ['epic fiction', 'recommendations'] },
    { id: '7FJHnyErjecA5On4cxIZ8h', title: 'Literary + Genre = match made in heaven', show: 'What Should I Read Next?', tags: ['genre fiction', 'literary fiction'] },
    { id: '00lq30NUsHE6zKcDjETMFh', title: 'Choosing book gifts your friends will love', show: 'What Should I Read Next?', tags: ['book gifts', 'recommendations'] },
    { id: '1GppQE50Xh23gopR454O3h', title: "Our team's best books of summer, Part 1", show: 'What Should I Read Next?', tags: ['recommendations', 'summer reading'] },
    { id: '072R11KrKRs0QcEldw3Rwn', title: 'Maggie O’Farrell on writing for the page and screen', show: 'What Should I Read Next?', tags: ['author interview', 'writing'] },
    { id: '7jXop7oRhTw2rePFx5lbc7', title: 'Marian Keyes', show: "The Queen's Reading Room Podcast", tags: ['author interview', 'reading life'] },
    { id: '7nPsc3tZBjBnmaZ6XmsDOu', title: 'Sir Ian Rankin', show: "The Queen's Reading Room Podcast", tags: ['author interview', 'crime fiction'] },
    { id: '6J01OMXw25v3WV9eExrBvK', title: 'Peter James', show: "The Queen's Reading Room Podcast", tags: ['author interview', 'crime fiction'] },
    { id: '1ASnd4tVDoH1dZVQeCpyKG', title: 'Richard E. Grant — Bonus Episode', show: "The Queen's Reading Room Podcast", tags: ['reading life', 'books'] },
    { id: '5V7fOaMGYVjaV3YoBPSX3f', title: 'Daisy Dunn and Andrew Hunter Murray', show: 'A Good Read', tags: ['book discussion', 'recommendations'] },
    { id: '7uXGLwv2fs5NYvxDuapecr', title: 'Julia Shaw and Hayaatun Sillem', show: 'A Good Read', tags: ['book discussion', 'recommendations'] },
    { id: '0AHazNQUupIeI6lPLleRf3', title: 'Colson Whitehead on The Underground Railroad', show: 'The Book Review', tags: ['author interview', 'literary fiction'] },
    { id: '6zvMpm4FtI8RsglKA2A6wu', title: 'The 100 Best Books of the 21st Century', show: 'The Book Review', tags: ['best books', 'criticism'] },
    { id: '0bbF7Dq6bl3g6RXCTtUwUw', title: 'Jennifer Egan on A Visit from the Goon Squad', show: 'The Book Review / The Interview', tags: ['author interview', 'literary fiction'] },
    { id: '70C8n8rx7HLLX5wWMfH8JQ', title: 'Patricia Cornwell on Her Dark Childhood and Best-Selling Novels', show: 'The Book Review', tags: ['author interview', 'crime fiction'] },
    { id: '6NDrAj4sxzaOOEgtz3xROd', title: '25 Years of 21st Century: Books', show: 'Front Row', tags: ['literature', '21st century'] },
    { id: '5unvrsOGsfXIieIhYauAm6', title: 'Min Jin Lee on Pachinko', show: 'The Book Review', tags: ['author interview', 'Pachinko'] },
    { id: '5gR4sNmfVtnWSZZNmkxW0h', title: 'How to Become a Reader of Good Books', show: 'Strong Women', tags: ['reading habits', 'good books'] },
    { id: '2hJ8bVhwhxLURYWWwXScW2', title: 'Summer books 2025', show: 'Backlisted', tags: ['recommendations', 'summer reading'] }
  ];

  const STORAGE_KEY = 'joshbooks-independent-podcast-v1';
  const VISIBILITY_KEY = 'joshbooks-podcast-button-hidden-v1';
  const VISIBILITY_EVENT = 'joshbooks:podcast-visibility';
  const VISIBILITY_CHANGED_EVENT = 'joshbooks:podcast-visibility-changed';

  class BookPodcastPlayer extends HTMLElement {
    constructor() {
      super();
      this.attachShadow({ mode: 'open' });
      this.open = false;
      this.currentIndex = 0;
      this.recent = [];
      this.hiddenByUser = false;

      try {
        const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}');
        if (Number.isInteger(saved.currentIndex) && saved.currentIndex >= 0 && saved.currentIndex < episodes.length) {
          this.currentIndex = saved.currentIndex;
        }
        if (Array.isArray(saved.recent)) {
          this.recent = saved.recent.filter((n) => Number.isInteger(n) && n >= 0 && n < episodes.length).slice(0, 6);
        }
        this.hiddenByUser = localStorage.getItem(VISIBILITY_KEY) === '1';
      } catch (_) {}

      this.handleExternalPlay = (event) => {
        const media = event.target;
        if (media instanceof HTMLAudioElement || media instanceof HTMLVideoElement) {
          this.open = false;
          this.render();
        }
      };

      this.handleVisibilityRequest = (event) => {
        const hidden = Boolean(event && event.detail && event.detail.hidden);
        this.setHidden(hidden);
      };
    }

    connectedCallback() {
      document.addEventListener('play', this.handleExternalPlay, true);
      window.addEventListener(VISIBILITY_EVENT, this.handleVisibilityRequest);
      this.render();
    }

    disconnectedCallback() {
      document.removeEventListener('play', this.handleExternalPlay, true);
      window.removeEventListener(VISIBILITY_EVENT, this.handleVisibilityRequest);
    }

    save() {
      try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify({ currentIndex: this.currentIndex, recent: this.recent.slice(0, 6) }));
      } catch (_) {}
    }

    setHidden(hidden) {
      this.hiddenByUser = Boolean(hidden);
      if (this.hiddenByUser) this.open = false;
      try {
        localStorage.setItem(VISIBILITY_KEY, this.hiddenByUser ? '1' : '0');
      } catch (_) {}
      window.dispatchEvent(new CustomEvent(VISIBILITY_CHANGED_EVENT, { detail: { hidden: this.hiddenByUser } }));
      this.render();
    }

    chooseDifferent() {
      const excluded = new Set([this.currentIndex, ...this.recent]);
      let candidates = episodes.map((_, index) => index).filter((index) => !excluded.has(index));
      if (!candidates.length) candidates = episodes.map((_, index) => index).filter((index) => index !== this.currentIndex);
      if (!candidates.length) candidates = [0];
      const previous = this.currentIndex;
      this.currentIndex = candidates[Math.floor(Math.random() * candidates.length)];
      if (previous !== this.currentIndex) this.recent = [previous, ...this.recent.filter((index) => index !== previous)].slice(0, 6);
      this.save();
    }

    render() {
      const current = episodes[this.currentIndex];
      this.shadowRoot.innerHTML = `
        <style>
          :host{position:relative;z-index:2147483000;font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
          button,a{font:inherit}
          .launcher-wrap{position:fixed;left:50%;bottom:max(12px,env(safe-area-inset-bottom));transform:translateX(-50%);z-index:2147483000;display:flex;align-items:center;gap:6px}
          .launcher{border:1px solid rgba(255,255,255,.16);border-radius:999px;background:#2a2019;color:#fff8ec;padding:12px 18px;font-weight:750;box-shadow:0 12px 34px rgba(0,0,0,.34);cursor:pointer;touch-action:manipulation;white-space:nowrap}
          .hide-launcher{width:34px;height:34px;flex:0 0 34px;border:1px solid rgba(255,255,255,.18);border-radius:50%;background:#17120f;color:#f0e3d4;font-size:20px;line-height:1;box-shadow:0 10px 26px rgba(0,0,0,.3);cursor:pointer;touch-action:manipulation}
          .panel{position:fixed;left:50%;bottom:max(8px,env(safe-area-inset-bottom));transform:translateX(-50%);z-index:2147483000;width:min(620px,calc(100vw - 16px));box-sizing:border-box;border:1px solid #705a49;border-radius:18px;background:#211812;color:#fff8ec;padding:14px;box-shadow:0 20px 60px rgba(0,0,0,.58)}
          .head{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;margin-bottom:10px}.kicker{font-size:11px;font-weight:800;letter-spacing:.08em;text-transform:uppercase;color:#e7bc83}.title{font-size:16px;line-height:1.3;margin:4px 0 0}.meta{font-size:12px;line-height:1.45;color:#dacbbb;margin:5px 0 0}.close{width:40px;height:40px;flex:0 0 40px;border:1px solid #705a49;border-radius:50%;background:#38281e;color:#fff8ec;font-size:22px;cursor:pointer;touch-action:manipulation}.frame{display:block;width:100%;height:152px;border:0;border-radius:12px;background:#0d0907}.actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}.action,.link{border-radius:10px;padding:9px 12px;font-weight:750;text-decoration:none;cursor:pointer;touch-action:manipulation}.action{border:0;background:#8a5c30;color:#fff}.link{display:inline-flex;align-items:center;border:1px solid #705a49;background:#38281e;color:#fff8ec}.note{font-size:11px;color:#baa997;margin:9px 0 0}
          @media(max-width:640px){.panel{width:calc(100vw - 10px);padding:11px}.actions>*{flex:1;justify-content:center;text-align:center}.launcher{padding:11px 15px}.hide-launcher{width:32px;height:32px;flex-basis:32px}}
        </style>
        ${this.hiddenByUser && !this.open ? '' : this.open ? `
          <aside class="panel" aria-label="JoshBooks podcast player">
            <div class="head"><div><div class="kicker">JoshBooks · reading radio</div><h2 class="title"></h2><p class="meta"></p></div><button class="close" type="button" aria-label="Close podcast player">×</button></div>
            <iframe class="frame" title="Spotify podcast episode" loading="lazy" allow="autoplay; clipboard-write; encrypted-media; fullscreen; picture-in-picture"></iframe>
            <div class="actions"><button class="action different" type="button">📚 Different podcast</button><a class="link" target="_blank" rel="noopener noreferrer">Open in Spotify ↗</a></div>
            <p class="note">Independent 25-episode book podcast bank stored inside JoshBooksOnline.</p>
          </aside>` : '<div class="launcher-wrap"><button class="launcher" type="button" aria-label="Open book podcasts">🎧 Podcasts</button><button class="hide-launcher" type="button" aria-label="Hide podcast button" title="Hide podcast button">×</button></div>'}
      `;

      if (this.hiddenByUser && !this.open) return;

      if (!this.open) {
        this.shadowRoot.querySelector('.launcher').addEventListener('click', () => {
          this.open = true;
          this.render();
        });
        this.shadowRoot.querySelector('.hide-launcher').addEventListener('click', (event) => {
          event.stopPropagation();
          this.setHidden(true);
        });
        return;
      }

      this.shadowRoot.querySelector('.title').textContent = current.title;
      this.shadowRoot.querySelector('.meta').textContent = `${current.show} · ${current.tags.join(' · ')}`;
      const frame = this.shadowRoot.querySelector('.frame');
      frame.src = `https://open.spotify.com/embed/episode/${encodeURIComponent(current.id)}?theme=0`;
      frame.title = `Spotify episode: ${current.title}`;
      const link = this.shadowRoot.querySelector('.link');
      link.href = `https://open.spotify.com/episode/${encodeURIComponent(current.id)}`;
      this.shadowRoot.querySelector('.close').addEventListener('click', () => {
        this.open = false;
        this.render();
      });
      this.shadowRoot.querySelector('.different').addEventListener('click', () => {
        this.chooseDifferent();
        this.render();
      });
    }
  }

  if (!customElements.get('book-podcast-player')) {
    customElements.define('book-podcast-player', BookPodcastPlayer);
  }

  const mount = () => {
    if (!document.querySelector('book-podcast-player')) {
      document.body.appendChild(document.createElement('book-podcast-player'));
    }
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount, { once: true });
  else mount();
})();
