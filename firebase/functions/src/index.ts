import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { Timestamp, getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { getStorage } from "firebase-admin/storage";
import { logger, setGlobalOptions } from "firebase-functions/v2";
import { onDocumentUpdated, onDocumentWritten } from "firebase-functions/v2/firestore";
import { CallableRequest, HttpsError, onCall } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { DateTime } from "luxon";
import { deleteElderData as deleteElderDataImpl } from "./account";
import { onDoseChanged, processDueDoses, processSilentPhones } from "./alerts";
import { syncAllMedicines, syncMedicineDoses } from "./doses";
import { DEFAULT_TIMEZONE, DoseDoc, ElderDoc, MedicineDoc, REGION } from "./model";
import { fcmNotifier } from "./notifier";
import * as pairing from "./pairing";

initializeApp();
// Must run before any function below is defined. Keep the region next to the Firestore location.
setGlobalOptions({ region: REGION, maxInstances: 10 });

const db = getFirestore();
const notifier = () => fcmNotifier(db, getMessaging());

/** Every 5 minutes: alert family about unconfirmed doses and phones that went quiet. */
export const checkDoses = onSchedule(
  { schedule: "every 5 minutes", timeZone: DEFAULT_TIMEZONE, retryCount: 0, timeoutSeconds: 120 },
  async () => {
    const now = Timestamp.now();
    const doseAlerts = await processDueDoses(db, notifier(), now);
    const silentAlerts = await processSilentPhones(db, notifier(), now);
    if (doseAlerts + silentAlerts > 0) logger.info("Alerts sent", { doseAlerts, silentAlerts });
  },
);

/** Shortly after midnight: make sure every active medicine has its doses for today and tomorrow. */
export const topUpDoses = onSchedule(
  { schedule: "15 0 * * *", timeZone: DEFAULT_TIMEZONE, timeoutSeconds: 540 },
  async () => {
    const result = await syncAllMedicines(db, DateTime.now());
    logger.info("Doses topped up", result);
  },
);

/** A family member added, changed or removed a medicine: re-plan its doses and wake the elder's phone. */
export const onMedicineWritten = onDocumentWritten(
  "families/{familyId}/elders/{elderId}/medicines/{medicineId}",
  async (event) => {
    const { familyId, elderId, medicineId } = event.params;
    const elderRef = db.collection("families").doc(familyId).collection("elders").doc(elderId);
    const elderSnap = await elderRef.get();
    if (!elderSnap.exists) return;
    const elder = elderSnap.data() as ElderDoc;
    const after = event.data?.after;
    const medicine = after?.exists ? (after.data() as MedicineDoc) : null;
    await syncMedicineDoses(db, elderRef, elder, medicineId, medicine, DateTime.now());
    if (elder.fcmToken) await notifier().sendSync(elder.fcmToken);
  },
);

/** Tell the alerted family members when a dose is confirmed after all. */
export const onDoseUpdated = onDocumentUpdated(
  "families/{familyId}/elders/{elderId}/doses/{doseId}",
  async (event) => {
    if (!event.data) return;
    await onDoseChanged(
      db,
      notifier(),
      event.data.after.ref,
      event.data.before.data() as DoseDoc | undefined,
      event.data.after.data() as DoseDoc | undefined,
    );
  },
);

export const createPairingCode = onCall(async (request) => {
  const uid = requireUid(request);
  return pairing.createPairingCode(db, uid, Date.now());
});

export const claimPairingCode = onCall(async (request) => {
  const caller = requireFamilyMember(request);
  return pairing.claimPairingCode(db, getAuth(), caller, request.data, Date.now());
});

export const createFamilyInvite = onCall(async (request) => {
  const caller = requireFamilyMember(request);
  return pairing.createFamilyInvite(db, caller.uid, Date.now());
});

export const joinFamily = onCall(async (request) => {
  const caller = requireFamilyMember(request);
  return pairing.joinFamily(db, caller, request.data, Date.now());
});

export const deleteElderData = onCall(async (request) => {
  const uid = requireUid(request);
  return deleteElderDataImpl(db, getAuth(), getStorage().bucket(), { uid, token: request.auth!.token }, request.data);
});

function requireUid(request: CallableRequest): string {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first.");
  return uid;
}

/** Family actions need a Google account, not the elder phone's anonymous one. */
function requireFamilyMember(request: CallableRequest): { uid: string; name: string } {
  const uid = requireUid(request);
  const token = request.auth!.token;
  if (token.firebase?.sign_in_provider === "anonymous") {
    throw new HttpsError("permission-denied", "Sign in with Google in Family mode.");
  }
  return { uid, name: (token.name as string | undefined) ?? (token.email as string | undefined)?.split("@")[0] ?? "Family" };
}
