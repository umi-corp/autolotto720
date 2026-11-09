// 테마는 <head> 의 인라인 스크립트가 먼저 정한다(직접 고른 값 → 시스템 설정)
(() => {
  const root = document.documentElement;

  const store = {
    get: () => { try { return localStorage.getItem('theme'); } catch { return null; } },
    set: (v) => { try { localStorage.setItem('theme', v); } catch { /* 저장 못 해도 이번 화면은 바뀐다 */ } },
  };
  document.querySelectorAll('[data-theme-toggle]').forEach((btn) => btn.addEventListener('click', () => {
    root.dataset.theme = root.dataset.theme === 'light' ? 'dark' : 'light';
    store.set(root.dataset.theme);
  }));
  matchMedia('(prefers-color-scheme: light)').addEventListener?.('change', (e) => {
    if (!store.get()) root.dataset.theme = e.matches ? 'light' : 'dark';
  });

  const nav = document.querySelector('.site-nav');
  if (nav) {
    const onScroll = () => nav.classList.toggle('scrolled', scrollY > 8);
    onScroll();
    addEventListener('scroll', onScroll, { passive: true });
  }

  // 방문마다 조(1~5)와 6자리 번호를 새로 뽑는다
  const rand = (n) => Math.floor(Math.random() * n);
  document.querySelectorAll('[data-ticket]').forEach((list) => {
    const jo = list.querySelector('.jo');
    jo.textContent = jo.textContent.replace(/\d/, 1 + rand(5));
    list.querySelectorAll('.pb').forEach((b) => { b.textContent = rand(10); });
  });

  // 다운로드 링크·버전을 최신 릴리스로 바꾼다. 실패하면 릴리스 페이지 링크가 그대로 남는다
  const links = document.querySelectorAll('[data-apk]');
  if (links.length) {
    fetch('https://api.github.com/repos/umi-corp/autolotto720/releases/latest')
      .then((r) => (r.ok ? r.json() : Promise.reject(r.status)))
      .then((rel) => {
        const apks = (rel.assets || []).filter((a) => a.name.endsWith('.apk'));
        const apk = apks.find((a) => a.name.includes('universal')) || apks[0];
        if (!apk) return;
        links.forEach((a) => { a.href = apk.browser_download_url; });
        const meta = `${rel.tag_name} · Android 12+ · ${(apk.size / 1048576).toFixed(1)}MB`;
        document.querySelectorAll('[data-apk-meta]').forEach((el) => { el.textContent = meta; });
      })
      .catch(() => {});
  }
})();
