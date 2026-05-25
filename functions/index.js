const functions = require('firebase-functions');
const admin = require('firebase-admin');

admin.initializeApp();

// Scheduled cleanup for expired pairing codes (collection: pairing_codes)
exports.cleanupExpiredPairingCodes = functions.pubsub
    .schedule('every 24 hours')
    .onRun(async (context) => {
        const db = admin.firestore();
        const now = Date.now(); // pairing docs store expiresAt as milliseconds

        const expired = await db.collection('pairing_codes')
            .where('expiresAt', '<', now)
            .get();

        if (expired.empty) {
            functions.logger.info('No expired pairing codes to delete');
            return null;
        }

        const batch = db.batch();
        expired.docs.forEach(doc => batch.delete(doc.ref));
        await batch.commit();

        functions.logger.info(`Deleted ${expired.size} expired pairing codes`);
        return null;
    });
