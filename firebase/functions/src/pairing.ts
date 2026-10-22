import { randomInt } from "node:crypto";
import { FieldValue, Firestore, Timestamp } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { IANAZone } from "luxon";
import { DEFAULT_SETTINGS, DEFAULT_TIMEZONE, ElderDoc, FamilyDoc, Language } from "./model";

/**
 * Pairing without passwords or OTP for the elder:
 *  1. The elder's phone signs in anonymously and asks for a code (createPairingCode).
 *  2. It shows the code as a QR code and as 6 digits.
 *  3. The family member, signed in with Google, scans or types it (claimPairingCode).
 *  4. The elder's anonymous account gets custom claims {fid, eid}, which the security rules
 *     use to let that phone read its medicines and confirm its doses.
 */

export const PAIRING_TTL_MS = 30 * 60_000;
export const INVITE_TTL_MS = 24 * 60 * 60_000;
const MAX_FAILED_ATTEMPTS_PER_HOUR = 10;
const ALREADY_EXISTS = 6;

/** The parts of firebase-admin's Auth these functions use, so tests can pass a fake. */
export interface ClaimsSetter {
  setCustomUserClaims(uid: string, claims: object | null): Promise<void>;
}

export async function createPairingCode(db: Firestore, elderUid: string, nowMs: number) {
  const codes = db.collection("pairingCodes");
  const old = await codes.where("elderUid", "==", elderUid).get();
  await Promise.all(old.docs.filter((d) => !d.get("claimedAt")).map((d) => d.ref.delete()));
  return allocateCode(db, "pairingCodes", { elderUid }, nowMs, PAIRING_TTL_MS);
}

export interface ClaimInput {
  code: string;
  elderName: string;
  nickname?: string;
  language?: Language;
  phoneNumber?: string;
  timezone?: string;
}

export async function claimPairingCode(
  db: Firestore,
  auth: ClaimsSetter,
  caller: { uid: string; name: string },
  raw: unknown,
  nowMs: number,
): Promise<{ familyId: string; elderId: string }> {
  const input = validateClaim(raw);
  const userRef = db.collection("users").doc(caller.uid);
  await checkAttempts(db, caller.uid, nowMs);

  const codeRef = db.collection("pairingCodes").doc(input.code);
  const codeSnap = await codeRef.get();
  if (!codeSnap.exists || codeSnap.get("claimedAt") || codeSnap.get("expiresAt").toMillis() < nowMs) {
    await recordFailedAttempt(db, caller.uid, nowMs);
    throw new HttpsError("not-found", "Code not found or expired. Open the app on the elder's phone for a new code.");
  }
  const elderUid: string = codeSnap.get("elderUid");

  const existingFamilyId: string | undefined = (await userRef.get()).get("familyId");
  const familyRef = existingFamilyId ? db.collection("families").doc(existingFamilyId) : db.collection("families").doc();
  const elderRef = familyRef.collection("elders").doc();

  // Claims first: the elder's phone refreshes its token as soon as it sees the code claimed.
  await auth.setCustomUserClaims(elderUid, { fid: familyRef.id, eid: elderRef.id, role: "elder" });

  const now = Timestamp.fromMillis(nowMs);
  await db.runTransaction(async (tx) => {
    const [fresh, family] = await Promise.all([tx.get(codeRef), tx.get(familyRef)]);
    if (!fresh.exists || fresh.get("claimedAt")) {
      throw new HttpsError("already-exists", "This code was already used.");
    }
    if (!family.exists) {
      const newFamily: FamilyDoc = {
        name: `${caller.name}'s family`,
        memberUids: [caller.uid],
        alertOrder: [caller.uid],
        members: { [caller.uid]: { name: caller.name } },
        createdBy: caller.uid,
      };
      tx.set(familyRef, { ...newFamily, createdAt: now });
      tx.set(userRef, { familyId: familyRef.id }, { merge: true });
    } else if (!(family.get("memberUids") as string[]).includes(caller.uid)) {
      throw new HttpsError("permission-denied", "You are not a member of this family.");
    }
    const elder: ElderDoc = {
      name: input.elderName,
      nickname: input.nickname ?? "",
      language: input.language ?? "hi",
      timezone: input.timezone ?? DEFAULT_TIMEZONE,
      phoneNumber: input.phoneNumber ?? "",
      elderUid,
      settings: DEFAULT_SETTINGS,
      lastCheckInAt: null,
      silentAlertAt: null,
      consent: null,
    };
    tx.set(elderRef, { ...elder, createdAt: now, createdBy: caller.uid });
    tx.update(codeRef, { claimedAt: now, familyId: familyRef.id, elderId: elderRef.id, claimedBy: caller.uid });
  });
  return { familyId: familyRef.id, elderId: elderRef.id };
}

/** A code another family member types to join this family. */
export async function createFamilyInvite(db: Firestore, uid: string, nowMs: number) {
  const familyId: string | undefined = (await db.collection("users").doc(uid).get()).get("familyId");
  if (!familyId) throw new HttpsError("failed-precondition", "Pair an elder's phone first; that creates your family.");
  return allocateCode(db, "familyInvites", { familyId, createdBy: uid }, nowMs, INVITE_TTL_MS);
}

