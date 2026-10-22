import { RulesTestEnvironment, assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { Timestamp, deleteDoc, doc, getDoc, serverTimestamp, setDoc, updateDoc } from "firebase/firestore";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { after, before, beforeEach, describe, it } from "node:test";

// Run through `npm run test:emulator`, which starts the Firestore emulator.
const PROJECT = "demo-medreminder";
const RULES = readFileSync(resolve(__dirname, "../../../../firestore.rules"), "utf8");
const DOSE = "families/f1/elders/e1/doses/m1_20261019_0800";

let env: RulesTestEnvironment;

const google = (uid: string) =>
  env.authenticatedContext(uid, { firebase: { sign_in_provider: "google.com" } }).firestore();
const elderPhone = (eid = "e1") =>
  env.authenticatedContext(`phone-${eid}`, { firebase: { sign_in_provider: "anonymous" }, fid: "f1", eid }).firestore();

const medicine = (by: string) => ({
  name: "Amlodipine 5mg",
  spokenName: "BP की गोली",
  doseAmount: 1,
  doseUnit: "tablet",
  foodRule: "after",
  times: ["08:00", "20:00"],
  startDate: "2026-10-19",
  endDate: null,
  tabletsLeft: 30,
  active: true,
  addedBy: by,
  updatedBy: by,
});

before(async () => {
  env = await initializeTestEnvironment({ projectId: PROJECT, firestore: { rules: RULES } });
});

after(async () => {
  await env.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, "families/f1"), { name: "Joshi family", memberUids: ["alice", "bob"], alertOrder: ["alice", "bob"], createdBy: "alice" });
    await setDoc(doc(db, "families/f1/elders/e1"), { name: "Maa", language: "hi", elderUid: "phone-e1", settings: {} });
    await setDoc(doc(db, "families/f1/elders/e1/medicines/m1"), medicine("alice"));
    await setDoc(doc(db, DOSE), {
      medicineId: "m1",
      scheduledAt: Timestamp.fromMillis(Date.UTC(2026, 9, 19, 2, 30)),
      localDate: "2026-10-19",
      localTime: "08:00",
      status: "pending",
      alertLevel: 0,
      alertAt: Timestamp.fromMillis(Date.UTC(2026, 9, 19, 3, 0)),
      alertedUids: [],
    });
    await setDoc(doc(db, "pairingCodes/123456"), { elderUid: "phone-e1", expiresAt: Timestamp.now() });
  });
});

describe("reading", () => {
  it("family members read the elder, medicines and doses", async () => {
    await assertSucceeds(getDoc(doc(google("alice"), "families/f1/elders/e1")));
    await assertSucceeds(getDoc(doc(google("bob"), "families/f1/elders/e1/medicines/m1")));
    await assertSucceeds(getDoc(doc(google("bob"), DOSE)));
  });

  it("the paired phone reads its own elder data", async () => {
    await assertSucceeds(getDoc(doc(elderPhone(), "families/f1/elders/e1")));
    await assertSucceeds(getDoc(doc(elderPhone(), "families/f1/elders/e1/medicines/m1")));
  });

  it("strangers and other elders' phones cannot", async () => {
    await assertFails(getDoc(doc(google("mallory"), "families/f1/elders/e1")));
    await assertFails(getDoc(doc(google("mallory"), "families/f1")));
    await assertFails(getDoc(doc(elderPhone("e2"), "families/f1/elders/e1/medicines/m1")));
    await assertFails(getDoc(doc(elderPhone(), "families/f1")));
  });

  it("only the elder phone that made a pairing code can read it", async () => {
    await assertSucceeds(getDoc(doc(elderPhone(), "pairingCodes/123456")));
    await assertFails(getDoc(doc(google("alice"), "pairingCodes/123456")));
  });
});

describe("medicines", () => {
  it("a member adds a medicine in their own name", async () => {
    await assertSucceeds(setDoc(doc(google("bob"), "families/f1/elders/e1/medicines/m2"), medicine("bob")));
  });

  it("rejects bad data and other people's names", async () => {
    await assertFails(setDoc(doc(google("bob"), "families/f1/elders/e1/medicines/m2"), { ...medicine("bob"), doseUnit: "bucket" }));
    await assertFails(setDoc(doc(google("bob"), "families/f1/elders/e1/medicines/m2"), { ...medicine("bob"), times: [] }));
    await assertFails(setDoc(doc(google("bob"), "families/f1/elders/e1/medicines/m2"), medicine("alice")));
  });

  it("an edit keeps who added it", async () => {
    await assertSucceeds(updateDoc(doc(google("bob"), "families/f1/elders/e1/medicines/m1"), { times: ["09:00"], updatedBy: "bob" }));
    await assertFails(updateDoc(doc(google("bob"), "families/f1/elders/e1/medicines/m1"), { addedBy: "bob", updatedBy: "bob" }));
  });

  it("the elder's phone cannot change medicines", async () => {
    await assertFails(updateDoc(doc(elderPhone(), "families/f1/elders/e1/medicines/m1"), { times: ["09:00"] }));
    await assertFails(deleteDoc(doc(elderPhone(), "families/f1/elders/e1/medicines/m1")));
  });
});

