const deviceService = require('../services/deviceService');
const logger = require('../config/logger');

const createPair = async (req, res, next) => {
  try {
    const { userId, deviceId, deviceName } = req.body;
    if (!userId || !deviceId) {
      res.status(400);
      throw new Error('userId and deviceId are required');
    }

    const result = await deviceService.createDevicePair(userId, deviceId, deviceName);
    res.json(result);
  } catch (error) {
    next(error);
  }
};

const verifyPair = async (req, res, next) => {
  try {
    const { pairId } = req.params;
    const result = await deviceService.verifyDevicePair(pairId);
    
    if (!result) {
      res.status(404);
      throw new Error('Pair not found');
    }
    
    res.json(result);
  } catch (error) {
    next(error);
  }
};

const deletePair = async (req, res, next) => {
  try {
    const { pairId } = req.params;
    const result = await deviceService.deleteDevicePair(pairId);
    res.json(result);
  } catch (error) {
    next(error);
  }
};

module.exports = {
  createPair,
  verifyPair,
  deletePair,
};
