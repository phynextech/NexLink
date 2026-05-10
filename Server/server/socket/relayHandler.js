const { rooms, socketMeta } = require('./deviceHandler');
const { getDb } = require('../config/firebase');

// ── Control & signaling events only — NO media frame relaying ─────────────
// screen_frame / camera_frame / mobile_screen_frame / mobile_camera_frame are
// intentionally removed. These must travel via LAN direct or WebRTC P2P to
// avoid consuming Render relay bandwidth.
const RELAY_EVENTS = [
  'send_notification',
  'file_request', 'file_response', 'file_chunk',
  'launch_app',
  'clipboard_sync',
  'media_control', 'media_seek', 'volume', 'brightness',
  'volume_ack', 'brightness_ack',
  'request_info', 'get_wallpaper',
  'system_state', 'state_update',
  'wifi_info', 'battery_info', 'bt_info', 'wallpaper', 'now_playing',
  'app_list', 'file_list', 'browse', 'open_file', 'download_file',
  'request_running_apps', 'close_app', 'focus_app', 'running_apps',
  'file_preview', 'file_preview_data',
  'clipboard_pull', 'clipboard_push',
  'notification', 'sms_received', 'sms_list', 'sms_send',

  // ── Stream control signals (start/stop only — not the frames themselves) ─
  'start_screen', 'stop_screen', 'start_camera', 'stop_camera',
  'start_mobile_camera', 'stop_mobile_camera',
  'start_mobile_screen', 'stop_mobile_screen',
  'screen_share_start', 'screen_share_stop',

  'lock_pc',
  'mouse_move', 'mouse_tap', 'mouse_right_tap', 'mouse_scroll',
  'usb_connected', 'usb_disconnected',
  'handshake', 'ping', 'pong',

  // ── LAN discovery ─────────────────────────────────────────────────────────
  // Windows sends this once on connect; server relays local IP to Android
  'lan_info',

  // ── Reverse sync: Android → Windows ──────────────────────────────────────
  'mobile_status',
  'mobile_wallpaper',
  'mobile_sms_list',
  'mobile_photo_list',
  'mobile_photo_thumbnail',

  // ── Remote control: Windows → Android ────────────────────────────────────
  'lock_phone',
  'open_camera',
  'ringer_mode',
  'mobile_volume',
  'mobile_ringer_volume',
  'mobile_camera_frame',   // kept for TURN relay fallback (last resort only)
  'mobile_screen_frame',   // kept for TURN relay fallback (last resort only)
  'request_mobile_sms',
  'request_photos',
  'request_photo_thumbnail',
  'get_thread',

  // ── Notifications snapshot ────────────────────────────────────────────────
  'notification_list',

  // ── Chat & File Transfer ─────────────────────────────────────────────────
  'chat_message',
  'chat_file_offer', 'chat_file_accept', 'chat_file_reject',
  'chat_file_chunk', 'chat_file_ack', 'chat_file_done',
  'chat_file_pause', 'chat_file_resume', 'chat_file_cancel',
  'chat_typing', 'chat_delivered', 'chat_read',
  'chat_reaction', 'chat_history_req', 'chat_history',
  'chat_voice_message', 'chat_clipboard', 'chat_screenshot',
  'chat_star',
];

const registerRelayEvents = (io, socket) => {
  RELAY_EVENTS.forEach((event) => {
    socket.on(event, (data) => {
      const key  = socket.data.roomKey;
      const role = socket.data.role;
      if (!key || !role) return;

      if (event === 'ping') { socket.emit('pong', {}); return; }
      if (event === 'pong') return;

      const room = rooms.get(key);
      if (!room) return;

      const peer   = role === 'desktop' ? 'mobile' : 'desktop';
      const peerId = room[peer];
      if (!peerId) return;

      const peerSock = io.sockets.sockets.get(peerId);
      if (peerSock) peerSock.emit(event, data);

      // Persist notifications to RTDB
      if (event === 'send_notification' || event === 'notification') {
        const meta = socketMeta.get(socket.id);
        const db   = getDb();
        if (db && meta) {
          db.ref(`notifications/${meta.userId}`).push({ ...data, timestamp: Date.now() });
        }
      }
    });
  });
};

module.exports = { registerRelayEvents };
