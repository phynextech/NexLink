const admin = require('firebase-admin');
const logger = require('./logger');

let db = null;

try {
  const serviceAccount = process.env.FIREBASE_SERVICE_ACCOUNT
    ? JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT)
    : null;

  if (serviceAccount) {
    admin.initializeApp({
      credential: admin.credential.cert(serviceAccount),
      databaseURL: process.env.FIREBASE_DATABASE_URL || 'https://nexlink-62e41-default-rtdb.firebaseio.com/',
      projectId: process.env.FIREBASE_PROJECT_ID || 'nexlink-62e41',
    });
    db = admin.database();
    logger.info('✅ Firebase Admin initialized (Realtime DB)');
  } else {
    logger.warn('⚠️  FIREBASE_SERVICE_ACCOUNT not set — running in memory-only mode');
  }
} catch (error) {
  logger.error('Firebase init error: %s', error.message);
}

module.exports = {
  admin,
  getDb: () => db,
};
