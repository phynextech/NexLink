const { Server } = require('socket.io');
const { verifySocketToken } = require('../middleware/auth');
const logger = require('../config/logger');
const { handleDeviceConnect, handleDeviceDisconnect, cleanupStaleSockets } = require('./deviceHandler');
const { registerRelayEvents } = require('./relayHandler');
const { registerSignalingEvents } = require('./signalingHandler');

const initSocketIO = (server) => {
  const io = new Server(server, {
    cors: { origin: '*', methods: ['GET', 'POST'] },
    transports: ['websocket', 'polling'],
    pingInterval: 25000,
    pingTimeout: 60000,
  });

  // Authentication Middleware
  io.use(verifySocketToken);

  io.on('connection', (socket) => {
    logger.info(`🔌 Socket connected: ${socket.id} (uid=${socket.data.uid})`);

    // Register handlers
    handleDeviceConnect(io, socket);
    registerRelayEvents(io, socket);
    registerSignalingEvents(io, socket);

    socket.on('disconnect_device', () => handleDeviceDisconnect(io, socket));
    socket.on('disconnect', () => handleDeviceDisconnect(io, socket));
    socket.on('error', (e) => logger.error(`WS error [${socket.id}]: ${e.message}`));
  });

  // Start stale socket cleanup routine
  cleanupStaleSockets(io);

  return io;
};

module.exports = initSocketIO;