describe("doses from the elder's phone", () => {
  it("confirms a dose", async () => {
    await assertSucceeds(
      updateDoc(doc(elderPhone(), DOSE), { status: "confirmed", confirmedBy: "elder", confirmedAt: Timestamp.now(), alertAt: null }),
    );
  });

  it("cannot claim a family confirmation or leave the alert running", async () => {
    await assertFails(
      updateDoc(doc(elderPhone(), DOSE), { status: "confirmed_by_family", confirmedBy: "elder", confirmedAt: Timestamp.now(), alertAt: null }),
    );
    await assertFails(updateDoc(doc(elderPhone(), DOSE), { status: "confirmed", confirmedBy: "elder", confirmedAt: Timestamp.now() }));
  });

  it("snoozing keeps the family's alert time", async () => {
    await assertSucceeds(updateDoc(doc(elderPhone(), DOSE), { status: "snoozed", snoozedAt: Timestamp.now() }));
    await assertFails(
      updateDoc(doc(elderPhone(), DOSE), { status: "snoozed", snoozedAt: Timestamp.now(), alertAt: Timestamp.fromMillis(Date.UTC(2027, 0, 1)) }),
    );
  });

  it("creates a confirmed dose the server has not made yet, under its proper id", async () => {
    const data = {
      medicineId: "m1",
      scheduledAt: Timestamp.fromMillis(Date.UTC(2026, 9, 19, 14, 30)),
      localDate: "2026-10-19",
      localTime: "20:00",
      status: "confirmed",
      confirmedBy: "elder",
      confirmedAt: Timestamp.now(),
      alertAt: null,
    };
    await assertSucceeds(setDoc(doc(elderPhone(), "families/f1/elders/e1/doses/m1_20261019_2000"), data));
    await assertFails(setDoc(doc(elderPhone(), "families/f1/elders/e1/doses/m1_20261019_2100"), data));
  });

  it("another elder's phone cannot touch it", async () => {
    await assertFails(
      updateDoc(doc(elderPhone("e2"), DOSE), { status: "confirmed", confirmedBy: "elder", confirmedAt: Timestamp.now(), alertAt: null }),
    );
  });
});

describe("doses from family members", () => {
  it("'She took it, I checked' in their own name", async () => {
    await assertSucceeds(
      updateDoc(doc(google("bob"), DOSE), { status: "confirmed_by_family", confirmedBy: "bob", confirmedAt: Timestamp.now(), alertAt: null }),
    );
  });

  it("not in someone else's name", async () => {
    await assertFails(
      updateDoc(doc(google("bob"), DOSE), { status: "confirmed_by_family", confirmedBy: "alice", confirmedAt: Timestamp.now(), alertAt: null }),
    );
  });

  it("tapping Call acknowledges and stops escalation", async () => {
    await assertSucceeds(updateDoc(doc(google("alice"), DOSE), { acknowledgedBy: "alice", acknowledgedAt: Timestamp.now(), alertAt: null }));
  });

  it("strangers cannot", async () => {
    await assertFails(updateDoc(doc(google("mallory"), DOSE), { acknowledgedBy: "mallory", acknowledgedAt: Timestamp.now(), alertAt: null }));
  });
});

describe("elder document", () => {
  it("the phone checks in with the server time and the next silent-alert time", async () => {
    await assertSucceeds(
      updateDoc(doc(elderPhone(), "families/f1/elders/e1"), {
        lastCheckInAt: serverTimestamp(),
        silentAlertAt: Timestamp.fromMillis(Date.now() + 8 * 3_600_000),
        fcmToken: "token-1",
      }),
    );
  });

  it("the phone cannot push the silent alert days away or rename the elder", async () => {
    await assertFails(
      updateDoc(doc(elderPhone(), "families/f1/elders/e1"), { silentAlertAt: Timestamp.fromMillis(Date.now() + 100 * 3_600_000) }),
    );
    await assertFails(updateDoc(doc(elderPhone(), "families/f1/elders/e1"), { name: "Someone else" }));
    await assertFails(updateDoc(doc(elderPhone(), "families/f1/elders/e1"), { callNumber: "+1 555 0100" }));
    await assertFails(updateDoc(doc(elderPhone(), "families/f1/elders/e1"), { lastCheckInAt: Timestamp.fromMillis(0) }));
  });

  it("family members change names and timings within limits", async () => {
    await assertSucceeds(updateDoc(doc(google("bob"), "families/f1/elders/e1"), { nickname: "मां", settings: { alertAfterMinutes: 45 } }));
    await assertSucceeds(updateDoc(doc(google("bob"), "families/f1/elders/e1"), { callName: "Bob", callNumber: "+91 99999 00000" }));
    await assertFails(updateDoc(doc(google("bob"), "families/f1/elders/e1"), { settings: { alertAfterMinutes: 1 } }));
    await assertFails(updateDoc(doc(google("bob"), "families/f1/elders/e1"), { elderUid: "bob" }));
  });
});

describe("family and user documents", () => {
  it("members reorder who is alerted first", async () => {
    await assertSucceeds(updateDoc(doc(google("bob"), "families/f1"), { alertOrder: ["bob", "alice"] }));
  });

  it("but cannot add outsiders or change membership", async () => {
    await assertFails(updateDoc(doc(google("bob"), "families/f1"), { alertOrder: ["bob", "mallory"] }));
    await assertFails(updateDoc(doc(google("bob"), "families/f1"), { memberUids: ["alice", "bob", "mallory"] }));
    await assertFails(updateDoc(doc(google("mallory"), "families/f1"), { name: "Mine now" }));
  });

  it("users keep their own profile but never set their family", async () => {
    await assertSucceeds(setDoc(doc(google("carol"), "users/carol"), { displayName: "Carol", fcmTokens: ["t"] }));
    await assertFails(setDoc(doc(google("dave"), "users/dave"), { displayName: "Dave", familyId: "f1" }));
    await assertFails(updateDoc(doc(google("carol"), "users/carol"), { familyId: "f1" }));
    await assertFails(getDoc(doc(google("mallory"), "users/carol")));
  });
});
