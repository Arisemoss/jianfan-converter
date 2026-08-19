// 文简书斋 Service Worker
// 提供离线缓存 + 静态资源加速
var CACHE_NAME = 'wenjian-v1';
var PRECACHE = [
  './',
  './ebook-tool.html',
  './manifest.json',
  './dict/dict.js'
];

self.addEventListener('install', function(e) {
  e.waitUntil(
    caches.open(CACHE_NAME).then(function(cache) {
      return cache.addAll(PRECACHE);
    })
  );
  self.skipWaiting();
});

self.addEventListener('activate', function(e) {
  e.waitUntil(
    caches.keys().then(function(names) {
      return Promise.all(
        names.filter(function(n) { return n !== CACHE_NAME; })
             .map(function(n) { return caches.delete(n); })
      );
    })
  );
  self.clients.claim();
});

self.addEventListener('fetch', function(e) {
  // 仅缓存同源请求
  if (e.request.url.indexOf(self.location.origin) !== 0) return;

  e.respondWith(
    caches.match(e.request).then(function(cached) {
      if (cached) return cached;
      return fetch(e.request).then(function(response) {
        // 成功响应才缓存
        if (!response || response.status !== 200) return response;
        var clone = response.clone();
        caches.open(CACHE_NAME).then(function(cache) {
          cache.put(e.request, clone);
        });
        return response;
      });
    }).catch(function() {
      // 离线时返回离线页面
      if (e.request.mode === 'navigate') {
        return caches.match('./ebook-tool.html');
      }
    })
  );
});
