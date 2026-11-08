// Which app build a take shows, and a warning once the app has moved on.
import { buildLabel, buildNote, oldTakeWarning } from "../lib/build.ts";
import type { BuildCheck } from "../lib/types.ts";
import { Dot } from "./ui.tsx";

/** The take card's build line: what the take was recorded with, and what changed since when that is older. */
export function BuildLine({ check }: { check: BuildCheck | null | undefined }) {
  if (!check) return null;
  const warn = oldTakeWarning(check), note = buildNote(check);
  if (warn) {
    return (
      <div className="mb-2.5 text-[12px] text-gold">
        <p><Dot tone="gold" /> <span className="ml-1">{warn}. Record again to show the current app.</span></p>
        {check.changes.length > 0 && (
          <details className="mt-1 text-faint">
            <summary className="cursor-pointer">What changed</summary>
            <ul className="mt-1 list-disc pl-5">{check.changes.map((c, i) => <li key={i}>{c}</li>)}{check.behind && check.behind > check.changes.length ? <li>…and {check.behind - check.changes.length} more</li> : null}</ul>
          </details>
        )}
      </div>
    );
  }
  if (note) return <p className="mb-2.5 text-[11.5px] text-faint">{note}</p>;
  return <p className="mb-2.5 text-[11.5px] text-faint">Recorded with the current app, {buildLabel(check.take)}.</p>;
}

/** A warning for the export panel: exporting an old take puts the old app in the video. */
export function OldTakeNotice({ check }: { check: BuildCheck | null | undefined }) {
  const warn = oldTakeWarning(check);
  if (!warn) return null;
  return (
    <p role="alert" className="mb-4 rounded-lg border border-gold/50 bg-gold/10 p-3 text-[12px] leading-relaxed text-ink">
      {warn}. This video will show the older app; record the take again first if that is not what you want.
    </p>
  );
}
