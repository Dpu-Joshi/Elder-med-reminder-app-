import assert from "node:assert/strict";
import { after, before, beforeEach, describe, it } from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { Firestore, Timestamp, getFirestore } from "firebase-admin/firestore";
import { DateTime } from "luxon";
import { deleteElderData } from "../../account";
import { onDoseChanged, processDueDoses, processSilentPhones } from "../../alerts";
import { syncMedicineDoses } from "../../doses";
import { AlertMessage } from "../../messages";
import { DoseDoc, ElderDoc, MedicineDoc } from "../../model";
import { Notifier } from "../../notifier";
import { claimPairingCode, createFamilyInvite, createPairingCode, joinFamily } from "../../pairing";

// Run through `npm run test:emulator`; FIRESTORE_EMULATOR_HOST is set by the emulator.
const PROJECT = "demo-medreminder";
const app = initializeApp({ projectId: PROJECT }, "logic-tests");
const db: Firestore = getFirestore(app);

class FakeNotifier implements Notifier {
  sent: { uid: string; message: AlertMessage }[] = [];
  syncs: string[] = [];
  async sendToUser(uid: string, message: AlertMessage) {
    this.sent.push({ uid, message });
    return 1;
  }
  async sendSync(token: string) {
    this.syncs.push(token);
  }
}

const IST = "Asia/Kolkata";
const ist = (iso: string) => DateTime.fromISO(iso, { zone: IST });
const elderRef = () => db.doc("families/f1/elders/e1");

const medicine: MedicineDoc = {
  name: "Amlodipine 5mg",
  doseAmount: 1,
  doseUnit: "tablet",
  foodRule: "after",
  times: ["08:00", "20:00"],
  startDate: "2026-10-01",
  endDate: null,
  active: true,
  addedBy: "alice",
  updatedBy: "alice",
};

const elder: ElderDoc = { name: "Maa", language: "hi", timezone: IST, elderUid: "phone-1", phoneNumber: "+91 98000 00000" };

async function clearFirestore() {
  const host = process.env.FIRESTORE_EMULATOR_HOST;
  assert.ok(host, "FIRESTORE_EMULATOR_HOST is not set; run via npm run test:emulator");
  await fetch(`http://${host}/emulator/v1/projects/${PROJECT}/databases/(default)/documents`, { method: "DELETE" });
}

async function seedFamily() {
  await db.doc("families/f1").set({
    name: "Joshi family",
    memberUids: ["alice", "bob"],
    alertOrder: ["alice", "bob"],
    members: { alice: { name: "Alice" }, bob: { name: "Bob" } },
    createdBy: "alice",
  });
  await elderRef().set(elder);
  await elderRef().collection("medicines").doc("m1").set(medicine);
}

before(async () => {
  await clearFirestore();
});

after(async () => {
  await deleteApp(app);
});

beforeEach(async () => {
  await clearFirestore();
  await seedFamily();
});

describe("dose planning", () => {
  it("creates future doses for today and tomorrow only", async () => {
    const now = ist("2026-10-19T10:00");
    const result = await syncMedicineDoses(db, elderRef(), elder, "m1", medicine, now);
    const ids = (await elderRef().collection("doses").get()).docs.map((d) => d.id).sort();
    assert.deepEqual(ids, ["m1_20261019_2000", "m1_20261020_0800", "m1_20261020_2000"]);
    assert.equal(result.created, 3);

    const dose = (await elderRef().collection("doses").doc("m1_20261019_2000").get()).data() as DoseDoc;
    assert.equal(dose.status, "pending");
    assert.equal(dose.alertAt!.toMillis() - dose.scheduledAt.toMillis(), 30 * 60_000);
  });

  it("is safe to run twice", async () => {
    const now = ist("2026-10-19T10:00");
    await syncMedicineDoses(db, elderRef(), elder, "m1", medicine, now);
    const second = await syncMedicineDoses(db, elderRef(), elder, "m1", medicine, now);
    assert.equal(second.created, 0);
    assert.equal((await elderRef().collection("doses").get()).size, 3);
  });

  it("a time change replaces pending doses but keeps confirmed ones", async () => {
    const now = ist("2026-10-19T10:00");
    await syncMedicineDoses(db, elderRef(), elder, "m1", medicine, now);
    await elderRef().collection("doses").doc("m1_20261020_0800").update({ status: "confirmed", alertAt: null });

    const changed = { ...medicine, times: ["09:00", "20:00"] };
    const result = await syncMedicineDoses(db, elderRef(), elder, "m1", changed, now);
    const ids = (await elderRef().collection("doses").get()).docs.map((d) => d.id).sort();
    assert.deepEqual(ids, ["m1_20261019_2000", "m1_20261020_0800", "m1_20261020_0900", "m1_20261020_2000"]);
    assert.equal(result.created, 1);
  });

  it("a deleted medicine loses its future pending doses", async () => {
    const now = ist("2026-10-19T10:00");
    await syncMedicineDoses(db, elderRef(), elder, "m1", medicine, now);
    const result = await syncMedicineDoses(db, elderRef(), elder, "m1", null, now);
    assert.equal(result.removed, 3);
    assert.equal((await elderRef().collection("doses").get()).size, 0);
  });
});