export async function joinFamily(
  db: Firestore,
  caller: { uid: string; name: string },
  raw: unknown,
  nowMs: number,
): Promise<{ familyId: string }> {
  const code = (raw as { code?: unknown })?.code;
  if (typeof code !== "string" || !/^\d{6}$/.test(code)) throw new HttpsError("invalid-argument", "Enter the 6-digit code.");
  await checkAttempts(db, caller.uid, nowMs);

  const inviteRef = db.collection("familyInvites").doc(code);
  const userRef = db.collection("users").doc(caller.uid);
  return db.runTransaction(async (tx) => {
    const [invite, user] = await Promise.all([tx.get(inviteRef), tx.get(userRef)]);
    if (!invite.exists || invite.get("expiresAt").toMillis() < nowMs) {
      throw new HttpsError("not-found", "Invite code not found or expired.");
    }
    const familyId: string = invite.get("familyId");
    const current: string | undefined = user.get("familyId");
    if (current && current !== familyId) {
      throw new HttpsError("failed-precondition", "You already belong to another family.");
    }
    const familyRef = db.collection("families").doc(familyId);
    tx.update(familyRef, {
      memberUids: FieldValue.arrayUnion(caller.uid),
      alertOrder: FieldValue.arrayUnion(caller.uid),
      [`members.${caller.uid}`]: { name: caller.name },
    });
    tx.set(userRef, { familyId }, { merge: true });
    return { familyId };
  }).catch(async (e) => {
    if (e instanceof HttpsError && e.code === "not-found") await recordFailedAttempt(db, caller.uid, nowMs);
    throw e;
  });
}

function validateClaim(raw: unknown): ClaimInput {
  const r = (raw ?? {}) as Record<string, unknown>;
  const str = (v: unknown) => (typeof v === "string" ? v.trim() : undefined);
  const code = str(r.code);
  const elderName = str(r.elderName);
  if (!code || !/^\d{6}$/.test(code)) throw new HttpsError("invalid-argument", "Enter the 6-digit code.");
  if (!elderName || elderName.length > 60) throw new HttpsError("invalid-argument", "Enter a name up to 60 letters.");
  const language = str(r.language);
  if (language && language !== "hi" && language !== "en") throw new HttpsError("invalid-argument", "Language must be hi or en.");
  const phoneNumber = str(r.phoneNumber);
  if (phoneNumber && !/^\+?[0-9 ]{6,16}$/.test(phoneNumber)) throw new HttpsError("invalid-argument", "Phone number looks wrong.");
  const timezone = str(r.timezone);
  if (timezone && !IANAZone.isValidZone(timezone)) throw new HttpsError("invalid-argument", "Unknown time zone.");
  const nickname = str(r.nickname);
  if (nickname && nickname.length > 30) throw new HttpsError("invalid-argument", "Keep the nickname short.");
  return { code, elderName, nickname, language: language as Language | undefined, phoneNumber, timezone };
}

async function allocateCode(
  db: Firestore,
  collection: "pairingCodes" | "familyInvites",
  fields: Record<string, unknown>,
  nowMs: number,
  ttlMs: number,
): Promise<{ code: string; expiresAtMillis: number }> {
  for (let i = 0; i < 20; i++) {
    const code = randomInt(0, 1_000_000).toString().padStart(6, "0");
    const ref = db.collection(collection).doc(code);
    try {
      await ref.create({
        ...fields,
        createdAt: Timestamp.fromMillis(nowMs),
        expiresAt: Timestamp.fromMillis(nowMs + ttlMs),
      });
      return { code, expiresAtMillis: nowMs + ttlMs };
    } catch (e) {
      if ((e as { code?: number }).code !== ALREADY_EXISTS) throw e;
      const existing = await ref.get();
      // Reuse codes that have expired.
      if (existing.exists && existing.get("expiresAt").toMillis() < nowMs) await ref.delete();
    }
  }
  throw new HttpsError("resource-exhausted", "Could not make a code. Try again.");
}

/** Six digits are guessable at scale; cap wrong codes per family member per hour. */
async function checkAttempts(db: Firestore, uid: string, nowMs: number): Promise<void> {
  const attempts = (await db.collection("users").doc(uid).get()).get("codeAttempts") as
    | { windowStart: number; failed: number }
    | undefined;
  if (attempts && nowMs - attempts.windowStart < 3_600_000 && attempts.failed >= MAX_FAILED_ATTEMPTS_PER_HOUR) {
    throw new HttpsError("resource-exhausted", "Too many wrong codes. Try again in an hour.");
  }
}

async function recordFailedAttempt(db: Firestore, uid: string, nowMs: number): Promise<void> {
  const ref = db.collection("users").doc(uid);
  await db.runTransaction(async (tx) => {
    const attempts = (await tx.get(ref)).get("codeAttempts") as { windowStart: number; failed: number } | undefined;
    const fresh = !attempts || nowMs - attempts.windowStart >= 3_600_000;
    tx.set(ref, { codeAttempts: fresh ? { windowStart: nowMs, failed: 1 } : { ...attempts, failed: attempts.failed + 1 } }, { merge: true });
  });
}
