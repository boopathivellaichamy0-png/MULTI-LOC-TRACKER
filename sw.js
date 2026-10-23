// QuantumGPS Service Worker v2 — Always-On Survival Engine
const CACHE_NAME   = 'antigravity-location-v2';
const OFFLINE_URLS = ['/', '/login.html', '/share.html', '/index.html', '/client.html', '/manifest.json'];

// ── Install: cache all pages for offline access ──
self.addEventListener('install', (event) => {
    self.skipWaiting();
    event.waitUntil(
        caches.open(CACHE_NAME).then(cache => cache.addAll(OFFLINE_URLS))
    );
});

self.addEventListener('activate', (event) => {
    // Clean old caches
    event.waitUntil(
        caches.keys().then(keys =>
            Promise.all(keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k)))
        ).then(() => self.clients.claim())
    );
});

// ── Fetch: serve from cache when offline ──
self.addEventListener('fetch', (event) => {
    if (event.request.url.includes('/api/')) return; // Don't cache API calls
    event.respondWith(
        fetch(event.request).catch(() =>
            caches.match(event.request).then(r => r || caches.match('/share.html'))
        )
    );
});

// ── Strategy 4: Background Sync — flush offline location queue ──
self.addEventListener('sync', (event) => {
    if (event.tag === 'send-location') {
        event.waitUntil(flushOfflineLocationQueue());
    }
});

// ── Strategy: Periodic Background Sync (Chrome Android) ──
self.addEventListener('periodicsync', (event) => {
    if (event.tag === 'location-sync') {
        event.waitUntil(syncBackgroundLocation());
    }
});

async function flushOfflineLocationQueue() {
    const db     = await openQueueDB();
    const queue  = await getAllFromDB(db);
    const failed = [];

    for (const item of queue) {
        try {
            const res = await fetch('/api/update', {
                method:  'POST',
                headers: { 'Content-Type': 'application/json' },
                body:    JSON.stringify(item)
            });
            if (!res.ok) failed.push(item);
        } catch {
            failed.push(item);
        }
    }

    // Re-queue failed items
    await clearDB(db);
    for (const item of failed) await putInDB(db, item);

    console.log(`[SW] Flushed queue: ${queue.length - failed.length} sent, ${failed.length} pending`);
}

async function syncBackgroundLocation() {
    // Notify all open clients to resume GPS if they stopped
    const clients = await self.clients.matchAll({ type: 'window' });
    clients.forEach(client => client.postMessage({ type: 'BG_SYNC_WAKE' }));
}

// ── Push Notifications (from admin) ──
self.addEventListener('push', (event) => {
    const data = event.data?.json() || {};
    const title  = data.title  || '📍 QuantumGPS';
    const body   = data.body   || 'Location update';
    const icon   = '/manifest.json';

    event.waitUntil(
        self.registration.showNotification(title, {
            body,
            icon,
            badge: icon,
            vibrate: [200, 100, 200],
            data:   { url: data.url || '/share.html' }
        })
    );
});

self.addEventListener('notificationclick', (event) => {
    event.notification.close();
    event.waitUntil(
        clients.openWindow(event.notification.data?.url || '/share.html')
    );
});

// ── Simple IndexedDB helpers for offline queue ──
function openQueueDB() {
    return new Promise((resolve, reject) => {
        const req = indexedDB.open('quantum_gps_queue', 1);
        req.onupgradeneeded = e => e.target.result.createObjectStore('queue', { autoIncrement: true });
        req.onsuccess = e => resolve(e.target.result);
        req.onerror   = reject;
    });
}
function getAllFromDB(db) {
    return new Promise((resolve, reject) => {
        const tx  = db.transaction('queue', 'readonly');
        const req = tx.objectStore('queue').getAll();
        req.onsuccess = e => resolve(e.target.result || []);
        req.onerror   = reject;
    });
}
function putInDB(db, item) {
    return new Promise((resolve, reject) => {
        const tx  = db.transaction('queue', 'readwrite');
        const req = tx.objectStore('queue').add(item);
        req.onsuccess = resolve;
        req.onerror   = reject;
    });
}
function clearDB(db) {
    return new Promise((resolve, reject) => {
        const tx  = db.transaction('queue', 'readwrite');
        const req = tx.objectStore('queue').clear();
        req.onsuccess = resolve;
        req.onerror   = reject;
    });
}
