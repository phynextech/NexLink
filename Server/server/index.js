require('dotenv').config();
const express = require('express');
const http = require('http');
const cors = require('cors');

const logger = require('./config/logger');
const deviceRoutes = require('./routes/deviceRoutes');
const errorHandler = require('./middleware/errorHandler');
const initSocketIO = require('./socket');
const { rooms } = require('./socket/deviceHandler');

// Initialize Firebase (run for side effects)
require('./config/firebase');

const app = express();
const server = http.createServer(app);

// Middleware
app.use(express.json());
app.use(cors());

// Health Check Endpoints
app.get('/', (_, res) => res.json({ status: 'ok', service: 'NexLink Relay v2 Modular', version: '2.1.0', ts: Date.now() }));
app.get('/health', (_, res) => res.json({ status: 'ok', version: '2.1.0', rooms: rooms.size, ts: Date.now() }));

// Routes
app.use('/pair', deviceRoutes);

// Error Handling Middleware
app.use(errorHandler);

// Initialize Socket.IO
initSocketIO(server);

// Start Server
const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
  logger.info(`🚀 NexLink Relay Server running on port ${PORT}`);
  logger.info(`   Socket.IO:  wss://nexlink-khhe.onrender.com`);
  logger.info(`   Health:     https://nexlink-khhe.onrender.com/health`);
});