describe("missed-dose alerts", () => {
  async function pendingDoseAt8() {
    await syncMedicineDoses(db, elderRef(), elder, "m1", medicine, ist("2026-10-19T07:00"));
    return elderRef().collection("doses").doc("m1_20261019_0800");
  }

  it("nothing is sent before 30 minutes", async () => {
    await pendingDoseAt8();
    const notifier = new FakeNotifier();
    await processDueDoses(db, notifier, Timestamp.fromMillis(ist("2026-10-19T08:29").toMillis()));
    assert.equal(notifier.sent.length, 0);
  });

  it("first contact at 30 minutes, second at 60, then stops", async () => {
    const ref = await pendingDoseAt8();
    const notifier = new FakeNotifier();

    await processDueDoses(db, notifier, Timestamp.fromMillis(ist("2026-10-19T08:31").toMillis()));
    assert.deepEqual(notifier.sent.map((s) => s.uid), ["alice"]);
    assert.match(notifier.sent[0].message.title, /Maa: Amlodipine 5mg not confirmed/);
    assert.equal(notifier.sent[0].message.data.phoneNumber, "+91 98000 00000");
    let dose = (await ref.get()).data() as DoseDoc;
    assert.equal(dose.status, "not_confirmed");
    assert.equal(dose.alertLevel, 1);
    assert.equal(dose.alertAt!.toMillis(), ist("2026-10-19T09:00").toMillis());

    await processDueDoses(db, notifier, Timestamp.fromMillis(ist("2026-10-19T08:45").toMillis()));
    assert.equal(notifier.sent.length, 1, "no repeat before the second contact's turn");

    await processDueDoses(db, notifier, Timestamp.fromMillis(ist("2026-10-19T09:01").toMillis()));
    assert.deepEqual(notifier.sent.map((s) => s.uid), ["alice", "bob"]);
    dose = (await ref.get()).data() as DoseDoc;
    assert.equal(dose.alertAt, null);
    assert.deepEqual(dose.alertedUids, ["alice", "bob"]);

    await processDueDoses(db, notifier, Timestamp.fromMillis(ist("2026-10-19T10:00").toMillis()));
    assert.equal(notifier.sent.length, 2);
  });

  it("a confirmed dose is never alerted", async () => {
    const ref = await pendingDoseAt8();
    await ref.update({ status: "confirmed", confirmedBy: "elder", confirmedAt: Timestamp.now(), alertAt: null });
    const notifier = new FakeNotifier();
    await processDueDoses(db, notifier, Timestamp.fromMillis(ist("2026-10-19T09:30").toMillis()));
    assert.equal(notifier.sent.length, 0);
  });

  it("a family acknowledgement stops escalation", async () => {
    const ref = await pendingDoseAt8();
    const notifier = new FakeNotifier();
    await processDueDoses(db, notifier, Timestamp.fromMillis(ist("2026-10-19T08:31").toMillis()));
    await ref.update({ acknowledgedBy: "alice", acknowledgedAt: Timestamp.now(), alertAt: null });
    await processDueDoses(db, notifier, Timestamp.fromMillis(ist("2026-10-19T09:30").toMillis()));
    assert.deepEqual(notifier.sent.map((s) => s.uid), ["alice"]);
  });

  it("the order follows the family's alertOrder", async () => {
    await db.doc("families/f1").update({ alertOrder: ["bob", "alice"] });
    await pendingDoseAt8();
    const notifier = new FakeNotifier();
    await processDueDoses(db, notifier, Timestamp.fromMillis(ist("2026-10-19T08:31").toMillis()));
    assert.deepEqual(notifier.sent.map((s) => s.uid), ["bob"]);
  });
});

