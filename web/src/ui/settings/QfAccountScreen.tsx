import { useState, useSyncExternalStore } from 'react'
import { qfAccount } from '../../account/qfAccount'
import { BackChevron } from '../kit/BackChevron'
import { PaperSwitch } from '../kit/PaperSwitch'
import { QfLogo } from './QfLogo'

export function QfAccountScreen({ onBack }: { onBack: () => void }) {
  const status = useSyncExternalStore(qfAccount.subscribe, qfAccount.getSnapshot, qfAccount.getSnapshot)
  const [adult, setAdult] = useState(false)
  const [consent, setConsent] = useState(false)
  const [confirmation, setConfirmation] = useState<'erase' | 'disconnect' | null>(null)
  const deviceCount = qfAccount.deviceCount()
  const legal = (
    <section className="settings-section">
      <p><a href="https://sguergachi.github.io/Beautiful-Quran/privacy.html" target="_blank" rel="noreferrer">Beautiful Quran privacy</a></p>
      <p><a href="https://quran.com/terms-and-conditions" target="_blank" rel="noreferrer">QF account terms</a></p>
      <p><a href="https://quran.com/privacy" target="_blank" rel="noreferrer">QF account privacy</a></p>
      <p><a href="https://quran.com" target="_blank" rel="noreferrer">Manage your QF account</a></p>
    </section>
  )
  return (
    <>
      <button type="button" className="back settings-back" aria-label="Back"
        onClick={() => confirmation ? setConfirmation(null) : onBack()}><BackChevron /></button>
      <h1 className="qf-account-heading"><QfLogo /></h1>
      <section className="settings-section">
        {confirmation ? (
          <>
            <p className="settings-caption">{confirmation === 'erase'
              ? 'Remove every Hafs verse bookmark in the Beautiful Quran collection from QF. Other collections, reading history and device bookmarks are kept. If offline, this request is saved until sync completes.'
              : 'Disconnect restores this browser’s separate bookmarks and clears its QF cache and ' +
                status.pending + ' queued edits. Export first to keep a copy. The collection and your account remain in QF.'}</p>
            <button type="button" className="settings-dev-link" onClick={() => {
              if (confirmation === 'erase') qfAccount.eraseSyncedBookmarks()
              else void qfAccount.disconnect()
              setConfirmation(null)
            }}>{confirmation === 'erase' ? 'Remove synced bookmarks' : 'Disconnect from this browser'}</button>
            <button type="button" className="settings-dev-link" onClick={() => setConfirmation(null)}>Keep connected</button>
          </>
        ) : (
          <>
            {status.available && status.environment === 'prelive'
              ? <p className="settings-caption">QF test environment</p> : null}
            {status.connected ? (
              <>
                <p className="settings-caption">{qfAccount.bookmarks().length} saved verses · {status.pending} edits waiting</p>
                {status.lastSyncedAt ? <p className="settings-caption">Last synced {new Date(status.lastSyncedAt).toLocaleString()}</p> : null}
                {status.deleting ? <p className="settings-caption">Removal is pending until QF confirms an empty collection.</p> : null}
                {!status.writable ? (
                  <>
                    <p className="settings-caption">Another tab is using QF bookmarks. Close it to edit in this tab.</p>
                    <button type="button" className="settings-dev-link" onClick={() => void qfAccount.claim()}>Use this tab</button>
                  </>
                ) : null}
                {status.authRequired ? (
                  <p className="settings-caption">Sign in to the same QF account to sync. Your offline edits are saved here.</p>
                ) : <button type="button" className="settings-dev-link" disabled={status.busy || !status.writable}
                  onClick={() => void qfAccount.retry()}>Sync now</button>}
                {deviceCount > 0 ? (
                  <>
                    <p className="settings-caption">Your {deviceCount} original device bookmarks are stored separately.</p>
                    <button type="button" className="settings-dev-link" disabled={!status.writable || status.deleting}
                      onClick={() => qfAccount.importDeviceBookmarks()}>Add {deviceCount} device bookmarks to QF</button>
                  </>
                ) : null}
                <button type="button" className="settings-dev-link" disabled={!status.writable || status.deleting}
                  onClick={() => setConfirmation('erase')}>Remove synced bookmarks</button>
                <button type="button" className="settings-dev-link" disabled={!status.writable}
                  onClick={() => setConfirmation('disconnect')}>Disconnect from this browser</button>
              </>
            ) : (
              <>
                <p className="settings-caption">Optional bookmark sync between Beautiful Quran on Android and the web.</p>
                <p className="settings-caption">Saved verse references can reveal religious activity. Connecting sends your account identity and future bookmark changes to QF. QF stores the Beautiful Quran collection, and other connected apps can access it. Existing device bookmarks are added only when you choose to add them.</p>
                <p className="settings-caption">Your notes, settings and listening position stay on this device. You can read without an account and export or remove synced bookmarks here. Manage app access or delete the full QF account through QF.</p>
              </>
            )}
            {!status.connected || status.authRequired ? (
              <>
                <PaperSwitch label="I am 18 or older" checked={adult} onChange={setAdult} />
                <PaperSwitch label="I agree to send my bookmark activity to QF" checked={consent} onChange={setConsent} />
                <button type="button" className="settings-dev-link settings-dev-primary qf-sign-in"
                  aria-label="Continue with Quran Foundation"
                  disabled={!status.available || !adult || !consent || status.busy}
                  onClick={() => void qfAccount.signIn()}><span>Continue with</span><QfLogo alt="" /></button>
                {!status.available ? <p className="settings-caption">Account sync is awaiting approved public-client configuration.</p> : null}
              </>
            ) : null}
            <p className="settings-caption" role="status">{status.message}</p>
            <button type="button" className="settings-dev-link" onClick={() => qfAccount.exportBookmarks()}>Export bookmarks</button>
          </>
        )}
      </section>
      {legal}
    </>
  )
}
