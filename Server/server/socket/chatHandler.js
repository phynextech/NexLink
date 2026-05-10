const { rooms, socketMeta } = require('./deviceHandler');
const { getDb } = require('../config/firebase');
const { v4: uuidv4 } = require('uuid');
const logger = require('../config/logger');

// ── In-memory transfer registry ────────────────────────────────────────────
const activeTransfers = new Map();

// ── Relay-only events (no Firebase write needed) ───────────────────────────
const RELAY_ONLY_EVENTS = [
  'chat_file_chunk',   // binary-heavy, just pass through
  'chat_file_accept',
  'chat_file_reject',
  'chat_typing',
  'chat_voice_message',
  'chat_clipboard',
  'chat_screenshot',
];

// ── Events that get Firebase persistence + relay ───────────────────────────
const HANDLED_EVENTS = [
  'chat_message',
  'chat_file_offer',
  'chat_file_ack',
  'chat_file_done',
  'chat_file_pause',
  'chat_file_resume',
  'chat_file_cancel',
  'chat_delivered',
  'chat_read',
  'chat_reaction',
  'chat_star',
];

const ALL_CHAT_EVENTS = [...RELAY_ONLY_EVENTS, ...HANDLED_EVENTS];

// ── Helper: relay to peer ─────────────────────────────────────────────────
const relayToPeer = (io, socket, event, data) => {
  const key  = socket.data.roomKey;
  const role = socket.data.role;
  if (!key || !role) return false;
  const room = rooms.get(key);
  if (!room) return false;
  const peer   = role === 'desktop' ? 'mobile' : 'desktop';
  const peerId = room[peer];
  if (peerId) {
    const peerSock = io.sockets.sockets.get(peerId);
    if (peerSock) { peerSock.emit(event, data); return true; }
  }
  return false;
};

