import { spawn } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// Runs each argument as a command at once and fails if any of them does.
// The type check and the bundle do not depend on each other, so a build is
// as long as the slower of the two rather than their sum.
const webRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const bin = resolve(webRoot, 'node_modules', '.bin')
const env = { ...process.env, PATH: `${bin}${process.platform === 'win32' ? ';' : ':'}${process.env.PATH ?? ''}` }

const children = process.argv.slice(2).map((command) => {
  const child = spawn(command, { cwd: webRoot, env, shell: true, stdio: 'inherit' })
  const done = new Promise((settle) => {
    child.on('error', () => settle(1))
    child.on('exit', (code, signal) => settle(signal ? 1 : (code ?? 1)))
  })
  return { command, child, done }
})

let failed = false
await Promise.all(children.map(async ({ command, done }) => {
  const code = await done
  if (code === 0 || failed) return
  failed = true
  console.error(`\n${command} failed (exit ${code})`)
  // The other command's result no longer matters.
  for (const other of children) if (other.command !== command) other.child.kill()
}))
process.exit(failed ? 1 : 0)
