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
const ROOM_MAX = 8;

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
  ws.peerId = crypto.randomBytes(8).toString('hex');
  ws.room = null;
  ws.isAlive = true;

  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); }
    catch { return; }
    if (!msg || typeof msg.type !== 'string') return;

    if (msg.type === 'join') {
      if (ws.room) return;
      if (typeof msg.room !== 'string' || msg.room.length < 16 || msg.room.length > 128) {
        send(ws, { type: 'error', error: 'invalid room id' });
        return;
      }
      let members = rooms.get(msg.room);
      if (!members) { members = new Map(); rooms.set(msg.room, members); }
      if (members.size >= ROOM_MAX) {
        send(ws, { type: 'error', error: 'room full' });
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

  ws.on('close', () => removeFromRoom(ws));
  ws.on('error', () => removeFromRoom(ws));
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
