import { DocumentReference, FieldValue, Firestore, Timestamp } from "firebase-admin/firestore";
import { DateTime } from "luxon";
import { DEFAULT_TIMEZONE, DoseDoc, ElderDoc, MedicineDoc, withDefaults } from "./model";
import { PlannedDose, planDoses } from "./planner";

/**
 * Keeps a "pending" dose document ready for every dose in the next two days. The missed-dose
 * check only has to look for documents whose alertAt has passed, which is one cheap query
 * instead of re-planning every medicine every five minutes.
 */

export const PLAN_DAYS = 2;
const ALREADY_EXISTS = 6;

export function newDoseDoc(planned: PlannedDose, alertAfterMinutes: number): Omit<DoseDoc, "scheduledAt" | "alertAt"> & {
  scheduledAt: Timestamp;
  alertAt: Timestamp;
} {
  return {
    medicineId: planned.medicineId,
    scheduledAt: Timestamp.fromMillis(planned.scheduledAtMillis),
    localDate: planned.localDate,
    localTime: planned.localTime,
    status: "pending",
    alertLevel: 0,
    alertAt: Timestamp.fromMillis(planned.scheduledAtMillis + alertAfterMinutes * 60_000),
    alertedUids: [],
  };
}

/**
 * Creates the future doses of one medicine and deletes future pending doses that no longer fit
 * its schedule (a time changed, an end date moved, the medicine was stopped or deleted).
 * Never overwrites a dose the elder already confirmed: creation fails on existing documents.
 */
export async function syncMedicineDoses(
  db: Firestore,
  elderRef: DocumentReference,
  elder: ElderDoc,
  medicineId: string,
  medicine: MedicineDoc | null,
  now: DateTime,
): Promise<{ created: number; removed: number }> {
  const zone = elder.timezone ?? DEFAULT_TIMEZONE;
  const settings = withDefaults(elder.settings);
  const local = now.setZone(zone);
  const planned = medicine
    ? planDoses(medicineId, medicine, zone, local.toISODate()!, local.plus({ days: PLAN_DAYS - 1 }).toISODate()!)
      .filter((d) => d.scheduledAtMillis > now.toMillis())
    : [];
  const plannedIds = new Set(planned.map((d) => d.id));

  const doses = elderRef.collection("doses");
  const existing = await doses
    .where("medicineId", "==", medicineId)
    .where("scheduledAt", ">", Timestamp.fromMillis(now.toMillis()))
    .get();

  let created = 0;
  let removed = 0;
  const writer = db.bulkWriter();
  writer.onWriteError((error) => error.code !== ALREADY_EXISTS && error.failedAttempts < 3);
  const ops: Promise<unknown>[] = [];

  for (const doc of existing.docs) {
    if (!plannedIds.has(doc.id) && doc.get("status") === "pending") {
      ops.push(writer.delete(doc.ref, { lastUpdateTime: doc.updateTime }).then(() => removed++, () => undefined));
    }
  }
  const existingIds = new Set(existing.docs.map((d) => d.id));
  for (const dose of planned) {
    if (existingIds.has(dose.id)) continue;
    const data = { ...newDoseDoc(dose, settings.alertAfterMinutes), createdAt: FieldValue.serverTimestamp() };
    ops.push(writer.create(doses.doc(dose.id), data).then(() => created++, () => undefined));
  }
  await writer.close();
  await Promise.all(ops);
  return { created, removed };
}

/** Daily top-up so every active medicine has its doses for today and tomorrow. */
export async function syncAllMedicines(db: Firestore, now: DateTime): Promise<{ medicines: number; created: number }> {
  const medicines = await db.collectionGroup("medicines").where("active", "==", true).get();
  const elders = new Map<string, ElderDoc | null>();
  let created = 0;
  for (const doc of medicines.docs) {
    const elderRef = doc.ref.parent.parent;
    if (!elderRef) continue;
    if (!elders.has(elderRef.path)) {
      const snap = await elderRef.get();
      elders.set(elderRef.path, snap.exists ? (snap.data() as ElderDoc) : null);
    }
    const elder = elders.get(elderRef.path);
    if (!elder) continue;
    const result = await syncMedicineDoses(db, elderRef, elder, doc.id, doc.data() as MedicineDoc, now);
    created += result.created;
  }
  return { medicines: medicines.size, created };
}
