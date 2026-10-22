import { DateTime } from "luxon";

/**
 * Turns a medicine's schedule into dated doses. The Android app has the same logic in
 * DosePlanner.kt; both must give the same ids, so the elder's phone and the server agree on
 * which dose was confirmed even when the phone was offline. shared/dose-plan-vectors.json
 * holds cases that both test suites check.
 */

export interface MedicineSchedule {
  times: string[];
  startDate: string;
  endDate?: string | null;
  active: boolean;
}

export interface PlannedDose {
  id: string;
  medicineId: string;
  localDate: string;
  localTime: string;
  scheduledAtMillis: number;
}

const TIME_RE = /^([01]\d|2[0-3]):([0-5]\d)$/;

export function doseId(medicineId: string, localDate: string, localTime: string): string {
  return `${medicineId}_${localDate.replace(/-/g, "")}_${localTime.replace(":", "")}`;
}

/** Doses for every local date from [fromDate] to [toDate] inclusive ("yyyy-MM-dd"). */
export function planDoses(
  medicineId: string,
  schedule: MedicineSchedule,
  timeZone: string,
  fromDate: string,
  toDate: string,
): PlannedDose[] {
  if (!schedule.active) return [];
  const times = [...new Set(schedule.times.filter((t) => TIME_RE.test(t)))].sort();
  const doses: PlannedDose[] = [];
  let day = DateTime.fromISO(fromDate, { zone: timeZone });
  const last = DateTime.fromISO(toDate, { zone: timeZone });
  if (!day.isValid || !last.isValid) return [];

  while (day <= last) {
    const date = day.toISODate()!;
    const started = date >= schedule.startDate;
    const notEnded = !schedule.endDate || date <= schedule.endDate;
    if (started && notEnded) {
      for (const time of times) {
        const [hour, minute] = time.split(":").map(Number);
        const at = DateTime.fromObject(
          { year: day.year, month: day.month, day: day.day, hour, minute },
          { zone: timeZone },
        );
        doses.push({
          id: doseId(medicineId, date, time),
          medicineId,
          localDate: date,
          localTime: time,
          scheduledAtMillis: at.toMillis(),
        });
      }
    }
    day = day.plus({ days: 1 });
  }
  return doses;
}

export function localDate(millis: number, timeZone: string): string {
  return DateTime.fromMillis(millis, { zone: timeZone }).toISODate()!;
}
