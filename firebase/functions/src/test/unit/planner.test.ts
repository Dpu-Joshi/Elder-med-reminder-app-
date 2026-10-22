import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, it } from "node:test";
import { alertOffsetMinutes, inQuietHours } from "../../alerts";
import { clockLabel, doseText } from "../../messages";
import { DEFAULT_SETTINGS, alertOrder, withDefaults } from "../../model";
import { MedicineSchedule, doseId, planDoses } from "../../planner";

interface VectorCase {
  name: string;
  medicineId: string;
  timeZone: string;
  schedule: MedicineSchedule;
  from: string;
  to: string;
  expected: { id: string; scheduledAt: string }[];
}

// lib/test/unit → repository root
const vectors = JSON.parse(
  readFileSync(resolve(__dirname, "../../../../../shared/dose-plan-vectors.json"), "utf8"),
) as { cases: VectorCase[] };

describe("planDoses matches the shared vectors", () => {
  for (const c of vectors.cases) {
    it(c.name, () => {
      const actual = planDoses(c.medicineId, c.schedule, c.timeZone, c.from, c.to).map((d) => ({
        id: d.id,
        scheduledAt: new Date(d.scheduledAtMillis).toISOString().replace(".000Z", "Z"),
      }));
      assert.deepEqual(actual, c.expected);
    });
  }
});

describe("doseId", () => {
  it("joins medicine, date and time without separators", () => {
    assert.equal(doseId("abc", "2026-10-19", "08:05"), "abc_20261019_0805");
  });
});

describe("alert timing", () => {
  it("first contact at 30 minutes, second at 60, third at 90 by default", () => {
    assert.deepEqual([0, 1, 2].map((l) => alertOffsetMinutes(DEFAULT_SETTINGS, l)), [30, 60, 90]);
  });

  it("escalation is always after the first alert", () => {
    assert.equal(withDefaults({ alertAfterMinutes: 45, escalateAfterMinutes: 20 }).escalateAfterMinutes, 75);
  });

  it("quiet hours wrap past midnight", () => {
    const s = DEFAULT_SETTINGS; // 22:00 to 07:00
    assert.deepEqual([21, 22, 23, 0, 6, 7, 12].map((h) => inQuietHours(h, s)), [false, true, true, true, true, false, false]);
  });
});

describe("alertOrder", () => {
  it("drops people who left and appends members missing from the order", () => {
    assert.deepEqual(
      alertOrder({ name: "f", createdBy: "a", memberUids: ["a", "b", "c"], alertOrder: ["b", "x", "a"] }),
      ["b", "a", "c"],
    );
  });
});

describe("message text", () => {
  it("formats clock times", () => {
    assert.deepEqual(["00:05", "08:00", "12:30", "20:15"].map(clockLabel), ["12:05 am", "8:00 am", "12:30 pm", "8:15 pm"]);
  });

  it("formats doses", () => {
    assert.equal(doseText(1, "tablet"), "1 tablet");
    assert.equal(doseText(2, "tablet"), "2 tablets");
    assert.equal(doseText(0.5, "tablet"), "½ tablet");
    assert.equal(doseText(1.5, "spoon"), "1½ spoons");
    assert.equal(doseText(5, "ml"), "5 ml");
  });
});
