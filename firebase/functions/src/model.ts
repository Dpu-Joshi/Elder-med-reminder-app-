import { Timestamp } from "firebase-admin/firestore";

/**
 * Firestore documents. Paths:
 *   users/{uid}
 *   families/{familyId}
 *   families/{familyId}/elders/{elderId}
 *   families/{familyId}/elders/{elderId}/medicines/{medicineId}
 *   families/{familyId}/elders/{elderId}/doses/{doseId}      doseId = medicineId_yyyyMMdd_HHmm
 *   pairingCodes/{code}
 *   familyInvites/{code}
 * The Android app's FirestoreModels.kt mirrors these field names.
 */

export const REGION = "asia-south1";
export const DEFAULT_TIMEZONE = "Asia/Kolkata";

export interface UserDoc {
  displayName?: string;
  fcmTokens?: string[];
  /** Set only by Cloud Functions. One family per user in the first release. */
  familyId?: string;
}

export interface FamilyDoc {
  name: string;
  memberUids: string[];
  /** Who is alerted first, second, ... Always a permutation of memberUids. */
  alertOrder: string[];
  members?: Record<string, { name?: string; phoneNumber?: string }>;
  createdBy: string;
}

export interface ElderSettings {
  /** Minutes after the dose time before the first family contact is alerted. */
  alertAfterMinutes: number;
  /** Minutes after the dose time before the second contact is alerted. */
  escalateAfterMinutes: number;
  /** Hours without a check-in before "phone is silent". */
  silentAfterHours: number;
  /** No "phone is silent" alerts between these local hours. */
  quietStartHour: number;
  quietEndHour: number;
}

export const DEFAULT_SETTINGS: ElderSettings = {
  alertAfterMinutes: 30,
  escalateAfterMinutes: 60,
  silentAfterHours: 8,
  quietStartHour: 22,
  quietEndHour: 7,
};

export function withDefaults(settings?: Partial<ElderSettings> | null): ElderSettings {
  const s = { ...DEFAULT_SETTINGS, ...(settings ?? {}) };
  if (s.escalateAfterMinutes <= s.alertAfterMinutes) s.escalateAfterMinutes = s.alertAfterMinutes + 30;
  return s;
}

export type Language = "hi" | "en";

export interface ElderDoc {
  name: string;
  /** What the spoken reminder calls them, for example "मां". */
  nickname?: string;
  language: Language;
  timezone?: string;
  /** The elder's own number, for the family's Call button. */
  phoneNumber?: string;
  /** Who the elder's "Call" button rings, set by the family. */
  callName?: string;
  callNumber?: string;
  /** Anonymous Firebase Auth uid of the elder's phone. */
  elderUid: string;
  fcmToken?: string;
  settings?: Partial<ElderSettings>;
  lastCheckInAt?: Timestamp | null;
  silentAlertAt?: Timestamp | null;
  silentAlertSentAt?: Timestamp | null;
  consent?: { acceptedAt: Timestamp; version: number } | null;
}

export type FoodRule = "before" | "after" | "any";
export type DoseUnit = "tablet" | "capsule" | "spoon" | "drops" | "puff" | "injection" | "ml";

export interface MedicineDoc {
  name: string;
  /** What the family calls it out loud, for example "BP की गोली". */
  spokenName?: string;
  photoPath?: string | null;
  doseAmount: number;
  doseUnit: DoseUnit;
  foodRule: FoodRule;
  /** Local "HH:mm" times. */
  times: string[];
  /** Local "yyyy-MM-dd", inclusive. */
  startDate: string;
  endDate?: string | null;
  tabletsLeft?: number | null;
  active: boolean;
  addedBy: string;
  updatedBy: string;
}

export type DoseStatus = "pending" | "snoozed" | "confirmed" | "not_confirmed" | "confirmed_by_family";

export interface DoseDoc {
  medicineId: string;
  scheduledAt: Timestamp;
  localDate: string;
  localTime: string;
  status: DoseStatus;
  confirmedAt?: Timestamp | null;
  /** "elder" or the uid of the family member who checked. */
  confirmedBy?: string | null;
  /** How many contacts have been alerted so far. */
  alertLevel: number;
  /** When the next alert is due; null once nothing more should be sent. */
  alertAt: Timestamp | null;
  alertedUids?: string[];
  acknowledgedAt?: Timestamp | null;
  acknowledgedBy?: string | null;
}

/** alertOrder cleaned against memberUids, with any member missing from it appended. */
export function alertOrder(family: FamilyDoc): string[] {
  const members = family.memberUids ?? [];
  const ordered = (family.alertOrder ?? []).filter((uid) => members.includes(uid));
  return [...ordered, ...members.filter((uid) => !ordered.includes(uid))];
}
