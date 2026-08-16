/*
 * Cipher P2P Chat — signaling relay
 *
 * Dumb WebSocket relay: peers join a room by passcode-derived id, and
 * the server blindly forwards signal payloads between them. Payloads
 * are encrypted end-to-end with a key derived from the passcode, so
 * the server never sees plaintext SDP or identities.
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 8080;
const INDEX_PATH = path.join(__dirname, 'index.html');
const APK_PATH = path.join(__dirname, 'cipher.apk');

const server = http.createServer((req, res) => {
  if (req.url === '/healthz') {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('ok');
    return;
  }
  if (req.method !== 'GET') {
    res.writeHead(405); res.end();
    return;
  }
  if (req.url === '/cipher.apk' || req.url === '/app') {
    fs.readFile(APK_PATH, (err, data) => {
      if (err) {
        res.writeHead(404, { 'Content-Type': 'text/plain' });
        res.end('APK not available');
        return;
      }
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Disposition': 'attachment; filename="Cipher.apk"',
        'Content-Length': data.length,
        'Cache-Control': 'no-cache',
      });
      res.end(data);
    });
    return;
  }
  if (req.url === '/' || req.url === '/index.html' || req.url.startsWith('/#')) {
    fs.readFile(INDEX_PATH, (err, data) => {
      if (err) {
        res.writeHead(500, { 'Content-Type': 'text/plain' });
        res.end('Failed to load index.html');
        return;
      }
      res.writeHead(200, {
        'Content-Type': 'text/html; charset=utf-8',
        'Cache-Control': 'no-cache',
        'Referrer-Policy': 'no-referrer',
        'X-Content-Type-Options': 'nosniff',
        'X-Frame-Options': 'DENY',
        // Defence in depth for the single-file client: everything it needs
        // is inline or same-origin, plus Google Fonts and the STUN/TURN
        // endpoints. Blocking other origins means an injected node cannot
        // exfiltrate identity keys or history even if escaping ever fails.
        'Content-Security-Policy': [
          "default-src 'self'",
          "script-src 'unsafe-inline' 'self'",
          "style-src 'unsafe-inline' 'self' https://fonts.googleapis.com",
          "font-src 'self' https://fonts.gstatic.com data:",
          "img-src 'self' data: blob:",
          "media-src 'self' blob: mediastream:",
          "connect-src 'self' ws: wss: blob: data:",
          "frame-ancestors 'none'",
          "base-uri 'none'",
          "form-action 'none'",
          "object-src 'none'",
        ].join('; '),
      });
      res.end(data);
    });
    return;
  }
  res.writeHead(404); res.end('not found');
});

const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 256 * 1024 });

// room -> Map<peerId, ws>
const rooms = new Map();
// Hard cap on a single room's membership. The client uses a sparse
// mesh + gossip layer for chat sync, so this can comfortably exceed
// the old full-mesh practical limit of ~6.
const ROOM_MAX = 32;

// Abuse limits. The relay is deliberately dumb, but "dumb" must not mean
// "free to exhaust": without these, one client could open unlimited
// sockets, create unlimited rooms, or flood signal traffic until the
// process dies.
const MAX_TOTAL_CONNECTIONS = 5000;
const MAX_CONNECTIONS_PER_IP = 50;
const MAX_ROOMS = 2000;
const MSG_RATE_WINDOW_MS = 10000;
const MSG_RATE_MAX = 300;           // messages per window per socket
// Optional origin allowlist (comma-separated). Unset = allow any, which
// keeps self-hosting simple; set it in production to block cross-site
// WebSocket connections from arbitrary pages.
const ALLOWED_ORIGINS = (process.env.ALLOWED_ORIGINS || '')
  .split(',').map(s => s.trim()).filter(Boolean);

const connectionsPerIp = new Map(); // ip -> count

function clientIp(req) {
  const fwd = req.headers['x-forwarded-for'];
  if (typeof fwd === 'string' && fwd.length) return fwd.split(',')[0].trim();
  return req.socket.remoteAddress || 'unknown';
}

function send(ws, obj) {
  if (ws.readyState !== 1) return;
  try { ws.send(JSON.stringify(obj)); } catch {}
}

function broadcast(room, obj, exceptId) {
  const members = rooms.get(room);
  if (!members) return;
  for (const [pid, w] of members) {
    if (pid !== exceptId) send(w, obj);
  }
}

function removeFromRoom(ws) {
  if (!ws.room) return;
  const members = rooms.get(ws.room);
  if (members) {
    members.delete(ws.peerId);
    if (members.size === 0) rooms.delete(ws.room);
    else broadcast(ws.room, { type: 'peer-left', peerId: ws.peerId });
  }
  ws.room = null;
}

wss.on('connection', (ws, req) => {
  const origin = req.headers.origin;
  if (ALLOWED_ORIGINS.length && origin && !ALLOWED_ORIGINS.includes(origin)) {
    try { ws.close(1008, 'origin not allowed'); } catch {}
    return;
  }
  if (wss.clients.size > MAX_TOTAL_CONNECTIONS) {
    try { ws.close(1013, 'server busy'); } catch {}
    return;
  }
  const ip = clientIp(req);
  const ipCount = (connectionsPerIp.get(ip) || 0) + 1;
  if (ipCount > MAX_CONNECTIONS_PER_IP) {
    try { ws.close(1013, 'too many connections'); } catch {}
    return;
  }
  connectionsPerIp.set(ip, ipCount);
  ws.clientIp = ip;

  ws.peerId = crypto.randomBytes(8).toString('hex');
  ws.room = null;
  ws.isAlive = true;
  ws.msgWindowStart = Date.now();
  ws.msgCount = 0;

  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (raw) => {
    // Per-socket rate limit — a room member can otherwise spin the relay
    // (and every peer it forwards to) with unbounded signal traffic.
    const now = Date.now();
    if (now - ws.msgWindowStart > MSG_RATE_WINDOW_MS) {
      ws.msgWindowStart = now;
      ws.msgCount = 0;
    }
    if (++ws.msgCount > MSG_RATE_MAX) {
      send(ws, { type: 'error', error: 'rate limited' });
      try { ws.close(1008, 'rate limited'); } catch {}
      return;
    }

    let msg;
    try { msg = JSON.parse(raw.toString()); }
    catch { return; }
    if (!msg || typeof msg.type !== 'string') return;

    if (msg.type === 'join') {
      if (ws.room) return;
      // Room id is the SHA-256 hex of the passcode (64 chars). Reject
      // anything that isn't lower-case hex of plausible length so two
      // clients can't accidentally end up in different rooms because
      // of unicode normalization.
      if (typeof msg.room !== 'string' || !/^[a-f0-9]{16,128}$/.test(msg.room)) {
        send(ws, { type: 'error', error: 'invalid room id' });
        return;
      }
      let members = rooms.get(msg.room);
      if (!members) {
        // Creating a room is free, so cap how many can exist at once —
        // otherwise a single client can mint unlimited rooms and grow the
        // map until the process runs out of memory.
        if (rooms.size >= MAX_ROOMS) {
          send(ws, { type: 'error', error: 'server at capacity' });
          return;
        }
        members = new Map();
        rooms.set(msg.room, members);
      }
      if (members.size >= ROOM_MAX) {
        send(ws, { type: 'error', error: 'room full' });
        // An empty room we just created must not be left behind.
        if (members.size === 0) rooms.delete(msg.room);
        return;
      }
      ws.room = msg.room;
      const existing = Array.from(members.keys());
      send(ws, { type: 'joined', peerId: ws.peerId, peers: existing });
      broadcast(msg.room, { type: 'peer-joined', peerId: ws.peerId }, ws.peerId);
      members.set(ws.peerId, ws);
      return;
    }

    if (msg.type === 'signal') {
      if (!ws.room) return;
      if (typeof msg.to !== 'string' || typeof msg.payload !== 'string') return;
      if (msg.payload.length > 200 * 1024) return;
      const members = rooms.get(ws.room);
      if (!members) return;
      const target = members.get(msg.to);
      if (!target) return;
      send(target, { type: 'signal', from: ws.peerId, payload: msg.payload });
      return;
    }

    if (msg.type === 'leave') {
      removeFromRoom(ws);
      return;
    }
  });

  const cleanup = () => {
    removeFromRoom(ws);
    if (ws.clientIp) {
      const left = (connectionsPerIp.get(ws.clientIp) || 1) - 1;
      if (left > 0) connectionsPerIp.set(ws.clientIp, left);
      else connectionsPerIp.delete(ws.clientIp);
      ws.clientIp = null;
    }
  };
  ws.on('close', cleanup);
  ws.on('error', cleanup);
});

const pingInterval = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) { try { ws.terminate(); } catch {} continue; }
    ws.isAlive = false;
    try { ws.ping(); } catch {}
  }
}, 30000);
wss.on('close', () => clearInterval(pingInterval));

server.listen(PORT, () => {
  console.log(`Cipher relay listening on :${PORT}`);
});

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    console.log(`Received ${sig}, shutting down`);
    clearInterval(pingInterval);
    wss.close(() => server.close(() => process.exit(0)));
    setTimeout(() => process.exit(1), 5000).unref();
  });
}
