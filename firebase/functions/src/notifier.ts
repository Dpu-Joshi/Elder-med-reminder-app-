import { FieldValue, Firestore } from "firebase-admin/firestore";
import { Messaging } from "firebase-admin/messaging";
import { logger } from "firebase-functions/v2";
import { AlertMessage } from "./messages";

/** Where alerts go. Tests swap in a fake that records what would have been sent. */
export interface Notifier {
  /** Sends to every device of a family member; returns how many devices accepted it. */
  sendToUser(uid: string, message: AlertMessage): Promise<number>;
  /** Wakes the elder's phone to pull medicine changes now instead of at the next periodic sync. */
  sendSync(token: string): Promise<void>;
}

const DEAD_TOKEN_CODES = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
]);

/** Android notification channel the app creates for family alerts. */
export const FAMILY_ALERT_CHANNEL = "family_alerts";

export function fcmNotifier(db: Firestore, messaging: Messaging): Notifier {
  return {
    async sendToUser(uid, message) {
      const userRef = db.collection("users").doc(uid);
      const tokens: string[] = (await userRef.get()).get("fcmTokens") ?? [];
      if (tokens.length === 0) {
        logger.warn("No devices for family member", { uid });
        return 0;
      }
      // A notification message is shown by Android even when the app is not running,
      // which is more reliable on phones that kill background apps than a data-only message.
      const response = await messaging.sendEachForMulticast({
        tokens,
        notification: { title: message.title, body: message.body },
        data: message.data,
        android: {
          priority: "high",
          collapseKey: message.tag,
          notification: { channelId: FAMILY_ALERT_CHANNEL, tag: message.tag },
        },
      });
      const dead = response.responses
        .map((r, i) => (!r.success && r.error && DEAD_TOKEN_CODES.has(r.error.code) ? tokens[i] : null))
        .filter((t): t is string => t !== null);
      if (dead.length > 0) await userRef.update({ fcmTokens: FieldValue.arrayRemove(...dead) });
      return response.successCount;
    },

    async sendSync(token) {
      try {
        await messaging.send({ token, data: { type: "sync" }, android: { priority: "high" } });
      } catch (e) {
        logger.warn("Sync push to elder phone failed", { error: String(e) });
      }
    },
  };
}