describe("confirmation after an alert", () => {
  const base: DoseDoc = {
    medicineId: "m1",
    scheduledAt: Timestamp.fromMillis(ist("2026-10-19T08:00").toMillis()),
    localDate: "2026-10-19",
    localTime: "08:00",
    status: "not_confirmed",
    alertLevel: 2,
    alertAt: null,
    alertedUids: ["alice", "bob"],
  };
  const ref = () => elderRef().collection("doses").doc("m1_20261019_0800");

  it("tells everyone who was alerted when the elder confirms late", async () => {
    const notifier = new FakeNotifier();
    const late = { ...base, status: "confirmed" as const, confirmedBy: "elder", confirmedAt: Timestamp.fromMillis(ist("2026-10-19T08:47").toMillis()) };
    assert.equal(await onDoseChanged(db, notifier, ref(), base, late), 2);
    assert.match(notifier.sent[0].message.body, /8:47 am, 47 min after the 8:00 am reminder/);
  });

  it("does not tell the person who checked", async () => {
    const notifier = new FakeNotifier();
    const checked = { ...base, status: "confirmed_by_family" as const, confirmedBy: "alice", confirmedAt: Timestamp.now() };
    await onDoseChanged(db, notifier, ref(), base, checked);
    assert.deepEqual(notifier.sent.map((s) => s.uid), ["bob"]);
    assert.match(notifier.sent[0].message.body, /^Alice checked/);
  });

  it("stays quiet when nobody had been alerted", async () => {
    const notifier = new FakeNotifier();
    const quiet = { ...base, status: "pending" as const, alertedUids: [] };
    await onDoseChanged(db, notifier, ref(), quiet, { ...quiet, status: "confirmed" });
    assert.equal(notifier.sent.length, 0);
  });
});

