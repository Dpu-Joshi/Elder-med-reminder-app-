import { DateTime } from "luxon";
import { DEFAULT_TIMEZONE, DoseDoc, DoseUnit, ElderDoc, FoodRule, MedicineDoc } from "./model";

/**
 * Text of the pushes sent to family members. Alerts say "not confirmed", never "missed":
 * a tap is not proof the pill was swallowed, and a missing tap is not proof it was not.
 */

export interface AlertMessage {
  title: string;
  body: string;
  /** Read by the Android app when the notification is tapped. */
  data: Record<string, string>;
  /** Replaces an earlier notification about the same thing. */
  tag?: string;
}

export function doseAlertMessage(args: {
  familyId: string;
  elderId: string;
  doseId: string;
  elder: ElderDoc;
  medicine: MedicineDoc | null;
  dose: DoseDoc;
  level: number;
}): AlertMessage {
  const { elder, medicine, dose, level } = args;
  const what = medicine?.name ?? "medicine";
  const when = clockLabel(dose.localTime);
  const dosePart = medicine ? `${doseText(medicine.doseAmount, medicine.doseUnit)}, ${foodText(medicine.foodRule)}` : "";
  const second = level > 0 ? " The first contact has not responded." : "";
  return {
    title: `${elder.name}: ${what} not confirmed`,
    body: `${when} dose${dosePart ? ` · ${dosePart}` : ""}. Tap to call or mark as checked.${second}`,
    data: {
      type: "dose_not_confirmed",
      familyId: args.familyId,
      elderId: args.elderId,
      doseId: args.doseId,
      phoneNumber: elder.phoneNumber ?? "",
    },
    tag: args.doseId,
  };
}

export function silentPhoneMessage(args: { familyId: string; elderId: string; elder: ElderDoc }): AlertMessage {
  const { elder } = args;
  const last = elder.lastCheckInAt
    ? DateTime.fromMillis(elder.lastCheckInAt.toMillis(), { zone: elder.timezone ?? DEFAULT_TIMEZONE })
      .toFormat("d LLL, h:mm a")
      .replace("AM", "am")
      .replace("PM", "pm")
    : "setup";
  return {
    title: `${elder.name}'s phone is silent`,
    body: `No check-in since ${last}. It may be off, out of battery, or the app was closed. Reminders may not ring.`,
    data: {
      type: "phone_silent",
      familyId: args.familyId,
      elderId: args.elderId,
      phoneNumber: elder.phoneNumber ?? "",
    },
    tag: `silent_${args.elderId}`,
  };
}

export function lateConfirmationMessage(args: {
  familyId: string;
  elderId: string;
  doseId: string;
  elder: ElderDoc;
  medicine: MedicineDoc | null;
  dose: DoseDoc;
  byFamilyName?: string;
}): AlertMessage {
  const { elder, medicine, dose } = args;
  const what = medicine?.name ?? "medicine";
  const confirmedAt = dose.confirmedAt ?? dose.scheduledAt;
  const minutes = Math.max(0, Math.round((confirmedAt.toMillis() - dose.scheduledAt.toMillis()) / 60_000));
  const at = DateTime.fromMillis(confirmedAt.toMillis(), { zone: elder.timezone ?? DEFAULT_TIMEZONE })
    .toFormat("h:mm a")
    .toLowerCase();
  const who = args.byFamilyName ? `${args.byFamilyName} checked` : "Confirmed on the phone";
  return {
    title: `${elder.name}: ${what} confirmed`,
    body: `${who} at ${at}, ${minutes} min after the ${clockLabel(dose.localTime)} reminder.`,
    data: {
      type: "dose_confirmed",
      familyId: args.familyId,
      elderId: args.elderId,
      doseId: args.doseId,
    },
    tag: args.doseId,
  };
}

/** "08:00" → "8:00 am" */
export function clockLabel(localTime: string): string {
  const [h, m] = localTime.split(":").map(Number);
  const suffix = h < 12 ? "am" : "pm";
  const hour12 = h % 12 === 0 ? 12 : h % 12;
  return `${hour12}:${String(m).padStart(2, "0")} ${suffix}`;
}

export function doseText(amount: number, unit: DoseUnit): string {
  const whole = Math.floor(amount);
  const half = amount - whole >= 0.5;
  const number = whole === 0 && half ? "½" : `${whole}${half ? "½" : ""}`;
  const plural = amount > 1 && unit !== "ml" && unit !== "drops";
  const names: Record<DoseUnit, string> = {
    tablet: "tablet",
    capsule: "capsule",
    spoon: "spoon",
    drops: "drops",
    puff: "puff",
    injection: "injection",
    ml: "ml",
  };
  return `${number} ${names[unit]}${plural ? "s" : ""}`;
}

export function foodText(rule: FoodRule): string {
  switch (rule) {
    case "before":
      return "before food";
    case "after":
      return "after food";
    default:
      return "with or without food";
  }
}
