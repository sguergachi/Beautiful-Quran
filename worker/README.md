# Quran Foundation Content Sync proxy

This is a small Cloudflare Worker, deployed from this repository by Cloudflare's
Git integration with `npx wrangler deploy`. It is not a content host or a
general-purpose proxy: it accepts only the three Content Sync resources the
reader requires, holds a short-lived OAuth token in Worker memory, and streams
the response directly to the device's local cache.

## One-time Cloudflare setup

In **Workers & Pages → beautiful-quran → Settings → Variables and Secrets**,
add these **secrets** to the Prelive environment:

| Name | Value |
| --- | --- |
| `QF_CLIENT_ID` | The rotated Quran Foundation Prelive client ID |
| `QF_CLIENT_SECRET` | The rotated Quran Foundation Prelive client secret |

Add these ordinary variables (or rely on their safe defaults):

| Name | Value |
| --- | --- |
| `QF_ENV` | `prelive` |
| `ALLOWED_ORIGIN` | `https://sguergachi.github.io` |

After Cloudflare deploys `master`, visit `https://<your-worker>/healthz`. A
successful response is `{ "ok": true, "environment": "prelive" }`. This
does not call QF and never exposes credentials.

Do **not** place either secret in GitHub Actions, repository variables,
GitHub Pages, Android, or the web bundle. The Worker is the only secret holder.

## Before switching clients

The current public client cache uses the legacy combined word/QCF response.
QF Content Sync provides separate Mushaf, word-translation, and
word-transliteration resources. Capture one approved Prelive sync + snapshot
and verify its record schema and resource IDs before changing the Android/web
mapper; then the client will join those three local resources atomically. This
avoids a successful deployment silently changing Quran page or search fidelity.
