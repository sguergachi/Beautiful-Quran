const $ = id => document.getElementById(id);
const params = new URLSearchParams(location.search);
const cloud = params.get('broker') === 'cloud';
const options = { config: { iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] } };
const customIce = await fetch('/ice-config').then(response => response.json());
if (customIce) options.config = customIce;
// Opt in to the TURN servers bundled in PeerJS 1.5.5 to check their availability.
if (params.get('turn') === 'peerjs') delete options.config;
if (!cloud) Object.assign(options, { host: location.hostname, port: Number(location.port || (location.protocol === 'https:' ? 443 : 80)), path: '/signal', secure: location.protocol === 'https:' });
const peer = new Peer(`bq-web-${crypto.randomUUID()}`, options);
if (params.get('relay') === '1') peer.options.config.iceTransportPolicy = 'relay';
const entries = [], received = {}, seen = new Set(), pending = new Map();
let connection;
const personal = {
  kind: 'personal',
  reading: { surahId: 2, ayah: 255 },
  bookmarks: [{ surahId: 18, ayah: 10 }],
  notes: [{ surahId: 18, ayah: 10, text: 'Trial note · تجربة' }],
  preferences: { reciterId: 1, showTranslation: true, themeMode: 'system' },
};
const handoff = {
  kind: 'handoff', handoffId: crypto.randomUUID(),
  surahId: 2, ayah: 255, positionMs: 12345,
  reciterId: 1, speed: 1.25, repeat: { from: 255, to: 257, count: 2 },
  action: 'prepare-only',
};
function log(message) {
  entries.push(`${new Date().toLocaleTimeString()} ${message}`);
  if (entries.length > 50) entries.shift();
  $('log').textContent = entries.join('\n');
}
function status(message) { $('status').textContent = message; log(message); }
function controls() { for (const id of ['personal', 'handoff', 'drop']) $(id).disabled = !connection?.open; }

// The receipt acknowledges this lab's in-memory delivery, never disk persistence or playback.
function send(payload) {
  if (!connection?.open) return Promise.reject(new Error('No open data channel'));
  const message = { version: 1, id: crypto.randomUUID(), payload };
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(message.id); reject(new Error('Receipt timed out')); }, 5000);
    pending.set(message.id, { resolve, timer, started: performance.now() });
    connection.send(message);
    log(`Sent ${payload.kind} ${message.id}`);
  });
}
function attach(next) {
  if (connection && connection !== next) connection.close();
  connection = next;
  next.on('open', () => { status(`Data channel open · ${next.peer}`); controls(); });
  next.on('close', () => { if (connection === next) { status('Data channel closed · connect again to retry'); controls(); } });
  next.on('error', error => status(`Channel error: ${error.message}`));
  next.on('data', message => {
    if (message?.version !== 1) { log('Ignored unknown protocol version'); return; }
    if (message.kind === 'receipt') {
      const waiting = pending.get(message.id);
      if (waiting) {
        clearTimeout(waiting.timer); pending.delete(message.id);
        const rttMs = Math.round((performance.now() - waiting.started) * 100) / 100;
        log(`Receipt ${message.id} · ${rttMs} ms round trip`);
        waiting.resolve({ id: message.id, rttMs, receiver: message.receiver });
      }
      return;
    }
    if (typeof message.id !== 'string' || !['personal', 'handoff'].includes(message.payload?.kind)) { log('Ignored malformed sample'); return; }
    if (!seen.has(message.id)) {
      seen.add(message.id);
      if (seen.size > 1000) seen.delete(seen.values().next().value);
      received[message.payload.kind] = message.payload;
      $('received').textContent = JSON.stringify(received, null, 2);
      log(`Received ${message.payload.kind} ${message.id}`);
    } else log(`Duplicate suppressed ${message.id}`);
    next.send({ version: 1, kind: 'receipt', id: message.id, receiver: 'browser', accepted: 'memory-only' });
  });
  controls();
}
function connect(remoteId = $('remote-id').value.trim()) {
  $('remote-id').value = remoteId;
  if (!remoteId || remoteId === peer.id) { status('Enter the other device’s peer ID'); return; }
  if (peer.disconnected || peer.destroyed) { status('Reconnect signaling before starting a new channel'); return; }
  attach(peer.connect(remoteId, { serialization: 'json', reliable: true }));
  status(`Connecting to ${remoteId}…`);
}
async function route() {
  const stats = await connection?.peerConnection?.getStats();
  if (!stats) return null;
  const transport = [...stats.values()].find(value => value.type === 'transport' && value.selectedCandidatePairId);
  const pair = stats.get(transport?.selectedCandidatePairId) || [...stats.values()].find(value => value.type === 'candidate-pair' && value.nominated && value.state === 'succeeded');
  if (!pair) return null;
  const local = stats.get(pair.localCandidateId), remote = stats.get(pair.remoteCandidateId);
  return { localType: local?.candidateType, remoteType: remote?.candidateType, protocol: local?.protocol, relayed: local?.candidateType === 'relay' || remote?.candidateType === 'relay' };
}
peer.on('open', id => {
  $('peer-id').textContent = id;
  $('signaling').textContent = 'Disconnect signaling';
  status(connection?.open ? `Data channel open · ${connection.peer}` : 'Ready · waiting for another device');
});
peer.on('connection', attach);
peer.on('disconnected', () => { $('signaling').textContent = 'Reconnect signaling'; log('Signaling disconnected; existing WebRTC channels may continue'); });
peer.on('error', error => status(`PeerJS ${error.type}: ${error.message}`));
$('connect').onclick = () => connect();
$('personal').onclick = () => send(personal).catch(error => log(error.message));
$('handoff').onclick = () => send(handoff).catch(error => log(error.message));
$('drop').onclick = () => connection?.close();
$('signaling').onclick = () => {
  if (peer.disconnected) { peer.reconnect(); $('signaling').textContent = 'Disconnect signaling'; }
  else peer.disconnect();
};
$('broker').textContent = `${cloud ? 'Public' : 'Local'} PeerServer signaling · ${params.get('turn') === 'peerjs' ? 'PeerJS default TURN' : customIce ? 'Custom ICE configuration' : 'STUN only'}${params.get('relay') === '1' ? ' · forced relay' : ''}`;
$('second').href = location.href;
// A small diagnostic surface lets the collaborative browser exercise real transfers.
window.lab = { peer, connect, send, personal, handoff, route, received, entries, get connection() { return connection; } };
