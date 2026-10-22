import { DocumentReference, FieldValue, Firestore, Timestamp } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { DateTime } from "luxon";
import { doseAlertMessage, lateConfirmationMessage, silentPhoneMessage } from "./messages";
import {
  DEFAULT_TIMEZONE,
  DoseDoc,
  ElderDoc,
  ElderSettings,
  FamilyDoc,
  MedicineDoc,
  alertOrder,
  withDefaults,
} from "./model";
import { Notifier } from "./notifier";

/**
 * The checks run on the server, not on the elder's phone, so the family is alerted even when
 * that phone is switched off, out of battery or offline.
 */

const BATCH_LIMIT = 500;
const FAILED_PRECONDITION = 9;

/** Minutes after the dose time at which alert number [level] (0 = first contact) is due. */
export function alertOffsetMinutes(settings: ElderSettings, level: number): number {
  return settings.alertAfterMinutes + level * (settings.escalateAfterMinutes - settings.alertAfterMinutes);
}

export function inQuietHours(hour: number, settings: ElderSettings): boolean {
  const { quietStartHour: start, quietEndHour: end } = settings;
  if (start === end) return false;
  return start > end ? hour >= start || hour < end : hour >= start && hour < end;
}

/**
 * Alerts the next family contact for every dose whose alertAt has passed, and moves alertAt to
 * the next contact's turn (or clears it when everyone has been told).
 */
export async function processDueDoses(db: Firestore, notifier: Notifier, now: Timestamp): Promise<number> {
  const due = await db.collectionGroup("doses").where("alertAt", "<=", now).orderBy("alertAt").limit(BATCH_LIMIT).get();
  const cache = new DocCache();
  let sent = 0;

  for (const doc of due.docs) {
    const dose = doc.data() as DoseDoc;
    const elderRef = doc.ref.parent.parent;
    const familyRef = elderRef?.parent.parent;
    if (!elderRef || !familyRef) continue;

    if (dose.status === "confirmed" || dose.status === "confirmed_by_family") {
      await updateIfUnchanged(doc.ref, { alertAt: null }, doc.updateTime);
      continue;
    }
    const [family, elder] = await Promise.all([cache.get<FamilyDoc>(familyRef), cache.get<ElderDoc>(elderRef)]);
    if (!family || !elder) {
      await updateIfUnchanged(doc.ref, { alertAt: null }, doc.updateTime);
      continue;
    }

    const settings = withDefaults(elder.settings);
    const order = alertOrder(family);
    const level = dose.alertLevel ?? 0;
    const uid = order[level];
    const nextLevel = level + 1;
    const update: Record<string, unknown> = {
      alertLevel: nextLevel,
      alertAt: nextLevel < order.length
        ? Timestamp.fromMillis(dose.scheduledAt.toMillis() + alertOffsetMinutes(settings, nextLevel) * 60_000)
        : null,
    };
    if (dose.status === "pending" || dose.status === "snoozed") update.status = "not_confirmed";
    if (uid) update.alertedUids = FieldValue.arrayUnion(uid);

    // Claim this alert first; if the elder confirmed in the meantime the write fails and nothing is sent.
    if (!(await updateIfUnchanged(doc.ref, update, doc.updateTime))) continue;
    if (!uid) continue;

    const medicine = await cache.get<MedicineDoc>(elderRef.collection("medicines").doc(dose.medicineId));
    try {
      await notifier.sendToUser(
        uid,
        doseAlertMessage({ familyId: familyRef.id, elderId: elderRef.id, doseId: doc.id, elder, medicine, dose, level }),
      );
      sent++;
    } catch (e) {
      logger.error("Dose alert failed", { dose: doc.ref.path, uid, error: String(e) });
    }
  }
  return sent;
}

/**
 * Alerts the first contact when an elder's phone has not checked in for silentAfterHours.
 * The phone sets silentAlertAt on every check-in; outside quiet hours a passed silentAlertAt
 * means it has gone quiet.
 */
