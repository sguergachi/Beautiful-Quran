# APK delivery (Pixel 10)

One script is the whole delivery. There is no subagent and no manual
procedure: the main agent decides **when** to send, the script does **how**.

## When to send

Only after the thread's requested work is complete **and the T3 queue is
empty** — check `t3_queue_list` first. If any message is still queued, that
work owns this turn's APK; it sends when it finishes instead. Never send
mid-task or with follow-up work pending.

## How to send

```bash
scripts/send_apk_to_phone.sh --release --label "<work>"
```

The script builds through `scripts/publish_apk.sh` (the worktree's known APK
location), stages a copy named after the work + commit, verifies the copy is
whole, shares it to the paired Pixel 10, and — with no further steps —
retries a failed send 3 times (10s, 30s, 60s) and deletes older staged
builds once the new one is away. Trigger it, read the exit status, done.

Only plain APKs are sent; the zip detour is retired. `--zip` is still
accepted and ignored, solely so the T3 Code **Send APK** action's hardcoded
`--release --zip --published --wait` flags keep parsing.

Rules the script already enforces: named after work + short SHA (never
generic `app-*.apk`; KDE drops repeats of the same filename), whole-copy
`cmp` before share, older staged builds deleted after a successful send.

## If it fails

A non-zero exit means the send did not go. The staged copy is kept at the
printed path for a re-send. Report that plainly — never connect to port 1739
to "check" (that steals the payload from the phone), and scope any re-send
or cleanup to the staging dir, never a bare `/tmp/*.apk`.

## Report back

Filename, bytes, commit SHA — one line.