describe("phone is silent", () => {
  async function silentSince(iso: string) {
    await elderRef().update({
      lastCheckInAt: Timestamp.fromMillis(ist(iso).toMillis()),
      silentAlertAt: Timestamp.fromMillis(ist(iso).plus({ hours: 8 }).toMillis()),
    });
  }

  it("alerts the first contact once", async () => {
    await silentSince("2026-10-19T05:00");
    const notifier = new FakeNotifier();
    await processSilentPhones(db, notifier, Timestamp.fromMillis(ist("2026-10-19T13:05").toMillis()));
    await processSilentPhones(db, notifier, Timestamp.fromMillis(ist("2026-10-19T13:10").toMillis()));
    assert.deepEqual(notifier.sent.map((s) => s.uid), ["alice"]);
    assert.match(notifier.sent[0].message.title, /Maa's phone is silent/);
    assert.equal((await elderRef().get()).get("silentAlertAt"), null);
  });

  it("waits until 7 am during quiet hours", async () => {
    await silentSince("2026-10-19T15:00");
    const notifier = new FakeNotifier();
    await processSilentPhones(db, notifier, Timestamp.fromMillis(ist("2026-10-19T23:05").toMillis()));
    assert.equal(notifier.sent.length, 0);
    const moved = ((await elderRef().get()).get("silentAlertAt") as Timestamp).toMillis();
    assert.equal(moved, ist("2026-10-20T07:00").toMillis());

    await processSilentPhones(db, notifier, Timestamp.fromMillis(ist("2026-10-20T07:05").toMillis()));
    assert.deepEqual(notifier.sent.map((s) => s.uid), ["alice"]);
  });
});

describe("pairing", () => {
  const claims: { uid: string; claims: object | null }[] = [];
  const fakeAuth = {
    async setCustomUserClaims(uid: string, c: object | null) {
      claims.push({ uid, claims: c });
    },
  };

  it("a new family member pairs an elder's phone and gets a family", async () => {
    const now = Date.now();
    const { code } = await createPairingCode(db, "phone-9", now);
    assert.match(code, /^\d{6}$/);

    const result = await claimPairingCode(db, fakeAuth, { uid: "carol", name: "Carol" }, { code, elderName: "Papa", language: "en" }, now);
    const family = (await db.doc(`families/${result.familyId}`).get()).data()!;
    assert.deepEqual(family.memberUids, ["carol"]);
    assert.equal((await db.doc("users/carol").get()).get("familyId"), result.familyId);
    const newElder = (await db.doc(`families/${result.familyId}/elders/${result.elderId}`).get()).data() as ElderDoc;
    assert.equal(newElder.elderUid, "phone-9");
    assert.equal(newElder.language, "en");
    assert.deepEqual(claims.at(-1), { uid: "phone-9", claims: { fid: result.familyId, eid: result.elderId, role: "elder" } });
    assert.equal((await db.doc(`pairingCodes/${code}`).get()).get("elderId"), result.elderId);

    await assert.rejects(
      claimPairingCode(db, fakeAuth, { uid: "carol", name: "Carol" }, { code, elderName: "Papa" }, now),
      /not found or expired/,
    );
  });

  it("a second elder joins the same family", async () => {
    await db.doc("users/alice").set({ familyId: "f1" });
    const now = Date.now();
    const { code } = await createPairingCode(db, "phone-2", now);
    const result = await claimPairingCode(db, fakeAuth, { uid: "alice", name: "Alice" }, { code, elderName: "Dadi" }, now);
    assert.equal(result.familyId, "f1");
  });

  it("rejects expired codes and bad input", async () => {
    const then = Date.now() - 31 * 60_000;
    const { code } = await createPairingCode(db, "phone-3", then);
    await assert.rejects(claimPairingCode(db, fakeAuth, { uid: "dave", name: "Dave" }, { code, elderName: "X" }, Date.now()), /expired/);
    await assert.rejects(claimPairingCode(db, fakeAuth, { uid: "dave", name: "Dave" }, { code: "12ab", elderName: "X" }, Date.now()), /6-digit/);
  });

  it("locks out after ten wrong codes in an hour", async () => {
    const now = Date.now();
    for (let i = 0; i < 10; i++) {
      await assert.rejects(claimPairingCode(db, fakeAuth, { uid: "eve", name: "Eve" }, { code: "000000", elderName: "X" }, now));
    }
    await assert.rejects(
      claimPairingCode(db, fakeAuth, { uid: "eve", name: "Eve" }, { code: "000000", elderName: "X" }, now),
      /Too many wrong codes/,
    );
  });

  it("another family member joins with an invite code", async () => {
    await db.doc("users/alice").set({ familyId: "f1" });
    const { code } = await createFamilyInvite(db, "alice", Date.now());
    await joinFamily(db, { uid: "frank", name: "Frank" }, { code }, Date.now());
    const family = (await db.doc("families/f1").get()).data()!;
    assert.deepEqual(family.memberUids, ["alice", "bob", "frank"]);
    assert.deepEqual(family.alertOrder, ["alice", "bob", "frank"]);
  });
});

describe("deleting an elder's data", () => {
  const deleted: string[] = [];
  const fakeAuth = {
    async deleteUser(uid: string) {
      deleted.push(uid);
    },
  };

  it("the elder's own phone can delete everything about the elder", async () => {
    await syncMedicineDoses(db, elderRef(), elder, "m1", medicine, ist("2026-10-19T07:00"));
    const caller = { uid: "phone-1", token: { fid: "f1", eid: "e1", firebase: { sign_in_provider: "anonymous" } } };
    await deleteElderData(db, fakeAuth, null, caller, { familyId: "f1", elderId: "e1" });
    assert.equal((await elderRef().get()).exists, false);
    assert.equal((await elderRef().collection("medicines").get()).size, 0);
    assert.equal((await elderRef().collection("doses").get()).size, 0);
    assert.deepEqual(deleted, ["phone-1"]);
  });

  it("strangers cannot", async () => {
    const caller = { uid: "mallory", token: { firebase: { sign_in_provider: "google.com" } } };
    await assert.rejects(deleteElderData(db, fakeAuth, null, caller, { familyId: "f1", elderId: "e1" }), /Not allowed/);
    assert.equal((await elderRef().get()).exists, true);
  });
});
