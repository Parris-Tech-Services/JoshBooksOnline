const SHELL_CACHE='joshbooks-podcast-shell-v1';
const AUDIO_CACHE='joshbooks-podcast-audio-v1';
const ROOT='/';

async function cacheShell(){
  const cache=await caches.open(SHELL_CACHE);
  const response=await fetch(ROOT,{cache:'reload'});
  if(!response.ok)throw new Error('Unable to cache JoshBooks shell');
  await cache.put(ROOT,response.clone());
  const html=await response.text();
  const urls=new Set([ROOT,'/book-podcast-player.js?v=3']);
  for(const match of html.matchAll(/(?:src|href)=["']([^"'#]+)["']/g)){
    try{const u=new URL(match[1],self.location.origin);if(u.origin===self.location.origin)urls.add(u.pathname+u.search)}catch{}
  }
  await Promise.all([...urls].map(async u=>{if(u===ROOT)return;try{const r=await fetch(u,{cache:'reload'});if(r.ok)await cache.put(u,r)}catch{}}));
}
self.addEventListener('install',event=>event.waitUntil(cacheShell().then(()=>self.skipWaiting())));
self.addEventListener('activate',event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k.startsWith('joshbooks-podcast-')&&![SHELL_CACHE,AUDIO_CACHE].includes(k)).map(k=>caches.delete(k)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',event=>{
 const request=event.request;if(request.method!=='GET')return;
 if(request.destination==='audio'){event.respondWith(caches.open(AUDIO_CACHE).then(c=>c.match(request,{ignoreVary:true})).then(hit=>hit||fetch(request)));return}
 const url=new URL(request.url);if(url.origin!==self.location.origin)return;
 if(request.mode==='navigate'){event.respondWith(fetch(request).then(r=>{if(r.ok)caches.open(SHELL_CACHE).then(c=>c.put(request,r.clone())).catch(()=>{});return r}).catch(async()=>{const c=await caches.open(SHELL_CACHE);return(await c.match(request))||(await c.match(ROOT))}));return}
 event.respondWith(caches.match(request).then(hit=>hit||fetch(request).then(r=>{if(r.ok)caches.open(SHELL_CACHE).then(c=>c.put(request,r.clone())).catch(()=>{});return r})));
});
