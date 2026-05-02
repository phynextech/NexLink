const { v4: uuidv4 } = require('uuid');
const { getDb } = require('../config/firebase');
const logger = require('../config/logger');

const createDevicePair = async (userId, deviceId, deviceName) => {
  const db = getDb();
  const pairId = uuidv4();
  const pairData = {
    pairId,
    userId,
    deviceId,
    deviceName: deviceName || 'My PC',
    createdAt: Date.now(),
    lastSeen: Date.now(),
  };

  if (db) {
    await db.ref(`devices/${deviceId}`).set(pairData);
    await db.ref(`users/${userId}/devices/${deviceId}`).set({
      pairId,
      deviceName: pairData.deviceName,
    });
  }

  logger.info(`🔑 Pair created: pairId=${pairId} user=${userId} device=${deviceId}`);
  return { pairId, relayUrl: 'https://nexlink-khhe.onrender.com' };
};

const verifyDevicePair = async (pairId) => {
  const db = getDb();
  if (!db) {
    return { pairId, status: 'memory-only' };
  }

  const snap = await db.ref('devices').orderByChild('pairId').equalTo(pairId).once('value');
  if (!snap.exists()) {
    return null;
  }
  return Object.values(snap.val())[0];
};

const deleteDevicePair = async (pairId) => {
  const db = getDb();
  if (db) {
    const snap = await db.ref('devices').orderByChild('pairId').equalTo(pairId).once('value');
    if (snap.exists()) {
      const [deviceId] = Object.keys(snap.val());
      await db.ref(`devices/${deviceId}`).remove();
    }
  }
  return { success: true };
};

module.exports = {
  createDevicePair,
  verifyDevicePair,
  deleteDevicePair,
};
