/**
 * NexLink Cloud Relay Server
 * ==========================
 * Bridges Windows PC (desktop role) and Android (mobile role) across ANY network.
 * Both sides connect here using their Firebase pairId as the room key.
 * Messages are forwarded transparently between desktop<->mobile.
 *
 * Deploy on Render.com (free tier, keep-alive via /health ping).
 */

require('dotenv').config();
const express = require('express');
const http = require('http');
const { WebSocketServer, WebSocket } = require('ws');
const admin = require('firebase-admin');
const { v4: uuidv4 } = require('uuid');

// ─── Firebase Admin Init ────────────────────────────────────────────────
let db = null;
try {
  const serviceAccount = process.env.FIREBASE_SERVICE_ACCOUNT
    ? JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT)
    : null;

  if (serviceAccount) {
    admin.initializeApp({
      credential: admin.credential.cert(serviceAccount),
      projectId: 'nexlink-62e41',
    });
    db = admin.firestore();
    console.log('✅ Firebase Admin initialized');
  } else {
    console.warn('⚠️  FIREBASE_SERVICE_ACCOUNT not set — pairing stored in memory only');
  }
} catch (e) {
  console.error('Firebase init error:', e.message);
}

// ─── In-memory room registry ─────────────────────────────────────────────
// rooms[pairId] = { desktop: WebSocket | null, mobile: WebSocket | null }
const rooms = new Map();

function getOrCreateRoom(pairId) {
  if (!rooms.has(pairId)) {
    rooms.set(pairId, { desktop: null, mobile: null });
  }
  return rooms.get(pairId);
}

function cleanRoom(pairId) {
  const room = rooms.get(pairId);
  if (room && !room.desktop && !room.mobile) {
    rooms.delete(pairId);
    console.log(`🗑  Room ${pairId} removed (empty)`);
  }
}

// ─── Express app (REST + WebSocket) ─────────────────────────────────────
const app = express();
app.use(express.json());
app.use(require('cors')());

// Health check – Render.com / UptimeRobot ping this to keep server alive
app.get('/', (_, res) => res.json({ status: 'ok', service: 'NexLink Cloud Relay' }));
app.get('/health', (_, res) => res.json({ status: 'ok', rooms: rooms.size, ts: Date.now() }));

// ── Pairing API ──
// POST /pair/create  → creates a new pairId in Firestore, returns it
app.post('/pair/create', async (req, res) => {
  const { userId, deviceName } = req.body;
  if (!userId) return res.status(400).json({ error: 'userId required' });

  const pairId = uuidv4();
  const pairData = {
    pairId,
    userId,
    deviceName: deviceName || 'My PC',
    createdAt: Date.now(),
    lastSeen: Date.now(),
  };

  if (db) {
    await db.collection('pairs').doc(pairId).set(pairData);
  }

  console.log(`🔑 New pair created: ${pairId} for user ${userId}`);
  res.json({ pairId });
});

// GET /pair/:pairId  → verify a pair exists
app.get('/pair/:pairId', async (req, res) => {
  const { pairId } = req.params;

  if (db) {
    const doc = await db.collection('pairs').doc(pairId).get();
    if (!doc.exists) return res.status(404).json({ error: 'Pair not found' });
    return res.json(doc.data());
  }

  // Memory fallback
  if (rooms.has(pairId)) {
    return res.json({ pairId, status: 'active' });
  }
  res.status(404).json({ error: 'Pair not found' });
});

// DELETE /pair/:pairId  → remove pairing
app.delete('/pair/:pairId', async (req, res) => {
  const { pairId } = req.params;
  if (db) {
    await db.collection('pairs').doc(pairId).delete();
  }
  rooms.delete(pairId);
  res.json({ success: true });
});

// ─── HTTP server ─────────────────────────────────────────────────────────
const server = http.createServer(app);

// ─── WebSocket server ─────────────────────────────────────────────────────
const wss = new WebSocketServer({ server, path: '/relay' });

wss.on('connection', (ws, req) => {
  // URL format: wss://server/relay?pairId=xxx&role=desktop|mobile
  const url = new URL(req.url, `http://${req.headers.host}`);
  const pairId = url.searchParams.get('pairId');
  const role = url.searchParams.get('role'); // 'desktop' or 'mobile'

  if (!pairId || !['desktop', 'mobile'].includes(role)) {
    ws.close(4000, 'Missing pairId or role');
    return;
  }

  const room = getOrCreateRoom(pairId);

  // Close existing socket for this role if reconnecting
  if (room[role] && room[role].readyState === WebSocket.OPEN) {
    room[role].close(1001, 'New connection took over');
  }
  room[role] = ws;

  const peer = role === 'desktop' ? 'mobile' : 'desktop';

  console.log(`🔌 [${pairId}] ${role} connected (${req.socket.remoteAddress})`);

  // Notify peer that partner came online
  if (room[peer] && room[peer].readyState === WebSocket.OPEN) {
    safeSend(room[peer], JSON.stringify({ type: 'relay_peer_online', role }));
    safeSend(ws, JSON.stringify({ type: 'relay_peer_online', role: peer }));
  } else {
    safeSend(ws, JSON.stringify({ type: 'relay_peer_offline', role: peer }));
  }

  // ── Update lastSeen in Firestore every connect ──
  if (db) {
    db.collection('pairs').doc(pairId).update({ lastSeen: Date.now() }).catch(() => {});
  }

  ws.on('message', (data) => {
    // Forward raw message to the other side
    const target = room[peer];
    if (target && target.readyState === WebSocket.OPEN) {
      safeSend(target, data);
    }
    // Drop silently if peer not connected — messages are command-driven, not buffered
  });

  ws.on('close', () => {
    console.log(`❌ [${pairId}] ${role} disconnected`);
    if (room[role] === ws) {
      room[role] = null;
      // Notify peer
      if (room[peer] && room[peer].readyState === WebSocket.OPEN) {
        safeSend(room[peer], JSON.stringify({ type: 'relay_peer_offline', role }));
      }
      cleanRoom(pairId);
    }
  });

  ws.on('error', (e) => {
    console.error(`WS error [${pairId}] ${role}:`, e.message);
  });
});

function safeSend(ws, data) {
  try {
    if (ws.readyState === WebSocket.OPEN) ws.send(data);
  } catch (e) { /* ignore */ }
}

// ─── Keep-alive interval: clear dead sockets ─────────────────────────────
setInterval(() => {
  for (const [pairId, room] of rooms.entries()) {
    for (const role of ['desktop', 'mobile']) {
      if (room[role] && room[role].readyState !== WebSocket.OPEN) {
        room[role] = null;
      }
    }
    cleanRoom(pairId);
  }
}, 30_000);

// ─── Start ────────────────────────────────────────────────────────────────
const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
  console.log(`🚀 NexLink Relay Server running on port ${PORT}`);
  console.log(`   WebSocket: ws://localhost:${PORT}/relay?pairId=XXX&role=desktop|mobile`);
  console.log(`   Health:    http://localhost:${PORT}/health`);
});
