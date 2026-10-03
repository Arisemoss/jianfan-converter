// 文简书斋 Service Worker
// 提供离线缓存 + 静态资源加速
var CACHE_NAME = 'wenjian-v2';
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
    }).then(function() { return self.clients.claim(); })
  );
});

self.addEventListener('fetch', function(e) {
  var req = e.request;
  // 仅缓存同源请求（用 URL.origin 比较，避免字符串前缀被 site.com.evil.com 绕过）
  try {
    if (new URL(req.url).origin !== self.location.origin) return;
  } catch (err) { return; }
  // Cache API 只接受 GET
  if (req.method !== 'GET') return;

  // 导航请求：网络优先，失败回退缓存 —— 保证新版本页面能到达回访用户
  if (req.mode === 'navigate') {
    e.respondWith(
      fetch(req).then(function(response) {
        if (response && response.status === 200) {
          var clone = response.clone();
          e.waitUntil(
            caches.open(CACHE_NAME).then(function(cache) { return cache.put(req, clone); })
          );
        }
        return response;
      }).catch(function() {
        return caches.match(req).then(function(cached) {
          return cached || caches.match('./ebook-tool.html');
        });
      })
    );
    return;
  }

  // 其余静态资源：缓存优先，未命中时联网获取并写入缓存
  e.respondWith(
    caches.match(req).then(function(cached) {
      if (cached) return cached;
      return fetch(req).then(function(response) {
        if (!response || response.status !== 200) return response;
        var clone = response.clone();
        e.waitUntil(
          caches.open(CACHE_NAME).then(function(cache) { return cache.put(req, clone); })
        );
        return response;
      });
    }).catch(function() {
      return undefined;
    })
  );
});
