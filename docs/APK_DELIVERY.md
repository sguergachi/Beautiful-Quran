# APK delivery (Pixel 10)

Single procedure for sending a build to the phone. Main agent decides
**what** to send; a cheap haiku subagent runs this doc start to finish.

## Delegate, don't do it inline

Main session stays on code. Hand the subagent label + commit SHA only:

> Send a release APK for `<work>` at `<sha>` following `docs/APK_DELIVERY.md`.
> You are a subagent. Don't run memo.

## Where the APK lives

`scripts/publish_apk.sh` puts the built APK at one fixed place per worktree
(= per T3 Code thread): `~/.t3/artifacts/Beautiful-Quran/apks/<worktree-dir>/release.apk`
(or `debug.apk`) with a `release.info` beside it recording the commit it was
built from. `send_apk_to_phone.sh` builds through it, so the sent file is
always the published one. The T3 Code **Send APK** action runs
`scripts/send_apk_to_phone.sh --release --zip --published --wait`: it sends the
published APK as-is (building only if none exists) and warns when it was built
from a different commit than HEAD or from a dirty tree.

## Run

```bash
scripts/send_apk_to_phone.sh --release --label "<work>"
```

Bandwidth is slow: prefer the zipped send (script zips with `--zip`):

```bash
scripts/send_apk_to_phone.sh --release --zip --label "<work>"
```

Rules the script already enforces (`scripts/send_apk_to_phone.sh`):
named after work + short SHA (never generic `app-*.apk`; KDE drops
repeats of the same filename), whole-copy `cmp` before share, old staged
builds deleted after the new one is away.

## If the share stalls

The script retries the share 3 times on its own (10s, 30s, 60s). If all
4 attempts fail:

1. `kdeconnect-cli --refresh`, reopen the KDE Connect app on both ends,
   and re-run (a repeat name gets a fresh minute stamp automatically).
2. Still stalled: SFTP tail-append through the mounted KDE storage, then
   verify remote size + 3 × 64 KiB samples + `unzip -l` (zipped) — size
   only on a slow link, never a full remote checksum.
3. Taildrop is last resort. Never connect to port 1739 to "check" — that
   steals the payload from the phone.
4. Never re-share a file the phone already accepted. If unreachable,
   report plainly and keep the staged file.

## After phone confirms receipt

- Delete the just-sent local staged copy too (`/tmp` is a small tmpfs;
  ~250 MB builds once truncated a copy mid-send).
- Delete the previous build's phone + local copies only after the new one
  verifies. Keep the new staged file until then.
- Scope every delete to the staging dir / files you staged — never a bare
  `/tmp/*.apk`.

## Report back

Filename, bytes, commit SHA, verification (size / samples / zip listing),
phone state, what was kept vs deleted.