const registerChatEvents = (io, socket) => {

  // ── Relay-only events ─────────────────────────────────────────────────────
  RELAY_ONLY_EVENTS.forEach((event) => {
    socket.on(event, (data) => {
      relayToPeer(io, socket, event, data);
    });
  });

  // ── Handled events (Firebase + relay) ────────────────────────────────────
  HANDLED_EVENTS.forEach((event) => {
    socket.on(event, async (data) => {
      const key  = socket.data.roomKey;
      const role = socket.data.role;
      if (!key || !role) return;

      // Relay first for minimum latency
      relayToPeer(io, socket, event, data);

      const db = getDb();
      if (!db) return;

      try {
        switch (event) {
          case 'chat_message': {
            const msgId = data.messageId || uuidv4();
            await db.ref(`chat/${key}/messages/${msgId}`).set({
              messageId:     msgId,
              senderId:      role,
              type:          'text',
              content:       data.content       || '',
              fileName:      data.fileName      || null,
              fileMime:      data.fileMime      || null,
              fileSizeBytes: data.fileSizeBytes || null,
              fileId:        data.fileId        || null,
              reaction:      null,
              replyToId:     data.replyToId     || null,
              isStarred:     false,
              isDelivered:   false,
              isRead:        false,
              timestamp:     data.timestamp     || Date.now(),
            });
            await db.ref(`chat/${key}/summary`).update({
              lastMessage:   data.content || (data.fileName ? `📎 ${data.fileName}` : 'File'),
              lastTimestamp: data.timestamp || Date.now(),
            });
            logger.info(`[Chat] Saved msg ${msgId} for room ${key}`);
            break;
          }

          case 'chat_file_offer': {
            const { fileId, name, size, mimeType, totalChunks, messageId } = data;
            if (!fileId) break;
            activeTransfers.set(fileId, {
              roomKey: key, senderId: role,
              name, size, mimeType, totalChunks,
              receivedChunks: new Set(), paused: false, cancelled: false,
              startedAt: Date.now(),
            });
            await db.ref(`chat/${key}/transfers/${fileId}`).set({
              fileId, name, size, mimeType, totalChunks,
              messageId: messageId || null,
              senderId: role, status: 'offered', startedAt: Date.now(),
            });
            logger.info(`[Chat] File offer: ${name} (${size}B) room=${key}`);
            break;
          }

          case 'chat_file_ack': {
            const { fileId, chunkIndex } = data;
            const xfer = activeTransfers.get(fileId);
            if (xfer) {
              xfer.receivedChunks.add(chunkIndex);
              if (xfer.receivedChunks.size % 10 === 0) {
                const pct = Math.round((xfer.receivedChunks.size / xfer.totalChunks) * 100);
                await db.ref(`chat/${key}/transfers/${fileId}/progress`).set(pct);
              }
            }
            break;
          }

          case 'chat_file_done': {
            activeTransfers.delete(data.fileId);
            await db.ref(`chat/${key}/transfers/${data.fileId}`).update({
              status: 'complete', completedAt: Date.now(), progress: 100,
            });
            break;
          }

          case 'chat_delivered': {
            if (data.messageId)
              await db.ref(`chat/${key}/messages/${data.messageId}/isDelivered`).set(true);
            break;
          }
          case 'chat_read': {
            if (data.messageId)
              await db.ref(`chat/${key}/messages/${data.messageId}/isRead`).set(true);
            break;
          }
          case 'chat_reaction': {
            if (data.messageId)
              await db.ref(`chat/${key}/messages/${data.messageId}/reaction`).set(data.emoji || null);
            break;
          }
          case 'chat_star': {
            if (data.messageId)
              await db.ref(`chat/${key}/messages/${data.messageId}/isStarred`).set(!!data.starred);
            break;
          }
          case 'chat_file_pause': {
            const xfer = activeTransfers.get(data.fileId);
            if (xfer) xfer.paused = true;
            await db.ref(`chat/${key}/transfers/${data.fileId}/status`).set('paused');
            break;
          }
          case 'chat_file_resume': {
            const xfer = activeTransfers.get(data.fileId);
            if (xfer) xfer.paused = false;
            await db.ref(`chat/${key}/transfers/${data.fileId}/status`).set('transferring');
            break;
          }
          case 'chat_file_cancel': {
            activeTransfers.delete(data.fileId);
            await db.ref(`chat/${key}/transfers/${data.fileId}/status`).set('cancelled');
            break;
          }
          default: break;
        }
      } catch (err) {
        logger.error(`[Chat] Firebase error on ${event}: ${err.message}`);
      }
    });
  });

  // ── History request: ONLY returned to requester, NEVER relayed ────────────
  socket.on('chat_history_req', async (payload) => {
    const limit = (payload && payload.limit) || 100;
    const key   = socket.data.roomKey;
    if (!key) { socket.emit('chat_history', { messages: [], transfers: [] }); return; }
    const db = getDb();
    if (!db) { socket.emit('chat_history', { messages: [], transfers: [] }); return; }
    try {
      const [msgsSnap, xferSnap] = await Promise.all([
        db.ref(`chat/${key}/messages`).orderByChild('timestamp').limitToLast(limit).once('value'),
        db.ref(`chat/${key}/transfers`).once('value'),
      ]);
      const messages  = msgsSnap.val()
        ? Object.values(msgsSnap.val()).sort((a, b) => a.timestamp - b.timestamp)
        : [];
      const transfers = xferSnap.val() ? Object.values(xferSnap.val()) : [];
      socket.emit('chat_history', { messages, transfers });
      logger.info(`[Chat] History: sent ${messages.length} msgs to ${socket.id} (room=${key})`);
    } catch (err) {
      logger.error(`[Chat] history_req error: ${err.message}`);
      socket.emit('chat_history', { messages: [], transfers: [] });
    }
  });
};

// ── Periodic cleanup of stale in-memory transfers (>24h) ─────────────────────
const cleanupTransfers = () => {
  setInterval(() => {
    const cutoff = Date.now() - 24 * 60 * 60 * 1000;
    for (const [fileId, xfer] of activeTransfers.entries()) {
      if (xfer.startedAt && xfer.startedAt < cutoff) {
        activeTransfers.delete(fileId);
        logger.info(`[Chat] Cleaned stale transfer: ${fileId}`);
      }
    }
  }, 60 * 60 * 1000);
};

module.exports = { registerChatEvents, cleanupTransfers, CHAT_EVENTS: ALL_CHAT_EVENTS };
