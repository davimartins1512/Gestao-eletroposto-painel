const CACHE='gestao-v5-cache-20261007';
const ASSETS=['./','./index.html','./painel.html','./carregar.html','./config.js','./project-ui.js','./manifest.webmanifest'];
const assetPaths=new Set(ASSETS.map(path=>new URL(path,self.registration.scope).pathname));
self.addEventListener('install',event=>event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(ASSETS)).then(()=>self.skipWaiting())));
self.addEventListener('activate',event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(key=>key.startsWith('gestao-v5-cache-')&&key!==CACHE).map(key=>caches.delete(key)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',event=>{
  const request=event.request,url=new URL(request.url);
  // Authenticated APIs and cross-origin responses must never enter a shared offline cache.
  if(request.method!=='GET'||url.origin!==self.location.origin||!assetPaths.has(url.pathname)||url.search||request.headers.has('authorization')||request.cache==='no-store')return;
  event.respondWith(fetch(request).then(async response=>{
    const directives=response.headers.get('cache-control')||'';
    if(response.ok&&!/no-store|private/i.test(directives)){
      const copy=response.clone();
      try{await caches.open(CACHE).then(cache=>cache.put(request,copy));}catch{/* storage failure must not discard a valid network response */}
    }
    return response;
  }).catch(async()=>{
    const stored=await caches.match(request);
    return stored||Response.error();
  }));
});
