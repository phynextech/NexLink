const { v4: uuidv4 } = require('uuid');
const { getDb } = require('../config/firebase');
const logger = require('../config/logger');

// rooms[roomKey] = { desktop: socketId|null, mobile: socketId|null }
const rooms = new Map();
// socketMeta[socketId] = { userId, deviceId, role, roomKey }
const socketMeta = new Map();

const roomKey = (userId, deviceId) => `${userId}:${deviceId}`;

const handleDeviceConnect = (io, socket) => {
  socket.on('connect_device', async ({ userId, deviceId, role, deviceName }) => {
    if (!userId || !deviceId || !['desktop', 'mobile'].includes(role)) {
      socket.emit('error', { message: 'connect_device requires userId, deviceId, role (desktop|mobile)' });
      return;
    }

    const key = roomKey(userId, deviceId);
    let room = rooms.get(key);
    if (!room) {
      room = { desktop: null, mobile: null };
      rooms.set(key, room);
    }

    // Kick out old socket for the same role
    if (room[role] && room[role] !== socket.id) {
      const oldSock = io.sockets.sockets.get(room[role]);
      if (oldSock) oldSock.disconnect(true);
    }

    room[role] = socket.id;
    socketMeta.set(socket.id, { userId, deviceId, role, roomKey: key });

    socket.join(key);
    socket.data.roomKey = key;
    socket.data.role = role;

    logger.info(`✅ [${key}] ${role} registered (${socket.id})`);

    // Persist session to RTDB
    const db = getDb();
    if (db) {
      const sessionId = uuidv4();
      socket.data.sessionId = sessionId;
      await db.ref(`sessions/${key}/${role}`).set({
        sessionId,
        socketId: socket.id,
        connectionStatus: 'online',
        deviceName: deviceName || (role === 'desktop' ? 'PC' : 'Phone'),
        connectedAt: Date.now(),
      });
      await db.ref(`devices/${deviceId}/lastSeen`).set(Date.now());
    }

    // Notify peer
    const peer = role === 'desktop' ? 'mobile' : 'desktop';
    const peerSock = room[peer] ? io.sockets.sockets.get(room[peer]) : null;
    if (peerSock) {
      peerSock.emit('peer_online', { role });
      socket.emit('peer_online', { role: peer });
    } else {
      socket.emit('peer_offline', { role: peer });
    }

    socket.emit('device_registered', { roomKey: key, role, sessionId: socket.data.sessionId });
  });
};

const handleDeviceDisconnect = async (io, socket) => {
  const key = socket.data.roomKey;
  const role = socket.data.role;
  if (!key || !role) return;

  const room = rooms.get(key);
  if (!room) return;

  if (room[role] === socket.id) {
    room[role] = null;
    logger.info(`❌ [${key}] ${role} disconnected`);

    // Notify peer
    const peer = role === 'desktop' ? 'mobile' : 'desktop';
    const peerId = room[peer];
    if (peerId) {
      const peerSock = io.sockets.sockets.get(peerId);
      if (peerSock) peerSock.emit('peer_offline', { role });
    }

    // Update RTDB
    const db = getDb();
    if (db) {
      await db.ref(`sessions/${key}/${role}/connectionStatus`).set('offline').catch(() => {});
      await db.ref(`sessions/${key}/${role}/disconnectedAt`).set(Date.now()).catch(() => {});
    }

    // Remove empty room
    if (!room.desktop && !room.mobile) {
      rooms.delete(key);
      logger.info(`🗑  Room ${key} removed`);
    }
  }
};

const cleanupStaleSockets = (io) => {
  setInterval(() => {
    for (const [key, room] of rooms.entries()) {
      for (const role of ['desktop', 'mobile']) {
        if (room[role] && !io.sockets.sockets.has(room[role])) {
          room[role] = null;
        }
      }
      if (!room.desktop && !room.mobile) {
        rooms.delete(key);
      }
    }
  }, 30000);
};

module.exports = {
  handleDeviceConnect,
  handleDeviceDisconnect,
  cleanupStaleSockets,
  rooms,
  socketMeta,
};
