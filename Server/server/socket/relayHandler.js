const { rooms, socketMeta } = require('./deviceHandler');
const { getDb } = require('../config/firebase');

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
  'screen_frame', 'camera_frame',
  'start_screen', 'stop_screen', 'start_camera', 'stop_camera',
  'lock_pc',
  'mouse_move', 'mouse_tap', 'mouse_right_tap', 'mouse_scroll',
  'usb_connected', 'usb_disconnected',
  'handshake', 'ping', 'pong',

  // ── Reverse sync: Android → Windows ───────────────────────────────────
  'mobile_status',          // ringer mode + phone volume + notif count
  'mobile_wallpaper',       // home screen wallpaper image (base64)
  'mobile_sms_list',        // full SMS thread list (on connect / on request)
  'mobile_photo_list',      // photo metadata grouped by album (on connect / on request)
  'mobile_photo_thumbnail', // single photo thumbnail on demand (lazy load)

  // ── Remote control: Windows → Android ────────────────────────────────
  'lock_phone',             // lock mobile screen instantly
  'open_camera',            // open CameraX inside NexLink (back/front, no popup)
  'ringer_mode',            // set ringer mode 0=Silent 1=Vibrate 2=Ring
  'mobile_volume',          // set phone speaker volume (0-100)
  'mobile_ringer_volume',   // set phone ringer volume (0-100)
  'start_mobile_camera',    // PC requests Android camera
  'stop_mobile_camera',     // PC requests Android camera stop
  'mobile_camera_frame',    // Android sends camera frame to PC
  'start_mobile_screen',    // PC requests Android screen
  'stop_mobile_screen',     // PC requests Android screen stop
  'mobile_screen_frame',    // Android sends screen frame to PC
  'screen_share_start',     // start screen sharing session
  'screen_share_stop',      // stop screen sharing session
  'request_mobile_sms',     // Windows asks Android to send sms list
  'request_photos',         // Windows asks Android to send photo list
  'request_photo_thumbnail',// Windows asks Android for a specific photo thumbnail
  'get_thread',             // Windows requests full messages of a thread

  // ── Notifications snapshot ─────────────────────────────────────────────
  'notification_list',      // bulk notification list on connect
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
