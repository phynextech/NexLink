const { rooms } = require('./deviceHandler');
const logger = require('../config/logger');

// ── WebRTC signaling events ───────────────────────────────────────────────
// These are forwarded transparently between peers for WebRTC negotiation.
// ICE candidates are buffered per-room so late-arriving candidates are not lost.
const WEBRTC_EVENTS = [
  'webrtc_offer',
  'webrtc_answer',
  'webrtc_ice',
  'webrtc_candidate',   // alias accepted from both client versions
];

// Per-room ICE candidate queue (buffers candidates until peer is available)
// Map<roomKey, { desktop: [], mobile: [] }>
const iceQueues = new Map();

const registerSignalingEvents = (io, socket) => {
  WEBRTC_EVENTS.forEach((event) => {
    socket.on(event, (data) => {
      const key  = socket.data.roomKey;
      const role = socket.data.role;
      if (!key || !role) return;

      const room = rooms.get(key);
      if (!room) return;

      const peer   = role === 'desktop' ? 'mobile' : 'desktop';
      const peerId = room[peer];

      const peerSock = peerId ? io.sockets.sockets.get(peerId) : null;

      if (peerSock) {
        // Peer online — deliver immediately
        peerSock.emit(event, data);

        // Also flush any buffered ICE candidates from this role to the peer
        const queue = iceQueues.get(key);
        if (queue && queue[role] && queue[role].length > 0) {
          queue[role].forEach((candidate) => peerSock.emit('webrtc_ice', candidate));
          queue[role] = [];
          logger.debug(`[Signaling] Flushed ${queue[role]?.length ?? 0} buffered ICE for ${key}:${role}`);
        }
      } else {
        // Peer not yet connected — buffer ICE candidates so they are not lost
        if (event === 'webrtc_ice' || event === 'webrtc_candidate') {
          if (!iceQueues.has(key)) iceQueues.set(key, { desktop: [], mobile: [] });
          const q = iceQueues.get(key);
          q[role].push(data);
          logger.debug(`[Signaling] Buffered ICE candidate for ${key}:${role} (${q[role].length} queued)`);
        }
        // Offer/Answer: log and drop (peer not present, renegotiation needed)
        if (event === 'webrtc_offer' || event === 'webrtc_answer') {
          logger.debug(`[Signaling] ${event} dropped — peer ${peer} not connected in room ${key}`);
        }
      }
    });
  });

  // Clean up ice queues when socket disconnects
  socket.on('disconnect', () => {
    const key  = socket.data.roomKey;
    const role = socket.data.role;
    if (key && role && iceQueues.has(key)) {
      const q = iceQueues.get(key);
      q[role] = [];
    }
  });
};

// Clean up stale ice queues for empty rooms
const cleanupIceQueues = (io) => {
  setInterval(() => {
    for (const [key] of iceQueues.entries()) {
      const room = Array.from(io.sockets.adapter.rooms.get(key) ?? []);
      if (room.length === 0) iceQueues.delete(key);
    }
  }, 60000);
};

module.exports = { registerSignalingEvents, cleanupIceQueues };
