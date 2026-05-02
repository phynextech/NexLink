const { rooms } = require('./deviceHandler');

const WEBRTC_EVENTS = ['webrtc_offer', 'webrtc_answer', 'webrtc_ice'];

const registerSignalingEvents = (io, socket) => {
  WEBRTC_EVENTS.forEach((event) => {
    socket.on(event, (data) => {
      const key = socket.data.roomKey;
      const role = socket.data.role;
      if (!key || !role) return;

      const room = rooms.get(key);
      if (!room) return;

      const peer = role === 'desktop' ? 'mobile' : 'desktop';
      const peerId = room[peer];
      if (!peerId) return;

      const peerSock = io.sockets.sockets.get(peerId);
      if (peerSock) {
        peerSock.emit(event, data);
      }
    });
  });
};

module.exports = {
  registerSignalingEvents,
};
