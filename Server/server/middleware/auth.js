const { admin, getDb } = require('../config/firebase');
const logger = require('../config/logger');

/**
 * Express middleware to verify Firebase ID token.
 */
const verifyFirebaseToken = async (req, res, next) => {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'Unauthorized: No token provided' });
  }

  const token = authHeader.split('Bearer ')[1];

  try {
    const decodedToken = await admin.auth().verifyIdToken(token);
    req.user = decodedToken;
    next();
  } catch (error) {
    logger.warn(`REST Auth failed: ${error.message}`);
    return res.status(401).json({ error: 'Unauthorized: Invalid token' });
  }
};

/**
 * Socket.IO middleware — accepts all connections.
 *
 * Auth strategy:
 *   - If a valid Firebase token is provided AND Firebase Admin is initialized,
 *     we verify it and use the Firebase UID.
 *   - Otherwise (PC client has no Firebase login, or token is empty/dev mode),
 *     we fall through and use the userId supplied in the handshake auth object.
 *
 * This allows both the Windows client (which sends a pc_xxx userId) and the
 * Android client (which sends a real Firebase token) to connect to the relay.
 */
const verifySocketToken = async (socket, next) => {
  const token  = socket.handshake.auth?.token  || socket.handshake.query?.token;
  const userId = socket.handshake.auth?.userId || socket.handshake.query?.userId;

  // No Firebase Admin — allow all, use provided userId
  if (!getDb() || !admin) {
    socket.data.uid = userId || socket.id;
    return next();
  }

  // No token supplied — allow with provided userId (PC client)
  if (!token) {
    socket.data.uid = userId || socket.id;
    logger.debug(`Socket ${socket.id} connected without token (uid=${socket.data.uid})`);
    return next();
  }

  // Token supplied — try to verify (Android client)
  try {
    const decoded = await admin.auth().verifyIdToken(token);
    socket.data.uid = decoded.uid;
    return next();
  } catch (err) {
    logger.warn(`⚠️  Token verify failed for ${socket.id}: ${err.message} — falling back to userId`);
    // Don't reject — fall back to the userId in handshake so Android with stale token still works
    socket.data.uid = userId || socket.id;
    return next();
  }
};

module.exports = {
  verifyFirebaseToken,
  verifySocketToken,
};
