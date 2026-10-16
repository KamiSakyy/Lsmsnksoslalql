// Ультра-легковесный сервер Tsuyu на чистом Node.js (без тяжелых зависимостей, баз данных и сокетов)
// Потребление памяти: ~25-35 МБ (при лимите 512 МБ)
// Хранение данных: в оперативной памяти (In-Memory Map)

const http = require('http');
const url = require('url');
const crypto = require('crypto');

const PORT = parseInt(process.env.PORT || '8080', 10);

function log(msg) {
  const d = new Date();
  const time = d.toLocaleDateString('ru-RU') + ' ' + d.toLocaleTimeString('ru-RU');
  console.log(`[${time}] [СЕРВЕР] ${msg}`);
}

function logError(msg) {
  const d = new Date();
  const time = d.toLocaleDateString('ru-RU') + ' ' + d.toLocaleTimeString('ru-RU');
  console.error(`[${time}] [ОШИБКА] ${msg}`);
}

// Хранилище в оперативной памяти (RAM)
const users = new Map();       // uid -> { uid, email, username, passwordHash, displayName, avatarUrl, about, lastSeen, createdAt }
const userByEmail = new Map(); // email -> uid
const userByName = new Map();  // username -> uid
const sessions = new Map();    // token -> uid
const keys = new Map();        // uid -> key_bundle (object)
const messages = [];           // array of { id, chatId, senderUid, recipientUid, payload, cipher, signalType, kind, createdAt }
const files = new Map();       // fileId -> { fileId, filename, contentType, buffer }

function hashPassword(pwd) {
  const salt = crypto.randomBytes(16).toString('hex');
  const hash = crypto.createHash('sha256').update(pwd + salt).digest('hex');
  return `${salt}:${hash}`;
}

function verifyPassword(pwd, stored) {
  try {
    const [salt, hash] = stored.split(':');
    return crypto.createHash('sha256').update(pwd + salt).digest('hex') === hash;
  } catch (e) {
    return false;
  }
}

function sendJson(res, statusCode, data) {
  const json = JSON.stringify(data);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type, Accept'
  });
  res.end(json);
}

function getAuthUid(req) {
  const auth = req.headers['authorization'] || '';
  let token = '';
  if (auth.startsWith('Bearer ')) {
    token = auth.substring(7).trim();
  }
  if (!token) {
    const parsed = url.parse(req.url, true);
    token = parsed.query.token;
  }
  if (token && sessions.has(token)) {
    return sessions.get(token);
  }
  return null;
}

function parseJsonBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', chunk => {
      body += chunk;
      if (body.length > 10 * 1024 * 1024) { // 10MB limit
        reject(new Error('Размер данных превышен'));
      }
    });
    req.on('end', () => {
      if (!body) return resolve({});
      try {
        resolve(JSON.parse(body));
      } catch (e) {
        reject(new Error('Неверный формат JSON'));
      }
    });
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  // CORS Preflight
  if (req.method === 'OPTIONS') {
    res.writeHead(200, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
      'Access-Control-Allow-Headers': 'Authorization, Content-Type, Accept'
    });
    return res.end();
  }

  const parsedUrl = url.parse(req.url, true);
  const pathname = parsedUrl.pathname;

  try {
    // 1. Healthcheck / Ping
    if ((pathname === '/' || pathname === '/api/ping') && req.method === 'GET') {
      const mem = process.memoryUsage();
      return sendJson(res, 200, {
        status: 'ok',
        engine: 'Node.js Ultra-Lightweight Tsuyu Server',
        memory_used_mb: Math.round(mem.rss / 1024 / 1024),
        users_count: users.size,
        messages_count: messages.length,
        time: Date.now()
      });
    }

    // 2. Регистрация пользователя
    if (pathname === '/api/auth/register' && req.method === 'POST') {
      const data = await parseJsonBody(req);
      const email = (data.email || '').trim().toLowerCase();
      const password = data.password || '';
      let username = (data.username || '').trim().replace(/^@/, '').toLowerCase();
      if (!username && email) {
        username = email.split('@')[0].replace(/[^a-z0-9_]/gi, '');
      }
      const displayName = (data.display_name || '').trim() || username;

      if (!email || !password) {
        logError(`Ошибка регистрации: не передан email или пароль`);
        return sendJson(res, 400, { error: 'Введите email и пароль' });
      }

      if (userByEmail.has(email) || userByName.has(username)) {
        logError(`Попытка дублирующей регистрации: ${email} / @${username}`);
        return sendJson(res, 400, { error: 'Пользователь с таким email или юзернеймом уже есть' });
      }

      const uid = 'u_' + crypto.randomBytes(8).toString('hex');
      const passwordHash = hashPassword(password);
      const now = Date.now();

      const user = {
        uid,
        email,
        username,
        passwordHash,
        displayName,
        avatarUrl: '',
        about: '',
        lastSeen: now,
        createdAt: now
      };

      users.set(uid, user);
      userByEmail.set(email, uid);
      userByName.set(username, uid);

      const token = crypto.randomBytes(32).toString('hex');
      sessions.set(token, uid);

      log(`🎉 [РЕГИСТРАЦИЯ] @${username} (${displayName}), UID: ${uid}, Email: ${email}`);

      return sendJson(res, 200, {
        status: 'success',
        token,
        user: {
          uid: user.uid,
          email: user.email,
          username: user.username,
          display_name: user.displayName,
          avatar_url: user.avatarUrl,
          about: user.about
        }
      });
    }

    // 3. Авторизация / Вход
    if (pathname === '/api/auth/login' && req.method === 'POST') {
      const data = await parseJsonBody(req);
      const account = (data.email || '').trim().toLowerCase().replace(/^@/, '');
      const password = data.password || '';

      let targetUid = userByEmail.get(account) || userByName.get(account);
      const user = targetUid ? users.get(targetUid) : null;

      if (!user || !verifyPassword(password, user.passwordHash)) {
        logError(`Неудачная попытка входа: ${account}`);
        return sendJson(res, 401, { error: 'Неверный логин или пароль' });
      }

      const token = crypto.randomBytes(32).toString('hex');
      sessions.set(token, user.uid);
      user.lastSeen = Date.now();

      log(`🔑 [ВХОД] Пользователь @${user.username} вошел в аккаунт (UID: ${user.uid})`);

      return sendJson(res, 200, {
        status: 'success',
        token,
        user: {
          uid: user.uid,
          email: user.email,
          username: user.username,
          display_name: user.displayName,
          avatar_url: user.avatarUrl,
          about: user.about
        }
      });
    }

    // 4. Получение профиля
    if (pathname === '/api/profile' && req.method === 'GET') {
      const uid = parsedUrl.query.uid || getAuthUid(req);
      if (!uid || !users.has(uid)) {
        return sendJson(res, 404, { error: 'Пользователь не найден' });
      }
      const u = users.get(uid);
      return sendJson(res, 200, {
        user: {
          uid: u.uid,
          email: u.email,
          username: u.username,
          display_name: u.displayName,
          avatar_url: u.avatarUrl,
          about: u.about,
          last_seen: u.lastSeen
        }
      });
    }

    // 5. Сохранение профиля
    if (pathname === '/api/profile' && req.method === 'POST') {
      const authUid = getAuthUid(req);
      if (!authUid || !users.has(authUid)) {
        return sendJson(res, 401, { error: 'Требуется авторизация' });
      }
      const data = await parseJsonBody(req);
      const u = users.get(authUid);
      if (data.display_name) u.displayName = data.display_name.trim();
      if (data.username) {
        const newUname = data.username.trim().replace(/^@/, '').toLowerCase();
        if (newUname !== u.username) {
          if (userByName.has(newUname)) {
            return sendJson(res, 400, { error: 'Этот юзернейм уже занят' });
          }
          userByName.delete(u.username);
          u.username = newUname;
          userByName.set(newUname, authUid);
        }
      }
      if (data.about !== undefined) u.about = data.about;
      if (data.avatar_url !== undefined) u.avatarUrl = data.avatar_url;

      log(`👤 [ПРОФИЛЬ ОБНОВЛЕН] @${u.username} (${u.displayName})`);
      return sendJson(res, 200, { status: 'success', user: u });
    }

    // 6. Поиск пользователей
    if (pathname === '/api/search' && req.method === 'GET') {
      const query = (parsedUrl.query.q || '').trim().replace(/^@/, '').toLowerCase();
      if (!query) {
        return sendJson(res, 200, { users: [] });
      }
      const found = [];
      for (const u of users.values()) {
        if (u.username.toLowerCase().includes(query) || u.displayName.toLowerCase().includes(query)) {
          found.push({
            uid: u.uid,
            username: u.username,
            display_name: u.displayName,
            avatar_url: u.avatarUrl,
            about: u.about,
            last_seen: u.lastSeen
          });
          if (found.length >= 25) break;
        }
      }
      log(`🔍 [ПОИСК] Запрос "${query}": найдено ${found.length} пользователей`);
      return sendJson(res, 200, { users: found });
    }

    // 7. Отправка сообщения (HTTP REST)
    if (pathname === '/api/messages/send' && req.method === 'POST') {
      const data = await parseJsonBody(req);
      const authUid = getAuthUid(req) || data.sender_uid;
      const recipientUid = data.recipient_uid;
      const payload = data.payload || data.content || '';
      const cipher = data.cipher || null;
      const signalType = data.signalType || 1;
      const kind = data.kind || 'text';
      const msgId = data.id || ('m_' + Date.now() + '_' + crypto.randomBytes(4).toString('hex'));
      const chatId = data.chat_id || [authUid, recipientUid].sort().join('_');
      const now = Date.now();

      const msg = {
        id: msgId,
        chat_id: chatId,
        sender_uid: authUid,
        recipient_uid: recipientUid,
        payload,
        cipher,
        signalType,
        kind,
        created_at: now
      };

      messages.push(msg);
      if (messages.length > 50000) {
        messages.shift(); // Очистка старых сообщений для экономии RAM
      }

      log(`💬 [СООБЩЕНИЕ] Чат ${chatId} | От: ${authUid} -> Кому: ${recipientUid} (ID: ${msgId})`);

      return sendJson(res, 200, {
        status: 'success',
        messageId: msgId,
        sentAt: now
      });
    }

    // 8. Получение сообщений чата (Polling вместо тяжёлого сокета)
    if (pathname === '/api/messages' && req.method === 'GET') {
      const authUid = getAuthUid(req);
      const peerUid = parsedUrl.query.peer_uid;
      const chatId = parsedUrl.query.chat_id || (peerUid && authUid ? [authUid, peerUid].sort().join('_') : null);
      const after = parseInt(parsedUrl.query.after || '0', 10);

      let chatMsgs = [];
      if (chatId) {
        chatMsgs = messages.filter(m => m.chat_id === chatId && m.created_at > after);
      } else if (authUid && peerUid) {
        chatMsgs = messages.filter(m => 
          ((m.sender_uid === authUid && m.recipient_uid === peerUid) ||
           (m.sender_uid === peerUid && m.recipient_uid === authUid)) &&
          m.created_at > after
        );
      } else {
        chatMsgs = messages.slice(-50);
      }

      return sendJson(res, 200, { messages: chatMsgs });
    }

    // 9. E2EE ключи
    if (pathname.startsWith('/api/keys/')) {
      const targetUid = pathname.split('/').pop();
      if (req.method === 'GET') {
        const bundle = keys.get(targetUid) || {};
        return sendJson(res, 200, { bundle });
      }
      if (req.method === 'POST') {
        const bundle = await parseJsonBody(req);
        keys.set(targetUid, bundle);
        log(`🔐 [E2EE КЛЮЧ] Обновлен ключ для ${targetUid}`);
        return sendJson(res, 200, { status: 'success' });
      }
    }

    // 404
    sendJson(res, 404, { error: 'Маршрут не найден: ' + pathname });
  } catch (err) {
    logError(`Ошибка обработки ${pathname}: ${err.message}`);
    sendJson(res, 500, { error: err.message });
  }
});

server.listen(PORT, '0.0.0.0', () => {
  log(`🚀 Легковесный Node.js сервер Tsuyu запущен на порту ${PORT}`);
  log(`⚡ Потребление памяти: ~${Math.round(process.memoryUsage().rss / 1024 / 1024)} МБ (лимит 512 МБ соблюден!)`);
  log(`💾 Все данные хранятся в оперативной памяти (RAM) без SQLite/Firebase`);
});
