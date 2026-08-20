import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";

const DEFAULT_SCRIPT = resolve(
  fileURLToPath(new URL("../../tools/normalize_runtime_timings.py", import.meta.url)),
);
const DEFAULT_DATABASE = resolve(
  fileURLToPath(new URL("../../data/quran.db", import.meta.url)),
);

/** Run the canonical Python timing pipeline without duplicating it in clients. */
export function createRuntimeTimingNormalizer({
  python = process.env.PYTHON || "python3",
  script = DEFAULT_SCRIPT,
  database = DEFAULT_DATABASE,
  timeoutMs = 60_000,
  maxOutputBytes = 20 * 1024 * 1024,
} = {}) {
  return ({ appReciterId, chapters }) => new Promise((resolvePromise, reject) => {
    const child = spawn(python, [script, "--database", database], {
      stdio: ["pipe", "pipe", "pipe"],
    });
    const stdout = [];
    const stderr = [];
    let stdoutBytes = 0;
    let stderrBytes = 0;
    let settled = false;
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      finish(new Error("timing normalizer timed out"));
    }, timeoutMs);

    child.stdout.on("data", (chunk) => {
      stdoutBytes += chunk.length;
      if (stdoutBytes > maxOutputBytes) {
        child.kill("SIGKILL");
        finish(new Error("timing normalizer exceeded output limit"));
      } else {
        stdout.push(chunk);
      }
    });
    child.stderr.on("data", (chunk) => {
      stderrBytes += chunk.length;
      if (stderrBytes <= 1024 * 1024) stderr.push(chunk);
    });
    child.stdin.on("error", finish);
    child.on("error", finish);
    child.on("close", (code) => {
      if (settled) return;
      if (code !== 0) {
        finish(new Error(`timing normalizer failed (${code}): ${Buffer.concat(stderr).toString("utf8")}`));
        return;
      }
      try {
        const body = Buffer.concat(stdout).toString("utf8");
        validateSnapshot(JSON.parse(body), appReciterId);
        finish(null, body);
      } catch (error) {
        finish(error);
      }
    });
    child.stdin.end(JSON.stringify({ app_reciter_id: appReciterId, chapters }));

    function finish(error, value) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (error) reject(error);
      else resolvePromise(value);
    }
  });
}

export function validateSnapshot(snapshot, reciterId, minimumRecords = 6_000) {
  const keys = new Set();
  if (
    snapshot?.schema_version !== 1 ||
    snapshot?.resource_group !== "recitations" ||
    snapshot?.resource_id !== reciterId ||
    !Array.isArray(snapshot?.records) ||
    snapshot.records.length < minimumRecords ||
    snapshot.records.some((record) => {
      const key = `${record?.surah_id}:${record?.ayah_number}`;
      const invalid =
        record?.record_type !== "timing" ||
        record?.record_key !== key ||
        !Number.isInteger(record?.surah_id) ||
        record.surah_id < 1 ||
        record.surah_id > 114 ||
        !Number.isInteger(record?.ayah_number) ||
        record.ayah_number < 1 ||
        !Array.isArray(record?.segments) ||
        record.segments.some((segment) =>
          !Array.isArray(segment) ||
          segment.length !== 3 ||
          !Number.isInteger(segment[0]) ||
          segment[0] < 1 ||
          !Number.isFinite(segment[1]) ||
          segment[1] < 0 ||
          !Number.isFinite(segment[2]) ||
          segment[2] <= segment[1]
        ) ||
        keys.has(key);
      keys.add(key);
      return invalid;
    })
  ) {
    throw new Error("timing normalizer returned an invalid snapshot");
  }
  return snapshot;
}
