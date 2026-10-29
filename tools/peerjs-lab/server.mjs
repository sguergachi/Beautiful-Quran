import express from 'express';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { readFileSync } from 'node:fs';
import { ExpressPeerServer } from 'peer';

const app = express();
const server = createServer(app);
// A local, disposable ICE configuration for relay experiments. Never use production secrets.
app.get('/ice-config', (_request, response) => {
  response.set('Cache-Control', 'no-store').json(process.env.ICE_CONFIG
    ? JSON.parse(readFileSync(process.env.ICE_CONFIG, 'utf8')) : null);
});
app.use('/signal', ExpressPeerServer(server, { path: '/', allow_discovery: false }));
app.use('/vendor', express.static(fileURLToPath(new URL('node_modules/peerjs/dist/', import.meta.url))));
app.use(express.static(fileURLToPath(new URL('public/', import.meta.url))));
server.listen(Number(process.env.PORT || 4317), '0.0.0.0', () => {
  console.log(`PeerJS lab: http://localhost:${server.address().port} (local signaling)`);
  console.log('Add ?broker=cloud for public PeerServer signaling. ICE_CONFIG can supply a test TURN server.');
});
