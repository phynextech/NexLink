const express = require('express');
const router = express.Router();
const deviceController = require('../controllers/deviceController');

// POST /pair/create  → create pairing in RTDB, return pairId
router.post('/create', deviceController.createPair);

// GET /pair/:pairId  → verify pairing exists
router.get('/:pairId', deviceController.verifyPair);

// DELETE /pair/:pairId  → remove pairing
router.delete('/:pairId', deviceController.deletePair);

module.exports = router;
