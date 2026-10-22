import { Firestore } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";

/**
 * Consent withdrawal: the elder (or a family member) removes the elder's data. Under the DPDP
 * Act the elder is the one who consents, so the elder's phone must offer this as easily as it
 * offered consent.
 */

export interface UserDeleter {
  deleteUser(uid: string): Promise<void>;
}

export interface FileDeleter {
  deleteFiles(options: { prefix: string }): Promise<unknown>;
}

export interface Caller {
  uid: string;
  token: { fid?: unknown; eid?: unknown; firebase?: { sign_in_provider?: string } };
}

export async function deleteElderData(
  db: Firestore,
  auth: UserDeleter,
  files: FileDeleter | null,
  caller: Caller,
  raw: unknown,
): Promise<{ deleted: boolean }> {
  const { familyId, elderId } = (raw ?? {}) as { familyId?: unknown; elderId?: unknown };
  if (typeof familyId !== "string" || typeof elderId !== "string" || !familyId || !elderId) {
    throw new HttpsError("invalid-argument", "familyId and elderId are required.");
  }
  const familySnap = await db.collection("families").doc(familyId).get();
  const isElder = caller.token.fid === familyId && caller.token.eid === elderId;
  const isMember =
    familySnap.exists &&
    caller.token.firebase?.sign_in_provider !== "anonymous" &&
    (familySnap.get("memberUids") as string[]).includes(caller.uid);
  if (!isElder && !isMember) throw new HttpsError("permission-denied", "Not allowed.");

  const elderRef = familySnap.ref.collection("elders").doc(elderId);
  const elderUid: string | undefined = (await elderRef.get()).get("elderUid");
  await db.recursiveDelete(elderRef);
  if (files) await files.deleteFiles({ prefix: `families/${familyId}/elders/${elderId}/` }).catch(() => undefined);
  if (elderUid) await auth.deleteUser(elderUid).catch(() => undefined);
  return { deleted: true };
}
