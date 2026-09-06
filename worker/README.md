# Quran Foundation Content Sync proxy

This is a small Cloudflare Worker, deployed from this repository by Cloudflare's
Git integration with `npx wrangler deploy`. It is not a content host or a
general-purpose proxy: it accepts only the three Content Sync resources the
reader requires, holds a short-lived OAuth token in Worker memory, and streams
the response directly to the device's local cache.

## One-time Cloudflare setup

In **Workers & Pages → beautiful-quran → Settings → Variables and Secrets**,
add these **secrets** to the deployed Worker:

| Name | Value |
| --- | --- |
| `QF_CLIENT_ID` | The Quran Foundation Production client ID |
| `QF_CLIENT_SECRET` | The current Quran Foundation Production client secret |

Add these ordinary variables (or rely on their safe defaults):

| Name | Value |
| --- | --- |
| `QF_ENV` | `production` |
| `ALLOWED_ORIGIN` | `https://sguergachi.github.io` |

These non-secret production values are also pinned in `wrangler.jsonc`, so Git
preview deployments cannot silently fall back to Prelive. Only the credentials
belong in Cloudflare's secret store.

After Cloudflare deploys `master`, visit `https://<your-worker>/healthz`. A
successful response is `{ "ok": true, "environment": "production" }`. This
does not call QF and never exposes credentials.

Do **not** place either secret in GitHub Actions, repository variables,
GitHub Pages, Android, or the web bundle. The Worker is the only secret holder.

## Client contract

Android and web use only authenticated Production endpoints exposed by this
allowlist: Content Sync for `mushafs:1`, English word translations `59`, and
word transliterations `60`; those three snapshot routes; and five fixed verse
lookups used to repair known ambiguities in the transliteration snapshot. The
clients validate all 77,429 canonical words and every QCF page-font run before
advancing the opaque sync checkpoint.

The Worker stores no Quran content or client cache. It caches only the
short-lived OAuth access token in memory, retries one `401`, returns `no-store`,
and never returns an upstream error body. Rotate the Cloudflare secret whenever
the QF secret is rotated; do not commit either value.