export async function processSilentPhones(db: Firestore, notifier: Notifier, now: Timestamp): Promise<number> {
  const due = await db.collectionGroup("elders").where("silentAlertAt", "<=", now).limit(BATCH_LIMIT).get();
  const cache = new DocCache();
  let sent = 0;

  for (const doc of due.docs) {
    const elder = doc.data() as ElderDoc;
    const familyRef = doc.ref.parent.parent;
    if (!familyRef) continue;
    const settings = withDefaults(elder.settings);
    const local = DateTime.fromMillis(now.toMillis(), { zone: elder.timezone ?? DEFAULT_TIMEZONE });

    if (inQuietHours(local.hour, settings)) {
      let resume = local.set({ hour: settings.quietEndHour, minute: 0, second: 0, millisecond: 0 });
      if (resume <= local) resume = resume.plus({ days: 1 });
      await updateIfUnchanged(doc.ref, { silentAlertAt: Timestamp.fromMillis(resume.toMillis()) }, doc.updateTime);
      continue;
    }
    if (!(await updateIfUnchanged(doc.ref, { silentAlertAt: null, silentAlertSentAt: now }, doc.updateTime))) continue;

    const family = await cache.get<FamilyDoc>(familyRef);
    const uid = family ? alertOrder(family)[0] : undefined;
    if (!uid) continue;
    try {
      await notifier.sendToUser(uid, silentPhoneMessage({ familyId: familyRef.id, elderId: doc.id, elder }));
      sent++;
    } catch (e) {
      logger.error("Silent-phone alert failed", { elder: doc.ref.path, error: String(e) });
    }
  }
  return sent;
}

/**
 * After an alert went out, tell the people who were alerted when the dose is confirmed after
 * all, so nobody calls for nothing.
 */
export async function onDoseChanged(
  db: Firestore,
  notifier: Notifier,
  doseRef: DocumentReference,
  before: DoseDoc | undefined,
  after: DoseDoc | undefined,
): Promise<number> {
  if (!before || !after) return 0;
  const alerted = after.alertedUids ?? [];
  if (alerted.length === 0) return 0;
  const becameConfirmed =
    (after.status === "confirmed" || after.status === "confirmed_by_family") && after.status !== before.status &&
    before.status !== "confirmed" && before.status !== "confirmed_by_family";
  if (!becameConfirmed) return 0;

  const elderRef = doseRef.parent.parent;
  const familyRef = elderRef?.parent.parent;
  if (!elderRef || !familyRef) return 0;
  const [elderSnap, familySnap, medicineSnap] = await Promise.all([
    elderRef.get(),
    familyRef.get(),
    elderRef.collection("medicines").doc(after.medicineId).get(),
  ]);
  if (!elderSnap.exists) return 0;
  const family = familySnap.data() as FamilyDoc | undefined;
  const byFamily = after.status === "confirmed_by_family" ? after.confirmedBy ?? undefined : undefined;
  const message = lateConfirmationMessage({
    familyId: familyRef.id,
    elderId: elderRef.id,
    doseId: doseRef.id,
    elder: elderSnap.data() as ElderDoc,
    medicine: medicineSnap.exists ? (medicineSnap.data() as MedicineDoc) : null,
    dose: after,
    byFamilyName: byFamily ? family?.members?.[byFamily]?.name ?? "A family member" : undefined,
  });

  let sent = 0;
  for (const uid of alerted) {
    if (uid === byFamily) continue;
    try {
      await notifier.sendToUser(uid, message);
      sent++;
    } catch (e) {
      logger.error("Confirmation push failed", { uid, error: String(e) });
    }
  }
  return sent;
}

async function updateIfUnchanged(
  ref: DocumentReference,
  data: Record<string, unknown>,
  lastUpdateTime: Timestamp,
): Promise<boolean> {
  try {
    await ref.update(data, { lastUpdateTime });
    return true;
  } catch (e) {
    if ((e as { code?: number }).code === FAILED_PRECONDITION) return false;
    throw e;
  }
}

class DocCache {
  private readonly docs = new Map<string, Promise<unknown>>();

  get<T>(ref: DocumentReference): Promise<T | null> {
    if (!this.docs.has(ref.path)) {
      this.docs.set(ref.path, ref.get().then((s) => (s.exists ? (s.data() as T) : null)));
    }
    return this.docs.get(ref.path) as Promise<T | null>;
  }
}
