# PeerJS trial

A transport experiment for Beautiful Quran's Android/web sync. Run the browser
lab and a separate Kotlin app; send synthetic reading progress, bookmarks,
Arabic/English notes, preferences, and a precise playback checkpoint. Receipts
acknowledge delivery to memory. They do not save app data or start audio.

## Try two browsers

```sh
cd tools/peerjs-lab
npm ci
npm start
```

Open <http://localhost:4317> twice. Copy one peer ID into the other page, connect,
then send either sample. Both pages can send. Close the channel and connect
again to try reconnection. Disconnect signaling while the channel is open to
see that established WebRTC transfers can continue independently.

`?broker=cloud` uses PeerJS's public signaling service. Without it, both peers
must use this lab's `/signal` PeerServer. Neither setting changes the chosen
ICE configuration: this lab starts with STUN only.

## Try native Android

Build from the repository root with JDK 21 and the existing Android SDK:

```sh
./gradlew -p tools/peerjs-lab/android assembleRelease
```

Install `tools/peerjs-lab/android/build/outputs/apk/release/PeerJsProbe-release.apk`.
It appears as **PeerJS trial**, package `com.beautifulquran.peerjsprobe`, and
coexists with Beautiful Quran. This release variant uses a development signing
key. The APK includes ARM64 and x86_64 native WebRTC libraries, about 30 MiB
in total; it contains no Quran assets.

The app registers with public PeerServer signaling on launch. Keep it open,
open the browser lab with `?broker=cloud`, paste the Android peer ID, and connect.
Android receives samples and sends JSON receipts back. The probe implements
only the receive side of PeerJS data offers, plus answers, ICE candidates,
heartbeats and receipts; it is not a general Android PeerJS SDK.

For networks requiring a relay, supply the same test TURN settings to both
clients using the instructions below. On the phone, enter the development
machine's `http://<LAN-address>:4317/ice-config` URL and tap **Load relay settings**
before connecting. On an emulator, the host alias is `10.0.2.2`. Cleartext access
is enabled only in this separate test app.

For automated local signaling, launch the activity with these optional extras:

```sh
adb shell am start -n com.beautifulquran.peerjsprobe/.ProbeActivity \
  --es broker 'ws://10.0.2.2:4317/signal/peerjs?key=peerjs' \
  --es peerId bq-android-probe
```

`iceServers` accepts a JSON array of ICE servers with string `urls`, `username`
and `credential`; `relay=true` forces relay candidates. To change launch extras,
stop the probe first. Agent emulators must be leased through `scripts/emu.sh`
and released when finished.

## Test TURN explicitly

Use a disposable [coturn](https://github.com/coturn/coturn) instance. Create a
local JSON file outside the repository, with a server reachable by both peers:

```json
{
  "iceServers": [{
    "urls": "turn:YOUR-LAN-ADDRESS:3479?transport=udp",
    "username": "lab",
    "credential": "DISPOSABLE-LAB-CREDENTIAL"
  }]
}
```

```sh
ICE_CONFIG=/absolute/path/ice.json npm start
```

This development server serves that file at `/ice-config`, so use only
throwaway lab credentials. The phone loads it through the relay URL field.
Browser `?relay=1` forces TURN; combine it with `?broker=cloud&relay=1` to use
Internet signaling. `await lab.route()` in the browser console reports the
selected candidate types, protocol and whether the path uses a relay.

For comparison, browser `?turn=peerjs&relay=1` tries the TURN configuration
bundled with PeerJS 1.5.5; Android's `turn=peerjs` extra tries the same servers.
Those hostnames did not resolve in the October 9 trial, and produced no relay
candidates. Do not assume that bundled configuration is an available relay.

## Measured results — October 9, 2026

| Exercise | Observed result |
|---|---|
| Browser → browser and back | Samples and receipts passed; direct host/host UDP |
| Browser → native Android, STUN only | Signaling/SDP succeeded; emulator ICE failed |
| Browser → native Android with coturn 4.18 | Both samples received and acknowledged; relay/relay UDP |
| Same native transfer with public PeerServer | Passed; data still used the local coturn relay |
| Close data channel, reconnect, send again | Passed |
| 20 sequential native checkpoint transfers | 20 receipts; 4–17.7 ms round trip, median 16.5 ms |
| Disconnect signaling on an established channel | Browser and native receipts still arrived |
| Deliver a message ID twice | One native delivery logged; duplicate acknowledged and suppressed |
| Unknown protocol version | Ignored |

On October 11, the final APK was tested through its relay URL field. Public
signaling, 22 transfers (both samples plus 20 checkpoints), signaling disconnect
and channel reconnection passed. Round trips were 5.5–23.1 ms, median 16.5 ms;
the selected route was relay/srflx UDP with only the browser forced to relay.
The standalone release build, JavaScript syntax check, and repository Android
unit tests passed.

These are transport measurements on one development machine and its emulator.
Public signaling crossed the Internet, while the relay remained on the LAN.
Mobile-data/CGNAT, two distant networks, TURN over TLS/TCP, process suspension,
and actual playback takeover have not been verified.

## What this establishes

PeerJS can carry our small state/checkpoint messages, and its JSON data channels
interoperate with native Android WebRTC. The native adapter is code we maintain;
the browser library does not remove Android signaling work.

Production sync would still need authenticated pairing, revisions/deletions and
conflict rules, durable local delivery/outbox handling, and explicit prepare /
ready / commit playback handoff. The probe has no authentication, persistence,
automatic recovery, or offline mailbox; use synthetic data. Both runtimes need
to be reachable for a live transfer. PeerJS JSON messages are limited to about
16 KB, so eventual full sync must split larger payloads.

The production app and web reader have no dependency on this directory.

## Pinned libraries and protocol evidence

- [PeerJS 1.5.5 JSON framing](https://github.com/peers/peerjs/blob/v1.5.5/lib/dataconnection/BufferedConnection/Json.ts): binary UTF-8 frames, with `serialization: 'json'` and `reliable: true`.
- [PeerJS signaling](https://github.com/peers/peerjs/blob/v1.5.5/lib/negotiator.ts) and [WebSocket registration](https://github.com/peers/peerjs/blob/v1.5.5/lib/socket.ts).
- [PeerServer](https://github.com/peers/peerjs-server) 1.0.2 and Express 5.3.0 for the local lab.
- [Android WebRTC](https://github.com/webrtc-sdk/android) `150.7871.01` and [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket) 1.6.0 for the native probe.
