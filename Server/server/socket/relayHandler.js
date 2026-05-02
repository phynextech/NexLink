const { rooms, socketMeta } = require('./deviceHandler');
const { getDb } = require('../config/firebase');

const RELAY_EVENTS = [
  'send_notification',
  'file_request', 'file_response', 'file_chunk',
  'launch_app',
  'clipboard_sync',
  'media_control', 'media_seek', 'volume', 'brightness',
  'request_info', 'get_wallpaper',
  'wifi_info', 'battery_info', 'bt_info', 'wallpaper', 'now_playing',
  'app_list', 'file_list', 'browse', 'open_file', 'download_file',
  'clipboard_pull', 'clipboard_push',
  'notification', 'sms_received', 'sms_list', 'sms_send',
  'screen_frame', 'camera_frame',
  'start_screen', 'stop_screen', 'start_camera', 'stop_camera',
  'lock_pc',
  'mouse_move', 'mouse_tap', 'mouse_right_tap', 'mouse_scroll',
  'usb_connected', 'usb_disconnected',
  'handshake', 'ping', 'pong',
];

const registerRelayEvents = (io, socket) => {
  RELAY_EVENTS.forEach((event) => {
    socket.on(event, (data) => {
      const key = socket.data.roomKey;
      const role = socket.data.role;
      if (!key || !role) return;

      if (event === 'ping') {
        socket.emit('pong', {});
        return;
      }
      if (event === 'pong') return;

      const room = rooms.get(key);
      if (!room) return;

      const peer = role === 'desktop' ? 'mobile' : 'desktop';
      const peerId = room[peer];
      if (!peerId) return; // Peer not connected, drop silently

      const peerSock = io.sockets.sockets.get(peerId);
      if (peerSock) {
        peerSock.emit(event, data);
      }

      // Persist notifications to RTDB
      if (event === 'send_notification' || event === 'notification') {
        const meta = socketMeta.get(socket.id);
        const db = getDb();
        if (db && meta) {
          db.ref(`notifications/${meta.userId}`).push({
            ...data,
            timestamp: Date.now(),
          });
        }
      }
    });
  });
};

module.exports = {
  registerRelayEvents,
};
