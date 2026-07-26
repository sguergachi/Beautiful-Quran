# Agent reviews — Codex and Claude

How AI agents (and humans) run **real** Codex / Claude code reviews on this
repo. Do **not** simulate them with a Grok subagent when the user asked for
Codex or Claude by name.

## When to use what

| Ask | Tool | Model |
|---|---|---|
| “Codex 5.6 sol”, “Codex review” | `codex` CLI | `gpt-5.6-sol` |
| “Claude Opus”, “Claude review” | `claude` CLI | `opus` (or full id e.g. `claude-opus-4-8`) |
| Generic “review this” / `/review` | Grok review skill | default Grok |

Both CLIs live on the machine (`~/.local/bin/codex`, `~/.local/bin/claude`).
Auth is already configured for the workspace user.

## Codex (`gpt-5.6-sol`)

### Uncommitted working tree (most common)

```bash
# Custom product laws optional — --uncommitted cannot take a PROMPT argument.
# Write principles into the review body by using plain `codex exec` instead,
# or run the stock review:

codex exec review --uncommitted -m gpt-5.6-sol \
  -c 'sandbox_mode="read-only"' \
  > /tmp/codex-review.md 2> /tmp/codex-review.err
```

Findings land in **stdout** (short summary). The full agent transcript is in
**stderr**. Always read both:

```bash
cat /tmp/codex-review.md
# if thin, extract the final codex block from stderr:
rg -n 'Full review comments|P[0-9]|## ' /tmp/codex-review.err | tail -40
```

### Against a base branch

```bash
codex exec review --base master -m gpt-5.6-sol \
  -c 'sandbox_mode="read-only"'
```

### Custom principles + uncommitted

`--uncommitted` is **mutually exclusive** with a custom `[PROMPT]` on
`codex exec review`. To inject product laws, use plain exec:

```bash
codex exec -m gpt-5.6-sol -c 'sandbox_mode="read-only"' \
  "$(cat <<'EOF'
Review the uncommitted changes (git status / git diff HEAD).

Principles:
1. ...
Write: ## Summary, then ## Issues with severity bug|suggestion|nit.
EOF
)"
```

### Gotchas

- **Do not** pass both `--uncommitted` and a positional prompt — CLI errors
  immediately (`cannot be used with '[PROMPT]'`).
- Default reasoning effort may be `none`; still produces usable P1/P2 notes.
- Session id is printed in stderr for `codex resume` if needed.

## Claude (Opus)

### Uncommitted / local review (print mode)

```bash
claude -p --model opus \
  --allowedTools "Read,Grep,Glob,Bash(git *),Bash(rg *),Bash(sqlite3 *)" \
  --output-format text \
  "$(cat <<'EOF'
Review uncommitted changes. Run git status and git diff HEAD; read changed files.

Principles:
1. ...
Write: ## Summary, then ## Issues with severity bug|suggestion|nit.
EOF
)" > /tmp/claude-opus-review.md 2> /tmp/claude-opus-review.err
```

- `-p` / `--print` = non-interactive; exits when done.
- `--model opus` picks the current Opus alias (see `claude --help`).
- Give **read** tools so it can inspect the tree; avoid write tools for pure
  review. Add `Bash(./gradlew *)` / `Bash(npx *)` only if you want it to run
  tests (needs a less tight sandbox).

### Gotchas

- Sandbox may deny `gradlew` / `vitest` unless those Bash tools are allowed —
  static review still works.
- Large diffs: point it at paths (`app/.../reader/`, `web/src/render/`) so it
  does not thrash the whole monorepo.

## Dual review (recommended for ink / karaoke)

When the change touches **repeat wash, HighlightEngine, or reader hot path**,
run **both** and reconcile:

```bash
# parallel
codex exec review --uncommitted -m gpt-5.6-sol -c 'sandbox_mode="read-only"' \
  > /tmp/codex-review.md 2> /tmp/codex-review.err &
claude -p --model opus --allowedTools "Read,Grep,Glob,Bash(git *)" \
  --output-format text "…" > /tmp/claude-opus-review.md &
wait
```

1. Confirm each finding against the code (do not rubber-stamp).
2. Prefer fixes that appear in **both** reviews.
3. Do **not** treat “must fix timing DB” as the root of an animation bug when
   Timings Lab proves membership (same `AyahBlock` + `HighlightEngine`).

## What not to do

- Do **not** spawn a Grok `general-purpose` subagent and label it “Codex” or
  “Claude”. That is a different model and violates an explicit ask.
- Do **not** invent model slugs; use `gpt-5.6-sol` and `opus` as above (or
  whatever `codex` / `claude` list as current).
- Do **not** open a PR from a review-only run unless the user asked for fixes.

## Related

- Stock Grok `/review` skill: orchestrated local/branch/PR review (Grok only).
- Prior multi-agent pass: the `ANDROID_QUALITY_*.md` audits under this
  directory (2026-07-22).
