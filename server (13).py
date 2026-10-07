import os
import re
import json
import random
import threading
import requests
from io import BytesIO
from datetime import datetime
from flask import Flask, render_template_string, request, jsonify, Response

from cloud import cloud_bp

app = Flask(__name__)
app.secret_key = os.environ.get('FLASK_SECRET', os.urandom(32).hex())

VK_API = "https://api.vk.com/method"
API_VERSION = "5.199"

FIREBASE_DB_URL = os.environ.get('FIREBASE_DB_URL', 'https://meow-874ce-default-rtdb.europe-west1.firebasedatabase.app')
FIREBASE_API_KEY = os.environ.get('FIREBASE_API_KEY', '')

KEYS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'keys_storage.json')

_session_local = threading.local()

def get_session():
    if not hasattr(_session_local, 'session'):
        _session_local.session = requests.Session()
    return _session_local.session

def load_local_keys():
    if os.path.exists(KEYS_FILE):
        try:
            with open(KEYS_FILE, 'r', encoding='utf-8') as f:
                return json.load(f)
        except Exception as e:
            print("Local key load error:", e)
            return {}
    return {}

def save_local_keys(data):
    try:
        with open(KEYS_FILE, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
    except Exception as e:
        print("Local key save error:", e)

def firebase_get(path):
    if not FIREBASE_DB_URL:
        return None
    url = f"{FIREBASE_DB_URL.rstrip('/')}/{path}.json"
    if FIREBASE_API_KEY:
        url += f"?auth={FIREBASE_API_KEY}"
    try:
        resp = get_session().get(url, timeout=5)
        if resp.status_code == 200:
            return resp.json()
    except Exception as e:
        print("Firebase GET error:", e)
    return None

def firebase_put(path, data):
    if not FIREBASE_DB_URL:
        return False
    url = f"{FIREBASE_DB_URL.rstrip('/')}/{path}.json"
    if FIREBASE_API_KEY:
        url += f"?auth={FIREBASE_API_KEY}"
    try:
        resp = get_session().put(url, json=data, timeout=5)
        return resp.status_code == 200
    except Exception as e:
        print("Firebase PUT error:", e)
    return False

def get_stored_pub_key(vk_id):
    vk_id_str = str(vk_id)
    if FIREBASE_DB_URL:
        fb_data = firebase_get(f"public_keys/{vk_id_str}")
        if fb_data and isinstance(fb_data, dict) and 'public_key' in fb_data:
            return fb_data
    local_data = load_local_keys()
    return local_data.get(f"pub_{vk_id_str}")

def store_pub_key(vk_id, data):
    vk_id_str = str(vk_id)
    local_data = load_local_keys()
    local_data[f"pub_{vk_id_str}"] = data
    save_local_keys(local_data)

    if FIREBASE_DB_URL and 'public_key' in data:
        firebase_put(f"public_keys/{vk_id_str}", {
            'public_key': data['public_key'],
            'created_at': data.get('created_at', datetime.now().isoformat())
        })

def get_stored_priv_key(vk_id):
    vk_id_str = str(vk_id)
    if FIREBASE_DB_URL:
        fb_data = firebase_get(f"private_keys/{vk_id_str}")
        if fb_data and isinstance(fb_data, dict) and 'private_key_enc' in fb_data:
            return fb_data
    local_data = load_local_keys()
    return local_data.get(f"priv_{vk_id_str}")

def store_priv_key(vk_id, data):
    vk_id_str = str(vk_id)
    local_data = load_local_keys()
    local_data[f"priv_{vk_id_str}"] = data
    save_local_keys(local_data)

    if FIREBASE_DB_URL and 'private_key_enc' in data:
        firebase_put(f"private_keys/{vk_id_str}", {
            'private_key_enc': data['private_key_enc'],
            'created_at': data.get('created_at', datetime.now().isoformat())
        })

def vk_request(method, token, **params):
    params['access_token'] = token
    params['v'] = API_VERSION
    try:
        resp = get_session().get(f"{VK_API}/{method}", params=params, timeout=10)
        data = resp.json()
        return data.get('response', data.get('error'))
    except Exception as e:
        return {'error': str(e)}

def format_last_seen(u):
    online = u.get('online', 0)
    sex = u.get('sex', 1)
    if online == 1:
        return "в сети"
    
    last_seen = u.get('last_seen', {})
    time_sec = last_seen.get('time')
    if time_sec:
        dt = datetime.fromtimestamp(time_sec)
        now = datetime.now()
        verb = "была" if sex == 1 else "был"
        if dt.date() == now.date():
            return f"{verb} в сети в {dt.strftime('%H:%M')}"
        elif (now.date() - dt.date()).days == 1:
            return f"{verb} в сети вчера в {dt.strftime('%H:%M')}"
        else:
            return f"{verb} в сети {dt.strftime('%d.%m в %H:%M')}"
    return "не в сети"

def extract_doc_attachment(save_result):
    if not save_result or (isinstance(save_result, dict) and 'error' in save_result):
        return None
    
    if isinstance(save_result, list) and len(save_result) > 0:
        d = save_result[0]
        if isinstance(d, dict) and 'owner_id' in d and 'id' in d:
            return f"doc{d['owner_id']}_{d['id']}"

    if isinstance(save_result, dict):
        if 'doc' in save_result:
            d = save_result['doc']
            if isinstance(d, dict) and 'owner_id' in d and 'id' in d:
                return f"doc{d['owner_id']}_{d['id']}"
        if 'audio_message' in save_result:
            d = save_result['audio_message']
            if isinstance(d, dict) and 'owner_id' in d and 'id' in d:
                return f"doc{d['owner_id']}_{d['id']}"
        if 'owner_id' in save_result and 'id' in save_result:
            return f"doc{save_result['owner_id']}_{save_result['id']}"
            
    return None

SW_JS = """
const CACHE_NAME = 'vk-tsuyu-v1-cache';
const STATIC_ASSETS = ['/', '/sw.js', '/api/ping'];

// IndexedDB for offline messages
const DB_NAME = 'vk-meow-db';
const DB_VERSION = 1;

function openDB() {
    return new Promise((resolve, reject) => {
        const req = indexedDB.open(DB_NAME, DB_VERSION);
        req.onerror = () => reject(req.error);
        req.onsuccess = () => resolve(req.result);
        req.onupgradeneeded = (e) => {
            const db = e.target.result;
            if (!db.objectStoreNames.contains('messages')) {
                db.createObjectStore('messages', { keyPath: 'id' });
            }
            if (!db.objectStoreNames.contains('dialogs')) {
                db.createObjectStore('dialogs', { keyPath: 'id' });
            }
        };
    });
}

self.addEventListener('install', (evt) => {
    evt.waitUntil(
        caches.open(CACHE_NAME).then((cache) => cache.addAll(STATIC_ASSETS))
    );
    self.skipWaiting();
});

self.addEventListener('activate', (evt) => {
    evt.waitUntil(
        caches.keys().then((keys) =>
            Promise.all(keys.map((k) => k !== CACHE_NAME && caches.delete(k)))
        )
    );
    self.clients.claim();
});

self.addEventListener('fetch', (evt) => {
    const url = evt.request.url;

    // API requests - network first, cache fallback
    if (url.includes('/api/')) {
        evt.respondWith(
            fetch(evt.request).then(response => {
                if (response.ok && evt.request.method === 'GET') {
                    const clone = response.clone();
                    caches.open(CACHE_NAME).then(cache => cache.put(evt.request, clone));
                }
                return response;
            }).catch(() => {
                return caches.match(evt.request).then(cached => {
                    return cached || new Response(JSON.stringify({error: 'offline'}), {
                        headers: {'Content-Type': 'application/json'}
                    });
                });
            })
        );
        return;
    }

    // Images and media - cache first
    if (
        evt.request.destination === 'image' ||
        url.includes('/api/proxy_file') ||
        url.includes('vk.com/images/') ||
        url.includes('userapi.com/') ||
        url.includes('pp.vk.me/') ||
        url.includes('sun9-')
    ) {
        evt.respondWith(
            caches.open(CACHE_NAME).then(async (cache) => {
                const cached = await cache.match(evt.request);
                if (cached) return cached;
                try {
                    const response = await fetch(evt.request);
                    if (response.status === 200) {
                        cache.put(evt.request, response.clone());
                    }
                    return response;
                } catch (e) {
                    return cached || new Response('', {status: 404});
                }
            })
        );
        return;
    }

    // Static assets - stale-while-revalidate
    evt.respondWith(
        caches.match(evt.request).then(cached => {
            const fetchPromise = fetch(evt.request).then(response => {
                if (response.ok) {
                    caches.open(CACHE_NAME).then(cache => cache.put(evt.request, response.clone()));
                }
                return response;
            }).catch(() => cached);
            return cached || fetchPromise;
        })
    );
});
"""

HTML = """
<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>VK Tsuyu - True E2EE Messenger</title>
<style>
*{margin:0;padding:0;box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;background:#000;color:#fff;height:100vh;overflow:hidden;-webkit-font-smoothing:antialiased}
.app{height:100vh;display:flex;flex-direction:column;position:relative;overflow:hidden}

/* Login Screen */
.login-screen{position:fixed;top:0;left:0;width:100%;height:100%;background:#000;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:20px;z-index:800;animation:fadeIn 0.2s ease-out}
.login-screen h1{font-size:32px;margin-bottom:6px;font-weight:900;color:#fff;letter-spacing:1.5px}
.login-screen p{color:#888;margin-bottom:24px;font-size:13px;text-align:center;max-width:320px}
.badge-e2e{background:#1c1c1e;color:#8e8e93;border:1px solid #2c2c2e;padding:6px 12px;border-radius:14px;font-size:12px;font-weight:600;margin-bottom:20px;display:inline-flex;align-items:center;gap:6px}
.token-input,.pass-input{width:100%;max-width:360px;padding:14px 16px;border:none;border-radius:14px;background:#161616;color:#fff;font-size:15px;margin-bottom:12px;outline:none;border:1px solid #2c2c2c;transition:border-color 0.2s}
.token-input:focus,.pass-input:focus{border-color:#555}
.token-input::placeholder,.pass-input::placeholder{color:#666}
.btn{width:100%;max-width:360px;padding:14px;border:none;border-radius:14px;background:#fff;color:#000;font-size:16px;font-weight:600;cursor:pointer;margin-bottom:8px;transition:all 0.1s active}
.btn:active{transform:scale(0.97);opacity:.85}
.btn-secondary{background:transparent;color:#fff;border:1px solid #333}
.btn-danger{background:#ff3b30;color:#fff}

/* Header */
.header{height:56px;background:#0d0d0d;display:flex;align-items:center;padding:0 12px;border-bottom:1px solid #1c1c1c;flex-shrink:0}
.header-menu-btn{width:40px;height:40px;display:flex;align-items:center;justify-content:center;cursor:pointer;border-radius:50%;margin-right:10px;background:rgba(255,255,255,0.08);color:#fff;flex-shrink:0}
.header-menu-btn:active{background:rgba(255,255,255,0.2)}
.header-back{width:40px;height:40px;display:flex;align-items:center;justify-content:center;cursor:pointer;border-radius:50%;margin-right:6px;background:rgba(255,255,255,0.1);color:#fff;flex-shrink:0}
.header-back svg{width:22px;height:22px;stroke:#fff;stroke-width:2.5px;fill:none}
.header-back:active{background:rgba(255,255,255,0.25)}
.header-avatar{width:38px;height:38px;border-radius:50%;object-fit:cover;margin-right:10px;background:#222;flex-shrink:0;cursor:pointer}
.header-info{flex:1;min-width:0;cursor:pointer}

.header-title-main{font-size:20px;font-weight:900;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;letter-spacing:0.8px;color:#fff}
.header-title{font-size:16px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;letter-spacing:0.5px}

.header-subtitle{font-size:12px;color:#8e8e93;display:flex;align-items:center;gap:4px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.header-actions{display:flex;gap:6px;align-items:center}
.header-btn{width:38px;height:38px;display:flex;align-items:center;justify-content:center;border-radius:50%;cursor:pointer;color:#aaa;background:rgba(255,255,255,0.05);border:none;outline:none}
.header-btn:active{background:rgba(255,255,255,0.15);color:#fff}
.header-btn.active{color:#fff;background:rgba(255,255,255,0.15)}

/* Mark Read Header Action Button */
.mark-read-btn{background:#0a84ff;color:#fff;font-size:12px;font-weight:600;padding:6px 12px;border-radius:14px;border:none;cursor:pointer;display:flex;align-items:center;gap:4px}
.mark-read-btn:active{opacity:0.8}

/* Search Bar */
.search-bar-container{padding:8px 12px;background:#0d0d0d;border-bottom:1px solid #1a1a1a}
.search-input-wrap{display:flex;align-items:center;background:#1c1c1e;border-radius:12px;padding:0 12px;border:1px solid #2a2a2c;width:100%}
.search-input-wrap svg{color:#8e8e93;flex-shrink:0;margin-right:8px}
.search-input{flex:1;background:transparent;border:none;padding:10px 0;color:#fff;font-size:14px;outline:none;font-family:inherit}
.search-input::placeholder{color:#666}

/* Global Search Section Header */
.search-section-header{padding:12px 14px 6px;font-size:12px;font-weight:700;color:#ffffff;text-transform:uppercase;letter-spacing:0.8px;opacity:0.9}

/* Dialogs Screen */
.dialogs-screen{position:relative;z-index:1;flex:1;display:flex;flex-direction:column;overflow:hidden;animation:fadeIn 0.15s ease-out}
.dialogs-list{flex:1;overflow-y:auto;-webkit-overflow-scrolling:touch}
.dialog{display:flex;align-items:center;padding:12px 14px;cursor:pointer;border-bottom:1px solid #111;position:relative}
.dialog:active{background:#111}
.dialog-avatar-wrap{position:relative;width:50px;height:50px;flex-shrink:0;margin-right:12px}
.dialog-avatar{width:50px;height:50px;border-radius:50%;object-fit:cover;background:#222;display:block}
.dialog-online-dot{position:absolute;bottom:2px;right:2px;width:14px;height:14px;border-radius:50%;background:#34c759;border:2.5px solid #000;z-index:2}
.dialog-online-dot.offline{background:#8e8e93}
.dialog-unread-blue{min-width:20px;height:20px;border-radius:50%;background:#0a84ff;color:#fff;font-size:11px;font-weight:700;display:flex;align-items:center;justify-content:center;padding:0 6px;flex-shrink:0}

.dialog-pin-icon{position:absolute;top:10px;right:14px;color:#8e8e93;font-size:12px}

/* TG Style Dialog Preview Thumbs */
.dialog-preview-wrap{display:flex;align-items:center;gap:6px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1;font-size:13px;color:#888}
.dialog-preview-thumb{width:20px;height:20px;border-radius:4px;object-fit:cover;filter:blur(2.5px);flex-shrink:0;background:#333;display:inline-block}

/* Folder Tabs */
.folder-tabs{display:flex;gap:4px;padding:8px 12px;overflow-x:auto;-webkit-overflow-scrolling:touch;scrollbar-width:none;background:#0d0d0d;border-bottom:1px solid #1c1c1c}
.folder-tabs::-webkit-scrollbar{display:none}
.folder-tab{white-space:nowrap;padding:6px 14px;border-radius:16px;background:#1c1c1e;color:#8e8e93;font-size:13px;font-weight:500;cursor:pointer;border:1px solid transparent;transition:all 0.15s}
.folder-tab.active{background:#2c2c2e;color:#fff;border-color:#3a3a3c}
.folder-tab:active{background:#333}

/* TG Channel Card Style */
.tg-channel-card{background:#141416;border-radius:16px;margin-bottom:12px;border:1px solid #1c1c1c;overflow:hidden}
.tg-channel-header{display:flex;align-items:center;gap:12px;padding:12px 14px;border-bottom:1px solid #1c1c1c}
.tg-channel-avatar{width:42px;height:42px;border-radius:50%;object-fit:cover;background:#222;flex-shrink:0}
.tg-channel-title{font-size:15px;font-weight:700;color:#fff;line-height:1.2}
.tg-channel-meta{font-size:12px;color:#8e8e93;margin-top:2px}
.tg-channel-body{padding:14px;font-size:14px;line-height:1.5;color:#ddd;white-space:pre-line}
.tg-channel-media{width:100%;max-height:380px;object-fit:cover;cursor:pointer;display:block}
.tg-channel-video-wrap{width:100%;background:#000;position:relative}
.tg-channel-video{width:100%;max-height:380px;display:block}
.tg-channel-iframe{width:100%;height:260px;border:none}
.tg-channel-footer{display:flex;align-items:center;justify-content:space-between;padding:10px 14px;border-top:1px solid #1c1c1c;background:#101012;color:#8e8e93;font-size:13px}
.tg-channel-actions{display:flex;gap:16px;align-items:center}
.tg-channel-btn{display:flex;align-items:center;gap:6px;cursor:pointer;color:#8e8e93;transition:color 0.15s;font-size:13px;font-weight:500}
.tg-channel-btn:active{color:#fff}
.tg-channel-btn svg{width:18px;height:18px;stroke:currentColor;fill:none;stroke-width:2}

/* Comments & Likes Modal */
.comments-modal{position:fixed;top:0;left:0;width:100%;height:100%;background:#000;z-index:750;display:flex;flex-direction:column;transform:translateY(100%);transition:transform 0.25s cubic-bezier(0.1,0.9,0.2,1)}
.comments-modal.active{transform:translateY(0)}
.comments-header{height:56px;background:#0d0d0d;display:flex;align-items:center;padding:0 12px;border-bottom:1px solid #1c1c1c;flex-shrink:0}
.comments-list{flex:1;overflow-y:auto;-webkit-overflow-scrolling:touch;padding:12px}
.comment-item{display:flex;gap:10px;margin-bottom:14px;background:#141416;padding:10px 12px;border-radius:12px;border:1px solid #1c1c1c}
.comment-avatar{width:36px;height:36px;border-radius:50%;object-fit:cover;background:#222;flex-shrink:0}
.comment-body{flex:1;min-width:0}
.comment-author{font-size:13px;font-weight:700;color:#fff;margin-bottom:2px}
.comment-text{font-size:13px;color:#ddd;line-height:1.4}
.comment-time{font-size:10px;color:#666;margin-top:4px}

.like-user-item{display:flex;align-items:center;justify-content:space-between;padding:10px 12px;background:#141416;border-radius:12px;border:1px solid #1c1c1c;margin-bottom:8px}
.like-user-left{display:flex;align-items:center;gap:12px}
.like-user-avatar{width:40px;height:40px;border-radius:50%;object-fit:cover;background:#222}
.like-user-name{font-size:14px;font-weight:600;color:#fff}
.like-user-btn{background:#2c2c2e;color:#fff;border:none;padding:6px 12px;border-radius:12px;font-size:12px;font-weight:600;cursor:pointer}
.like-user-btn:active{background:#3a3a3c}

/* Pinned Message Bar in Chat */
.pinned-msg-bar{display:flex;align-items:center;justify-content:space-between;padding:8px 12px;background:#141416;border-bottom:1px solid #222;z-index:10}
.pinned-msg-info{flex:1;min-width:0;border-left:2px solid #0a84ff;padding-left:8px;cursor:pointer}
.pinned-msg-title{font-size:11px;font-weight:700;color:#0a84ff;text-transform:uppercase}
.pinned-msg-text{font-size:12px;color:#aaa;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.pinned-msg-close{width:28px;height:28px;display:flex;align-items:center;justify-content:center;cursor:pointer;color:#888;border-radius:50%}

/* News Feed */
.news-feed{flex:1;overflow-y:auto;-webkit-overflow-scrolling:touch;padding:10px 12px}

/* Profile View Modal */
.profile-view-modal{position:fixed;top:0;left:0;width:100%;height:100%;background:#000;z-index:650;display:flex;flex-direction:column;overflow:hidden;transform:translateY(100%);transition:transform 0.25s cubic-bezier(0.1,0.9,0.2,1)}
.profile-view-modal.active{transform:translateY(0)}
.profile-view-header{height:56px;background:#0d0d0d;display:flex;align-items:center;padding:0 12px;border-bottom:1px solid #1c1c1c;flex-shrink:0}
.profile-view-cover{height:150px;background:#1c1c1e;background-size:cover;background-position:center;position:relative}
.profile-view-avatar-wrap{position:relative;margin-top:-50px;padding:0 16px;display:flex;align-items:flex-end;gap:12px}
.profile-view-avatar{width:100px;height:100px;border-radius:50%;object-fit:cover;background:#222;border:4px solid #000;cursor:pointer}
.profile-view-name-wrap{flex:1;padding-bottom:8px}
.profile-view-name{font-size:20px;font-weight:700;color:#fff}
.profile-view-status{font-size:13px;color:#8e8e93;margin-top:2px}
.profile-view-info{padding:16px;display:flex;flex-direction:column;gap:12px}
.profile-view-info-item{display:flex;align-items:center;gap:10px;color:#aaa;font-size:14px}
.profile-view-posts{flex:1;overflow-y:auto;-webkit-overflow-scrolling:touch;padding:0 12px 20px}
.profile-view-post-title{font-size:16px;font-weight:700;color:#fff;padding:12px 4px}
.profile-view-empty{color:#666;text-align:center;padding:40px 20px;font-size:14px}

/* Photo Viewer Modal */
.photo-viewer-modal{position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.95);z-index:999;display:flex;align-items:center;justify-content:center;opacity:0;pointer-events:none;transition:opacity 0.2s ease}
.photo-viewer-modal.active{opacity:1;pointer-events:auto}
.photo-viewer-img{max-width:96%;max-height:92vh;object-fit:contain;border-radius:8px;box-shadow:0 0 30px rgba(0,0,0,0.8)}
.photo-viewer-close{position:absolute;top:16px;right:20px;color:#fff;font-size:32px;cursor:pointer;font-weight:bold;z-index:1000;width:40px;height:40px;display:flex;align-items:center;justify-content:center;background:rgba(255,255,255,0.15);border-radius:50%}

/* Encryption Settings Modal */
.encrypt-modal-content{background:#161616;border-radius:20px;padding:20px;width:100%;max-width:420px;border:1px solid #282828;max-height:85vh;overflow-y:auto}
.key-box{background:#0a0a0a;border:1px solid #222;padding:10px;border-radius:10px;font-family:monospace;font-size:11px;color:#34c759;word-break:break-all;max-height:80px;overflow-y:auto;margin-top:4px}
.warning-box{background:rgba(255,59,48,0.1);border:1px solid rgba(255,59,48,0.3);color:#ff3b30;padding:10px;border-radius:10px;font-size:12px;margin-bottom:14px;line-height:1.4}
.delfan-box{background:rgba(10,132,255,0.1);border:1px solid rgba(10,132,255,0.3);color:#0a84ff;padding:10px;border-radius:10px;font-size:12px;margin-bottom:14px;font-family:monospace;word-break:break-all}

.dialog-info{flex:1;min-width:0}
.dialog-top{display:flex;justify-content:space-between;align-items:baseline;margin-bottom:4px}
.dialog-name{font-size:15px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;flex:1;margin-right:8px}
.dialog-time{font-size:11px;color:#666;flex-shrink:0}
.dialog-bottom{display:flex;align-items:center;gap:6px}
.dialog-preview{font-size:13px;color:#888;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;flex:1}

/* Navigation Drawer */
.drawer-overlay{position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.6);z-index:400;opacity:0;pointer-events:none;transition:opacity 0.25s ease}
.drawer-overlay.active{opacity:1;pointer-events:auto}
.drawer{position:fixed;top:0;left:0;width:82%;max-width:320px;height:100%;background:#141416;z-index:401;transform:translateX(-100%);transition:transform 0.25s cubic-bezier(0.1,0.9,0.2,1);display:flex;flex-direction:column;box-shadow:5px 0 25px rgba(0,0,0,0.8);border-right:1px solid #222}
.drawer.active{transform:translateX(0)}

.drawer-header{padding:24px 18px;background:#1c1c1e;border-bottom:1px solid #28282a;display:flex;flex-direction:column;gap:12px;position:relative}
.drawer-avatar-wrap{position:relative;width:72px;height:72px;margin-bottom:4px}
.drawer-avatar{width:72px;height:72px;border-radius:50%;object-fit:cover;background:#333;border:2px solid rgba(255,255,255,0.1);cursor:pointer}
.drawer-avatar-edit{position:absolute;bottom:0;right:0;width:26px;height:26px;background:#fff;color:#000;border-radius:50%;display:flex;align-items:center;justify-content:center;cursor:pointer;box-shadow:0 2px 6px rgba(0,0,0,0.4)}
.drawer-user-name{font-size:18px;font-weight:700;color:#fff}
.drawer-user-status{font-size:13px;color:#aaa;line-height:1.3;cursor:pointer;display:flex;align-items:center;gap:6px}
.drawer-user-online-text{font-size:13px;color:#34c759;font-weight:600;margin-top:2px}
.drawer-user-online-text.offline{color:#8e8e93;font-weight:400}

.drawer-content{flex:1;overflow-y:auto;padding:12px 10px;display:flex;flex-direction:column;gap:6px}
.drawer-item{display:flex;align-items:center;justify-content:space-between;padding:12px 14px;border-radius:12px;color:#ddd;font-size:15px;font-weight:500;cursor:pointer;transition:background 0.15s}
.drawer-item-left{display:flex;align-items:center;gap:14px}
.drawer-item:active{background:rgba(255,255,255,0.08);color:#fff}
.drawer-item svg{color:#aaa}

/* Switch UI */
.switch{position:relative;display:inline-block;width:44px;height:24px;flex-shrink:0}
.switch input{opacity:0;width:0;height:0}
.slider{position:absolute;cursor:pointer;top:0;left:0;right:0;bottom:0;background-color:#3a3a3c;transition:.2s;border-radius:24px}
.slider:before{position:absolute;content:"";height:18px;width:18px;left:3px;bottom:3px;background-color:white;transition:.2s;border-radius:50%}
input:checked + .slider{background-color:#34c759}
input:checked + .slider:before{transform:translateX(20px)}

/* Chat Screen */
.chat-screen{position:fixed;top:0;left:0;width:100%;height:100%;background:#000;display:flex;flex-direction:column;z-index:100;transform:translateX(100%);transition:transform 0.22s cubic-bezier(0.1, 0.9, 0.2, 1)}
.chat-screen.active{transform:translateX(0)}
.messages-wrapper{flex:1;position:relative;overflow:hidden;display:flex;flex-direction:column}
.messages{flex:1;overflow-y:auto;padding:10px 12px;display:flex;flex-direction:column;gap:8px;-webkit-overflow-scrolling:touch}

/* Message styling */
.msg-container{position:relative;display:flex;width:100%;align-items:flex-end;touch-action:pan-y;margin-bottom:2px}
.msg-swipe-bg{position:absolute;top:0;bottom:0;display:flex;align-items:center;justify-content:center;width:40px;opacity:0;transition:opacity 0.15s;color:#8e8e93;z-index:1}
.msg-swipe-right{right:-40px}

.msg{max-width:82%;padding:8px 12px;border-radius:18px;font-size:14px;line-height:1.4;word-wrap:break-word;position:relative;animation:msgAppear 0.15s ease-out}
@keyframes msgAppear{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:translateY(0)}}

.msg-in{align-self:flex-start;background:#1c1c1e;border-bottom-left-radius:4px;color:#fff}
.msg-out{align-self:flex-end;background:#2c2c2e;border-bottom-right-radius:4px;color:#fff}

/* Стикеры в стиле Telegram */
.msg-sticker{
    background: transparent !important;
    padding: 0 !important;
    box-shadow: none !important;
    border: none !important;
    outline: none !important;
    border-radius: 0 !important;
    max-width: 160px !important;
    display: flex;
    justify-content: center;
    align-items: center;
    position: relative;
    min-height: 140px;
}
.msg-sticker img{
    width: 140px;
    height: 140px;
    object-fit: contain;
    display: block;
    border-radius: 18px;
    border: none !important;
    outline: none !important;
    box-shadow: none !important;
    filter: drop-shadow(0 4px 10px rgba(0,0,0,0.45));
}
.msg-sticker .msg-time {
    position: absolute;
    bottom: 4px;
    right: 6px;
    background: rgba(0,0,0,0.65);
    padding: 2px 6px;
    border-radius: 10px;
    backdrop-filter: blur(6px);
    z-index: 5;
    color: #fff;
    font-size: 10px;
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 3px;
}

/* Оформление времени поверх фото/видео/стикеров (Telegram Style) */
.media-wrapper,
.sticker-wrapper {
    position: relative;
    display: inline-block;
    max-width: 100%;
    overflow: hidden;
    border-radius: 12px;
}
.media-time-badge {
    position: absolute;
    bottom: 6px;
    right: 8px;
    background: rgba(0, 0, 0, 0.45);
    backdrop-filter: blur(4px);
    -webkit-backdrop-filter: blur(4px);
    color: #ffffff;
    font-size: 11px;
    font-weight: 500;
    line-height: 1;
    padding: 3px 6px;
    border-radius: 10px;
    pointer-events: none;
    display: flex;
    align-items: center;
    gap: 3px;
    z-index: 5;
}
.sticker-wrapper .media-time-badge {
    background: rgba(0, 0, 0, 0.25);
}



.msg-circle-mode{background:transparent !important;padding:0 !important;border-radius:0 !important;box-shadow:none !important;max-width:200px !important}
.msg-circle-mode .msg-time{position:absolute;bottom:6px;right:10px;background:rgba(0,0,0,0.55);padding:2px 6px;border-radius:10px;backdrop-filter:blur(4px);z-index:5}

.msg-reply-quote{background:rgba(255,255,255,0.08);border-left:3px solid #8e8e93;padding:4px 8px;border-radius:4px;margin-bottom:6px;font-size:12px;cursor:pointer}
.msg-reply-name{font-weight:600;color:#aaa;margin-bottom:2px;font-size:11px}
.msg-reply-text{color:#ddd;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}

.msg-author{font-size:11px;color:#aaa;font-weight:600;margin-bottom:2px}
.msg-text{color:#fff}
.msg-time{font-size:10px;color:#888;margin-top:4px;text-align:right;display:flex;align-items:center;justify-content:flex-end;gap:3px}
.decrypting-shimmer{color:#888;font-style:italic}

/* TG Circle Video Message (.mkru) */
.tg-circle-container{width:200px;height:200px;position:relative;border-radius:50%;overflow:hidden;margin:0;background:#111;box-shadow:0 4px 15px rgba(0,0,0,0.5);transform:translateZ(0)}
.tg-circle-video{width:100%;height:100%;object-fit:cover;border-radius:50%;cursor:pointer;display:block}
.tg-circle-overlay{position:absolute;top:0;left:0;width:100%;height:100%;border-radius:50%;pointer-events:none;box-shadow:inset 0 0 0 2px rgba(255,255,255,0.15)}

/* TG Voice Message (.mgs) */
.tg-voice-container{display:flex;align-items:center;gap:10px;padding:4px 0;width:220px;user-select:none}
.tg-voice-play-btn{width:38px;height:38px;border-radius:50%;background:#fff;color:#000;display:flex;align-items:center;justify-content:center;cursor:pointer;flex-shrink:0}
.tg-voice-play-btn:active{transform:scale(0.92)}
.tg-voice-wave-wrap{flex:1;display:flex;flex-direction:column;gap:4px}
.tg-voice-waveform{display:flex;align-items:center;gap:2px;height:24px;cursor:pointer}
.tg-voice-bar{flex:1;background:rgba(255,255,255,0.3);border-radius:2px;min-height:3px}
.tg-voice-bar.active{background:#fff}
.tg-voice-info{display:flex;justify-content:space-between;font-size:10px;color:#aaa}

/* TG Music Player (.mmu) */
.tg-music-container{display:flex;align-items:center;gap:10px;padding:8px 12px;background:#1c1c1e;border-radius:14px;width:280px;user-select:none;border:1px solid #2c2c2e}
.tg-music-play-btn{width:42px;height:42px;border-radius:50%;background:#0a84ff;color:#fff;display:flex;align-items:center;justify-content:center;cursor:pointer;flex-shrink:0}
.tg-music-play-btn:active{transform:scale(0.92)}
.tg-music-info{flex:1;min-width:0;display:flex;flex-direction:column;gap:3px}
.tg-music-title{font-size:13px;font-weight:600;color:#fff;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.tg-music-artist{font-size:11px;color:#8e8e93;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.tg-music-progress{height:3px;background:#3a3a3c;border-radius:2px;margin-top:4px;overflow:hidden}
.tg-music-progress-bar{height:100%;background:#0a84ff;border-radius:2px;width:0%;transition:width 0.1s linear}
.tg-music-time{font-size:10px;color:#666;display:flex;justify-content:space-between;margin-top:2px}

.msg-photo{max-width:100%;border-radius:12px;margin-top:6px;display:block;max-height:280px;object-fit:cover;cursor:pointer;background:#111;border:none!important;outline:none!important;box-shadow:none!important}
.msg-video{max-width:100%;border-radius:12px;margin-top:6px;display:block;max-height:280px;background:#000;border:none!important;outline:none!important;box-shadow:none!important}
/* Удаление обводок у медиа-контейнеров */
.msg-photo, .msg-video, .msg-sticker, .msg-sticker img {
    border: none !important;
    outline: none !important;
    box-shadow: none !important;
}
.msg-file{background:rgba(255,255,255,.05);padding:10px;border-radius:12px;margin-top:6px;display:flex;align-items:center;gap:10px;cursor:pointer;color:#aaa}

/* Input Bar & Actions */
.input-area-wrapper{background:#0d0d0d;border-top:1px solid #1a1a1a;display:flex;flex-direction:column;flex-shrink:0;z-index:15}
.reply-preview-bar{display:flex;align-items:center;justify-content:space-between;padding:8px 12px;background:#141416;border-bottom:1px solid #222}
.reply-preview-info{flex:1;min-width:0;border-left:2px solid #8e8e93;padding-left:8px}
.reply-preview-title{font-size:12px;font-weight:600;color:#aaa}
.reply-preview-text{font-size:12px;color:#888;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.reply-preview-close{width:28px;height:28px;display:flex;align-items:center;justify-content:center;cursor:pointer;color:#888;border-radius:50%}

.input-area{min-height:54px;display:flex;align-items:flex-end;padding:8px;gap:6px;position:relative}
.input-attach{width:38px;height:38px;display:flex;align-items:center;justify-content:center;border-radius:50%;cursor:pointer;flex-shrink:0;color:#aaa}
.message-input{flex:1;padding:10px 14px;border:none;border-radius:20px;background:#1c1c1e;color:#fff;font-size:14px;outline:none;resize:none;max-height:100px;font-family:inherit;line-height:1.4;border:1px solid #2a2a2c}
.send-btn,.media-rec-btn{width:38px;height:38px;border-radius:50%;background:#fff;display:flex;align-items:center;justify-content:center;cursor:pointer;flex-shrink:0;border:none;color:#000}

.recording-bar{display:flex;align-items:center;flex:1;padding:0 12px;height:38px;background:#1c1c1e;border-radius:20px;gap:10px}
.recording-dot{width:10px;height:10px;border-radius:50%;background:#ff3b30;animation:pulseDot 1s infinite alternate}
@keyframes pulseDot{from{opacity:0.3}to{opacity:1}}
.recording-timer{font-size:13px;font-weight:600;color:#fff;min-width:40px}
.recording-cancel{font-size:13px;color:#ff3b30;cursor:pointer;font-weight:500;padding:4px 8px;border-radius:12px}

/* TG Circle Camera Modal */
.circle-recorder-modal{position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.88);z-index:700;display:flex;flex-direction:column;align-items:center;justify-content:center}
.circle-preview-box{width:260px;height:260px;border-radius:50%;overflow:hidden;position:relative;box-shadow:0 0 30px rgba(0,0,0,0.8);border:4px solid #fff;background:#000}
.circle-preview-video{width:100%;height:100%;object-fit:cover;transform:scaleX(-1)}
.circle-rec-controls{margin-top:24px;display:flex;align-items:center;justify-content:center;gap:16px}
.circle-btn-action{width:52px;height:52px;border-radius:50%;display:flex;align-items:center;justify-content:center;cursor:pointer;user-select:none}
.circle-btn-sec{background:#2c2c2e;color:#fff;border:1px solid #3a3a3c}
.circle-btn-sec.active{background:#fff;color:#000}
.circle-btn-stop{background:#ff3b30;color:#fff;box-shadow:0 0 15px rgba(255,59,48,0.5)}
.circle-btn-cancel{background:#3a3a3c;color:#fff}

.upload-toast{position:fixed;top:60px;left:50%;transform:translateX(-50%);background:rgba(28,28,30,0.95);border:1px solid #3a3a3c;color:#fff;padding:8px 16px;border-radius:20px;font-size:13px;font-weight:500;z-index:900;display:flex;align-items:center;gap:10px;box-shadow:0 4px 16px rgba(0,0,0,0.5)}

/* Bottom Nav */
.bottom-nav{height:50px;background:#0d0d0d;border-top:1px solid #1a1a1a;display:flex;justify-content:space-around;align-items:center;flex-shrink:0}
.nav-item{flex:1;height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;cursor:pointer;color:#666}
.nav-item.active{color:#fff}
.nav-item span{font-size:10px}

/* Action Sheet */
.action-sheet{position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.6);z-index:500;display:flex;flex-direction:column;justify-content:flex-end}
.action-sheet-content{background:#1c1c1e;border-top-left-radius:20px;border-top-right-radius:20px;padding:16px;display:flex;flex-direction:column;gap:8px}
.action-sheet-item{padding:14px 16px;border-radius:12px;background:#2c2c2e;color:#fff;font-size:15px;font-weight:500;display:flex;align-items:center;gap:12px;cursor:pointer}
.action-sheet-item.danger{color:#ff3b30}

/* Forward Modal Target List */
.forward-list{max-height:300px;overflow-y:auto;display:flex;flex-direction:column;gap:6px;margin-top:12px}
.forward-item{display:flex;align-items:center;gap:10px;padding:8px 10px;border-radius:10px;background:#222;cursor:pointer}
.forward-item:active{background:#333}
.forward-item img{width:36px;height:36px;border-radius:50%;object-fit:cover}

/* Modals */
.modal{position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,.85);display:flex;align-items:center;justify-content:center;z-index:600;padding:20px}
.modal-content{background:#161616;border-radius:20px;padding:24px;width:100%;max-width:380px;border:1px solid #282828}
.modal-title{font-size:18px;font-weight:600;margin-bottom:10px;color:#fff}
.modal-text{font-size:13px;color:#aaa;margin-bottom:20px;line-height:1.5}
.modal-checkbox{display:flex;align-items:center;gap:10px;margin-bottom:20px;font-size:14px;color:#ddd;cursor:pointer}

.file-input{display:none}
.hidden{display:none!important}
.folder-create-item{display:flex;align-items:center;gap:10px;padding:8px 0;border-bottom:1px solid #222}
.folder-create-item img{width:36px;height:36px;border-radius:50%;object-fit:cover}
.folder-create-item input[type="checkbox"]{width:20px;height:20px}
.msg-search-highlight{background:#ffeb3b;color:#000;padding:1px 2px;border-radius:2px}
.loader{border:2px solid #333;border-top:2px solid #fff;border-radius:50%;width:14px;height:14px;animation:spin 0.6s linear infinite;display:inline-block;vertical-align:middle;margin-right:6px}
@keyframes spin{0%{transform:rotate(0deg)}100%{transform:rotate(360deg)}}
@keyframes fadeIn{from{opacity:0}to{opacity:1}}

/* ===== NEW FEATURES STYLES ===== */

/* Typing Indicator */
.typing-indicator{display:flex;align-items:center;gap:4px;padding:8px 12px;background:#1c1c1e;border-radius:18px;align-self:flex-start;margin-bottom:8px;animation:fadeIn 0.3s ease}
.typing-indicator span{width:6px;height:6px;border-radius:50%;background:#8e8e93;animation:typingBounce 1.4s infinite ease-in-out}
.typing-indicator span:nth-child(1){animation-delay:0s}
.typing-indicator span:nth-child(2){animation-delay:0.2s}
.typing-indicator span:nth-child(3){animation-delay:0.4s}
@keyframes typingBounce{0%,80%,100%{transform:scale(0.6);opacity:0.4}40%{transform:scale(1);opacity:1}}

/* Pull-to-Refresh */
.ptr-container{position:relative;overflow:hidden}
.ptr-spinner{position:absolute;top:-50px;left:50%;transform:translateX(-50%);width:36px;height:36px;display:flex;align-items:center;justify-content:center;transition:top 0.3s ease}
.ptr-spinner.active{top:12px}
.ptr-spinner .loader{border-color:#333;border-top-color:#0a84ff;width:24px;height:24px}

/* Message Selection Mode */
.msg-select-mode .msg{cursor:pointer;transition:background 0.15s}
.msg-select-mode .msg:hover{background:rgba(10,132,255,0.1)}
.msg-selected{background:rgba(10,132,255,0.2)!important;border:1px solid #0a84ff!important}
.msg-select-checkbox{position:absolute;top:8px;left:-30px;width:20px;height:20px;border-radius:50%;border:2px solid #8e8e93;background:transparent;cursor:pointer;display:none}
.msg-select-mode .msg-select-checkbox{display:block}
.msg-select-mode .msg-selected .msg-select-checkbox{background:#0a84ff;border-color:#0a84ff}

/* Multi-select Action Bar */
.multi-select-bar{position:fixed;bottom:0;left:0;width:100%;background:#0d0d0d;border-top:1px solid #1c1c1c;padding:10px 16px;display:flex;align-items:center;justify-content:space-between;z-index:200;transform:translateY(100%);transition:transform 0.25s cubic-bezier(0.1,0.9,0.2,1)}
.multi-select-bar.active{transform:translateY(0)}
.multi-select-count{font-size:14px;font-weight:600;color:#fff}
.multi-select-actions{display:flex;gap:12px}
.multi-select-btn{background:#2c2c2e;color:#fff;border:none;padding:8px 14px;border-radius:12px;font-size:13px;font-weight:600;cursor:pointer;display:flex;align-items:center;gap:6px}
.multi-select-btn:active{background:#3a3a3c}

/* Theme: OLED Black (darker) */
/* Theme: Dark Blue */
/* Theme: Sepia (easy on eyes) */
/* Offline Banner */
.offline-banner{position:fixed;top:0;left:0;width:100%;background:#ff9500;color:#000;text-align:center;padding:6px;font-size:12px;font-weight:600;z-index:1000;transform:translateY(-100%);transition:transform 0.3s ease}
.offline-banner.active{transform:translateY(0)}

/* Scroll to Bottom FAB */
.scroll-fab{position:fixed;bottom:70px;right:16px;width:44px;height:44px;border-radius:50%;background:#0a84ff;color:#fff;display:flex;align-items:center;justify-content:center;cursor:pointer;box-shadow:0 4px 12px rgba(0,0,0,0.4);z-index:50;opacity:0;transform:scale(0.8);transition:all 0.2s ease}
.scroll-fab.active{opacity:1;transform:scale(1)}
.scroll-fab .unread-dot{position:absolute;top:-2px;right:-2px;min-width:18px;height:18px;border-radius:50%;background:#ff3b30;color:#fff;font-size:10px;font-weight:700;display:flex;align-items:center;justify-content:center;padding:0 4px}

/* Voice Message Waveform Animation */
.tg-voice-bar{transition:height 0.15s ease}
.tg-voice-container.playing .tg-voice-bar{animation:waveformPulse 0.5s infinite alternate}
@keyframes waveformPulse{from{opacity:0.3}to{opacity:1}}

/* Smooth scroll snap for messages */
.messages{scroll-behavior:smooth}

/* Improved scrollbar */
.dialogs-list::-webkit-scrollbar,.messages::-webkit-scrollbar,.news-feed::-webkit-scrollbar{width:3px}
.dialogs-list::-webkit-scrollbar-thumb,.messages::-webkit-scrollbar-thumb,.news-feed::-webkit-scrollbar-thumb{background:#333;border-radius:3px}
.dialogs-list::-webkit-scrollbar-track,.messages::-webkit-scrollbar-track,.news-feed::-webkit-scrollbar-track{background:transparent}

/* Date Separator */
.msg-date-separator{display:flex;align-items:center;justify-content:center;margin:16px 0;position:relative}
.msg-date-separator::before{content:'';position:absolute;left:12px;right:12px;height:1px;background:#2c2c2e}
.msg-date-separator span{background:#000;color:#8e8e93;font-size:11px;padding:0 12px;position:relative;z-index:1;text-transform:uppercase;letter-spacing:0.5px}

/* Unread Messages Divider */
.unread-divider{display:flex;align-items:center;justify-content:center;margin:12px 0;position:relative}
.unread-divider::before{content:'';position:absolute;left:12px;right:12px;height:1px;background:#ff3b30;opacity:0.5}
.unread-divider span{background:#000;color:#ff3b30;font-size:11px;padding:0 12px;position:relative;z-index:1;font-weight:600;text-transform:uppercase}

/* Draft indicator in dialog list */
.dialog-draft{color:#ff9500;font-style:italic}

/* Message status icons */
.msg-status-sent{color:#8e8e93}
.msg-status-delivered{color:#34c759}
.msg-status-read{color:#0a84ff}

/* Improved photo grid for multiple photos */
.photo-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:4px;margin-top:6px;max-width:280px}
.photo-grid img{width:100%;height:120px;object-fit:cover;border-radius:8px;cursor:pointer}
.photo-grid-3{grid-template-columns:repeat(2,1fr)}
.photo-grid-3 img:first-child{grid-column:span 2;height:160px}

/* Link preview card */
.link-preview{background:#1c1c1e;border-radius:12px;padding:10px;margin-top:6px;border:1px solid #2c2c2e;cursor:pointer;max-width:280px}
.link-preview-domain{font-size:11px;color:#8e8e93;margin-bottom:4px;text-transform:uppercase;letter-spacing:0.5px}
.link-preview-title{font-size:13px;font-weight:600;color:#fff;margin-bottom:4px;line-height:1.3;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.link-preview-desc{font-size:12px;color:#aaa;line-height:1.3;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.link-preview-img{width:100%;height:140px;object-fit:cover;border-radius:8px;margin-bottom:8px}

/* Improved input with emoji button */
.input-area{position:relative}
.emoji-btn{position:absolute;left:46px;bottom:10px;width:32px;height:32px;display:flex;align-items:center;justify-content:center;border-radius:50%;cursor:pointer;color:#8e8e93;z-index:5}
.emoji-btn:active{background:rgba(255,255,255,0.1)}

/* Context menu (right-click) */
.context-menu{position:fixed;background:#1c1c1e;border-radius:12px;padding:6px 0;min-width:180px;box-shadow:0 8px 32px rgba(0,0,0,0.6);z-index:700;border:1px solid #2c2c2e;opacity:0;pointer-events:none;transform:scale(0.95);transition:all 0.15s ease}
.context-menu.active{opacity:1;pointer-events:auto;transform:scale(1)}
.context-menu-item{padding:10px 16px;font-size:14px;color:#ddd;cursor:pointer;display:flex;align-items:center;gap:10px;transition:background 0.1s}
.context-menu-item:hover{background:rgba(255,255,255,0.08)}
.context-menu-item.danger{color:#ff3b30}
.context-menu-divider{height:1px;background:#2c2c2e;margin:4px 0}

/* Improved modal backdrop blur */
.modal{background:rgba(0,0,0,0.85);backdrop-filter:blur(8px)}

/* Network status dot in header */
.network-dot{width:8px;height:8px;border-radius:50%;background:#34c759;margin-right:6px;animation:none}
.network-dot.connecting{background:#ff9500;animation:pulseDot 1s infinite}
.network-dot.offline{background:#ff3b30}

/* Bio/PIN auth modal */
.bio-modal-content{text-align:center;padding:30px 20px}
.bio-icon{width:64px;height:64px;margin:0 auto 16px;border-radius:50%;background:#2c2c2e;display:flex;align-items:center;justify-content:center}
.bio-icon svg{width:32px;height:32px;color:#0a84ff}
.bio-title{font-size:20px;font-weight:700;color:#fff;margin-bottom:8px}
.bio-text{font-size:14px;color:#8e8e93;margin-bottom:24px;line-height:1.4}
.bio-dots{display:flex;justify-content:center;gap:12px;margin-bottom:24px}
.bio-dot{width:12px;height:12px;border-radius:50%;border:2px solid #3a3a3c;background:transparent;transition:all 0.2s}
.bio-dot.filled{background:#0a84ff;border-color:#0a84ff}
.bio-shake{animation:shake 0.4s ease}
@keyframes shake{0%,100%{transform:translateX(0)}20%{transform:translateX(-8px)}40%{transform:translateX(8px)}60%{transform:translateX(-4px)}80%{transform:translateX(4px)}}

/* Improved send button animation */
.send-btn{transition:all 0.2s cubic-bezier(0.34,1.56,0.64,1)}
.send-btn:active{transform:scale(0.85)}
.send-btn.sending{animation:sendPulse 0.4s ease}
@keyframes sendPulse{0%{transform:scale(1)}50%{transform:scale(1.15)}100%{transform:scale(1)}}

/* Skeleton loading for dialogs */
.skeleton-dialog{display:flex;align-items:center;padding:12px 14px;gap:12px}
.skeleton-avatar{width:50px;height:50px;border-radius:50%;background:#1c1c1e;animation:skeletonPulse 1.5s infinite}
.skeleton-lines{flex:1;display:flex;flex-direction:column;gap:8px}
.skeleton-line{height:12px;background:#1c1c1e;border-radius:6px;animation:skeletonPulse 1.5s infinite}
.skeleton-line.short{width:60%}
@keyframes skeletonPulse{0%{opacity:0.4}50%{opacity:0.8}100%{opacity:0.4}}

/* Improved message bubble shadows */
.msg{box-shadow:0 1px 2px rgba(0,0,0,0.2)}
.msg-out{box-shadow:0 1px 2px rgba(0,0,0,0.3)}

/* Call button in header */
.call-btn{width:38px;height:38px;display:flex;align-items:center;justify-content:center;border-radius:50%;cursor:pointer;color:#34c759;background:rgba(52,199,89,0.1);border:none;outline:none;margin-right:6px}
.call-btn:active{background:rgba(52,199,89,0.2)}

/* Voice message recording animation */
.recording-bar.recording .recording-dot{animation:pulseDot 0.8s infinite alternate}

/* Improved sticker picker */
.sticker-picker{position:fixed;bottom:0;left:0;width:100%;height:300px;background:#0d0d0d;border-top:1px solid #1c1c1c;z-index:100;transform:translateY(100%);transition:transform 0.3s cubic-bezier(0.1,0.9,0.2,1);display:flex;flex-direction:column}
.sticker-picker.active{transform:translateY(0)}
.sticker-picker-header{height:44px;display:flex;align-items:center;padding:0 12px;border-bottom:1px solid #1c1c1c;gap:8px;overflow-x:auto}
.sticker-picker-tab{padding:6px 12px;border-radius:16px;background:#1c1c1e;color:#8e8e93;font-size:13px;cursor:pointer;white-space:nowrap}
.sticker-picker-tab.active{background:#2c2c2e;color:#fff}
.sticker-picker-grid{flex:1;overflow-y:auto;padding:12px;display:grid;grid-template-columns:repeat(5,1fr);gap:8px}
.sticker-picker-item{width:100%;aspect-ratio:1;object-fit:contain;cursor:pointer;border-radius:8px;padding:4px;transition:transform 0.1s}
.sticker-picker-item:active{transform:scale(0.9);background:rgba(255,255,255,0.05)}

</style>
</head>
<body>
<div class="app">

<div class="upload-toast hidden" id="uploadToast">
<span class="loader"></span>
<span id="uploadToastText">Загрузка...</span>
</div>

<!-- Soft iOS / Telegram Chime Base64 Sound -->
<audio id="notifSound" preload="auto">
<source src="data:audio/mp3;base64,SUQ3BAAAAAAAI1RTU0UAAAAPAAADTGF2ZjU4Ljc2LjEwMAAAAAAAAAAAAAAA//7AABAAAAAAAAAAAAAAAAAAAAAAAABGcm9udA0AAAAAAAAAAAAAAAAAAAAAAABIaWdoAGNhbGlicmF0aW9uAAAAAAAAAAAAAD/+wAFAAAACAAAAAgAAAAIAAAAD2R1cmF0aW9uAFRpbWUAAAAPAAADTGF2ZjU4Ljc2LjEwMAAAAP7AAAAD8AAAA/AAAAPwAAAA/v4AAAAAMUAAAAAAAAAAMAAAAAD+///+/v4=" type="audio/mp3">
</audio>

<div class="drawer-overlay" id="drawerOverlay" onclick="closeDrawer()"></div>
<div class="drawer" id="drawer">
<div class="drawer-header">
<div class="drawer-avatar-wrap">
<img class="drawer-avatar" id="drawerAvatar" src="" alt="" onclick="openPhotoViewer(this.src)">
<div class="drawer-avatar-edit" onclick="triggerAvatarSelect()" title="Изменить фото">
<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/></svg>
</div>
</div>
<div class="drawer-user-name" id="drawerName">Имя Фамилия</div>
<div class="drawer-user-online-text" id="drawerOnlineStatus">Загрузка...</div>
<div class="drawer-user-status" id="drawerStatus" onclick="openProfileEditModal()" style="margin-top:4px">
<span id="drawerStatusText">Нажмите, чтобы изменить описание...</span>
<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>
</div>
</div>

<div class="drawer-content">

<div class="drawer-item" onclick="toggleStealthRead()">
<div class="drawer-item-left">
<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
<span>Режим «Не читать»</span>
</div>
<label class="switch" onclick="event.stopPropagation()">
<input type="checkbox" id="stealthReadToggle" onchange="setStealthReadState(this.checked)">
<span class="slider"></span>
</label>
</div>

<div class="drawer-item" onclick="toggleSoundNotifications()">
<div class="drawer-item-left">
<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>
<span>Звук уведомлений</span>
</div>
<label class="switch" onclick="event.stopPropagation()">
<input type="checkbox" id="soundToggle" onchange="setSoundState(this.checked)">
<span class="slider"></span>
</label>
</div>

<div class="drawer-item" onclick="toggleEncryptionMode()">
<div class="drawer-item-left">
<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
<span>Шифрование E2EE</span>
</div>
<label class="switch" onclick="event.stopPropagation()">
<input type="checkbox" id="encryptToggle" onchange="setEncryptionState(this.checked)">
<span class="slider"></span>
</label>
</div>

<div class="drawer-item" onclick="clearAppCache()">
<div class="drawer-item-left">
<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="1 4 1 10 7 10"/><polyline points="23 20 23 14 17 14"/><path d="M20.49 9A9 9 0 0 0 5.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 0 1 3.51 15"/></svg>
<span id="cacheSizeText">Очистить кэш (0.0 MB)</span>
</div>
</div>

<div class="drawer-item" onclick="openProfileEditModal()">
<div class="drawer-item-left">
<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
<span>Редактировать профиль</span>
</div>
</div>
<div class="drawer-item" onclick="triggerAvatarSelect()">
<div class="drawer-item-left">
<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>
<span>Сменить аватар (Kate Mobile)</span>
</div>
</div>
<div class="drawer-item" onclick="setPinCode()">
<div class="drawer-item-left">
<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
<span>Блокировка PIN-кодом</span>
</div>
</div>

<div class="drawer-item" onclick="openEncryptModal(); closeDrawer();">
<div class="drawer-item-left">
<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
<span>Облачное E2EE Шифрование</span>
</div>
</div>
<div class="drawer-item" onclick="window.location.href='/cloud'">
<div class="drawer-item-left">
<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z"/></svg>
<span>☁️ Облако VK Tsuyu</span>
</div>
</div>
<div style="flex:1"></div>
<div class="drawer-item" style="color:#ff3b30" onclick="logout()">
<div class="drawer-item-left">
<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
<span>Выйти из аккаунта</span>
</div>
</div>
</div>
</div>

<!-- Encryption & Keys Modal -->
<div class="modal hidden" id="encryptModal">
<div class="encrypt-modal-content">
<div class="modal-title"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:middle;margin-right:6px"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>Облачное E2EE шифрование</div>
<div class="warning-box">
<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:middle;margin-right:6px"><path d="M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z"/></svg>Ваши зашифрованные ключи надежно сохранены в <b>Firebase Realtime Database</b> и защищены вашим мастер-паролем. Вход с любого устройства восстановит доступ к вашим чатам!
</div>
<div class="modal-text" style="margin-bottom:10px">Отпечаток устройства (Delfan Fingerprint):</div>
<div class="delfan-box" id="delfanFingerprint">Вычисление отпечатка...</div>

<div class="modal-text" style="margin-bottom:4px">Ваш публичный ключ (RTDB Cloud):</div>
<div class="key-box" id="modalPubKey">Загрузка...</div>

<div class="modal-text" style="margin-top:10px;margin-bottom:4px">Ваш зашифрованный приватный ключ:</div>
<div class="key-box" id="modalPrivKey">Загрузка...</div>

<div style="display:flex;gap:8px;margin-top:16px">
<button class="btn btn-secondary" style="flex:1;font-size:13px;padding:10px" onclick="exportKeysFile()">Экспорт ключей</button>
<button class="btn btn-secondary" style="flex:1;font-size:13px;padding:10px" onclick="document.getElementById('importKeysInput').click()">Импорт ключей</button>
</div>
<input type="file" class="file-input" id="importKeysInput" accept=".json" onchange="importKeysFile(event)">

<button class="btn btn-danger" style="margin-top:10px;font-size:13px;padding:10px" onclick="regenerateKeysPrompt()">Сбросить пароль E2EE</button>
<button class="btn" style="margin-top:10px" onclick="closeEncryptModal()">Закрыть</button>
</div>
</div>

<!-- Profile Edit Modal -->
<div class="modal hidden" id="profileModal">
<div class="modal-content">
<div class="modal-title">Редактирование профиля</div>
<input type="text" class="token-input" id="editFirstName" placeholder="Имя">
<input type="text" class="token-input" id="editLastName" placeholder="Фамилия">
<input type="text" class="token-input" id="editStatusInput" placeholder="Статус (описание)">
<div style="display:flex;gap:8px;margin-top:8px">
<button class="btn btn-secondary" style="flex:1" onclick="closeProfileModal()">Отмена</button>
<button class="btn" style="flex:1" onclick="saveProfileChanges()">Сохранить</button>
</div>
</div>
</div>

<input type="file" class="file-input" id="avatarFileInput" accept="image/*" onchange="uploadAvatarFile(event)">

<!-- Photo Viewer Modal -->
<div class="photo-viewer-modal" id="photoViewerModal" onclick="closePhotoViewer()">
<span class="photo-viewer-close" onclick="closePhotoViewer()">&times;</span>
<img class="photo-viewer-img" id="photoViewerImg" src="" onclick="event.stopPropagation()" style="transition:transform 0.1s ease-out;transform-origin:center center">
</div>

<script>
// Pinch-to-zoom for photo viewer
(function() {
    const img = document.getElementById('photoViewerImg');
    const modal = document.getElementById('photoViewerModal');
    let scale = 1;
    let lastScale = 1;
    let startX = 0, startY = 0;
    let translateX = 0, translateY = 0;
    let isDragging = false;

    modal.addEventListener('touchstart', (e) => {
        if (e.touches.length === 2) {
            const dx = e.touches[0].clientX - e.touches[1].clientX;
            const dy = e.touches[0].clientY - e.touches[1].clientY;
            lastScale = Math.sqrt(dx*dx + dy*dy);
        } else if (e.touches.length === 1 && scale > 1) {
            isDragging = true;
            startX = e.touches[0].clientX - translateX;
            startY = e.touches[0].clientY - translateY;
        }
    }, { passive: true });

    modal.addEventListener('touchmove', (e) => {
        if (e.touches.length === 2) {
            e.preventDefault();
            const dx = e.touches[0].clientX - e.touches[1].clientX;
            const dy = e.touches[0].clientY - e.touches[1].clientY;
            const dist = Math.sqrt(dx*dx + dy*dy);
            scale = Math.min(Math.max(1, (dist / lastScale) * scale), 5);
            lastScale = dist;
            updateTransform();
        } else if (isDragging && scale > 1) {
            e.preventDefault();
            translateX = e.touches[0].clientX - startX;
            translateY = e.touches[0].clientY - startY;
            updateTransform();
        }
    }, { passive: false });

    modal.addEventListener('touchend', () => {
        isDragging = false;
        if (scale < 1) {
            scale = 1;
            translateX = 0;
            translateY = 0;
            updateTransform();
        }
    });

    modal.addEventListener('wheel', (e) => {
        e.preventDefault();
        const delta = e.deltaY > 0 ? 0.9 : 1.1;
        scale = Math.min(Math.max(1, scale * delta), 5);
        if (scale === 1) {
            translateX = 0;
            translateY = 0;
        }
        updateTransform();
    }, { passive: false });

    function updateTransform() {
        img.style.transform = `translate(${translateX}px, ${translateY}px) scale(${scale})`;
    }

    let lastTap = 0;
    modal.addEventListener('touchend', (e) => {
        const now = Date.now();
        if (now - lastTap < 300) {
            if (scale > 1) {
                scale = 1;
                translateX = 0;
                translateY = 0;
            } else {
                scale = 2.5;
            }
            updateTransform();
        }
        lastTap = now;
    });
})();
</script>

<!-- Forward Message Modal -->
<div class="modal hidden" id="forwardModal">
<div class="modal-content">
<div class="modal-title">Переслать сообщение</div>
<div class="modal-text">Выберите чат для пересылки:</div>
<div class="forward-list" id="forwardList"></div>
<button class="btn btn-secondary" style="margin-top:12px" onclick="closeForwardModal()">Отмена</button>
</div>
</div>

<!-- Comments & Likes View Modal -->
<div class="comments-modal" id="commentsModal">
<div class="comments-header">
<div class="header-back" onclick="closeCommentsModal()" style="margin-right:10px">
<svg viewBox="0 0 24 24" style="width:22px;height:22px;stroke:#fff;stroke-width:2.5px;fill:none">
<line x1="19" y1="12" x2="5" y2="12"></line>
<polyline points="12 19 5 12 12 5"></polyline>
</svg>
</div>
<div class="header-title" id="commentsHeaderTitle">Комментарии</div>
</div>
<div class="comments-list" id="commentsList"></div>
</div>

<!-- Login Screen -->
<div class="login-screen" id="loginScreen">
<h1>VK Tsuyu</h1>
<div class="badge-e2e">
<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
Kate Mobile API • Cloud Realtime E2EE
</div>
<p>Вход по токену Kate Mobile. Мгновенная работа, ТГ-каналы, поиск и облачное шифрование.</p>
<button class="btn btn-secondary" onclick="getToken()">1. Получить токен Kate Mobile</button>
<input type="text" class="token-input" id="tokenUrl" placeholder="Ссылка с токеном из строки браузера...">
<input type="password" class="pass-input" id="password" placeholder="Пароль для защиты E2EE ключей...">
<button class="btn" onclick="login()">Войти</button>
</div>

<!-- Delete Confirmation Modal -->
<div class="modal hidden" id="deleteModal">
<div class="modal-content">
<div class="modal-title">Удаление сообщения</div>
<div class="modal-text">Удалить выбранное сообщение?</div>
<label class="modal-checkbox">
<input type="checkbox" id="deleteForAllCheck" checked>
<span>Удалить у всех</span>
</label>
<div style="display:flex;gap:8px;">
<button class="btn btn-secondary" style="flex:1" onclick="closeDeleteModal()">Отмена</button>
<button class="btn btn-danger" style="flex:1" onclick="confirmDeleteMessage()">Удалить</button>
</div>
</div>
</div>

<!-- Dialogs Screen -->
<div class="dialogs-screen hidden" id="dialogsScreen">
<div class="header">
<div class="header-menu-btn" onclick="openDrawer()" title="Меню">
<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
</div>
<div class="header-info" onclick="openDrawer()">
<div class="header-title-main" id="dialogsHeaderTitle">VK Tsuyu</div>
<div class="header-subtitle" id="dialogsHeaderSubtitle">Защищено Cloud E2EE</div>
</div>
<div class="header-actions">
<button class="header-btn active" id="encryptBtn" onclick="openEncryptModal()" title="Шифрование E2EE">
<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
</button>
</div>
</div>

<!-- Search Input Bar -->
<div class="search-bar-container">
<div class="search-input-wrap">
<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#8e8e93" stroke-width="2.2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
<input class="search-input" id="dialogSearchInput" placeholder="Поиск по диалогам и ВКонтакте..." oninput="handleSearchInput()">
</div>
</div>

<!-- Folder Tabs -->
<div class="folder-tabs" id="folderTabs"></div>

<div class="dialogs-list" id="dialogsList"></div>
<div class="news-feed hidden" id="newsFeed"></div>

<div class="bottom-nav">
<div class="nav-item active" onclick="showDialogs()">
<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/></svg>
<span>Чаты</span>
</div>
<div class="nav-item" onclick="openDrawer()">
<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
<span>Профиль</span>
</div>
<div class="nav-item" onclick="logout()">
<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
<span>Выход</span>
</div>
</div>
</div>

<!-- Chat Screen -->
<div class="chat-screen" id="chatScreen">
<div class="header">
<div class="header-back" onclick="backToDialogs()" title="Назад к личным чатам">
<svg viewBox="0 0 24 24">
<line x1="19" y1="12" x2="5" y2="12"></line>
<polyline points="12 19 5 12 12 5"></polyline>
</svg>
</div>
<img class="header-avatar" id="chatAvatar" src="" alt="" onclick="openProfileView(currentPeer, currentPeer < 0)" style="cursor:pointer">
<div class="header-info" onclick="backToDialogs()">
<div class="header-title" id="chatTitle">...</div>
<div class="header-subtitle" id="chatEncryptStatus">в сети • <span id="msgCount">0</span> сообщений</div>
</div>
<div class="header-actions">
<!-- Call Button -->
<button class="call-btn" id="callBtn" onclick="startCall(currentPeer)" title="Позвонить">
<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"/></svg>
</button>

<!-- SVG Magnifying Glass Icon — clean, no gray box background -->
<button class="header-btn" id="searchChatBtn" onclick="toggleChatSearch()" title="Поиск по сообщениям в чате" style="background:transparent;border:none">
<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
</button>
<button class="mark-read-btn hidden" id="manualMarkReadBtn" onclick="manualMarkChatAsRead()"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" style="vertical-align:middle;margin-right:4px"><polyline points="20 6 9 17 4 12"/></svg>Прочитать</button>
</div>
</div>

<div class="search-chat-bar hidden" id="searchChatBar" style="padding:8px 12px;background:#141416;border-bottom:1px solid #222;display:flex;align-items:center;gap:8px;z-index:10">
<div class="search-input-wrap" style="flex:1">
<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#8e8e93" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
<input type="text" id="chatSearchInput" placeholder="Поиск по сообщениям..." style="flex:1;background:transparent;border:none;padding:8px 0;color:#fff;font-size:14px;outline:none" oninput="searchInChat()">
</div>
<div id="searchCounter" style="font-size:12px;color:#888;white-space:nowrap">0/0</div>
<button class="header-btn" onclick="prevSearchResult()" style="width:32px;height:32px;background:rgba(255,255,255,0.08);border-radius:50%">↑</button>
<button class="header-btn" onclick="nextSearchResult()" style="width:32px;height:32px;background:rgba(255,255,255,0.08);border-radius:50%">↓</button>
<button class="header-btn" onclick="toggleChatSearch()" style="width:32px;height:32px;background:rgba(255,255,255,0.08);border-radius:50%">✕</button>
</div>

<div class="pinned-msg-bar hidden" id="pinnedMsgBar">
<div class="pinned-msg-info" onclick="scrollToPinnedMsg()">
<div class="pinned-msg-title"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:middle;margin-right:4px"><line x1="12" y1="17" x2="12" y2="22"/><path d="M5 17h14l-1.5-7h-11z"/><path d="M9 10V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v6"/></svg>Закрепленное сообщение</div>
<div class="pinned-msg-text" id="pinnedMsgText">...</div>
</div>
<div class="pinned-msg-close" onclick="unpinCurrentMessage()">
<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
</div>
</div>

<div class="messages-wrapper">
<div class="messages" id="messages"></div>
</div>

<div class="input-area-wrapper">
<div class="reply-preview-bar hidden" id="replyPreviewBar">
<div class="reply-preview-info">
<div class="reply-preview-title" id="replyPreviewTitle">Ответ на сообщение</div>
<div class="reply-preview-text" id="replyPreviewText">...</div>
</div>
<div class="reply-preview-close" onclick="cancelReplyOrEdit()">
<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
</div>
</div>

<div class="input-area" id="inputAreaNormal">
<div class="input-attach" onclick="document.getElementById('fileInput').click()">
<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/></svg>
</div>
<input type="file" class="file-input" id="fileInput" accept="image/*,video/*,*/*" onchange="handleFile(event)">

<textarea class="message-input" id="msgInput" placeholder="Сообщение..." rows="1" oninput="handleInputTyping()"></textarea>

<!-- SVG ICON FOR VOICE -->
<button class="media-rec-btn" id="voiceRecBtn" onclick="startVoiceRecording()" title="Голосовое сообщение (.mgs)">
<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/><line x1="8" y1="23" x2="16" y2="23"/></svg>
</button>

<!-- SVG ICON FOR CIRCLE VIDEO -->
<button class="media-rec-btn" id="circleRecBtn" onclick="startCircleRecording()" title="Кружочек (.mkru)">
<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><polygon points="10 8 16 12 10 16 10 8"/></svg>
</button>

<!-- SVG ICON FOR CUSTOM STICKER FROM GALLERY -->
<button class="media-rec-btn" id="stickerBtn" onclick="createStickerFromPhoto()" title="Свой стикер из галереи (.mst)">
<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>
</button>

<button class="send-btn hidden" id="sendBtn" onclick="sendMessage()">
<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z"/></svg>
</button>
</div>

<div class="input-area hidden" id="inputAreaVoice">
<div class="recording-bar">
<div class="recording-dot"></div>
<div class="recording-timer" id="voiceTimer">0:00</div>
<div style="flex:1"></div>
<div class="recording-cancel" onclick="cancelVoiceRecording()">Отмена</div>
</div>
<button class="send-btn" style="background:#ff3b30;color:#fff" onclick="stopAndSendVoiceRecording()">
<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z"/></svg>
</button>
</div>

</div>
</div>

<!-- TG Circle Camera Preview Modal -->
<div class="circle-recorder-modal hidden" id="circleModal">
<div class="circle-preview-box">
<video class="circle-preview-video" id="circleVideoPreview" autoplay muted playsinline></video>
</div>
<div class="recording-timer" id="circleTimer" style="margin-top:16px;font-size:18px">0:00</div>
<div class="circle-rec-controls">
<div class="circle-btn-action circle-btn-sec" onclick="toggleCircleCamera()" title="Сменить камеру">
<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 10c0-4.418-3.582-8-8-8s-8 3.582-8 8"/><path d="M4 14c0 4.418 3.582 8 8 8s8-3.582 8-8"/><polyline points="1 7 4 10 7 7"/><polyline points="23 17 20 14 17 17"/></svg>
</div>
<div class="circle-btn-action circle-btn-sec" id="circleTorchBtn" onclick="toggleCircleTorch()" title="Фонарик">
<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
</div>
<div class="circle-btn-action circle-btn-cancel" onclick="cancelCircleRecording()" title="Отмена">
<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
</div>
<div class="circle-btn-action circle-btn-stop" onclick="stopAndSendCircleRecording()" title="Отправить">
<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z"/></svg>
</div>
</div>
</div>

<!-- Action Sheet -->
<div class="action-sheet hidden" id="actionSheet" onclick="closeActionSheet(event)">
<div class="action-sheet-content" onclick="event.stopPropagation()">
<div class="action-sheet-item" onclick="triggerReplyFromSheet()">
<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 17 4 12 9 7"/><path d="M20 18v-2a4 4 0 0 0-4-4H4"/></svg>
Ответить
</div>
<div class="action-sheet-item" onclick="triggerPinMessageFromSheet()">
<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="17" x2="12" y2="22"/><path d="M5 17h14l-1.5-7h-11z"/><path d="M9 10V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v6"/></svg>
Закрепить сообщение
</div>
<div class="action-sheet-item" onclick="triggerForwardFromSheet()">
<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 14 20 9 15 4"/><path d="M4 20v-7a4 4 0 0 1 4-4h12"/></svg>
Переслать
</div>
<div class="action-sheet-item" id="editSheetItem" onclick="triggerEditFromSheet()">
<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>
Редактировать
</div>
<div class="action-sheet-item danger" onclick="triggerDeleteFromSheet()">
<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/></svg>
Удалить
</div>
<div class="action-sheet-item" style="justify-content:center;color:#888" onclick="closeActionSheet()">
Отмена
</div>
</div>
</div>

</div>

<!-- Profile View Modal -->
<div class="profile-view-modal" id="profileViewModal">
<div class="profile-view-header">
<div class="header-back" onclick="closeProfileView()" style="margin-right:10px">
<svg viewBox="0 0 24 24" style="width:22px;height:22px;stroke:#fff;stroke-width:2.5px;fill:none">
<line x1="19" y1="12" x2="5" y2="12"></line>
<polyline points="12 19 5 12 12 5"></polyline>
</svg>
</div>
<div class="header-title" id="profileViewHeaderTitle">Профиль</div>
</div>
<div class="profile-view-cover" id="profileViewCover"></div>
<div class="profile-view-avatar-wrap">
<img class="profile-view-avatar" id="profileViewAvatar" src="" alt="" onclick="openPhotoViewer(this.src)">
<div class="profile-view-name-wrap">
<div class="profile-view-name" id="profileViewName">...</div>
<div class="profile-view-status" id="profileViewStatus">...</div>
</div>
</div>
<div class="profile-view-info" id="profileViewInfo"></div>
<div class="profile-view-posts">
<div class="profile-view-post-title">Записи на стене</div>
<div id="profilePostsList"></div>
</div>
</div>

<!-- Create Folder Modal -->
<div class="modal hidden" id="createFolderModal">
<div class="modal-content">
<div class="modal-title">Создать папку</div>
<input type="text" class="token-input" id="newFolderName" placeholder="Название папки">
<div class="folder-create-list" id="folderCreateList"></div>
<div style="display:flex;gap:8px;margin-top:8px">
<button class="btn btn-secondary" style="flex:1" onclick="closeCreateFolderModal()">Отмена</button>
<button class="btn" style="flex:1" onclick="saveNewFolder()">Создать</button>
</div>
</div>
</div>

<script>
if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
        navigator.serviceWorker.register('/sw.js').catch(err => console.log('SW reg error:', err));
    });
}

const SVG_LIKE = `<svg viewBox="0 0 24 24"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>`;
const SVG_COMMENT = `<svg viewBox="0 0 24 24"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/></svg>`;
const SVG_SHARE = `<svg viewBox="0 0 24 24"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>`;

const ENCRYPT_PREFIX = "ENC2:";
let token = localStorage.getItem('vk_token');
let password = localStorage.getItem('vk_pass');

let stealthRead = localStorage.getItem('stealth_read') !== 'false';
let soundEnabled = localStorage.getItem('sound_enabled') !== 'false';
let encryptionEnabled = localStorage.getItem('encryption_enabled') !== 'false';

let currentPeer = null;
let currentUser = null;
let dialogsData = [];
let userGroupsData = [];
let globalSearchResults = [];
let searchDebounce = null;

let longPollTs = null;
let longPollKey = null;
let longPollServer = null;
let myVkId = null;

let localKeyPair = null;
let peerKeysCache = {};
let decryptedCache = {};

let renderedMsgIds = new Set();

let replyToMsg = null;
let editMsg = null;
let selectedMsgForAction = null;
let typingTimeout = null;

let pinnedPeers = JSON.parse(localStorage.getItem('vk_pinned_peers') || '[]');
let archivedPeers = JSON.parse(localStorage.getItem('vk_archived_peers') || '[]');
let pinnedMessagesMap = JSON.parse(localStorage.getItem('vk_pinned_messages') || '{}');

function showUploadProgress(text) {
    const toast = document.getElementById('uploadToast');
    const toastText = document.getElementById('uploadToastText');
    if (toast && toastText) {
        toastText.textContent = text || 'Загрузка...';
        toast.classList.remove('hidden');
    }
}

function hideUploadProgress() {
    const toast = document.getElementById('uploadToast');
    if (toast) toast.classList.add('hidden');
}

function openPhotoViewer(url) {
    if (!url) return;
    const modal = document.getElementById('photoViewerModal');
    const img = document.getElementById('photoViewerImg');
    img.src = url;
    modal.classList.add('active');
}

function closePhotoViewer() {
    document.getElementById('photoViewerModal').classList.remove('active');
}

function toggleStealthRead() {
    stealthRead = !stealthRead;
    setStealthReadState(stealthRead);
}

function setStealthReadState(val) {
    stealthRead = val;
    localStorage.setItem('stealth_read', val ? 'true' : 'false');
    const chk = document.getElementById('stealthReadToggle');
    if (chk) chk.checked = val;
}

function toggleSoundNotifications() {
    soundEnabled = !soundEnabled;
    setSoundState(soundEnabled);
}

function setSoundState(val) {
    soundEnabled = val;
    localStorage.setItem('sound_enabled', val ? 'true' : 'false');
    const chk = document.getElementById('soundToggle');
    if (chk) chk.checked = val;
}

function toggleEncryptionMode() {
    encryptionEnabled = !encryptionEnabled;
    setEncryptionState(encryptionEnabled);
}

function setEncryptionState(val) {
    encryptionEnabled = val;
    localStorage.setItem('encryption_enabled', val ? 'true' : 'false');
    const chk = document.getElementById('encryptToggle');
    if (chk) chk.checked = val;
}

async function updateCacheSizeDisplay() {
    let totalBytes = 0;
    if ('caches' in window) {
        try {
            const keys = await caches.keys();
            for (const key of keys) {
                const cache = await caches.open(key);
                const requests = await cache.keys();
                for (const req of requests) {
                    const res = await cache.match(req);
                    if (res) {
                        const blob = await res.clone().blob();
                        totalBytes += blob.size;
                    }
                }
            }
        } catch(e) {}
    }
    const mb = (totalBytes / (1024 * 1024)).toFixed(1);
    const elem = document.getElementById('cacheSizeText');
    if (elem) elem.textContent = `Очистить кэш (${mb} MB)`;
}

async function clearAppCache() {
    if (confirm("Очистить локальный кэш медиа и расшифрованных данных?")) {
        showUploadProgress("Очистка кэша...");
        decryptedCache = {};
        if ('caches' in window) {
            const keys = await caches.keys();
            await Promise.all(keys.map(k => caches.delete(k)));
        }
        hideUploadProgress();
        await updateCacheSizeDisplay();
        alert("Кэш успешно очищен!");
        location.reload();
    }
}

function bufToB64(buf) {
    let binary = '';
    const bytes = new Uint8Array(buf);
    for (let i = 0; i < bytes.byteLength; i++) binary += String.fromCharCode(bytes[i]);
    return btoa(binary);
}

function b64ToBuf(b64) {
    const binary = atob(b64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes.buffer;
}

async function generateDelfanFingerprint(pubKeyStr) {
    const msgUint8 = new TextEncoder().encode(pubKeyStr + (myVkId || ''));
    const hashBuffer = await crypto.subtle.digest('SHA-256', msgUint8);
    const hashArray = Array.from(new Uint8Array(hashBuffer));
    const hashHex = hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
    return `${hashHex.substr(0,8)}-${hashHex.substr(8,8)}-${hashHex.substr(16,8)}-${hashHex.substr(24,8)}`;
}

async function updateDelfanDisplay() {
    const elem = document.getElementById('delfanFingerprint');
    const pubElem = document.getElementById('modalPubKey');
    const privElem = document.getElementById('modalPrivKey');
    if (!localKeyPair) return;
    
    pubElem.textContent = localKeyPair.pubJwkStr;
    privElem.textContent = "[СКРЫТ ДЛЯ БЕЗОПАСНОСТИ - ХРАНИТСЯ В FIREBASE RTDB]";
    const fp = await generateDelfanFingerprint(localKeyPair.pubJwkStr);
    elem.textContent = fp;
}

function openEncryptModal() {
    updateDelfanDisplay();
    document.getElementById('encryptModal').classList.remove('hidden');
}

function closeEncryptModal() {
    document.getElementById('encryptModal').classList.add('hidden');
}

async function exportKeysFile() {
    if (!localKeyPair) return;
    const res = await fetch(`/api/keys/private/${myVkId}`);
    const data = res.ok ? await res.json() : null;
    if (!data || !data.private_key_enc) {
        alert('Приватный ключ не найден');
        return;
    }
    const blob = new Blob([JSON.stringify(data, null, 2)], {type: 'application/json'});
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `vk_meow_keys_${myVkId}.json`;
    a.click();
}

async function importKeysFile(e) {
    const file = e.target.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = async (evt) => {
        try {
            const data = JSON.parse(evt.target.result);
            if (data.public_key && data.private_key_enc) {
                await fetch(`/api/keys/${myVkId}`, {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify(data)
                });
                alert('Ключи успешно импортированы в Firebase RTDB! Перезагрузка...');
                location.reload();
            } else {
                alert('Неверный формат файла ключей');
            }
        } catch(err) {
            alert('Ошибка чтения файла ключей');
        }
    };
    reader.readAsText(file);
    e.target.value = '';
}

async function regenerateKeysPrompt() {
    if (confirm('ВНИМАНИЕ: Вы уверены, что хотите обновить пароль и пересоздать ключи E2EE?')) {
        localStorage.removeItem('vk_pass');
        const newPass = prompt('Введите новый мастер-пароль для шифрования:');
        if (!newPass) return;
        password = newPass;
        localStorage.setItem('vk_pass', newPass);
        await initClientCrypto(true);
        alert('Ключи обновлены в облаке!');
        openEncryptModal();
    }
}

async function deriveMasterKey(pass, saltStr) {
    const enc = new TextEncoder();
    const keyMaterial = await window.crypto.subtle.importKey(
        "raw", enc.encode(pass), "PBKDF2", false, ["deriveKey"]
    );
    return await window.crypto.subtle.deriveKey(
        {
            name: "PBKDF2",
            salt: enc.encode(saltStr),
            iterations: 600000,
            hash: "SHA-256"
        },
        keyMaterial,
        { name: "AES-GCM", length: 256 },
        false,
        ["encrypt", "decrypt"]
    );
}

async function encryptAESGCM(key, plainBuf) {
    const iv = window.crypto.getRandomValues(new Uint8Array(12));
    const ciphertext = await window.crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, plainBuf);
    const combined = new Uint8Array(iv.byteLength + ciphertext.byteLength);
    combined.set(iv, 0);
    combined.set(new Uint8Array(ciphertext), iv.byteLength);
    return combined.buffer;
}

async function decryptAESGCM(key, combinedBuf) {
    const bytes = new Uint8Array(combinedBuf);
    const iv = bytes.slice(0, 12);
    const ciphertext = bytes.slice(12);
    return await window.crypto.subtle.decrypt({ name: "AES-GCM", iv }, key, ciphertext);
}

async function initClientCrypto(forceNew = false) {
    if (!myVkId || !password) return false;
    showUploadProgress('Синхронизация ключей (Firebase RTDB)...');

    try {
        const masterKey = await deriveMasterKey(password, myVkId + "_vk_e2ee_salt");
        const res = !forceNew ? await fetch(`/api/keys/private/${myVkId}`) : null;
        const stored = res && res.ok ? await res.json() : null;

        if (stored && stored.public_key && stored.private_key_enc) {
            try {
                const privEncBuf = b64ToBuf(stored.private_key_enc);
                const privDecBuf = await decryptAESGCM(masterKey, privEncBuf);
                const privJwk = JSON.parse(new TextDecoder().decode(privDecBuf));
                const pubJwk = JSON.parse(stored.public_key);

                const publicKey = await window.crypto.subtle.importKey(
                    "jwk", pubJwk, { name: "RSA-OAEP", hash: "SHA-256" }, true, ["encrypt"]
                );
                const privateKey = await window.crypto.subtle.importKey(
                    "jwk", privJwk, { name: "RSA-OAEP", hash: "SHA-256" }, true, ["decrypt"]
                );

                localKeyPair = { publicKey, privateKey, pubJwkStr: stored.public_key };
                return true;
            } catch(e) {
                console.error("Local Key Decryption Failed:", e);
                alert("Неверный пароль шифрования для данного VK ID! Введенный пароль не подходит к зашифрованному ключу в облаке.");
                return false;
            }
        } else {
            const keyPair = await window.crypto.subtle.generateKey(
                { name: "RSA-OAEP", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
                true, ["encrypt", "decrypt"]
            );

            const pubJwk = await window.crypto.subtle.exportKey("jwk", keyPair.publicKey);
            const privJwk = await window.crypto.subtle.exportKey("jwk", keyPair.privateKey);
            const pubJwkStr = JSON.stringify(pubJwk);

            const privEncBuf = await encryptAESGCM(masterKey, new TextEncoder().encode(JSON.stringify(privJwk)));
            const privEncB64 = bufToB64(privEncBuf);

            await fetch(`/api/keys/${myVkId}`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ public_key: pubJwkStr, private_key_enc: privEncB64 })
            });

            localKeyPair = { publicKey: keyPair.publicKey, privateKey: keyPair.privateKey, pubJwkStr };
            return true;
        }
    } finally {
        hideUploadProgress();
    }
}

async function getPeerPubKey(peerId) {
    if (peerKeysCache[peerId]) return peerKeysCache[peerId];
    if (String(peerId) === String(myVkId)) {
        if (localKeyPair) {
            peerKeysCache[peerId] = localKeyPair.publicKey;
            return localKeyPair.publicKey;
        }
    }
    
    try {
        const res = await fetch(`/api/keys/${peerId}`);
        if (!res.ok) return null;
        const stored = await res.json();
        if (stored && stored.public_key) {
            const pubJwk = JSON.parse(stored.public_key);
            const key = await window.crypto.subtle.importKey(
                "jwk", pubJwk, { name: "RSA-OAEP", hash: "SHA-256" }, true, ["encrypt"]
            );
            peerKeysCache[peerId] = key;
            return key;
        }
    } catch(e){}
    return null;
}

async function clientEncryptData(peerKey, plainBuf) {
    if (!localKeyPair) {
        await initClientCrypto();
    }
    const sessionKey = await window.crypto.subtle.generateKey(
        { name: "AES-GCM", length: 256 }, true, ["encrypt", "decrypt"]
    );

    const encPayload = await encryptAESGCM(sessionKey, plainBuf);
    const rawSession = await window.crypto.subtle.exportKey("raw", sessionKey);

    const encKeyPeer = await window.crypto.subtle.encrypt({ name: "RSA-OAEP" }, peerKey, rawSession);
    const encKeySelf = await window.crypto.subtle.encrypt({ name: "RSA-OAEP" }, localKeyPair.publicKey, rawSession);

    return {
        k1: bufToB64(encKeyPeer),
        k2: bufToB64(encKeySelf),
        payload: bufToB64(encPayload)
    };
}

async function clientDecryptData(encObj) {
    if (!localKeyPair) {
        await initClientCrypto();
    }
    if (!localKeyPair) return null;
    let rawSession = null;

    try {
        rawSession = await window.crypto.subtle.decrypt({ name: "RSA-OAEP" }, localKeyPair.privateKey, b64ToBuf(encObj.k1));
    } catch(e) {
        try {
            rawSession = await window.crypto.subtle.decrypt({ name: "RSA-OAEP" }, localKeyPair.privateKey, b64ToBuf(encObj.k2));
        } catch(e2) {
            return null;
        }
    }

    const sessionKey = await window.crypto.subtle.importKey(
        "raw", rawSession, { name: "AES-GCM" }, false, ["decrypt"]
    );

    return await decryptAESGCM(sessionKey, b64ToBuf(encObj.payload));
}

const AUTH_URL = 'https://oauth.vk.com/authorize?client_id=3682744&scope=messages,audio,photos,video,docs,notes,pages,status,wall,groups,email,stats,notifications,offline&redirect_uri=https://oauth.vk.com/blank.html&response_type=token';

function getToken() { window.open(AUTH_URL, '_blank'); }

async function login() {
    const url = document.getElementById('tokenUrl').value.trim();
    const pass = document.getElementById('password').value.trim();
    if (!url) { alert('Вставь ссылку с токеном'); return; }
    if (!pass) { alert('Укажи пароль для E2EE'); return; }

    showUploadProgress('Вход через Kate Mobile API...');
    try {
        const res = await fetch('/api/auth', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url }) });
        const data = await res.json();
        if (data.error) { alert(data.error); return; }

        token = data.token;
        currentUser = data.user;
        myVkId = data.user.id;
        password = pass;

        localStorage.setItem('vk_token', token);
        localStorage.setItem('vk_pass', pass);
        localStorage.setItem('vk_user', JSON.stringify(data.user));

        const ok = await initClientCrypto();
        if (ok) {
            document.getElementById('loginScreen').classList.add('hidden');
            showDialogsScreen();
            loadDialogs();
            loadFolders();
            startLongPolling();
            updateDrawerProfile();
        }
    } finally {
        hideUploadProgress();
    }
}

function showDialogsScreen() {
    document.getElementById('dialogsScreen').classList.remove('hidden');
    if (currentUser) {
        document.getElementById('dialogsHeaderTitle').textContent = 'VK Tsuyu';
        document.getElementById('dialogsHeaderSubtitle').textContent = 'Защищено Cloud E2EE';
        updateDrawerProfile();
    }
}

async function fetchMyRealStatus() {
    if (!token) return;
    try {
        const res = await fetch('/api/my_status', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ token })
        });
        const data = await res.json();
        const onlineElem = document.getElementById('drawerOnlineStatus');
        if (data.online_text && onlineElem) {
            onlineElem.textContent = data.online_text;
            if (data.online === 1) {
                onlineElem.classList.remove('offline');
            } else {
                onlineElem.classList.add('offline');
            }
        }
    } catch(e){}
}

function updateDrawerProfile() {
    if (!currentUser) return;
    document.getElementById('drawerAvatar').src = currentUser.photo || '';
    document.getElementById('drawerName').textContent = currentUser.name || '';
    
    if (currentUser.status) {
        document.getElementById('drawerStatusText').textContent = currentUser.status;
    } else {
        document.getElementById('drawerStatusText').textContent = 'Нажмите, чтобы изменить описание...';
    }
    
    fetchMyRealStatus();

    setStealthReadState(stealthRead);
    setSoundState(soundEnabled);
    setEncryptionState(encryptionEnabled);
    updateCacheSizeDisplay();
}

let touchStartX = 0;
let touchStartY = 0;

function openDrawer() {
    updateCacheSizeDisplay();
    fetchMyRealStatus();
    document.getElementById('drawerOverlay').classList.add('active');
    document.getElementById('drawer').classList.add('active');
}

function closeDrawer() {
    document.getElementById('drawerOverlay').classList.remove('active');
    document.getElementById('drawer').classList.remove('active');
}

document.addEventListener('touchstart', (e) => {
    touchStartX = e.touches[0].clientX;
    touchStartY = e.touches[0].clientY;
}, { passive: true });

document.addEventListener('touchend', (e) => {
    const diffX = e.changedTouches[0].clientX - touchStartX;
    const diffY = Math.abs(e.changedTouches[0].clientY - touchStartY);

    if (diffY < 60) {
        if (touchStartX < 50 && diffX > 60) {
            openDrawer();
        }
        if (diffX < -60 && document.getElementById('drawer').classList.contains('active')) {
            closeDrawer();
        }
    }
}, { passive: true });

function openProfileEditModal() {
    closeDrawer();
    const nameParts = (currentUser.name || '').split(' ');
    document.getElementById('editFirstName').value = nameParts[0] || '';
    document.getElementById('editLastName').value = nameParts.slice(1).join(' ') || '';
    document.getElementById('editStatusInput').value = currentUser.status || '';
    document.getElementById('profileModal').classList.remove('hidden');
}

function closeProfileModal() {
    document.getElementById('profileModal').classList.add('hidden');
}

async function saveProfileChanges() {
    const firstName = document.getElementById('editFirstName').value.trim();
    const lastName = document.getElementById('editLastName').value.trim();
    const statusText = document.getElementById('editStatusInput').value.trim();

    showUploadProgress('Сохранение в VK (Kate Mobile)...');
    try {
        const res = await fetch('/api/profile/update', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ token, first_name: firstName, last_name: lastName, status: statusText })
        });
        const data = await res.json();
        if (data.ok) {
            if (firstName || lastName) currentUser.name = `${firstName} ${lastName}`.trim();
            currentUser.status = statusText;
            localStorage.setItem('vk_user', JSON.stringify(currentUser));
            updateDrawerProfile();
            closeProfileModal();
        } else {
            alert('Ошибка при сохранении профиля');
        }
    } catch(e) {
        alert('Ошибка связи с сервером');
    } finally {
        hideUploadProgress();
    }
}

function triggerAvatarSelect() {
    closeDrawer();
    document.getElementById('avatarFileInput').click();
}

async function uploadAvatarFile(e) {
    const file = e.target.files[0];
    if (!file) return;

    showUploadProgress('Обновление аватара (Kate Mobile)...');
    const formData = new FormData();
    formData.append('token', token);
    formData.append('photo', file);

    try {
        const res = await fetch('/api/profile/upload_avatar', { method: 'POST', body: formData });
        const data = await res.json();
        if (data.ok && data.photo_url) {
            currentUser.photo = data.photo_url;
            localStorage.setItem('vk_user', JSON.stringify(currentUser));
            document.getElementById('drawerAvatar').src = data.photo_url;
        } else {
            alert('Не удалось обновить аватар: ' + (data.error || 'ошибка VK API'));
        }
    } catch(err) {
        alert('Ошибка при загрузке фото');
    } finally {
        hideUploadProgress();
        e.target.value = '';
    }
}

function buildDialogPreviewHTML(d) {
    let rawText = d.last_message || '';
    const atts = d.last_attachments || [];

    let photoUrl = null;
    let videoUrl = null;
    let isCircle = false;
    let isVoice = false;

    for (const a of atts) {
        if (a.type === 'photo') {
            const p = a.photo?.sizes?.[0];
            if (p) photoUrl = p.url;
        } else if (a.type === 'video') {
            videoUrl = a.video?.first_frame?.find(s => s.url)?.url || a.video?.image?.[0]?.url;
        } else if (a.type === 'doc') {
            const title = (a.doc?.title || '').toLowerCase();
            const ext = (a.doc?.ext || '').toLowerCase();
            if (ext === 'mkru' || ext === 'mec' || title.includes('.mkru')) isCircle = true;
            else if (ext === 'mgs' || ext === 'meg' || title.includes('.mgs')) isVoice = true;
            else if (ext === 'meow' || title.includes('.meow')) photoUrl = a.doc?.url;
            else if (ext === 'mst' || title.includes('.mst')) { photoUrl = a.doc?.url; }
            else if (ext === 'mmu' || title.includes('.mmu')) isVoice = true;
        }
    }

    if (rawText.startsWith(ENCRYPT_PREFIX)) {
        return `<span class="dialog-preview-wrap" id="dialog-dec-${d.id}"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#8e8e93" stroke-width="2" style="vertical-align:middle;margin-right:4px"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>Зашифрованное сообщение</span>`;
    }

    if (isCircle) {
        return `<span class="dialog-preview-wrap"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#8e8e93" stroke-width="2" style="vertical-align:middle;margin-right:4px;flex-shrink:0"><polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/></svg>Кружочек</span>`;
    } else if (isVoice) {
        return `<span class="dialog-preview-wrap"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#8e8e93" stroke-width="2" style="vertical-align:middle;margin-right:4px;flex-shrink:0"><path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/><line x1="8" y1="23" x2="16" y2="23"/></svg>Голосовое</span>`;
    } else if (photoUrl) {
        return `<span class="dialog-preview-wrap"><img class="dialog-preview-thumb" src="${photoUrl}" onerror="this.style.display='none'"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#8e8e93" stroke-width="2" style="vertical-align:middle;margin-right:4px;flex-shrink:0"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>Фото ${escapeHtml(rawText)}</span>`;
    } else if (videoUrl) {
        return `<span class="dialog-preview-wrap"><img class="dialog-preview-thumb" src="${videoUrl}" onerror="this.style.display='none'"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#8e8e93" stroke-width="2" style="vertical-align:middle;margin-right:4px;flex-shrink:0"><polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/></svg>Видео ${escapeHtml(rawText)}</span>`;
    } else if (atts.find(a => a.type === 'doc' && ((a.doc?.ext || '').toLowerCase() === 'mst' || (a.doc?.title || '').toLowerCase().includes('.mst')))) {
        return `<span class="dialog-preview-wrap"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#8e8e93" stroke-width="2" style="vertical-align:middle;margin-right:4px;flex-shrink:0"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>Стикер</span>`;
    } else if (atts.find(a => a.type === 'doc' && ((a.doc?.ext || '').toLowerCase() === 'mmu' || (a.doc?.title || '').toLowerCase().includes('.mmu')))) {
        return `<span class="dialog-preview-wrap"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#8e8e93" stroke-width="2" style="vertical-align:middle;margin-right:4px;flex-shrink:0"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>Музыка</span>`;
    }

    return `<span class="dialog-preview-wrap">${escapeHtml(rawText || 'Сообщение')}</span>`;
}

async function fastDecryptDialogPreviews(dialogs) {
    for (const d of dialogs) {
        if (d.last_message && d.last_message.startsWith(ENCRYPT_PREFIX)) {
            setTimeout(async () => {
                try {
                    if (!localKeyPair) await initClientCrypto();
                    if (!localKeyPair) return;

                    const cleanPayload = unescapeVkText(d.last_message.substring(ENCRYPT_PREFIX.length));
                    const encObj = JSON.parse(cleanPayload);
                    const decBuf = await clientDecryptData(encObj);
                    if (decBuf) {
                        const plainText = new TextDecoder().decode(decBuf);
                        const elem = document.getElementById(`dialog-dec-${d.id}`);
                        if (elem) elem.innerHTML = `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#8e8e93" stroke-width="2" style="vertical-align:middle;margin-right:4px"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>${escapeHtml(plainText)}`;
                    }
                } catch(e){}
            }, 5);
        }
    }
}

async function loadDialogs() {
    try {
        const res = await fetch('/api/dialogs', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token }) });
        const data = await res.json();
        if (data.error) return;
        dialogsData = data.dialogs;
        
        if (currentFolder !== 'news' && currentFolder !== 'channels') {
            renderDialogsListFiltered();
        }
        fastDecryptDialogPreviews(data.dialogs);
    } catch(e){}
}

function handleSearchInput() {
    if (searchDebounce) clearTimeout(searchDebounce);
    searchDebounce = setTimeout(async () => {
        const query = document.getElementById('dialogSearchInput').value.trim();
        if (query.length > 1) {
            try {
                const res = await fetch('/api/search_global', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({ token, query })
                });
                const data = await res.json();
                globalSearchResults = data.results || [];
            } catch(e) {
                globalSearchResults = [];
            }
        } else {
            globalSearchResults = [];
        }
        renderDialogsListFiltered();
    }, 300);
}

function renderDialogsListFiltered() {
    const list = document.getElementById('dialogsList');
    list.innerHTML = '';
    const query = document.getElementById('dialogSearchInput').value.toLowerCase().trim();

    let filtered = dialogsData.filter(d => {
        const isArchived = archivedPeers.includes(String(d.id));
        if (currentFolder === 'archive') return isArchived;
        if (isArchived) return false;

        if (currentFolder === 'all') {
            if (d.type !== 'user' || String(d.id) === '100') return false;
        } else if (currentFolder === 'groups') {
            if (d.type !== 'chat' && d.type !== 'group' && String(d.id) !== '100') return false;
        } else if (currentFolder === 'unread') {
            if (d.unread <= 0) return false;
        } else if (currentFolder !== 'channels' && currentFolder !== 'news') {
            const folderData = customFolders.find(f => f.id === currentFolder);
            if (folderData && !folderData.peers.includes(String(d.id))) return false;
        }

        if (query) {
            return (d.name || '').toLowerCase().includes(query) || (d.last_message || '').toLowerCase().includes(query);
        }
        return true;
    });

    filtered.sort((a, b) => {
        const aPinned = pinnedPeers.includes(String(a.id));
        const bPinned = pinnedPeers.includes(String(b.id));
        if (aPinned && !bPinned) return -1;
        if (!aPinned && bPinned) return 1;
        return (b.date || 0) - (a.date || 0);
    });

    for (const d of filtered) {
        const div = document.createElement('div');
        div.className = 'dialog';
        div.onclick = () => openChatByObject(d);

        attachDialogSwipe(div, d);

        const time = d.date ? new Date(d.date * 1000).toLocaleTimeString('ru', { hour: '2-digit', minute: '2-digit' }) : '';
        const previewHTML = buildDialogPreviewHTML(d);
        const isPinned = pinnedPeers.includes(String(d.id));

        div.innerHTML = `
            <div class="dialog-avatar-wrap" onclick="event.stopPropagation();openProfileView(${d.id}, ${d.type === 'group' ? 'true' : 'false'})">
                <img class="dialog-avatar" src="${d.photo || 'https://vk.com/images/camera_100.png'}" onerror="this.src='https://vk.com/images/camera_100.png'">
                <div class="dialog-online-dot ${d.online ? '' : 'offline'}" id="dot-${d.id}"></div>
            </div>
            <div class="dialog-info">
                <div class="dialog-top">
                    <div class="dialog-name">${escapeHtml(d.name)}</div>
                    <div class="dialog-time">${time}</div>
                </div>
                <div class="dialog-bottom" id="preview-${d.id}">
                    <div class="dialog-preview">${previewHTML}</div>
                    ${d.unread > 0 ? `<div class="dialog-unread-blue">${d.unread}</div>` : ''}
                </div>
            </div>
            ${isPinned ? '<div class="dialog-pin-icon"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#8e8e93" stroke-width="2"><line x1="12" y1="17" x2="12" y2="22"/><path d="M5 17h14l-1.5-7h-11z"/><path d="M9 10V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v6"/></svg></div>' : ''}
        `;
        list.appendChild(div);
    }

    if (query && globalSearchResults.length > 0) {
        const header = document.createElement('div');
        header.className = 'search-section-header';
        header.textContent = 'Глобальный поиск ВКонтакте';
        list.appendChild(header);

        for (const item of globalSearchResults) {
            const div = document.createElement('div');
            div.className = 'dialog';
            div.onclick = () => openProfileView(item.id, item.type === 'group');

            div.innerHTML = `
                <div class="dialog-avatar-wrap">
                    <img class="dialog-avatar" src="${item.photo || 'https://vk.com/images/camera_100.png'}" onerror="this.src='https://vk.com/images/camera_100.png'">
                </div>
                <div class="dialog-info">
                    <div class="dialog-top">
                        <div class="dialog-name">${escapeHtml(item.name)}</div>
                    </div>
                    <div class="dialog-bottom">
                        <div class="dialog-preview">${escapeHtml(item.type === 'group' ? 'Канал / Группа ВКонтакте' : 'Пользователь ВКонтакте')}</div>
                    </div>
                </div>
            `;
            list.appendChild(div);
        }
    }
}

function attachDialogSwipe(elem, d) {
    let startX = 0;
    let currentX = 0;
    let isDragging = false;

    elem.addEventListener('touchstart', (e) => {
        startX = e.touches[0].clientX;
        isDragging = true;
    }, { passive: true });

    elem.addEventListener('touchmove', (e) => {
        if (!isDragging) return;
        currentX = e.touches[0].clientX - startX;
        if (currentX > 0 && currentX < 120) {
            elem.style.transform = `translateX(${currentX}px)`;
            elem.style.background = `rgba(255,59,48,${currentX/200})`;
        } else if (currentX < 0 && currentX > -120) {
            elem.style.transform = `translateX(${currentX}px)`;
            elem.style.background = `rgba(10,132,255,${Math.abs(currentX)/200})`;
        }
    }, { passive: true });

    elem.addEventListener('touchend', () => {
        if (!isDragging) return;
        isDragging = false;
        elem.style.background = '';
        if (currentX > 60) {
            toggleArchivePeer(String(d.id));
        } else if (currentX < -60) {
            togglePinPeer(String(d.id));
            if (navigator.vibrate) navigator.vibrate(20);
        }
        elem.style.transform = 'translateX(0px)';
        currentX = 0;
    });
}

function toggleArchivePeer(peerIdStr) {
    if (archivedPeers.includes(peerIdStr)) {
        archivedPeers = archivedPeers.filter(p => p !== peerIdStr);
    } else {
        archivedPeers.push(peerIdStr);
    }
    localStorage.setItem('vk_archived_peers', JSON.stringify(archivedPeers));
    renderDialogsListFiltered();
}

function togglePinPeer(peerIdStr) {
    if (pinnedPeers.includes(peerIdStr)) {
        pinnedPeers = pinnedPeers.filter(p => p !== peerIdStr);
    } else {
        pinnedPeers.push(peerIdStr);
    }
    localStorage.setItem('vk_pinned_peers', JSON.stringify(pinnedPeers));
    renderDialogsListFiltered();
}

async function loadUserGroups() {
    try {
        const res = await fetch('/api/user_groups', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ token }) });
        const data = await res.json();
        if (data.groups) {
            userGroupsData = data.groups;
            renderUserGroupsListTGStyle();
        }
    } catch(e){}
}

function renderUserGroupsListTGStyle() {
    const list = document.getElementById('dialogsList');
    list.innerHTML = '';
    const query = document.getElementById('dialogSearchInput').value.toLowerCase().trim();

    const filtered = userGroupsData.filter(g => {
        if (query) return (g.name || '').toLowerCase().includes(query) || (g.description || '').toLowerCase().includes(query);
        return true;
    });

    for (const g of filtered) {
        const div = document.createElement('div');
        div.className = 'dialog';
        div.onclick = () => openProfileView(-g.id, true);

        const avatar = g.photo || g.photo_200 || g.photo_100 || 'https://vk.com/images/camera_100.png';

        div.innerHTML = `
            <div class="dialog-avatar-wrap">
                <img class="dialog-avatar" src="${avatar}" onerror="this.src='https://vk.com/images/camera_100.png'">
            </div>
            <div class="dialog-info">
                <div class="dialog-top">
                    <div class="dialog-name">${escapeHtml(g.name)}</div>
                </div>
                <div class="dialog-bottom">
                    <div class="dialog-preview">${escapeHtml(g.activity || g.description || 'Канал/Сообщество (нажмите для постов)')}</div>
                </div>
            </div>
        `;
        list.appendChild(div);
    }

    if (query && globalSearchResults.length > 0) {
        const header = document.createElement('div');
        header.className = 'search-section-header';
        header.textContent = 'Глобальный поиск каналов';
        list.appendChild(header);

        for (const item of globalSearchResults) {
            if (item.type === 'group') {
                const div = document.createElement('div');
                div.className = 'dialog';
                div.onclick = () => openProfileView(item.id, true);

                div.innerHTML = `
                    <div class="dialog-avatar-wrap">
                        <img class="dialog-avatar" src="${item.photo || 'https://vk.com/images/camera_100.png'}" onerror="this.src='https://vk.com/images/camera_100.png'">
                    </div>
                    <div class="dialog-info">
                        <div class="dialog-top">
                            <div class="dialog-name">${escapeHtml(item.name)}</div>
                        </div>
                        <div class="dialog-bottom">
                            <div class="dialog-preview">Канал ВКонтакте</div>
                        </div>
                    </div>
                `;
                list.appendChild(div);
            }
        }
    }
}

function openChatByObject(d) {
    currentPeer = d.id;
    document.getElementById('chatTitle').textContent = d.name;
    document.getElementById('chatAvatar').src = d.photo || 'https://vk.com/images/camera_100.png';
    document.getElementById('chatScreen').classList.add('active');

    renderedMsgIds.clear();
    document.getElementById('messages').innerHTML = '';
    messagesOffset = 0;
    allMessagesLoaded = false;

    const markBtn = document.getElementById('manualMarkReadBtn');
    if (stealthRead && d.unread > 0) {
        markBtn.classList.remove('hidden');
    } else {
        markBtn.classList.add('hidden');
    }

    checkPinnedMessage();
    fetchPeerStatus();
    cancelReplyOrEdit();

    if (!stealthRead && d.unread > 0) {
        markChatAsReadServer(d.id);
        d.unread = 0;
    }

    loadMessages(true);
}

function checkPinnedMessage() {
    const bar = document.getElementById('pinnedMsgBar');
    const textElem = document.getElementById('pinnedMsgText');
    const pinnedMsg = pinnedMessagesMap[String(currentPeer)];
    if (pinnedMsg) {
        textElem.textContent = pinnedMsg.text || 'Вложение';
        bar.classList.remove('hidden');
    } else {
        bar.classList.add('hidden');
    }
}

function unpinCurrentMessage() {
    delete pinnedMessagesMap[String(currentPeer)];
    localStorage.setItem('vk_pinned_messages', JSON.stringify(pinnedMessagesMap));
    checkPinnedMessage();
}

function scrollToPinnedMsg() {
    const pinnedMsg = pinnedMessagesMap[String(currentPeer)];
    if (pinnedMsg) scrollToMsg(pinnedMsg.id);
}

async function markChatAsReadServer(peerId) {
    try {
        await fetch('/api/mark_read', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ token, peer_id: peerId })
        });
    } catch(e){}
}

async function manualMarkChatAsRead() {
    if (!currentPeer) return;
    await markChatAsReadServer(currentPeer);
    document.getElementById('manualMarkReadBtn').classList.add('hidden');
    const d = dialogsData.find(item => String(item.id) === String(currentPeer));
    if (d) d.unread = 0;
    renderDialogsListFiltered();
}

let chatSearchResults = [];
let currentSearchIndex = -1;

function toggleChatSearch() {
    const bar = document.getElementById('searchChatBar');
    bar.classList.toggle('hidden');
    if (!bar.classList.contains('hidden')) {
        document.getElementById('chatSearchInput').focus();
    }
}

function searchInChat() {
    const query = document.getElementById('chatSearchInput').value.toLowerCase().trim();
    chatSearchResults = [];
    currentSearchIndex = -1;

    document.querySelectorAll('.msg-search-highlight').forEach(el => {
        el.outerHTML = el.innerHTML;
    });

    if (!query) {
        document.getElementById('searchCounter').textContent = '0/0';
        return;
    }

    const msgs = document.querySelectorAll('.msg-text');
    msgs.forEach((el, idx) => {
        const text = el.textContent.toLowerCase();
        if (text.includes(query)) {
            chatSearchResults.push(el.closest('.msg'));
            const html = el.innerHTML;
            const regex = new RegExp(`(${escapeHtml(query)})`, 'gi');
            el.innerHTML = html.replace(regex, '<mark class="msg-search-highlight" style="background:#ffeb3b;color:#000;padding:1px 2px;border-radius:2px">$1</mark>');
        }
    });

    document.getElementById('searchCounter').textContent = `${chatSearchResults.length > 0 ? 1 : 0}/${chatSearchResults.length}`;
    if (chatSearchResults.length > 0) {
        currentSearchIndex = 0;
        chatSearchResults[0].scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
}

function nextSearchResult() {
    if (chatSearchResults.length === 0) return;
    currentSearchIndex = (currentSearchIndex + 1) % chatSearchResults.length;
    chatSearchResults[currentSearchIndex].scrollIntoView({ behavior: 'smooth', block: 'center' });
    document.getElementById('searchCounter').textContent = `${currentSearchIndex + 1}/${chatSearchResults.length}`;
}

function prevSearchResult() {
    if (chatSearchResults.length === 0) return;
    currentSearchIndex = (currentSearchIndex - 1 + chatSearchResults.length) % chatSearchResults.length;
    chatSearchResults[currentSearchIndex].scrollIntoView({ behavior: 'smooth', block: 'center' });
    document.getElementById('searchCounter').textContent = `${currentSearchIndex + 1}/${chatSearchResults.length}`;
}

function backToDialogs() {
    document.getElementById('chatScreen').classList.remove('active');
    currentPeer = null;
    renderedMsgIds.clear();
    messagesOffset = 0;
    allMessagesLoaded = false;
    cancelReplyOrEdit();
    chatSearchResults = [];
    currentSearchIndex = -1;
    document.getElementById('searchChatBar').classList.add('hidden');
    loadDialogs();
}

async function fetchPeerStatus() {
    if (!currentPeer) return;
    try {
        const res = await fetch('/api/peer_status', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ token, peer_id: currentPeer })
        });
        const data = await res.json();
        const statusElem = document.getElementById('chatEncryptStatus');
        if (data.status_text && !statusElem.classList.contains('typing')) {
            statusElem.textContent = data.status_text;
        }
    } catch(e){}
}

function triggerTypingSignal() {
    if (!currentPeer || stealthRead) return;
    if (typingTimeout) clearTimeout(typingTimeout);
    
    fetch('/api/typing', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({ token, peer_id: currentPeer })
    });

    typingTimeout = setTimeout(() => {}, 5000);
}

async function loadMessages(initialScroll = false) {
    if (!currentPeer) return;
    try {
        const res = await fetch('/api/messages', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token, peer_id: currentPeer }) });
        const data = await res.json();
        const container = document.getElementById('messages');
        
        if (data.messages) {
            const msgs = data.messages.reverse();
            const wasAtBottom = (container.scrollHeight - container.scrollTop - container.clientHeight) < 80;

            let hasNew = false;
            for (const m of msgs) {
                if (!renderedMsgIds.has(m.id)) {
                    renderedMsgIds.add(m.id);
                    renderMessageItem(container, m);
                    hasNew = true;
                }
            }

            if (initialScroll || (wasAtBottom && hasNew)) {
                container.scrollTop = container.scrollHeight;
            }
        }
    } catch(e){}
}

// Function to handle on-demand real-time decryption safely inside current chat
// Вспомогательная функция разэкранирования HTML-сущностей от VK LongPoll
function unescapeVkText(str) {
    if (!str) return '';
    return str
        .replace(/&quot;/g, '"')
        .replace(/&amp;/g, '&')
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .replace(/&#39;/g, "'")
        .replace(/&apos;/g, "'");
}

async function tryDecryptMessageRealTime(msgId, encryptedText) {
    try {
        if (!localKeyPair) await initClientCrypto();
        if (!localKeyPair) return;

        // Очищаем строку от экранирования VK LongPoll
        const cleanPayload = unescapeVkText(encryptedText.substring(ENCRYPT_PREFIX.length));
        const encObj = JSON.parse(cleanPayload);
        const decBuf = await clientDecryptData(encObj);

        if (decBuf) {
            const plainText = new TextDecoder().decode(decBuf);
            decryptedCache[msgId] = plainText;



            // Используем setTimeout, чтобы гарантировать, что элемент уже находится в DOM
            setTimeout(() => {
                const textElem = document.getElementById('msg-' + msgId)?.querySelector('.msg-text');
                if (textElem) {
                    textElem.innerHTML = escapeHtml(plainText);
                }
            }, 20);
        } else {
            setTimeout(() => {
                const textElem = document.getElementById('msg-' + msgId)?.querySelector('.msg-text');
                if (textElem) {
                    textElem.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#ff3b30" stroke-width="2" style="vertical-align:middle;margin-right:4px"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>Ошибка расшифровки';
                }
            }, 20);
        }
    } catch(e) {
        console.error('Real-time decrypt error:', e);
        setTimeout(() => {
            const textElem = document.getElementById('msg-' + msgId)?.querySelector('.msg-text');
            if (textElem) {
                textElem.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#ff3b30" stroke-width="2" style="vertical-align:middle;margin-right:4px"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>Ошибка расшифровки';
            }
        }, 20);
    }
}

function renderMessageItem(containerOrFragment, msg) {
    const container = containerOrFragment.nodeType === 11 ? document.getElementById('messages') : containerOrFragment;
    const containerDiv = document.createElement('div');
    containerDiv.className = 'msg-container';
    containerDiv.style.justifyContent = msg.out ? 'flex-end' : 'flex-start';
    
    const isEncrypted = msg.text && msg.text.startsWith(ENCRYPT_PREFIX);
    
    const swipeBgRight = document.createElement('div');
    swipeBgRight.className = 'msg-swipe-bg msg-swipe-right';
    swipeBgRight.innerHTML = `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 17 4 12 9 7"/><path d="M20 18v-2a4 4 0 0 0-4-4H4"/></svg>`;

    const div = document.createElement('div');
    div.className = 'msg ' + (msg.out ? 'msg-out' : 'msg-in');
    div.id = 'msg-' + msg.id;



    attachSwipeToReply(containerDiv, div, msg);

    let html = '';
    
    if (msg.reply_message) {
        const rMsg = msg.reply_message;
        const rAuthor = rMsg.name || (rMsg.from_id === myVkId ? 'Вы' : 'Собеседник');
        let rText = rMsg.text || 'Вложение';
        if (rText.startsWith(ENCRYPT_PREFIX)) rText = '[Зашифрованное]';
        html += `<div class="msg-reply-quote" onclick="scrollToMsg('${rMsg.id}')">
            <div class="msg-reply-name">${escapeHtml(rAuthor)}</div>
            <div class="msg-reply-text">${escapeHtml(rText)}</div>
        </div>`;
    }

    if (!msg.out && msg.name) html += `<div class="msg-author">${escapeHtml(msg.name)}</div>`;

    let displayText = msg.text || '';
    let isPureCircle = false;
    let isSticker = false;

    if (msg.attachments) {
        for (const a of msg.attachments) {
            if (a.type === 'doc') {
                const t = (a.doc?.title || '').toLowerCase();
                const e = (a.doc?.ext || '').toLowerCase();
                if (e === 'mst' || t.endsWith('.mst')) isSticker = true;
            }
        }
    }

    if (isEncrypted) {
        if (decryptedCache[msg.id]) {
            displayText = decryptedCache[msg.id];
            html += `<div class="msg-text">${escapeHtml(displayText)}</div>`;
        } else {
            html += `<div class="msg-text"><span class="decrypting-shimmer"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:middle;margin-right:4px"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>Расшифровка...</span></div>`;
            setTimeout(() => {
                tryDecryptMessageRealTime(msg.id, msg.text);
            }, 50);
        }
    } else {
        if (displayText) html += `<div class="msg-text">${escapeHtml(displayText)}</div>`;
    }

    if (msg.attachments) {
        for (const a of msg.attachments) {
            if (a.type === 'sticker') {
                isSticker = true;
                const images = a.sticker?.images || a.sticker?.images_with_background || [];
                const stickerUrl = images.length > 0 ? images[images.length - 1].url : '';
                if (stickerUrl) {
                    const proxiedUrl = `/api/proxy_file?url=${encodeURIComponent(stickerUrl)}`;
                    html += `<img src="${stickerUrl}" referrerpolicy="no-referrer" crossorigin="anonymous" onerror="this.onerror=null;this.src='${proxiedUrl}';" style="width:130px;height:130px;object-fit:contain;display:block">`;
                }
            } else if (a.type === 'photo') {
                const p = a.photo?.sizes?.find(s => s.type === 'x') || a.photo?.sizes?.[a.photo?.sizes?.length - 1];
                if (p) {
                    const photoUrl = p.url;
                    html += `<img class="msg-photo" src="${photoUrl}" onclick="openPhotoViewer('${photoUrl}')">`;
                }
            } else if (a.type === 'video') {
                const vidObj = a.video || {};
                const playerUrl = vidObj.player || '';
                const frameUrl = vidObj.first_frame?.find(s => s.url)?.url || vidObj.image?.[0]?.url;

                if (playerUrl) {
                    html += `<div class="tg-channel-video-wrap" style="margin-top:6px;border-radius:12px;overflow:hidden">
                        <iframe class="tg-channel-iframe" src="${playerUrl}" allowfullscreen></iframe>
                    </div>`;
                } else if (frameUrl) {
                    html += `<img class="msg-photo" src="${frameUrl}" onclick="openPhotoViewer('${frameUrl}')">`;
                }
            } else if (a.type === 'video_message') {
                // FIX: Support video messages (circles) from regular VK app
                const vm = a.video_message || {};
                const videoUrl = vm.link_mp4 || vm.link_ogg || '';
                const frameUrl = vm.first_frame?.find(s => s.url)?.url || vm.image?.[0]?.url || '';
                const vmId = `vm_${msg.id}_${vm.id || Date.now()}`;

                if (videoUrl) {
                    isPureCircle = true;
                    html += `<div class="tg-circle-container" id="${vmId}">
                        <video class="tg-circle-video" src="${videoUrl}" loop playsinline autoplay muted onclick="this.muted=!this.muted;this.paused?this.play():this.pause()"></video>
                        <div class="tg-circle-overlay"></div>
                    </div>`;
                } else if (frameUrl) {
                    html += `<img class="msg-photo" src="${frameUrl}" style="border-radius:50%;width:200px;height:200px;object-fit:cover">`;
                }
            } else if (a.type === 'audio_message') {
                const am = a.audio_message || {};
                const audioUrl = am.link_mp3 || am.link_ogg || '';
                const amId = `am_${msg.id}_${am.id}`;

                html += `<div class="tg-voice-container" id="${amId}">
                    <div class="tg-voice-play-btn" onclick="toggleAudioMsgPlay('${amId}', '${audioUrl}')">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>
                    </div>
                    <div class="tg-voice-wave-wrap">
                        <div class="tg-voice-waveform">
                            <div class="tg-voice-bar active" style="height:50%"></div>
                            <div class="tg-voice-bar active" style="height:80%"></div>
                            <div class="tg-voice-bar active" style="height:100%"></div>
                            <div class="tg-voice-bar" style="height:60%"></div>
                        </div>
                        <div class="tg-voice-info"><span><svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:middle;margin-right:3px"><path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/><line x1="8" y1="23" x2="16" y2="23"/></svg>Голосовое</span></div>
                    </div>
                </div>`;

            } else if (a.type === 'doc') {
                const doc = a.doc;
                const title = (doc.title || '').toLowerCase();
                const ext = (doc.ext || '').toLowerCase();

                const isCustomEnc = title.startsWith('enc_') || 
                    ext === 'meow' || ext === 'mer' || ext === 'mkru' || ext === 'mgs' ||
                    ext === 'enc' || ext === 'mec' || ext === 'meg' || ext === 'mur' || ext === 'mst' || ext === 'mmu' ||
                    title.endsWith('.meow') || title.endsWith('.mer') || title.endsWith('.mkru') || title.endsWith('.mgs') ||
                    title.endsWith('.mec') || title.endsWith('.meg') || title.endsWith('.mst') || title.endsWith('.mmu');

                if (isCustomEnc) {
                    const docId = `doc_${doc.owner_id}_${doc.id}`;
                    
                    if (ext === 'mkru' || ext === 'mec' || title.includes('.mkru') || title.includes('.mec')) {
                        isPureCircle = true;
                        html += `<div class="tg-circle-container" id="${docId}">
                            <div style="display:flex;align-items:center;justify-content:center;height:100%;color:#888;font-size:12px"><span class="loader"></span></div>
                        </div>`;
                    } else if (ext === 'mgs' || ext === 'meg' || title.includes('.mgs') || title.includes('.meg')) {
                        html += `<div class="tg-voice-container" id="${docId}">
                            <div class="tg-voice-play-btn"><span class="loader"></span></div>
                            <div class="tg-voice-wave-wrap">
                                <div class="tg-voice-waveform"><div class="tg-voice-bar active" style="height:50%"></div></div>
                                <div class="tg-voice-info"><span><svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:middle;margin-right:3px"><path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/><line x1="8" y1="23" x2="16" y2="23"/></svg>Голосовое...</span></div>
                            </div>
                        </div>`;
                    } else if (ext === 'mst' || title.includes('.mst')) {
                        html += `<div id="${docId}" style="width:140px;height:140px;display:flex;align-items:center;justify-content:center"><span class="loader"></span></div>`;
                        isSticker = true;
                    } else if (ext === 'mmu' || title.includes('.mmu')) {
                        html += `<div class="tg-voice-container" id="${docId}">
                            <div class="tg-voice-play-btn"><span class="loader"></span></div>
                            <div class="tg-voice-wave-wrap">
                                <div class="tg-voice-waveform"><div class="tg-voice-bar active" style="height:50%"></div></div>
                                <div class="tg-voice-info"><span><svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:middle;margin-right:3px"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>Музыка...</span></div>
                            </div>
                        </div>`;
                    } else {
                        html += `<div class="msg-file" id="${docId}"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0;margin-right:8px"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg><div class="msg-file-info"><div class="msg-file-name">Зашифрованный файл</div><div class="msg-file-size">Загрузка...</div></div></div>`;
                    }

                    // FIX: Delayed decryption to ensure DOM is ready
                    requestAnimationFrame(() => {
                        setTimeout(() => {
                            const el = document.getElementById(docId);
                            if (el) processEncryptedAttachment(docId, doc.url, ext || title);
                            else setTimeout(() => processEncryptedAttachment(docId, doc.url, ext || title), 100);
                        }, 50);
                    });
                } else {
                    html += `<div class="msg-file" onclick="window.open('${doc.url}', '_blank')"><span class="msg-file-icon">📎</span><div class="msg-file-info"><div class="msg-file-name">${escapeHtml(doc.title || 'Файл')}</div><div class="msg-file-size">${(doc.size / 1024).toFixed(1)} KB</div></div></div>`;
                }
            }
        }
    }

    if (isSticker) {
        div.classList.add('msg-sticker');
    }

    if (isPureCircle) {
        div.classList.add('msg-circle-mode');
    }

    const msgTime = msg.date ? new Date(msg.date * 1000).toLocaleTimeString('ru', { hour: '2-digit', minute: '2-digit' }) : '';
    html += `<div class="msg-time">${msgTime} ${msg.out ? '<span class="msg-status">✓</span>' : ''}</div>`;
    
    div.innerHTML = html;
    containerDiv.appendChild(div);
    containerDiv.appendChild(swipeBgRight);
    if (containerOrFragment.nodeType === 11) {
        containerOrFragment.appendChild(containerDiv);
    } else {
        container.appendChild(containerDiv);
    }
}

const audioPlayersCache = {};
function toggleAudioMsgPlay(elemId, url) {
    const elem = document.getElementById(elemId);
    if (!elem || !url) return;
    const btn = elem.querySelector('.tg-voice-play-btn');

    if (!audioPlayersCache[elemId]) {
        audioPlayersCache[elemId] = new Audio(url);
        audioPlayersCache[elemId].onended = () => {
            btn.innerHTML = `<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>`;
        };
    }

    const audio = audioPlayersCache[elemId];
    if (audio.paused) {
        audio.play();
        btn.innerHTML = `<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>`;
    } else {
        audio.pause();
        btn.innerHTML = `<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>`;
    }
}

function attachSwipeToReply(container, elem, msg) {
    let startX = 0;
    let currentX = 0;
    let isDragging = false;
    const swipeBg = container.querySelector('.msg-swipe-right');

    elem.addEventListener('touchstart', (e) => {
        startX = e.touches[0].clientX;
        isDragging = true;
    }, { passive: true });

    elem.addEventListener('touchmove', (e) => {
        if (!isDragging) return;
        currentX = e.touches[0].clientX - startX;
        if (currentX < 0 && currentX > -100) {
            elem.style.transform = `translateX(${currentX}px)`;
            if (swipeBg) swipeBg.style.opacity = Math.min(1, Math.abs(currentX) / 50);
        }
    }, { passive: true });

    elem.addEventListener('touchend', () => {
        if (!isDragging) return;
        isDragging = false;
        if (currentX < -50) {
            if (navigator.vibrate) navigator.vibrate(15);
            setReplyToMessage(msg);
        }
        elem.style.transform = 'translateX(0px)';
        if (swipeBg) swipeBg.style.opacity = '0';
        currentX = 0;
    });
}

function openActionSheet(msg) {
    selectedMsgForAction = msg;
    const editBtn = document.getElementById('editSheetItem');
    if (msg.out) editBtn.classList.remove('hidden');
    else editBtn.classList.add('hidden');
    document.getElementById('actionSheet').classList.remove('hidden');
}

function closeActionSheet() {
    document.getElementById('actionSheet').classList.add('hidden');
}

function triggerReplyFromSheet() {
    closeActionSheet();
    if (selectedMsgForAction) setReplyToMessage(selectedMsgForAction);
}

function triggerPinMessageFromSheet() {
    closeActionSheet();
    if (selectedMsgForAction && currentPeer) {
        pinnedMessagesMap[String(currentPeer)] = {
            id: selectedMsgForAction.id,
            text: decryptedCache[selectedMsgForAction.id] || selectedMsgForAction.text || 'Сообщение'
        };
        localStorage.setItem('vk_pinned_messages', JSON.stringify(pinnedMessagesMap));
        checkPinnedMessage();
    }
}

function triggerForwardFromSheet() {
    closeActionSheet();
    if (!selectedMsgForAction) return;
    const list = document.getElementById('forwardList');
    list.innerHTML = '';

    for (const d of dialogsData) {
        if (d.type === 'user') {
            const item = document.createElement('div');
            item.className = 'forward-item';
            item.onclick = () => confirmForwardToPeer(d.id);
            item.innerHTML = `
                <img src="${d.photo || 'https://vk.com/images/camera_100.png'}" onerror="this.src='https://vk.com/images/camera_100.png'">
                <div><b>${escapeHtml(d.name)}</b></div>
            `;
            list.appendChild(item);
        }
    }
    document.getElementById('forwardModal').classList.remove('hidden');
}

function closeForwardModal() {
    document.getElementById('forwardModal').classList.add('hidden');
}

async function confirmForwardToPeer(targetPeerId) {
    closeForwardModal();
    if (!selectedMsgForAction) return;

    showUploadProgress('Пересылка сообщения...');
    const forwardText = decryptedCache[selectedMsgForAction.id] || selectedMsgForAction.text || '';

    try {
        await fetch('/api/send', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ token, peer_id: targetPeerId, text: `[Переслано]: ${forwardText}` })
        });
        alert('Сообщение успешно переслано!');
    } catch(e) {
        alert('Ошибка при пересылке');
    } finally {
        hideUploadProgress();
    }
}

function triggerEditFromSheet() {
    closeActionSheet();
    if (selectedMsgForAction && selectedMsgForAction.out) startEditingMessage(selectedMsgForAction);
}

function triggerDeleteFromSheet() {
    closeActionSheet();
    if (selectedMsgForAction) document.getElementById('deleteModal').classList.remove('hidden');
}

function closeDeleteModal() {
    document.getElementById('deleteModal').classList.add('hidden');
}

async function confirmDeleteMessage() {
    if (!selectedMsgForAction) return;
    const msgId = selectedMsgForAction.id;
    const deleteForAll = document.getElementById('deleteForAllCheck').checked ? 1 : 0;
    
    closeDeleteModal();
    showUploadProgress('Удаление...');
    
    const elem = document.getElementById(`msg-${msgId}`);
    if (elem) elem.closest('.msg-container').remove();
    
    try {
        fetch('/api/delete', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ token, message_ids: msgId, delete_for_all: deleteForAll })
        });
    } finally {
        setTimeout(hideUploadProgress, 300);
        selectedMsgForAction = null;
    }
}

function setReplyToMessage(msg) {
    cancelReplyOrEdit();
    replyToMsg = msg;
    const title = document.getElementById('replyPreviewTitle');
    const text = document.getElementById('replyPreviewText');
    const author = msg.name || (msg.out ? 'Вы' : 'Собеседник');
    
    title.textContent = `Ответ на сообщение (${author})`;
    let previewText = msg.text || 'Вложение';
    if (previewText.startsWith(ENCRYPT_PREFIX)) previewText = '[Зашифрованное]';
    text.textContent = previewText;
    
    document.getElementById('replyPreviewBar').classList.remove('hidden');
    document.getElementById('msgInput').focus();
}

function startEditingMessage(msg) {
    cancelReplyOrEdit();
    editMsg = msg;
    const title = document.getElementById('replyPreviewTitle');
    const text = document.getElementById('replyPreviewText');
    
    title.textContent = 'Редактирование';
    let plainText = msg.text || '';
    if (decryptedCache[msg.id]) plainText = decryptedCache[msg.id];
    
    text.textContent = plainText;
    document.getElementById('msgInput').value = plainText;
    document.getElementById('replyPreviewBar').classList.remove('hidden');
    handleInputTyping();
    document.getElementById('msgInput').focus();
}

function cancelReplyOrEdit() {
    replyToMsg = null;
    editMsg = null;
    document.getElementById('replyPreviewBar').classList.add('hidden');
}

function scrollToMsg(msgId) {
    const elem = document.getElementById(`msg-${msgId}`);
    if (elem) {
        elem.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
}

async function processEncryptedAttachment(elemId, url, extInfo, retryCount = 0) {
    const elem = document.getElementById(elemId);
    if (!elem) {
        if (retryCount < 5) {
            setTimeout(() => processEncryptedAttachment(elemId, url, extInfo, retryCount + 1), 100);
        }
        return;
    }

    if (decryptedCache[elemId]) {
        renderDecryptedMedia(elem, decryptedCache[elemId]);
        return;
    }

    try {
        const resp = await fetch(`/api/proxy_file?url=${encodeURIComponent(url)}`);
        if (!resp.ok) {
            throw new Error(`Proxy error: ${resp.status}`);
        }
        const encArrayBuf = await resp.arrayBuffer();

        if (encArrayBuf.byteLength > 4) {
            try {
                const view = new DataView(encArrayBuf);
                const headerLen = view.getUint32(0);
                if (headerLen > 0 && headerLen < encArrayBuf.byteLength - 4) {
                    const headerJsonBytes = new Uint8Array(encArrayBuf, 4, headerLen);
                    const headerStr = new TextDecoder().decode(headerJsonBytes);

                    // FIX: Unescape HTML entities from VK LongPoll in header
                    const cleanHeaderStr = unescapeVkText(headerStr);
                    const header = JSON.parse(cleanHeaderStr);

                    if (header.k1 && header.k2) {
                        const encPayload = encArrayBuf.slice(4 + headerLen);
                        const decPayloadBuf = await clientDecryptData({
                            k1: header.k1,
                            k2: header.k2,
                            payload: bufToB64(encPayload)
                        });

                        if (decPayloadBuf) {
                            const blob = new Blob([decPayloadBuf], { type: header.mime || 'application/octet-stream' });
                            const blobUrl = URL.createObjectURL(blob);
                            decryptedCache[elemId] = { blobUrl, mime: header.mime, name: header.name, extInfo };
                            renderDecryptedMedia(elem, decryptedCache[elemId]);
                            return;
                        }
                    }
                }
            } catch(eParse) {
                console.log("Not encrypted binary file or header parse failed:", eParse);
            }
        }

        let fallbackMime = 'application/octet-stream';
        if (extInfo.includes('mmu')) fallbackMime = 'audio/mpeg';
        else if (extInfo.includes('mgs')) fallbackMime = 'audio/webm';
        else if (extInfo.includes('meow') || extInfo.includes('image')) fallbackMime = 'image/jpeg';
        else if (extInfo.includes('mer') || extInfo.includes('video')) fallbackMime = 'video/mp4';
        else if (extInfo.includes('mst')) fallbackMime = 'image/png';

        decryptedCache[elemId] = { blobUrl: `/api/proxy_file?url=${encodeURIComponent(url)}`, mime: fallbackMime, name: extInfo, extInfo };
        renderDecryptedMedia(elem, decryptedCache[elemId]);

    } catch (e) {
        console.error("Attachment process error:", e);
        if (elem.querySelector('.msg-file-size')) elem.querySelector('.msg-file-size').textContent = 'Ошибка загрузки';
    }
}

function renderDecryptedMedia(elem, data) {
    const nameStr = (data.name || '').toLowerCase();
    const extStr = (data.extInfo || '').toLowerCase();
    const mimeStr = (data.mime || '').toLowerCase();

    const isCircle = nameStr.includes('.mkru') || nameStr.includes('.mec') || 
                     extStr.includes('mkru') || extStr.includes('mec') || mimeStr.includes('video/webm');

    const isVoice = nameStr.includes('.mgs') || nameStr.includes('.meg') || 
                    extStr.includes('mgs') || extStr.includes('meg');

    const isMusic = nameStr.includes('.mmu') || extStr.includes('.mmu');

    const isPhoto = nameStr.includes('.meow') || extStr.includes('meow') || 
                    (mimeStr.startsWith('image/') && !nameStr.includes('.mst'));

    const isVideo = nameStr.includes('.mer') || extStr.includes('mer') || mimeStr.startsWith('video/mp4');

    const isSticker = nameStr.includes('.mst') || extStr.includes('.mst');

    if (isCircle) {
        const container = document.createElement('div');
        container.className = 'tg-circle-container';
        
        const video = document.createElement('video');
        video.className = 'tg-circle-video';
        video.src = data.blobUrl;
        video.loop = true;
        video.playsInline = true;
        video.autoplay = true;

        const overlay = document.createElement('div');
        overlay.className = 'tg-circle-overlay';

        container.appendChild(video);
        container.appendChild(overlay);

        container.onclick = () => {
            video.muted = !video.muted;
            if (video.paused) video.play();
        };

        const parentMsg = elem.closest('.msg');
        if (parentMsg) parentMsg.classList.add('msg-circle-mode');

        elem.replaceWith(container);

    } else if (isVoice) {
        const container = document.createElement('div');
        container.className = 'tg-voice-container';
        const audio = new Audio(data.blobUrl);
        
        container.innerHTML = `
            <div class="tg-voice-play-btn">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>
            </div>
            <div class="tg-voice-wave-wrap">
                <div class="tg-voice-waveform">
                    <div class="tg-voice-bar active" style="height:40%"></div>
                    <div class="tg-voice-bar active" style="height:70%"></div>
                    <div class="tg-voice-bar active" style="height:100%"></div>
                    <div class="tg-voice-bar active" style="height:60%"></div>
                    <div class="tg-voice-bar" style="height:80%"></div>
                    <div class="tg-voice-bar" style="height:50%"></div>
                </div>
                <div class="tg-voice-info">
                    <span class="v-time">0:00</span>
                    <span><svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:middle;margin-right:3px"><path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/><line x1="8" y1="23" x2="16" y2="23"/></svg>Голосовое</span>
                </div>
            </div>
        `;

        const playBtn = container.querySelector('.tg-voice-play-btn');
        const timeStr = container.querySelector('.v-time');

        audio.onloadedmetadata = () => {
            const m = Math.floor(audio.duration / 60);
            const s = Math.floor(audio.duration % 60).toString().padStart(2, '0');
            timeStr.textContent = `${m}:${s}`;
        };

        audio.ontimeupdate = () => {
            const cur = Math.floor(audio.currentTime);
            const m = Math.floor(cur / 60);
            const s = Math.floor(cur % 60).toString().padStart(2, '0');
            timeStr.textContent = `${m}:${s}`;
        };

        audio.onended = () => {
            playBtn.innerHTML = `<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>`;
        };

        playBtn.onclick = () => {
            if (audio.paused) {
                audio.play();
                playBtn.innerHTML = `<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>`;
            } else {
                audio.pause();
                playBtn.innerHTML = `<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>`;
            }
        };

        elem.replaceWith(container);

    } else if (isPhoto) {
        const img = document.createElement('img');
        img.className = 'msg-photo';
        img.src = data.blobUrl;
        img.onclick = () => openPhotoViewer(data.blobUrl);
        elem.replaceWith(img);
    } else if (isVideo) {
        const vid = document.createElement('video');
        vid.className = 'msg-video';
        vid.src = data.blobUrl;
        vid.controls = true;
        elem.replaceWith(vid);
    } else if ((data.name && data.name.endsWith('.mst')) || (data.extInfo && data.extInfo.includes('mst')) || data.mime === 'image/png') {
        const img = document.createElement('img');
        img.style.width = '140px';
        img.style.height = '140px';
        img.style.objectFit = 'contain';
        img.style.display = 'block';
        img.src = data.blobUrl;

        const parentMsg = elem.closest('.msg');
        if (parentMsg) {
            // Применяем новый класс для стилей Telegram
            parentMsg.classList.add('msg-sticker');
            // Удаляем старые inline-стили, которые теперь управляются CSS-классом
            parentMsg.style.background = '';
            parentMsg.style.padding = '';
            parentMsg.style.borderRadius = '';
            parentMsg.style.boxShadow = '';
            parentMsg.style.maxWidth = '';
        }
        elem.replaceWith(img);
    } else if (isMusic) {
        const container = document.createElement('div');
        container.className = 'tg-music-container';
        const audio = new Audio(data.blobUrl);
        const musicId = 'music_' + Math.random().toString(36).substr(2, 9);

        container.innerHTML = `
            <div class="tg-music-play-btn" id="${musicId}_btn">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>
            </div>
            <div class="tg-music-info">
                <div class="tg-music-title">${escapeHtml(data.name || 'Трек')}</div>
                <div class="tg-music-artist">Аудио</div>
                <div class="tg-music-progress" id="${musicId}_progress"><div class="tg-music-progress-bar" id="${musicId}_bar"></div></div>
                <div class="tg-music-time"><span id="${musicId}_cur">0:00</span><span id="${musicId}_dur">0:00</span></div>
            </div>
        `;

        const playBtn = container.querySelector('#' + musicId + '_btn');
        const progressBar = container.querySelector('#' + musicId + '_bar');
        const curTime = container.querySelector('#' + musicId + '_cur');
        const durTime = container.querySelector('#' + musicId + '_dur');

        const formatTime = (sec) => {
            const m = Math.floor(sec / 60);
            const s = Math.floor(sec % 60).toString().padStart(2, '0');
            return `${m}:${s}`;
        };

        audio.onloadedmetadata = () => {
            durTime.textContent = formatTime(audio.duration || 0);
        };

        audio.ontimeupdate = () => {
            const pct = audio.duration ? (audio.currentTime / audio.duration) * 100 : 0;
            progressBar.style.width = pct + '%';
            curTime.textContent = formatTime(audio.currentTime);
        };

        audio.onended = () => {
            playBtn.innerHTML = `<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>`;
            progressBar.style.width = '0%';
        };

        playBtn.onclick = () => {
            if (audio.paused) {
                audio.play();
                playBtn.innerHTML = `<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>`;
            } else {
                audio.pause();
                playBtn.innerHTML = `<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>`;
            }
        };

        elem.replaceWith(container);
    } else {
        if (elem.querySelector('.msg-file-size')) {
            elem.querySelector('.msg-file-size').textContent = 'Расшифровано (нажмите для скачивания)';
            elem.onclick = () => {
                const a = document.createElement('a');
                a.href = data.blobUrl;
                a.download = data.name || 'file';
                a.click();
            };
        }
    }
}

function escapeHtml(t) { const d = document.createElement('div'); d.textContent = t; return d.innerHTML; }

function handleInputTyping() {
    const val = document.getElementById('msgInput').value;
    const sendBtn = document.getElementById('sendBtn');
    const voiceBtn = document.getElementById('voiceRecBtn');
    const circleBtn = document.getElementById('circleRecBtn');
    const stickerBtn = document.getElementById('stickerBtn');

    if (val.length > 0) {
        triggerTypingSignal();
    }

    if (val.trim().length > 0 || editMsg) {
        sendBtn.classList.remove('hidden');
        voiceBtn.classList.add('hidden');
        circleBtn.classList.add('hidden');
        stickerBtn.classList.add('hidden');
    } else {
        sendBtn.classList.add('hidden');
        voiceBtn.classList.remove('hidden');
        circleBtn.classList.remove('hidden');
        stickerBtn.classList.remove('hidden');
    }
}

async function sendMessage() {
    const input = document.getElementById('msgInput');
    const text = input.value.trim();
    if (!text || !currentPeer) return;

    showUploadProgress('Отправка...');
    input.value = '';
    handleInputTyping();

    let sendText = text;
    if (encryptionEnabled) {
        try {
            const peerKey = await getPeerPubKey(currentPeer);
            if (peerKey) {
                const plainBuf = new TextEncoder().encode(text).buffer;
                const encObj = await clientEncryptData(peerKey, plainBuf);
                sendText = ENCRYPT_PREFIX + JSON.stringify(encObj);
            }
        } catch(eEnc) {
            console.error("E2EE encryption error, fallback to plain text:", eEnc);
        }
    }

    const payload = { token, peer_id: currentPeer, text: sendText };
    if (replyToMsg) payload.reply_to = replyToMsg.id;
    
    cancelReplyOrEdit();

    try {
        if (editMsg) {
            await fetch('/api/edit', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token, peer_id: currentPeer, message_id: editMsg.id, text: sendText })
            });
        } else {
            await fetch('/api/send', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
        }
    } catch(errSend) {
        alert("Ошибка сети при отправке!");
    } finally {
        hideUploadProgress();
        loadMessages(true);
    }
}

function getSupportedMimeType(kind) {
    if (kind === 'video') {
        const types = ['video/webm;codecs=vp8,opus', 'video/webm', 'video/mp4'];
        for (let t of types) { if (window.MediaRecorder && MediaRecorder.isTypeSupported(t)) return t; }
        return '';
    } else {
        const types = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/aac'];
        for (let t of types) { if (window.MediaRecorder && MediaRecorder.isTypeSupported(t)) return t; }
        return '';
    }
}

async function sendMediaBlob(blob, filename, mimeType) {
    if (!currentPeer) return;
    showUploadProgress('Отправка медиа...');
    
    try {
        let sent = false;
        if (encryptionEnabled) {
            try {
                const peerKey = await getPeerPubKey(currentPeer);
                if (peerKey) {
                    await uploadEncryptedMedia(blob, filename, mimeType);
                    sent = true;
                }
            } catch(eEnc) {
                console.error("Encrypted upload failed, fallback to normal:", eEnc);
            }
        }

        if (!sent) {
            const formData = new FormData();
            formData.append('token', token);
            formData.append('peer_id', currentPeer);
            formData.append('file', blob, filename);

            const res = await fetch('/api/upload_normal', { method: 'POST', body: formData });
            if (!res.ok) {
                throw new Error("Ошибка при не зашифрованной загрузке файла");
            }
        }
    } catch(e) {
        alert('Ошибка при отправке: ' + (e.message || e));
    } finally {
        hideUploadProgress();
        loadMessages(true);
    }
}

let voiceRecorder = null;
let voiceChunks = [];
let voiceTimerInterval = null;
let voiceSeconds = 0;

async function startVoiceRecording() {
    try {
        const mimeType = getSupportedMimeType('audio');
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        voiceChunks = [];
        
        const recOptions = mimeType ? { mimeType } : undefined;
        voiceRecorder = new MediaRecorder(stream, recOptions);

        voiceRecorder.ondataavailable = e => { if (e.data && e.data.size > 0) voiceChunks.push(e.data); };
        voiceRecorder.start(100);

        document.getElementById('inputAreaNormal').classList.add('hidden');
        document.getElementById('inputAreaVoice').classList.remove('hidden');

        voiceSeconds = 0;
        document.getElementById('voiceTimer').textContent = '0:00';
        if (voiceTimerInterval) clearInterval(voiceTimerInterval);
        voiceTimerInterval = setInterval(() => {
            voiceSeconds++;
            const m = Math.floor(voiceSeconds / 60);
            const s = (voiceSeconds % 60).toString().padStart(2, '0');
            document.getElementById('voiceTimer').textContent = `${m}:${s}`;
        }, 1000);
    } catch(e) { alert("Микрофон недоступен!"); }
}

function cancelVoiceRecording() {
    if (voiceRecorder && voiceRecorder.state !== 'inactive') voiceRecorder.stop();
    if (voiceTimerInterval) clearInterval(voiceTimerInterval);
    document.getElementById('inputAreaVoice').classList.add('hidden');
    document.getElementById('inputAreaNormal').classList.remove('hidden');
}

async function stopAndSendVoiceRecording() {
    if (!voiceRecorder || voiceRecorder.state === 'inactive') return;
    showUploadProgress('Голосовое сообщение...');

    voiceRecorder.onstop = async () => {
        if (voiceTimerInterval) clearInterval(voiceTimerInterval);
        document.getElementById('inputAreaVoice').classList.add('hidden');
        document.getElementById('inputAreaNormal').classList.remove('hidden');

        const blob = new Blob(voiceChunks, { type: voiceRecorder.mimeType || 'audio/webm' });
        if (blob.size > 0) {
            await sendMediaBlob(blob, `voice_${Date.now()}.mgs`, blob.type || 'audio/webm');
        } else hideUploadProgress();
    };

    try { voiceRecorder.requestData(); } catch(e){}
    voiceRecorder.stop();
}

let circleRecorder = null;
let circleChunks = [];
let circleStream = null;
let circleTimerInterval = null;
let circleSeconds = 0;
let currentFacingMode = 'user';
let isTorchOn = false;

async function startCircleRecording() {
    try {
        currentFacingMode = 'user';
        isTorchOn = false;
        try {
            circleStream = await navigator.mediaDevices.getUserMedia({ 
                video: { facingMode: currentFacingMode, width: { ideal: 480 }, height: { ideal: 480 } }, 
                audio: true 
            });
        } catch(errFallback) {
            circleStream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });
        }

        circleChunks = [];
        const videoElem = document.getElementById('circleVideoPreview');
        videoElem.srcObject = circleStream;
        videoElem.style.transform = (currentFacingMode === 'user') ? 'scaleX(-1)' : 'scaleX(1)';
        await videoElem.play().catch(e => console.log(e));

        document.getElementById('circleModal').classList.remove('hidden');

        const mimeType = getSupportedMimeType('video');
        const recOptions = mimeType ? { mimeType } : undefined;
        circleRecorder = new MediaRecorder(circleStream, recOptions);
        
        circleRecorder.ondataavailable = e => { if (e.data && e.data.size > 0) circleChunks.push(e.data); };
        circleRecorder.start(100);

        circleSeconds = 0;
        document.getElementById('circleTimer').textContent = '0:00';
        if (circleTimerInterval) clearInterval(circleTimerInterval);
        circleTimerInterval = setInterval(() => {
            circleSeconds++;
            const m = Math.floor(circleSeconds / 60);
            const s = (circleSeconds % 60).toString().padStart(2, '0');
            document.getElementById('circleTimer').textContent = `${m}:${s}`;
        }, 1000);

    } catch(e) { alert("Камера недоступна!"); }
}

async function toggleCircleCamera() {
    currentFacingMode = (currentFacingMode === 'user') ? 'environment' : 'user';
    if (!circleStream) return;

    try {
        const newVideoStream = await navigator.mediaDevices.getUserMedia({
            video: { facingMode: currentFacingMode }
        });
        const newVideoTrack = newVideoStream.getVideoTracks()[0];
        const oldVideoTrack = circleStream.getVideoTracks()[0];

        if (oldVideoTrack) {
            oldVideoTrack.stop();
            circleStream.removeTrack(oldVideoTrack);
        }
        circleStream.addTrack(newVideoTrack);

        const videoElem = document.getElementById('circleVideoPreview');
        videoElem.srcObject = circleStream;
        videoElem.style.transform = (currentFacingMode === 'user') ? 'scaleX(-1)' : 'scaleX(1)';
    } catch(err){}
}

async function toggleCircleTorch() {
    if (!circleStream) return;
    const videoTrack = circleStream.getVideoTracks()[0];
    if (!videoTrack) return;

    try {
        isTorchOn = !isTorchOn;
        await videoTrack.applyConstraints({ advanced: [{ torch: isTorchOn }] });
        document.getElementById('circleTorchBtn')?.classList.toggle('active', isTorchOn);
    } catch (e) {
        alert("Фонарик не поддерживается на данном устройстве");
    }
}

function cancelCircleRecording() {
    if (circleRecorder && circleRecorder.state !== 'inactive') circleRecorder.stop();
    if (circleStream) { circleStream.getTracks().forEach(t => t.stop()); circleStream = null; }
    if (circleTimerInterval) clearInterval(circleTimerInterval);
    document.getElementById('circleModal').classList.add('hidden');
}

async function stopAndSendCircleRecording() {
    if (!circleRecorder || circleRecorder.state === 'inactive') return;
    showUploadProgress('Кружочек...');

    circleRecorder.onstop = async () => {
        if (circleTimerInterval) clearInterval(circleTimerInterval);
        if (circleStream) { circleStream.getTracks().forEach(t => t.stop()); circleStream = null; }
        document.getElementById('circleModal').classList.add('hidden');

        const blob = new Blob(circleChunks, { type: circleRecorder.mimeType || 'video/webm' });
        if (blob.size > 0) {
            await sendMediaBlob(blob, `circle_${Date.now()}.mkru`, blob.type || 'video/webm');
        } else hideUploadProgress();
    };

    try { circleRecorder.requestData(); } catch(e){}
    circleRecorder.stop();
}

async function uploadEncryptedMedia(blob, filename, mimeType) {
    if (!currentPeer) return;
    const peerKey = await getPeerPubKey(currentPeer);
    if (!peerKey) throw new Error("У собеседника нет публичного ключа шифрования!");

    const fileArrayBuf = await blob.arrayBuffer();
    const encObj = await clientEncryptData(peerKey, fileArrayBuf);
    const payloadBuf = b64ToBuf(encObj.payload);

    const headerStr = JSON.stringify({ k1: encObj.k1, k2: encObj.k2, mime: mimeType, name: filename });
    const headerBytes = new TextEncoder().encode(headerStr);

    const resultBuf = new ArrayBuffer(4 + headerBytes.byteLength + payloadBuf.byteLength);
    const view = new DataView(resultBuf);
    view.setUint32(0, headerBytes.byteLength);

    const u8 = new Uint8Array(resultBuf);
    u8.set(headerBytes, 4);
    u8.set(new Uint8Array(payloadBuf), 4 + headerBytes.byteLength);

    const encBlob = new Blob([resultBuf], { type: 'application/octet-stream' });

    let ext = 'enc';
    if (filename.endsWith('.mkru') || mimeType.includes('mkru')) ext = 'mkru';
    else if (filename.endsWith('.mgs') || mimeType.includes('mgs')) ext = 'mgs';
    else if (filename.endsWith('.meow') || mimeType.startsWith('image/')) ext = 'meow';
    else if (filename.endsWith('.mer') || mimeType.startsWith('video/')) ext = 'mer';
    else if (filename.endsWith('.mst') || mimeType === 'image/png') ext = 'mst';
    else if (filename.endsWith('.mmu') || mimeType.startsWith('audio/')) ext = 'mmu';

    const formData = new FormData();
    formData.append('token', token);
    formData.append('peer_id', currentPeer);
    formData.append('file', encBlob, `enc_${Date.now()}.${ext}`);

    const res = await fetch('/api/upload_encrypted_doc', { method: 'POST', body: formData });
    const data = await res.json();
    if (!res.ok || data.error) {
        throw new Error(data.error || "Ошибка загрузки документа в VK");
    }
}

async function handleFile(e) {
    const file = e.target.files[0];
    if (!file || !currentPeer) return;
    try {
        let name = file.name;
        if (file.type.startsWith('image/')) {
            name = `photo_${Date.now()}.meow`;
        } else if (file.type.startsWith('video/')) {
            name = `video_${Date.now()}.mer`;
        } else if (file.type.startsWith('audio/')) {
            name = `music_${Date.now()}.mmu`;
        } else if (file.name.endsWith('.mst')) {
            name = file.name;
        }
        await sendMediaBlob(file, name, file.type || 'application/octet-stream');
    } finally {
        e.target.value = '';
    }
}

// Sticker creator from gallery photo with 512x512 square crop and PNG formatting
async function createStickerFromPhoto() {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.onchange = async (e) => {
        const file = e.target.files[0];
        if (!file || !currentPeer) return;

        showUploadProgress('Создание зашифрованного стикера...');
        try {
            const blob = await new Promise((resolve, reject) => {
                const canvas = document.createElement('canvas');
                const ctx = canvas.getContext('2d');
                const img = new Image();
                img.onload = () => {
                    const size = 512;
                    canvas.width = size;
                    canvas.height = size;

                    const radius = 50; // Скругление углов стикера
                    ctx.beginPath();
                    ctx.moveTo(radius, 0);
                    ctx.arcTo(size, 0, size, size, radius);
                    ctx.arcTo(size, size, 0, size, radius);
                    ctx.arcTo(0, size, 0, 0, radius);
                    ctx.arcTo(0, 0, size, 0, radius);
                    ctx.closePath();
                    ctx.clip();

                    const minDim = Math.min(img.width, img.height);
                    const sx = (img.width - minDim) / 2;
                    const sy = (img.height - minDim) / 2;
                    ctx.drawImage(img, sx, sy, minDim, minDim, 0, 0, size, size);

                    canvas.toBlob((b) => {
                        if (b) resolve(b);
                        else reject(new Error('Canvas toBlob error'));
                    }, 'image/png');
                };
                img.onerror = reject;
                img.src = URL.createObjectURL(file);
            });

            await sendMediaBlob(blob, `sticker_${Date.now()}.mst`, 'image/png');
        } catch(err) {
            alert('Ошибка создания стикера: ' + err.message);
        } finally {
            hideUploadProgress();
        }
    };
    input.click();
}

function playNotificationSound() {
    if (!soundEnabled) return;
    const audio = document.getElementById('notifSound');
    if (audio) {
        audio.currentTime = 0;
        audio.play().catch(e => {});
    }
}

async function startLongPolling() {
    try {
        const res = await fetch('/api/longpoll/init', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ token })
        });
        const data = await res.json();
        if (data.server && data.key && data.ts) {
            longPollServer = data.server;
            longPollKey = data.key;
            longPollTs = data.ts;
            pollEvents();
        }
    } catch(e) {
        setTimeout(startLongPolling, 3000);
    }
}

async function pollEvents() {
    if (!longPollServer || !longPollKey || !longPollTs) {
        setTimeout(startLongPolling, 2000);
        return;
    }

    try {
        const res = await fetch('/api/longpoll/listen', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ server: longPollServer, key: longPollKey, ts: longPollTs })
        });
        const data = await res.json();
        if (data.ts) {
            longPollTs = data.ts;
        }
        if (data.failed) {
            startLongPolling();
            return;
        }
        if (data.updates && data.updates.length > 0) {
            let hasNewMsg = false;
            let needsDialogUpdate = false;
            for (const u of data.updates) {
                const eventCode = u[0];
                if (eventCode === 4) {
                    hasNewMsg = true;
                    needsDialogUpdate = true;
                    const peerId = u[3];
                    const msgId = u[1];
                    const flags = u[2];
                    const text = u[5] || '';
                    const fromId = u[6] || peerId;

                    const d = dialogsData.find(item => String(item.id) === String(peerId));
                    if (d) {
                        d.last_message = text;
                        d.date = Math.floor(Date.now() / 1000);
                        if (!(flags & 2)) {
                            d.unread = (d.unread || 0) + 1;
                        }
                        const idx = dialogsData.indexOf(d);
                        if (idx > 0) {
                            dialogsData.splice(idx, 1);
                            dialogsData.unshift(d);
                        }
                    }

                    if (currentPeer && String(peerId) === String(currentPeer)) {
                        const isOut = (flags & 2) !== 0;
                        const newMsg = {
                            id: msgId,
                            text: text,
                            date: Math.floor(Date.now() / 1000),
                            from_id: fromId,
                            out: isOut ? 1 : 0,
                            name: isOut ? 'Вы' : d?.name || 'Собеседник',
                            photo: '',
                            attachments: []
                        };
                        if (!renderedMsgIds.has(msgId)) {
                            renderedMsgIds.add(msgId);
                            const container = document.getElementById('messages');
                            renderMessageItem(container, newMsg);
                            container.scrollTop = container.scrollHeight;
                        }
                        
                        // FIX: Instant decryption for incoming messages inside the active chat screen
                        if (text && text.startsWith(ENCRYPT_PREFIX)) {
                            setTimeout(() => {
                                tryDecryptMessageRealTime(msgId, text);
                            }, 50);
                        }
                    }
                } else if (eventCode === 3) {
                    const msgId = u[1];
                    const elem = document.getElementById('msg-' + msgId);
                    if (elem) elem.closest('.msg-container').remove();
                } else if (eventCode === 5) {
                    needsDialogUpdate = true;
                } else if (eventCode === 6 || eventCode === 7 || eventCode === 80) {
                    needsDialogUpdate = true;
                }
            }

            if (needsDialogUpdate) {
                renderDialogsListFiltered();
            }
            if (hasNewMsg) {
                playNotificationSound();
            }
        }
    } catch(e) {
        await new Promise(r => setTimeout(r, 1000));
    }
    setTimeout(pollEvents, 100);
}

function logout() {
    localStorage.clear();
    location.reload();
}

function showDialogs() {
    document.getElementById('chatScreen').classList.remove('active');
    loadDialogs();
}

document.getElementById('msgInput').addEventListener('keypress', e => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); }
});

let currentFolder = 'all';
let customFolders = [];

async function loadFolders() {
    try {
        const res = await fetch(`/api/folders/${myVkId}`);
        if (res.ok) {
            const data = await res.json();
            customFolders = data.folders || [];
            renderFolderTabs();
        }
    } catch(e) {}
}

function renderFolderTabs() {
    const tabs = document.getElementById('folderTabs');
    if (!tabs) return;
    tabs.innerHTML = '';

    const createTab = (id, name) => {
        const tab = document.createElement('div');
        tab.className = 'folder-tab ' + (currentFolder === id ? 'active' : '');
        tab.textContent = name;
        tab.onclick = () => switchFolder(id);
        tabs.appendChild(tab);
    };

    createTab('all', 'Личные');
    createTab('groups', 'Группы');
    createTab('channels', 'Каналы');
    createTab('news', 'Новости');
    createTab('unread', 'Непрочитанные');
    createTab('archive', 'Архив');

    for (const f of customFolders) {
        createTab(f.id, f.name);
    }

    const addTab = document.createElement('div');
    addTab.className = 'folder-tab';
    addTab.textContent = '+';
    addTab.onclick = openCreateFolderModal;
    tabs.appendChild(addTab);
}

async function switchFolder(folder) {
    currentFolder = folder;
    renderFolderTabs();

    const dialogsList = document.getElementById('dialogsList');
    const newsFeed = document.getElementById('newsFeed');

    if (folder === 'news') {
        dialogsList.classList.add('hidden');
        newsFeed.classList.remove('hidden');
        loadNewsFeed();
    } else if (folder === 'channels') {
        dialogsList.classList.remove('hidden');
        newsFeed.classList.add('hidden');
        await loadUserGroups();
    } else {
        dialogsList.classList.remove('hidden');
        newsFeed.classList.add('hidden');
        await loadDialogs();
    }
}

function buildTGPostMediaHTML(photo, video) {
    if (video && video.player) {
        return `<div class="tg-channel-video-wrap">
            <iframe class="tg-channel-iframe" src="${video.player}" allowfullscreen></iframe>
        </div>`;
    } else if (photo) {
        return `<img class="tg-channel-media" src="${photo}" onclick="openPhotoViewer('${photo}')" onerror="this.style.display='none'">`;
    }
    return '';
}

async function loadNewsFeed() {
    const feed = document.getElementById('newsFeed');
    feed.innerHTML = '';
    showUploadProgress('Загрузка полной ленты новостей...');
    try {
        const res = await fetch('/api/news', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({token}) });
        const data = await res.json();
        if (data.items) {
            for (const item of data.items) {
                const div = document.createElement('div');
                div.className = 'tg-channel-card';
                const mediaHTML = buildTGPostMediaHTML(item.photo, item.video);
                div.innerHTML = `
                    <div class="tg-channel-header">
                        <img class="tg-channel-avatar" src="${item.author_photo || 'https://vk.com/images/camera_100.png'}" onerror="this.src='https://vk.com/images/camera_100.png'">
                        <div>
                            <div class="tg-channel-title">${escapeHtml(item.author_name)}</div>
                            <div class="tg-channel-meta">${item.time || ''}</div>
                        </div>
                    </div>
                    ${mediaHTML}
                    <div class="tg-channel-body">${escapeHtml(item.text || '')}</div>
                    <div class="tg-channel-footer">
                        <div class="tg-channel-actions">
                            <div class="tg-channel-btn" onclick="openLikesModal('${item.owner_id || 0}', '${item.post_id || 0}')">${SVG_LIKE} ${item.likes || 0}</div>
                            <div class="tg-channel-btn" onclick="openCommentsModal('${item.owner_id || 0}', '${item.post_id || 0}')">${SVG_COMMENT} ${item.comments || 0}</div>
                        </div>
                        <div class="tg-channel-btn">${SVG_SHARE} ${item.reposts || 0}</div>
                    </div>
                `;
                feed.appendChild(div);
            }
        }
    } catch(e) {}
    hideUploadProgress();
}

async function openLikesModal(ownerId, postId) {
    showUploadProgress('Загрузка оценок (Kate Mobile)...');
    try {
        const res = await fetch('/api/post_likes', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ token, owner_id: ownerId, item_id: postId })
        });
        const data = await res.json();
        const list = document.getElementById('commentsList');
        document.getElementById('commentsHeaderTitle').textContent = `Оценили (${data.users ? data.users.length : 0})`;
        list.innerHTML = '';

        if (data.users && data.users.length > 0) {
            for (const u of data.users) {
                const item = document.createElement('div');
                item.className = 'like-user-item';
                item.innerHTML = `
                    <div class="like-user-left">
                        <img class="like-user-avatar" src="${u.photo || 'https://vk.com/images/camera_100.png'}" onerror="this.src='https://vk.com/images/camera_100.png'">
                        <div class="like-user-name">${escapeHtml(u.name)}</div>
                    </div>
                    <button class="like-user-btn" onclick="closeCommentsModal(); openProfileView(${u.id}, false)">Профиль</button>
                `;
                list.appendChild(item);
            }
        } else {
            list.innerHTML = '<div style="color:#666;text-align:center;padding:40px"><svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#444" stroke-width="1.5" style="display:block;margin:0 auto 8px"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>Пока никто не оценил</div>';
        }
        document.getElementById('commentsModal').classList.add('active');
    } catch(e){}
    hideUploadProgress();
}

async function openCommentsModal(ownerId, postId) {
    showUploadProgress('Загрузка комментариев...');
    try {
        const res = await fetch('/api/wall_comments', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ token, owner_id: ownerId, post_id: postId })
        });
        const data = await res.json();
        const list = document.getElementById('commentsList');
        document.getElementById('commentsHeaderTitle').textContent = 'Комментарии';
        list.innerHTML = '';

        if (data.comments && data.comments.length > 0) {
            for (const c of data.comments) {
                const item = document.createElement('div');
                item.className = 'comment-item';
                item.innerHTML = `
                    <img class="comment-avatar" src="${c.photo || 'https://vk.com/images/camera_100.png'}" onerror="this.src='https://vk.com/images/camera_100.png'">
                    <div class="comment-body">
                        <div class="comment-author">${escapeHtml(c.name)}</div>
                        <div class="comment-text">${escapeHtml(c.text)}</div>
                        <div class="comment-time">${c.time || ''}</div>
                    </div>
                `;
                list.appendChild(item);
            }
        } else {
            list.innerHTML = '<div style="color:#666;text-align:center;padding:40px"><svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#444" stroke-width="1.5" style="display:block;margin:0 auto 8px"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/></svg>Комментариев пока нет</div>';
        }
        document.getElementById('commentsModal').classList.add('active');
    } catch(e){}
    hideUploadProgress();
}

function closeCommentsModal() {
    document.getElementById('commentsModal').classList.remove('active');
}

async function openProfileView(peerId, isGroup) {
    showUploadProgress('Загрузка профиля и постов канала...');
    try {
        const res = await fetch('/api/profile_view', {
            method: 'POST',
            headers: {'Content-Type':'application/json'},
            body: JSON.stringify({token, peer_id: peerId, is_group: isGroup})
        });
        const data = await res.json();
        if (data.error) { hideUploadProgress(); return; }

        document.getElementById('profileViewAvatar').src = data.photo || 'https://vk.com/images/camera_100.png';
        document.getElementById('profileViewName').textContent = data.name || '...';
        document.getElementById('profileViewStatus').textContent = data.status || '';
        document.getElementById('profileViewHeaderTitle').textContent = data.name || 'Профиль';

        const coverElem = document.getElementById('profileViewCover');
        if (data.cover_photo) {
            coverElem.style.backgroundImage = `url('${data.cover_photo}')`;
        } else {
            coverElem.style.backgroundImage = 'none';
        }

        const infoDiv = document.getElementById('profileViewInfo');
        infoDiv.innerHTML = '';
        if (data.city) infoDiv.innerHTML += `<div class="profile-view-info-item"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:middle;margin-right:6px;flex-shrink:0"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg>${escapeHtml(data.city)}</div>`;
        if (data.bdate) infoDiv.innerHTML += `<div class="profile-view-info-item"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:middle;margin-right:6px;flex-shrink:0"><rect x="3" y="4" width="18" height="18" rx="2" ry="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/></svg>${escapeHtml(data.bdate)}</div>`;
        if (data.site) infoDiv.innerHTML += `<div class="profile-view-info-item"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:middle;margin-right:6px;flex-shrink:0"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>${escapeHtml(data.site)}</div>`;

        const postsList = document.getElementById('profilePostsList');
        postsList.innerHTML = '';

        if (data.posts && data.posts.length > 0) {
            for (const post of data.posts) {
                const div = document.createElement('div');
                div.className = 'tg-channel-card';
                const mediaHTML = buildTGPostMediaHTML(post.photo, post.video);
                div.innerHTML = `
                    <div class="tg-channel-header">
                        <img class="tg-channel-avatar" src="${data.photo || 'https://vk.com/images/camera_100.png'}" onerror="this.src='https://vk.com/images/camera_100.png'">
                        <div>
                            <div class="tg-channel-title">${escapeHtml(data.name)}</div>
                        </div>
                    </div>
                    ${mediaHTML}
                    <div class="tg-channel-body">${escapeHtml(post.text || '')}</div>
                    <div class="tg-channel-footer">
                        <div class="tg-channel-actions">
                            <div class="tg-channel-btn" onclick="openLikesModal('${post.owner_id || peerId}', '${post.id}')">${SVG_LIKE} ${post.likes || 0}</div>
                            <div class="tg-channel-btn" onclick="openCommentsModal('${post.owner_id || peerId}', '${post.id}')">${SVG_COMMENT} ${post.comments || 0}</div>
                        </div>
                        <div class="tg-channel-btn">${SVG_SHARE} ${post.reposts || 0}</div>
                    </div>
                `;
                postsList.appendChild(div);
            }
        } else {
            postsList.innerHTML = '<div class="profile-view-empty"><svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#444" stroke-width="1.5" style="display:block;margin:0 auto 8px"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><line x1="9" y1="9" x2="15" y2="15"/><line x1="15" y1="9" x2="9" y2="15"/></svg>Нет постов для отображения</div>';
        }

        document.getElementById('profileViewModal').classList.add('active');
    } catch(e) {}
    hideUploadProgress();
}

function closeProfileView() {
    document.getElementById('profileViewModal').classList.remove('active');
}

function openCreateFolderModal() {
    document.getElementById('newFolderName').value = '';
    const list = document.getElementById('folderCreateList');
    list.innerHTML = '';
    for (const d of dialogsData) {
        if (d.type === 'user') {
            const item = document.createElement('div');
            item.className = 'folder-create-item';
            item.innerHTML = `
                <input type="checkbox" value="${d.id}" id="chk-${d.id}">
                <img src="${d.photo || 'https://vk.com/images/camera_100.png'}" onerror="this.src='https://vk.com/images/camera_100.png'">
                <span>${escapeHtml(d.name)}</span>
            `;
            list.appendChild(item);
        }
    }
    document.getElementById('createFolderModal').classList.remove('hidden');
}

function closeCreateFolderModal() {
    document.getElementById('createFolderModal').classList.add('hidden');
}

async function saveNewFolder() {
    const name = document.getElementById('newFolderName').value.trim();
    if (!name) return;

    const peers = [];
    document.querySelectorAll('#folderCreateList input[type="checkbox"]:checked').forEach(chk => {
        peers.push(chk.value);
    });

    showUploadProgress('Сохранение папки...');
    try {
        await fetch(`/api/folders/${myVkId}`, {
            method: 'POST',
            headers: {'Content-Type':'application/json'},
            body: JSON.stringify({name, peers})
        });
        await loadFolders();
        closeCreateFolderModal();
    } catch(e) {}
    hideUploadProgress();
}

(async () => {
    if (token && password && localStorage.getItem('vk_user')) {
        try {
            currentUser = JSON.parse(localStorage.getItem('vk_user'));
            myVkId = currentUser.id;
            document.getElementById('loginScreen').classList.add('hidden');
            showDialogsScreen();
            const ok = await initClientCrypto();
            if (ok) {
                loadDialogs();
                loadFolders();
                startLongPolling();
            } else {
                document.getElementById('loginScreen').classList.remove('hidden');
            }
        } catch(e) {
            document.getElementById('loginScreen').classList.remove('hidden');
        }
    }
})();


/* ===== NEW FEATURES & IMPROVEMENTS ===== */

/* --- 1. OFFLINE MODE & NETWORK MONITORING --- */
let isOnline = navigator.onLine;
let offlineQueue = [];

function updateNetworkStatus() {
    isOnline = navigator.onLine;
    const banner = document.getElementById('offlineBanner');
    if (!isOnline) {
        banner.classList.add('active');
    } else {
        banner.classList.remove('active');
        processOfflineQueue();
    }
}

window.addEventListener('online', updateNetworkStatus);
window.addEventListener('offline', updateNetworkStatus);

function queueOfflineAction(action) {
    offlineQueue.push(action);
    showUploadProgress('Сохранено для отправки офлайн...');
    setTimeout(hideUploadProgress, 1000);
}

async function processOfflineQueue() {
    if (offlineQueue.length === 0) return;
    showUploadProgress(`Отправка ${offlineQueue.length} сообщений из офлайн-очереди...`);
    while (offlineQueue.length > 0) {
        const action = offlineQueue.shift();
        try { await action(); } catch(e) {}
    }
    hideUploadProgress();
}

/* --- 2. SCROLL TO BOTTOM FAB --- */
let unreadInChat = 0;

function setupScrollFab() {
    const container = document.getElementById('messages');
    const fab = document.getElementById('scrollFab');
    const unreadDot = document.getElementById('scrollFabUnread');

    container.addEventListener('scroll', () => {
        const isNearBottom = (container.scrollHeight - container.scrollTop - container.clientHeight) < 200;
        if (isNearBottom) {
            fab.classList.remove('active');
            unreadInChat = 0;
            unreadDot.classList.add('hidden');
        } else if (unreadInChat > 0) {
            fab.classList.add('active');
        }
    });
}

function scrollToBottomChat() {
    const container = document.getElementById('messages');
    container.scrollTop = container.scrollHeight;
    unreadInChat = 0;
    document.getElementById('scrollFabUnread').classList.add('hidden');
}

function showScrollFab() {
    const fab = document.getElementById('scrollFab');
    const container = document.getElementById('messages');
    const isNearBottom = (container.scrollHeight - container.scrollTop - container.clientHeight) < 200;
    if (!isNearBottom) {
        fab.classList.add('active');
        unreadInChat++;
        const dot = document.getElementById('scrollFabUnread');
        dot.textContent = unreadInChat;
        dot.classList.remove('hidden');
    }
}



/* --- 4. MULTI-SELECT MODE --- */
let multiSelectMode = false;
let selectedMessages = new Set();

function enterMultiSelectMode() {
    multiSelectMode = true;
    selectedMessages.clear();
    document.body.classList.add('msg-select-mode');
    updateMultiSelectBar();
}

function exitMultiSelectMode() {
    multiSelectMode = false;
    selectedMessages.clear();
    document.body.classList.remove('msg-select-mode');
    document.querySelectorAll('.msg-selected').forEach(el => el.classList.remove('msg-selected'));
    document.getElementById('multiSelectBar').classList.remove('active');
}

function toggleMessageSelect(msgId) {
    const msgEl = document.getElementById('msg-' + msgId);
    if (!msgEl) return;

    if (selectedMessages.has(msgId)) {
        selectedMessages.delete(msgId);
        msgEl.classList.remove('msg-selected');
    } else {
        selectedMessages.add(msgId);
        msgEl.classList.add('msg-selected');
    }
    updateMultiSelectBar();
}

function updateMultiSelectBar() {
    const bar = document.getElementById('multiSelectBar');
    const count = document.getElementById('multiSelectCount');
    count.textContent = `Выбрано: ${selectedMessages.size}`;

    if (selectedMessages.size > 0) {
        bar.classList.add('active');
    } else {
        bar.classList.remove('active');
    }
}

function multiSelectForward() {
    const msgs = Array.from(selectedMessages).map(id => ({ id, text: decryptedCache[id] || '' }));
    // Forward all selected
    exitMultiSelectMode();
}

function multiSelectDelete() {
    if (!confirm(`Удалить ${selectedMessages.size} сообщений?`)) return;
    selectedMessages.forEach(id => {
        const elem = document.getElementById('msg-' + id);
        if (elem) elem.closest('.msg-container').remove();
        fetch('/api/delete', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ token, message_ids: id, delete_for_all: 1 })
        });
    });
    exitMultiSelectMode();
}

/* --- 6. PIN / APP LOCK --- */
let pinCode = localStorage.getItem('vk_pin') || '';
let tempPin = '';
let pinCallback = null;

function openPinModal(callback, text) {
    pinCallback = callback;
    tempPin = '';
    updatePinDots();
    document.getElementById('pinAuthText').textContent = text || 'Введите PIN-код';
    document.getElementById('pinAuthModal').classList.remove('hidden');
}

function closePinModal() {
    document.getElementById('pinAuthModal').classList.add('hidden');
    tempPin = '';
}

function pinInput(digit) {
    if (tempPin.length < 4) {
        tempPin += digit;
        updatePinDots();
        if (tempPin.length === 4) {
            setTimeout(() => {
                if (pinCallback) pinCallback(tempPin);
            }, 200);
        }
    }
}

function pinBackspace() {
    tempPin = tempPin.slice(0, -1);
    updatePinDots();
}

function pinCancel() {
    closePinModal();
}

function updatePinDots() {
    const dots = document.querySelectorAll('#pinDots .bio-dot');
    dots.forEach((dot, i) => {
        dot.classList.toggle('filled', i < tempPin.length);
    });
}

function shakePinModal() {
    const modal = document.querySelector('#pinAuthModal .modal-content');
    modal.classList.add('bio-shake');
    setTimeout(() => modal.classList.remove('bio-shake'), 400);
}

function setupAppLock() {
    const savedPin = localStorage.getItem('vk_pin');
    if (savedPin) {
        // Lock on tab switch / minimize
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'visible' && token) {
                openPinModal((entered) => {
                    if (entered === savedPin) {
                        closePinModal();
                    } else {
                        shakePinModal();
                        tempPin = '';
                        updatePinDots();
                    }
                }, 'Разблокируйте приложение');
            }
        });
    }
}

function setPinCode() {
    const newPin = prompt('Введите новый 4-значный PIN (или оставьте пустым для отключения):');
    if (newPin === '') {
        localStorage.removeItem('vk_pin');
        alert('Блокировка отключена');
    } else if (newPin && /^\d{4}$/.test(newPin)) {
        localStorage.setItem('vk_pin', newPin);
        alert('PIN установлен');
    } else if (newPin) {
        alert('PIN должен быть ровно 4 цифры');
    }
}

/* --- 8. IMPROVED MESSAGE RENDERING --- */
let lastRenderedDate = null;

function renderDateSeparator(timestamp) {
    const date = new Date(timestamp * 1000);
    const today = new Date();
    const yesterday = new Date(today);
    yesterday.setDate(yesterday.getDate() - 1);

    let label;
    if (date.toDateString() === today.toDateString()) label = 'Сегодня';
    else if (date.toDateString() === yesterday.toDateString()) label = 'Вчера';
    else label = date.toLocaleDateString('ru', { day: 'numeric', month: 'long' });

    const div = document.createElement('div');
    div.className = 'msg-date-separator';
    div.innerHTML = `<span>${label}</span>`;
    return div;
}

function renderUnreadDivider() {
    const div = document.createElement('div');
    div.className = 'unread-divider';
    div.innerHTML = '<span>Непрочитанные сообщения</span>';
    return div;
}

/* --- 9. IMPROVED POLL EVENTS (with typing indicator) --- */
let typingPeers = new Set();
let typingTimeoutMap = {};

function showTypingIndicator(peerId) {
    if (currentPeer && String(peerId) === String(currentPeer)) {
        const container = document.getElementById('messages');
        let indicator = document.getElementById('typingIndicator');
        if (!indicator) {
            indicator = document.createElement('div');
            indicator.id = 'typingIndicator';
            indicator.className = 'typing-indicator';
            indicator.innerHTML = '<span></span><span></span><span></span>';
            container.appendChild(indicator);
            container.scrollTop = container.scrollHeight;
        }

        if (typingTimeoutMap[peerId]) clearTimeout(typingTimeoutMap[peerId]);
        typingTimeoutMap[peerId] = setTimeout(() => {
            const ind = document.getElementById('typingIndicator');
            if (ind) ind.remove();
        }, 6000);
    }
}

/* --- 10. IMPROVED AUDIO CLEANUP --- */
function cleanupAudioPlayers() {
    Object.keys(audioPlayersCache).forEach(key => {
        const audio = audioPlayersCache[key];
        if (audio && audio.paused && audio.currentTime === 0) {
            audio.src = '';
            delete audioPlayersCache[key];
        }
    });
}

setInterval(cleanupAudioPlayers, 30000);

/* --- 7. STICKER PICKER (VK API Official) --- */
let stickerProducts = [];
let currentStickerPack = 0;
let favoriteStickers = [];

async function loadStickers() {
    try {
        const res = await fetch('/api/stickers', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ token })
        });
        const data = await res.json();
        stickerProducts = data.items || [];
        favoriteStickers = data.favorites || [];
    } catch(e) { console.log('Stickers load error:', e); }
}

function toggleStickerPicker() {
    const picker = document.getElementById('stickerPicker');
    if (picker.classList.contains('active')) {
        picker.classList.remove('active');
    } else {
        if (stickerProducts.length === 0) loadStickers();
        renderStickerPicker();
        picker.classList.add('active');
    }
}

function renderStickerPicker() {
    const header = document.getElementById('stickerPickerHeader');
    const grid = document.getElementById('stickerPickerGrid');
    header.innerHTML = '';
    grid.innerHTML = '';

    // Favorites tab
    if (favoriteStickers.length > 0) {
        const favTab = document.createElement('div');
        favTab.className = 'sticker-picker-tab ' + (currentStickerPack === -1 ? 'active' : '');
        favTab.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>';
        favTab.onclick = () => { currentStickerPack = -1; renderStickerPicker(); };
        header.appendChild(favTab);
    }

    stickerProducts.forEach((pack, idx) => {
        const tab = document.createElement('div');
        tab.className = 'sticker-picker-tab ' + (idx === currentStickerPack ? 'active' : '');
        const iconUrl = pack.icon || pack.preview || '';
        if (iconUrl) {
            tab.innerHTML = `<img src="${iconUrl}" style="width:28px;height:28px;object-fit:contain;border-radius:4px">`;
        } else {
            tab.textContent = (pack.title || 'Pack').substring(0, 3);
        }
        tab.onclick = () => { currentStickerPack = idx; renderStickerPicker(); };
        header.appendChild(tab);
    });

    let stickersToShow = [];
    if (currentStickerPack === -1) {
        stickersToShow = favoriteStickers;
    } else {
        const pack = stickerProducts[currentStickerPack];
        if (pack && pack.stickers) stickersToShow = pack.stickers;
    }

    stickersToShow.forEach(s => {
        const img = document.createElement('img');
        img.className = 'sticker-picker-item';
        const imgUrl = s.images?.[s.images.length - 1]?.url || s.url || '';
        img.src = imgUrl;
        img.onerror = function() { this.style.display = 'none'; };
        img.onclick = () => sendStickerVK(s.sticker_id || s.id);
        grid.appendChild(img);
    });
}

async function sendStickerVK(stickerId) {
    if (!currentPeer || !stickerId) return;
    document.getElementById('stickerPicker').classList.remove('active');
    showUploadProgress('Отправка...');
    try {
        const res = await fetch('/api/send_sticker', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ token, peer_id: currentPeer, sticker_id: stickerId })
        });
        const data = await res.json();
        if (data.error) console.error('Sticker send error:', data.error);
    } catch(e) { console.error('Sticker send network error:', e); }
    hideUploadProgress();
}


/* --- 11. DRAFT MESSAGES --- */
function saveDraft(peerId, text) {
    const drafts = JSON.parse(localStorage.getItem('vk_drafts') || '{}');
    if (text.trim()) {
        drafts[peerId] = text;
    } else {
        delete drafts[peerId];
    }
    localStorage.setItem('vk_drafts', JSON.stringify(drafts));
}

function loadDraft(peerId) {
    const drafts = JSON.parse(localStorage.getItem('vk_drafts') || '{}');
    return drafts[peerId] || '';
}

/* --- 12. MESSAGE SEARCH IMPROVEMENTS --- */
let searchHistory = JSON.parse(localStorage.getItem('vk_search_history') || '[]');

function addSearchHistory(query) {
    if (!query) return;
    searchHistory = searchHistory.filter(q => q !== query);
    searchHistory.unshift(query);
    if (searchHistory.length > 10) searchHistory.pop();
    localStorage.setItem('vk_search_history', JSON.stringify(searchHistory));
}

/* --- 13. ENHANCED SECURITY --- */
function sanitizeInput(input) {
    const div = document.createElement('div');
    div.textContent = input;
    return div.innerHTML;
}

function validateToken(token) {
    return /^[a-f0-9]{85,}$/i.test(token);
}

/* --- 14. PERFORMANCE: Virtual scroll for dialogs --- */
let dialogRenderOffset = 0;
const DIALOGS_BATCH_SIZE = 30;

function renderDialogsVirtual() {
    // Only render visible dialogs + buffer
    const list = document.getElementById('dialogsList');
    const visibleDialogs = filtered.slice(0, dialogRenderOffset + DIALOGS_BATCH_SIZE);
    // ... existing render logic but with visibleDialogs
}

/* --- 16. CALL BUTTON (placeholder) --- */
function startCall(peerId) {
    if (!peerId) {
        alert("Выберите собеседника для звонка");
        return;
    }
    window.location.href = `call.py?peer=${encodeURIComponent(peerId)}`;
}

/* --- 17. ENHANCED FILE UPLOAD with progress --- */
async function uploadFileWithProgress(file, onProgress) {
    return new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        const formData = new FormData();
        formData.append('file', file);

        xhr.upload.addEventListener('progress', (e) => {
            if (e.lengthComputable && onProgress) {
                onProgress(Math.round((e.loaded / e.total) * 100));
            }
        });

        xhr.addEventListener('load', () => resolve(xhr.response));
        xhr.addEventListener('error', reject);
        xhr.open('POST', '/api/upload_normal');
        xhr.send(formData);
    });
}

/* --- 18. IMPROVED POLL EVENTS (fixed) --- */
// Override the original pollEvents to add new features
const originalPollEvents = pollEvents;

/* --- 19. PULL TO REFRESH --- */
let ptrStartY = 0;
let ptrPulling = false;

function setupPullToRefresh() {
    const container = document.getElementById('dialogsList');
    const spinner = document.createElement('div');
    spinner.className = 'ptr-spinner';
    spinner.innerHTML = '<div class="loader"></div>';
    container.parentElement.insertBefore(spinner, container);

    container.addEventListener('touchstart', (e) => {
        if (container.scrollTop === 0) {
            ptrStartY = e.touches[0].clientY;
            ptrPulling = true;
        }
    }, { passive: true });

    container.addEventListener('touchmove', (e) => {
        if (!ptrPulling) return;
        const diff = e.touches[0].clientY - ptrStartY;
        if (diff > 60 && diff < 150) {
            spinner.style.top = (diff - 50) + 'px';
            spinner.classList.add('active');
        }
    }, { passive: true });

    container.addEventListener('touchend', () => {
        if (!ptrPulling) return;
        ptrPulling = false;
        const diff = event.changedTouches?.[0]?.clientY - ptrStartY;
        if (diff > 80) {
            spinner.classList.add('active');
            loadDialogs().then(() => {
                spinner.classList.remove('active');
                spinner.style.top = '-50px';
            });
        } else {
            spinner.classList.remove('active');
            spinner.style.top = '-50px';
        }
    });
}

/* --- 20. INIT ALL NEW FEATURES --- */
// Call this after login
function initNewFeatures() {
    setupScrollFab();
    setupPullToRefresh();
    setupAppLock();
    updateNetworkStatus();
    loadStickers();
}

// Hook into existing login flow
const originalShowDialogsScreen = showDialogsScreen;
showDialogsScreen = function() {
    originalShowDialogsScreen();
    initNewFeatures();
};

// Hook into message rendering for new features
// ЭТО ЕДИНСТВЕННЫЙ МЕХАНИЗМ ОБРАБОТКИ ВЗАИМОДЕЙСТВИЙ С СООБЩЕНИЯМИ
const originalRenderMessageItem = renderMessageItem;
renderMessageItem = function(containerOrFragment, msg) {
    originalRenderMessageItem(containerOrFragment, msg);

    const msgEl = document.getElementById('msg-' + msg.id);
    if (!msgEl) return;

    let longPressTimer;
    let startX = 0, startY = 0;
    let touchMoved = false;

    msgEl.addEventListener('touchstart', (e) => {
        touchMoved = false;
        if (e.touches.length === 1) {
            startX = e.touches[0].clientX;
            startY = e.touches[0].clientY;

            longPressTimer = setTimeout(() => {
                if (!touchMoved) {
                    if (navigator.vibrate) navigator.vibrate(20);
                    if (!multiSelectMode) {
                        openActionSheet(msg);
                    } else {
                        toggleMessageSelect(msg.id);
                    }
                }
            }, 500);
        }
    }, { passive: true });

    msgEl.addEventListener('touchmove', (e) => {
        if (e.touches.length === 1) {
            const diffX = Math.abs(e.touches[0].clientX - startX);
            const diffY = Math.abs(e.touches[0].clientY - startY);
            if (diffX > 8 || diffY > 8) { // Если палец сдвинулся больше чем на 8px — это скролл
                touchMoved = true;
                clearTimeout(longPressTimer);
            }
        }
    }, { passive: true });

    msgEl.addEventListener('touchend', () => {
        clearTimeout(longPressTimer);
        if (!touchMoved && multiSelectMode) {
            toggleMessageSelect(msg.id);
        }
    }, { passive: true });

    msgEl.addEventListener('contextmenu', (e) => {
        e.preventDefault();
        openActionSheet(msg);
    });
};

// Hook into pollEvents for typing indicator
const originalPollEventsRef = pollEvents;
pollEvents = async function() {
    // Store reference to call original
    await originalPollEventsRef();
};

// Override pollEvents properly
window.pollEvents = async function() {
    if (!longPollServer || !longPollKey || !longPollTs) {
        setTimeout(startLongPolling, 2000);
        return;
    }

    try {
        const res = await fetch('/api/longpoll/listen', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ server: longPollServer, key: longPollKey, ts: longPollTs })
        });
        const data = await res.json();
        if (data.ts) {
            longPollTs = data.ts;
        }
        if (data.failed) {
            startLongPolling();
            return;
        }
        if (data.updates && data.updates.length > 0) {
            let hasNewMsg = false;
            let needsDialogUpdate = false;
            for (const u of data.updates) {
                const eventCode = u[0];
                if (eventCode === 4) {
                    hasNewMsg = true;
                    needsDialogUpdate = true;
                    const peerId = u[3];
                    const msgId = u[1];
                    const flags = u[2];
                    const text = u[5] || '';
                    const fromId = u[6] || peerId;

                    const d = dialogsData.find(item => String(item.id) === String(peerId));
                    if (d) {
                        d.last_message = text;
                        d.date = Math.floor(Date.now() / 1000);
                        if (!(flags & 2)) {
                            d.unread = (d.unread || 0) + 1;
                        }
                        const idx = dialogsData.indexOf(d);
                        if (idx > 0) {
                            dialogsData.splice(idx, 1);
                            dialogsData.unshift(d);
                        }
                    }

                    if (currentPeer && String(peerId) === String(currentPeer)) {
                        // Подгружаем полные данные сообщения с вложениями
                        fetch('/api/message', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ token, message_ids: msgId })
                        }).then(r => r.json()).then(fullMsg => {
                            if (fullMsg && !fullMsg.error && !renderedMsgIds.has(msgId)) {
                                renderedMsgIds.add(msgId);
                                const container = document.getElementById('messages');
                                renderMessageItem(container, fullMsg);
                                container.scrollTop = container.scrollHeight;
                                showScrollFab();
                                if (fullMsg.text && fullMsg.text.startsWith(ENCRYPT_PREFIX)) {
                                    setTimeout(() => tryDecryptMessageRealTime(msgId, fullMsg.text), 50);
                                }
                            } else if (fullMsg && fullMsg.error) {
                                // Если API вернуло ошибку, логируем, но не отрисовываем пустое сообщение
                                console.warn(`Ошибка получения полного сообщения для ID ${msgId}:`, fullMsg.error);
                            }
                            // Важно: Удален catch блок, который отрисовывал сообщение без вложений.
                            // Теперь, если fetch не удался или fullMsg проблемный,
                            // сообщение просто не будет отображено в реальном времени.
                            // Оно будет загружено при следующем вызове loadMessages()
                            // (например, при перезаходе в чат).
                        });
                    }} else if (eventCode === 3) {
                    const msgId = u[1];
                    const elem = document.getElementById('msg-' + msgId);
                    if (elem) elem.closest('.msg-container').remove();
                } else if (eventCode === 5) {
                    needsDialogUpdate = true;
                } else if (eventCode === 6 || eventCode === 7 || eventCode === 80) {
                    needsDialogUpdate = true;
                } else if (eventCode === 61) {
                    // Typing indicator
                    const peerId = u[1];
                    showTypingIndicator(peerId);
                }
            }

            if (needsDialogUpdate) {
                renderDialogsListFiltered();
            }
            if (hasNewMsg) {
                playNotificationSound();
            }
        }
    } catch(e) {
        await new Promise(r => setTimeout(r, 1000));
    }
    setTimeout(pollEvents, 100);
};
</script>

<!-- Offline Banner -->
<div class="offline-banner" id="offlineBanner">⚠ Нет подключения к интернету</div>

<!-- Scroll to Bottom FAB -->
<div class="scroll-fab" id="scrollFab" onclick="scrollToBottomChat()">
<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="6 9 12 15 18 9"/></svg>
<div class="unread-dot hidden" id="scrollFabUnread">0</div>
</div>



<!-- Multi-Select Action Bar -->
<div class="multi-select-bar" id="multiSelectBar">
<div class="multi-select-count" id="multiSelectCount">Выбрано: 0</div>
<div class="multi-select-actions">
<button class="multi-select-btn" onclick="multiSelectForward()"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 14 20 9 15 4"/><path d="M4 20v-7a4 4 0 0 1 4-4h12"/></svg>Переслать</button>
<button class="multi-select-btn" onclick="multiSelectDelete()"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/></svg>Удалить</button>
<button class="multi-select-btn" onclick="exitMultiSelectMode()">Отмена</button>
</div>
</div>

<!-- PIN / Bio Auth Modal -->
<div class="modal hidden" id="pinAuthModal">
<div class="modal-content bio-modal-content">
<div class="bio-icon">
<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
</div>
<div class="bio-title">Блокировка приложения</div>
<div class="bio-text" id="pinAuthText">Введите PIN-код для разблокировки</div>
<div class="bio-dots" id="pinDots">
<div class="bio-dot"></div><div class="bio-dot"></div><div class="bio-dot"></div><div class="bio-dot"></div>
</div>
<div style="display:grid;grid-template-columns:repeat(3,1fr);gap:12px;max-width:240px;margin:0 auto">
<button class="btn btn-secondary" style="aspect-ratio:1;font-size:20px;font-weight:600;padding:0" onclick="pinInput(1)">1</button>
<button class="btn btn-secondary" style="aspect-ratio:1;font-size:20px;font-weight:600;padding:0" onclick="pinInput(2)">2</button>
<button class="btn btn-secondary" style="aspect-ratio:1;font-size:20px;font-weight:600;padding:0" onclick="pinInput(3)">3</button>
<button class="btn btn-secondary" style="aspect-ratio:1;font-size:20px;font-weight:600;padding:0" onclick="pinInput(4)">4</button>
<button class="btn btn-secondary" style="aspect-ratio:1;font-size:20px;font-weight:600;padding:0" onclick="pinInput(5)">5</button>
<button class="btn btn-secondary" style="aspect-ratio:1;font-size:20px;font-weight:600;padding:0" onclick="pinInput(6)">6</button>
<button class="btn btn-secondary" style="aspect-ratio:1;font-size:20px;font-weight:600;padding:0" onclick="pinInput(7)">7</button>
<button class="btn btn-secondary" style="aspect-ratio:1;font-size:20px;font-weight:600;padding:0" onclick="pinInput(8)">8</button>
<button class="btn btn-secondary" style="aspect-ratio:1;font-size:20px;font-weight:600;padding:0" onclick="pinInput(9)">9</button>
<button class="btn btn-secondary" style="aspect-ratio:1;font-size:20px;font-weight:600;padding:0;background:transparent;border:none;color:#ff3b30" onclick="pinBackspace()">⌫</button>
<button class="btn btn-secondary" style="aspect-ratio:1;font-size:20px;font-weight:600;padding:0" onclick="pinInput(0)">0</button>
<button class="btn btn-secondary" style="aspect-ratio:1;font-size:20px;font-weight:600;padding:0;background:transparent;border:none;color:#8e8e93" onclick="pinCancel()">✕</button>
</div>
</div>
</div>

<!-- Sticker Picker -->
<div class="sticker-picker" id="stickerPicker">
<div class="sticker-picker-header" id="stickerPickerHeader"></div>
<div class="sticker-picker-grid" id="stickerPickerGrid"></div>
</div>

</body>
</html>
"""


@app.route('/')
def index():
    return render_template_string(HTML)


@app.route('/sw.js')
def service_worker():
    return Response(SW_JS, mimetype='application/javascript')


@app.route('/api/auth', methods=['POST'])
def auth():
    url = request.json.get('url', '')
    token_match = re.search(r'access_token=([^&]+)', url)
    if not token_match:
        return jsonify({'error': 'Токен не найден в ссылке'}), 400
    token = token_match.group(1)
    user_info = vk_request('users.get', token, fields='photo_100,online,last_seen,sex,status')
    if isinstance(user_info, dict) and 'error' in user_info:
        return jsonify({'error': 'Неверный или просроченный токен'}), 400
    user = user_info[0] if isinstance(user_info, list) else user_info
    
    online_text = format_last_seen(user)
    
    return jsonify({
        'token': token,
        'user': {
            'id': user.get('id'),
            'name': user.get('first_name', '') + ' ' + user.get('last_name', ''),
            'photo': user.get('photo_100', ''),
            'online': user.get('online', 0),
            'online_text': online_text,
            'status': user.get('status', '')
        }
    })


@app.route('/api/my_status', methods=['POST'])
def my_status():
    token = request.json.get('token')
    u_info = vk_request('users.get', token, fields='online,last_seen,sex')
    if isinstance(u_info, list) and len(u_info) > 0:
        u = u_info[0]
        return jsonify({
            'online': u.get('online', 0),
            'online_text': format_last_seen(u)
        })
    return jsonify({'online': 0, 'online_text': 'неизвестно'})


@app.route('/api/mark_read', methods=['POST'])
def mark_read():
    token = request.json.get('token')
    peer_id = request.json.get('peer_id')
    if token and peer_id:
        vk_request('messages.markAsRead', token, peer_id=peer_id)
    return jsonify({'ok': True})


@app.route('/api/user_groups', methods=['POST'])
def user_groups():
    token = request.json.get('token')
    res = vk_request('groups.get', token, extended=1, fields='photo_100,photo_200,description,status,activity', count=100)

    if isinstance(res, dict) and 'error' in res:
        return jsonify(res), 400
    
    items = res.get('items', [])
    for g in items:
        if 'photo_200' in g and g['photo_200']:
            g['photo'] = g['photo_200']
        elif 'photo_100' in g and g['photo_100']:
            g['photo'] = g['photo_100']

    return jsonify({'groups': items})


@app.route('/api/search_global', methods=['POST'])
def search_global():
    token = request.json.get('token')
    query = request.json.get('query', '').strip()
    if not query:
        return jsonify({'results': []})

    results = []
    users_res = vk_request('users.search', token, q=query, count=10, fields='photo_100')
    if isinstance(users_res, dict) and 'items' in users_res:
        for u in users_res.get('items', []):
            results.append({
                'id': u.get('id'),
                'type': 'user',
                'name': f"{u.get('first_name', '')} {u.get('last_name', '')}".strip(),
                'photo': u.get('photo_100', '')
            })

    groups_res = vk_request('groups.search', token, q=query, count=10)
    if isinstance(groups_res, dict) and 'items' in groups_res:
        for g in groups_res.get('items', []):
            results.append({
                'id': -g.get('id'),
                'type': 'group',
                'name': g.get('name', ''),
                'photo': g.get('photo_100', '')
            })

    return jsonify({'results': results})


@app.route('/api/keys/<vk_id>', methods=['GET'])
def get_key(vk_id):
    stored = get_stored_pub_key(vk_id)
    if stored:
        return jsonify(stored)
    return jsonify({'error': 'Not found'}), 404


@app.route('/api/keys/private/<vk_id>', methods=['GET'])
def get_private_key_local(vk_id):
    stored_priv = get_stored_priv_key(vk_id)
    stored_pub = get_stored_pub_key(vk_id)

    if stored_priv and stored_pub:
        return jsonify({
            'public_key': stored_pub.get('public_key'),
            'private_key_enc': stored_priv.get('private_key_enc')
        })
    return jsonify({'error': 'Not found'}), 404


@app.route('/api/keys/<vk_id>', methods=['POST'])
def save_key(vk_id):
    data = request.json
    now_iso = datetime.now().isoformat()
    if 'public_key' in data:
        store_pub_key(vk_id, {'public_key': data['public_key'], 'created_at': now_iso})
    if 'private_key_enc' in data:
        store_priv_key(vk_id, {'private_key_enc': data['private_key_enc'], 'created_at': now_iso})
    return jsonify({'ok': True})


@app.route('/api/dialogs', methods=['POST'])
def get_dialogs():
    token = request.json.get('token')
    result = vk_request('messages.getConversations', token, count=100, offset=0, extended=1)

    if isinstance(result, dict) and 'error' in result:
        return jsonify(result), 400
    dialogs = []
    profiles = {p['id']: p for p in result.get('profiles', [])}
    groups = {g['id']: g for g in result.get('groups', [])}
    for item in result.get('items', []):
        conv = item.get('conversation', {})
        msg = item.get('last_message', {})
        peer = conv.get('peer', {})
        peer_id = peer.get('id')
        peer_type = peer.get('type')
        name = "Unknown"
        photo = ""
        if peer_type == 'user':
            profile = profiles.get(peer_id, {})
            name = profile.get('first_name', '') + ' ' + profile.get('last_name', '')
            photo = profile.get('photo_100', '')
        elif peer_type == 'group':
            group = groups.get(-peer_id, {})
            name = group.get('name', 'Group')
            photo = group.get('photo_100', '')
        elif peer_type == 'chat':
            chat_settings = conv.get('chat_settings', {})
            name = chat_settings.get('title', 'Chat')
            photo = chat_settings.get('photo', {}).get('photo_100', '')
        dialogs.append({
            'id': peer_id,
            'type': peer_type,
            'name': name.strip(),
            'photo': photo,
            'unread': conv.get('unread_count', 0),
            'last_message': msg.get('text', ''),
            'last_attachments': msg.get('attachments', []),
            'date': msg.get('date', 0),
            'online': profile.get('online', 0) if peer_type == 'user' else 0
        })
    return jsonify({'dialogs': dialogs})


@app.route('/api/messages', methods=['POST'])
def get_messages():
    token = request.json.get('token')
    peer_id = request.json.get('peer_id')
    offset = request.json.get('offset', 0)
    result = vk_request('messages.getHistory', token, peer_id=peer_id, count=200, offset=offset, extended=1)

    if isinstance(result, dict) and 'error' in result:
        return jsonify(result), 400
    messages = []
    profiles = {p['id']: p for p in result.get('profiles', [])}
    for msg in result.get('items', []):
        from_id = msg.get('from_id', 0)
        profile = profiles.get(from_id, {})
        messages.append({
            'id': msg.get('id'),
            'text': msg.get('text', ''),
            'date': msg.get('date', 0),
            'from_id': from_id,
            'out': msg.get('out', 0),
            'name': profile.get('first_name', '') + ' ' + profile.get('last_name', ''),
            'photo': profile.get('photo_50', ''),
            'attachments': msg.get('attachments', []),
            'reply_message': msg.get('reply_message')
        })
    return jsonify({'messages': messages})


@app.route('/api/peer_status', methods=['POST'])
def peer_status():
    token = request.json.get('token')
    peer_id = request.json.get('peer_id')
    
    if not peer_id or int(peer_id) < 0:
        return jsonify({'status_text': 'сообщество'})

    user_info = vk_request('users.get', token, user_ids=peer_id, fields='online,last_seen,sex')

    if isinstance(user_info, list) and len(user_info) > 0:
        u = user_info[0]
        status_str = format_last_seen(u)
        return jsonify({'status_text': status_str, 'online': u.get('online', 0) == 1})

    return jsonify({'status_text': 'офлайн', 'online': False})


@app.route('/api/typing', methods=['POST'])
def set_typing():
    token = request.json.get('token')
    peer_id = request.json.get('peer_id')
    if token and peer_id:
        vk_request('messages.setActivity', token, peer_id=peer_id, type='typing')
    return jsonify({'ok': True})


@app.route('/api/profile/update', methods=['POST'])
def update_profile():
    token = request.json.get('token')
    first_name = request.json.get('first_name', '').strip()
    last_name = request.json.get('last_name', '').strip()
    status_text = request.json.get('status', '').strip()

    status_res = vk_request('status.set', token, text=status_text)
    profile_res = None
    if first_name and last_name:
        profile_res = vk_request('account.saveProfileInfo', token, first_name=first_name, last_name=last_name)
    
    return jsonify({'ok': True, 'status_res': status_res, 'profile_res': profile_res})


@app.route('/api/profile/upload_avatar', methods=['POST'])
def upload_avatar():
    token = request.form.get('token')
    photo_file = request.files.get('photo')

    if not photo_file:
        return jsonify({'error': 'Файл не выбран'}), 400

    upload_server = vk_request('photos.getOwnerPhotoUploadServer', token)
    if isinstance(upload_server, dict) and 'error' in upload_server:
        return jsonify(upload_server), 400

    upload_url = upload_server.get('upload_url')
    files = {'photo': (photo_file.filename, photo_file.read(), photo_file.content_type or 'image/jpeg')}
    upload_resp = get_session().post(upload_url, files=files, timeout=15).json()

    save_result = vk_request('photos.saveOwnerPhoto', token,
        server=upload_resp.get('server'),
        photo=upload_resp.get('photo'),
        hash=upload_resp.get('hash')
    )

    if isinstance(save_result, dict) and 'photo_hash' in save_result:
        u_info = vk_request('users.get', token, fields='photo_100')
        if isinstance(u_info, list) and len(u_info) > 0:
            return jsonify({'ok': True, 'photo_url': u_info[0].get('photo_100')})

    return jsonify({'ok': True})


@app.route('/api/send', methods=['POST'])
def send_message():
    token = request.json.get('token')
    peer_id = request.json.get('peer_id')
    text = request.json.get('text', '')
    reply_to = request.json.get('reply_to')
    
    params = {'peer_id': peer_id, 'message': text, 'random_id': random.randint(1, 2147483647)}
    if reply_to:
        params['reply_to'] = reply_to

    result = vk_request('messages.send', token, **params)
    return jsonify({'result': result})


@app.route('/api/edit', methods=['POST'])
def edit_message():
    token = request.json.get('token')
    peer_id = request.json.get('peer_id')
    message_id = request.json.get('message_id')
    text = request.json.get('text', '')

    result = vk_request('messages.edit', token, peer_id=peer_id, message_id=message_id, message=text)
    return jsonify({'result': result})


@app.route('/api/delete', methods=['POST'])
def delete_message():
    token = request.json.get('token')
    message_ids = request.json.get('message_ids')
    delete_for_all = request.json.get('delete_for_all', 1)

    result = vk_request('messages.delete', token, message_ids=str(message_ids), delete_for_all=delete_for_all)
    return jsonify({'result': result})


@app.route('/api/upload_encrypted_doc', methods=['POST'])
def upload_encrypted_doc():
    token = request.form.get('token')
    peer_id = request.form.get('peer_id')
    file = request.files.get('file')

    if not file:
        return jsonify({'error': 'No file'}), 400

    upload_server = vk_request('docs.getMessagesUploadServer', token, type='doc', peer_id=peer_id)
    if isinstance(upload_server, dict) and 'error' in upload_server:
        return jsonify(upload_server), 400

    upload_url = upload_server.get('upload_url')
    files = {'file': (file.filename, file.read(), 'application/octet-stream')}
    upload_resp = get_session().post(upload_url, files=files, timeout=15).json()

    # ДОБАВЛЕН СУФФИКС .doc ДЛЯ ОБХОДА БЛОКИРОВКИ РАСШИРЕНИЙ В VK API
    fname_lower = file.filename.lower()
    if fname_lower.endswith('.mgs') or fname_lower.endswith('.mmu'):
        safe_title = file.filename
    else:
        safe_title = file.filename if file.filename.endswith('.doc') else f"{file.filename}.doc"
    save_result = vk_request('docs.save', token, file=upload_resp.get('file'), title=safe_title)
    attachment = extract_doc_attachment(save_result)

    if attachment:
        vk_request('messages.send', token, peer_id=peer_id, attachment=attachment, random_id=random.randint(1, 2147483647))
        return jsonify({'ok': True})

    return jsonify({'error': 'Upload failed'}), 400


@app.route('/api/upload_normal', methods=['POST'])
def upload_normal():
    token = request.form.get('token')
    peer_id = request.form.get('peer_id')
    file = request.files.get('file')

    if not file:
        return jsonify({'error': 'No file'}), 400

    filename = file.filename.lower()
    file_bytes = file.read()

    # ИСКЛЮЧАЕМ .mst ИЗ ФОТО-СЕРВЕРА ВК, ЧТОБЫ ЗАШИФРОВАННЫЕ СТИКЕРЫ НЕ ЛОМАЛИСЬ
    if filename.endswith(('.jpg', '.jpeg', '.png', '.gif', '.webp', '.meow')) and not filename.endswith('.mst'):
        upload_server = vk_request('photos.getMessagesUploadServer', token, peer_id=peer_id)
        if isinstance(upload_server, dict) and 'error' in upload_server:
            return jsonify(upload_server), 400

        upload_url = upload_server.get('upload_url')
        files = {'photo': (filename, BytesIO(file_bytes), file.content_type or 'image/png')}
        upload_resp = get_session().post(upload_url, files=files, timeout=15).json()

        save_result = vk_request('photos.saveMessagesPhoto', token,
            photo=upload_resp.get('photo'),
            server=upload_resp.get('server'),
            hash=upload_resp.get('hash')
        )

        if isinstance(save_result, list) and len(save_result) > 0:
            photo = save_result[0]
            attachment = f"photo{photo['owner_id']}_{photo['id']}"
            vk_request('messages.send', token, peer_id=peer_id, attachment=attachment, random_id=random.randint(1, 2147483647))
            return jsonify({'ok': True})

    upload_server = vk_request('docs.getMessagesUploadServer', token, type='doc', peer_id=peer_id)
    if isinstance(upload_server, dict) and 'error' in upload_server:
        return jsonify(upload_server), 400

    upload_url = upload_server.get('upload_url')
    files = {'file': (filename, BytesIO(file_bytes), file.content_type or 'application/octet-stream')}
    upload_resp = get_session().post(upload_url, files=files, timeout=15).json()

    if filename.endswith('.mgs') or filename.endswith('.mmu'):
        safe_title = filename
    else:
        safe_title = filename if filename.endswith('.doc') else f"{filename}.doc"
    save_result = vk_request('docs.save', token, file=upload_resp.get('file'), title=safe_title)
    attachment = extract_doc_attachment(save_result)

    if attachment:
        vk_request('messages.send', token, peer_id=peer_id, attachment=attachment, random_id=random.randint(1, 2147483647))
        return jsonify({'ok': True})

    return jsonify({'error': 'Upload failed'}), 400


@app.route('/api/folders/<vk_id>', methods=['GET'])
def get_folders(vk_id):
    stored = get_stored_pub_key(f"folders_{vk_id}")
    if stored and 'folders' in stored:
        return jsonify({'folders': stored['folders']})
    return jsonify({'folders': []})


@app.route('/api/folders/<vk_id>', methods=['POST'])
def save_folders(vk_id):
    data = request.json
    existing = get_stored_pub_key(f"folders_{vk_id}") or {'folders': []}
    folder_id = 'folder_' + str(random.randint(1000, 9999))
    existing['folders'] = existing.get('folders', []) + [{
        'id': folder_id,
        'name': data.get('name', 'Папка'),
        'peers': data.get('peers', [])
    }]
    store_pub_key(f"folders_{vk_id}", existing)
    return jsonify({'ok': True})


def extract_media_from_attachments(attachments):
    photo = None
    video = None
    for a in attachments:
        a_type = a.get('type')
        if a_type == 'photo' and not photo:
            sizes = a.get('photo', {}).get('sizes', [])
            if sizes:
                photo = sizes[-1].get('url', '')
        elif a_type == 'video' and not video:
            v = a.get('video', {})
            player_url = v.get('player', '')
            image_url = ''
            if v.get('first_frame'):
                image_url = v.get('first_frame', [{}])[-1].get('url', '')
            elif v.get('image'):
                image_url = v.get('image', [{}])[-1].get('url', '')
            video = {
                'player': player_url,
                'image': image_url,
                'title': v.get('title', '')
            }
    return photo, video


@app.route('/api/news', methods=['POST'])
def get_news():
    token = request.json.get('token')
    result = vk_request('newsfeed.get', token, filters='post', count=100, fields='photo_50,photo_100')

    if isinstance(result, dict) and 'error' in result:
        return jsonify(result), 400
    items = []
    profiles = {p['id']: p for p in result.get('profiles', [])}
    groups = {g['id']: g for g in result.get('groups', [])}
    for item in result.get('items', []):
        source_id = item.get('source_id', 0)
        if source_id > 0:
            author = profiles.get(source_id, {})
            author_name = author.get('first_name', '') + ' ' + author.get('last_name', '')
            author_photo = author.get('photo_100') or author.get('photo_50', '')
        else:
            author = groups.get(-source_id, {})
            author_name = author.get('name', 'Group')
            author_photo = author.get('photo_100') or author.get('photo_50', '')

        photo, video = extract_media_from_attachments(item.get('attachments', []))

        items.append({
            'owner_id': source_id,
            'post_id': item.get('post_id', 0),
            'author_name': author_name.strip(),
            'author_photo': author_photo,
            'text': item.get('text', ''),
            'photo': photo,
            'video': video,
            'time': datetime.fromtimestamp(item.get('date', 0)).strftime('%H:%M') if item.get('date') else '',
            'likes': item.get('likes', {}).get('count', 0),
            'comments': item.get('comments', {}).get('count', 0),
            'reposts': item.get('reposts', {}).get('count', 0)
        })
    return jsonify({'items': items})


@app.route('/api/post_likes', methods=['POST'])
def post_likes():
    token = request.json.get('token')
    owner_id = request.json.get('owner_id')
    item_id = request.json.get('item_id')

    res = vk_request('likes.getList', token, type='post', owner_id=owner_id, item_id=item_id, extended=1, count=100, fields='photo_100')

    if isinstance(res, dict) and 'error' in res:
        return jsonify({'users': []})

    users = []
    for u in res.get('items', []):
        name = f"{u.get('first_name', '')} {u.get('last_name', '')}".strip() or u.get('name', '')
        users.append({
            'id': u.get('id'),
            'name': name,
            'photo': u.get('photo_100', '')
        })

    return jsonify({'users': users})


@app.route('/api/wall_comments', methods=['POST'])
def wall_comments():
    token = request.json.get('token')
    owner_id = request.json.get('owner_id')
    post_id = request.json.get('post_id')

    res = vk_request('wall.getComments', token, owner_id=owner_id, post_id=post_id, extended=1, count=50, fields='photo_50')

    if isinstance(res, dict) and 'error' in res:
        return jsonify({'comments': []})

    profiles = {p['id']: p for p in res.get('profiles', [])}
    groups = {g['id']: g for g in res.get('groups', [])}

    comments = []
    for item in res.get('items', []):
        from_id = item.get('from_id', 0)
        name = "Пользователь"
        photo = ""
        if from_id > 0:
            p = profiles.get(from_id, {})
            name = f"{p.get('first_name', '')} {p.get('last_name', '')}".strip()
            photo = p.get('photo_50', '')
        elif from_id < 0:
            g = groups.get(-from_id, {})
            name = g.get('name', '')
            photo = g.get('photo_50', '')

        comments.append({
            'id': item.get('id'),
            'name': name,
            'photo': photo,
            'text': item.get('text', ''),
            'time': datetime.fromtimestamp(item.get('date', 0)).strftime('%d.%m %H:%M') if item.get('date') else ''
        })

    return jsonify({'comments': comments})


@app.route('/api/profile_view', methods=['POST'])
def profile_view():
    token = request.json.get('token')
    peer_id = request.json.get('peer_id')
    is_group = request.json.get('is_group', False)

    try:
        peer_id_int = int(peer_id)
    except (ValueError, TypeError):
        peer_id_int = 0

    if is_group or peer_id_int < 0:
        group_id = abs(peer_id_int)
        group_info = vk_request('groups.getById', token, group_id=group_id, fields='description,status,photo_200,photo_100,cover')

        name = ""
        photo = ""
        status = ""
        cover_photo = None

        g_list = []
        if isinstance(group_info, list):
            g_list = group_info
        elif isinstance(group_info, dict) and 'groups' in group_info:
            g_list = group_info['groups']

        if len(g_list) > 0:
            g = g_list[0]
            name = g.get('name', '')
            photo = g.get('photo_200') or g.get('photo_100', '')
            status = g.get('status') or g.get('description', '')

            cover_data = g.get('cover', {})
            if cover_data.get('enabled') == 1:
                images = cover_data.get('images', [])
                if images:
                    cover_photo = images[-1].get('url')

        wall = vk_request('wall.get', token, owner_id=-group_id, count=50, extended=1)

        posts = []
        if isinstance(wall, dict) and 'items' in wall:
            items = wall.get('items', [])
            for p in items:
                p_photo, p_video = extract_media_from_attachments(p.get('attachments', []))
                posts.append({
                    'id': p.get('id'),
                    'owner_id': -group_id,
                    'text': p.get('text', ''),
                    'photo': p_photo,
                    'video': p_video,
                    'likes': p.get('likes', {}).get('count', 0),
                    'comments': p.get('comments', {}).get('count', 0),
                    'reposts': p.get('reposts', {}).get('count', 0)
                })

        return jsonify({
            'name': name,
            'photo': photo,
            'status': status,
            'cover_photo': cover_photo,
            'posts': posts
        })
    else:
        user_info = vk_request('users.get', token, user_ids=peer_id, fields='photo_200,photo_100,status,city,bdate,site,sex')

        if isinstance(user_info, list) and len(user_info) > 0:
            u = user_info[0]
            wall = vk_request('wall.get', token, owner_id=peer_id, count=50, extended=1, filter='owner')

            posts = []
            if isinstance(wall, dict) and 'items' in wall:
                for p in wall.get('items', []):
                    p_photo, p_video = extract_media_from_attachments(p.get('attachments', []))
                    posts.append({
                        'id': p.get('id'),
                        'owner_id': peer_id,
                        'text': p.get('text', ''),
                        'photo': p_photo,
                        'video': p_video,
                        'likes': p.get('likes', {}).get('count', 0),
                        'comments': p.get('comments', {}).get('count', 0),
                        'reposts': p.get('reposts', {}).get('count', 0)
                    })
            return jsonify({
                'name': u.get('first_name', '') + ' ' + u.get('last_name', ''),
                'photo': u.get('photo_200') or u.get('photo_100', ''),
                'status': u.get('status', ''),
                'city': u.get('city', {}).get('title', ''),
                'bdate': u.get('bdate', ''),
                'site': u.get('site', ''),
                'posts': posts
            })

    return jsonify({'error': 'Not found'}), 404


@app.route('/api/longpoll/init', methods=['POST'])
def longpoll_init():
    token = request.json.get('token')
    lp = vk_request('messages.getLongPollServer', token, need_pts=1, lp_version=3)

    if isinstance(lp, dict) and 'error' in lp:
        return jsonify(lp), 400
    return jsonify({
        'server': lp.get('server'),
        'key': lp.get('key'),
        'ts': lp.get('ts')
    })


@app.route('/api/longpoll/listen', methods=['POST'])
def longpoll_listen():
    data = request.json
    server = data.get('server')
    key = data.get('key')
    ts = data.get('ts')
    if not server or not key or not ts:
        return jsonify({'error': 'Missing params'}), 400
    
    url = f"https://{server}?act=a_check&key={key}&ts={ts}&wait=25&mode=2&version=3"
    try:
        resp = requests.get(url, timeout=30)
        json_data = resp.json()
        return jsonify(json_data)
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@app.route('/api/proxy_file')
def proxy_file():
    url = request.args.get('url')
    if not url:
        return jsonify({'error': 'No URL'}), 400
    try:
        resp = get_session().get(url, timeout=15, allow_redirects=True)
        if resp.status_code != 200:
            return jsonify({'error': f'HTTP {resp.status_code}'}), resp.status_code
        return Response(resp.content, mimetype='application/octet-stream', headers={
            'Access-Control-Allow-Origin': '*'
        })
    except Exception as e:
        return jsonify({'error': str(e)}), 500




@app.route('/api/stickers', methods=['POST'])
def get_stickers():
    token = request.json.get('token')

    # Get user's sticker products (purchased + free)
    products = vk_request('store.getProducts', token, type='stickers', filters='purchased', extended=1, count=100)

    # Get favorite stickers
    favorites = vk_request('store.getFavoriteStickers', token)

    items = []
    fav_stickers = []

    if isinstance(products, dict) and 'items' in products:
        for item in products.get('items', []):
            stickers_list = []
            if 'stickers' in item and isinstance(item['stickers'], list):
                for s in item['stickers']:
                    images = s.get('images_with_background', s.get('images', []))
                    sticker_data = {
                        'sticker_id': s.get('sticker_id', s.get('id', 0)),
                        'images': images,
                        'url': images[-1].get('url', '') if images else ''
                    }
                    stickers_list.append(sticker_data)

            items.append({
                'id': item.get('id'),
                'title': item.get('title', 'Pack'),
                'icon': item.get('icon', {}).get('base_url', '') if isinstance(item.get('icon'), dict) else '',
                'preview': item.get('thumb_photo', ''),
                'stickers': stickers_list
            })

    if isinstance(favorites, dict) and 'items' in favorites:
        for s in favorites.get('items', []):
            images = s.get('images_with_background', s.get('images', []))
            fav_stickers.append({
                'sticker_id': s.get('sticker_id', s.get('id', 0)),
                'images': images,
                'url': images[-1].get('url', '') if images else ''
            })

    return jsonify({'items': items, 'favorites': fav_stickers})


@app.route('/api/send_sticker', methods=['POST'])
def send_sticker():
    token = request.json.get('token')
    peer_id = request.json.get('peer_id')
    sticker_id = request.json.get('sticker_id', 0)

    if not sticker_id:
        return jsonify({'error': 'No sticker_id provided'}), 400

    result = vk_request('messages.send', token, 
        peer_id=peer_id, 
        sticker_id=int(sticker_id),
        random_id=random.randint(1, 2147483647)
    )

    if isinstance(result, dict) and 'error' in result:
        return jsonify(result), 400

    return jsonify({'result': result})


@app.route('/api/message', methods=['POST'])
def get_single_message():
    token = request.json.get('token')
    message_ids = request.json.get('message_ids', '')

    result = vk_request('messages.getById', token, message_ids=message_ids, extended=1)

    if isinstance(result, dict) and 'items' in result:
        msg = result['items'][0] if result['items'] else None
        if msg:
            from_id = msg.get('from_id', 0)
            profiles = {p['id']: p for p in result.get('profiles', [])}
            profile = profiles.get(from_id, {})

            return jsonify({
                'id': msg.get('id'),
                'text': msg.get('text', ''),
                'date': msg.get('date', 0),
                'from_id': from_id,
                'out': msg.get('out', 0),
                'name': profile.get('first_name', '') + ' ' + profile.get('last_name', ''),
                'photo': profile.get('photo_50', ''),
                'attachments': msg.get('attachments', []),
                'reply_message': msg.get('reply_message')
            })

    return jsonify({'error': 'Message not found'}), 404


@app.route('/api/forward', methods=['POST'])
def forward_messages():
    token = request.json.get('token')
    peer_id = request.json.get('peer_id')
    message_ids = request.json.get('message_ids', [])

    if isinstance(message_ids, list):
        message_ids = ','.join(map(str, message_ids))

    result = vk_request('messages.send', token, 
        peer_id=peer_id, 
        forward_messages=message_ids,
        random_id=random.randint(1, 2147483647)
    )
    return jsonify({'result': result})


@app.route('/api/call/start', methods=['POST'])
def start_call():
    token = request.json.get('token')
    peer_id = request.json.get('peer_id')

    # VK doesn't have direct API for calls, but we can send a message
    result = vk_request('messages.send', token,
        peer_id=peer_id,
        message='📞 Предложение позвонить (используйте VK для звонка)',
        random_id=random.randint(1, 2147483647)
    )
    return jsonify({'result': result})


@app.route('/api/backup', methods=['POST'])
def backup_data():
    vk_id = request.json.get('vk_id')
    data_type = request.json.get('type', 'settings')
    data = request.json.get('data', {})

    stored = get_stored_pub_key(f"backup_{vk_id}_{data_type}") or {}
    stored['data'] = data
    stored['updated_at'] = datetime.now().isoformat()
    store_pub_key(f"backup_{vk_id}_{data_type}", stored)

    return jsonify({'ok': True})


@app.route('/api/backup/<vk_id>/<data_type>', methods=['GET'])
def get_backup(vk_id, data_type):
    stored = get_stored_pub_key(f"backup_{vk_id}_{data_type}")
    if stored and 'data' in stored:
        return jsonify(stored['data'])
    return jsonify({'error': 'Not found'}), 404


@app.route('/api/ping', methods=['GET'])
def ping():
    return jsonify({'ok': True, 'time': datetime.now().isoformat()})

# Register cloud storage blueprint
app.register_blueprint(cloud_bp, url_prefix='/cloud')

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=int(os.environ.get('PORT', 8080)))
