package com.beautifulquran.peerjsprobe

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.widget.LinearLayout
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import org.json.JSONArray
import org.webrtc.*
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.util.UUID

/** Isolated, receive-only PeerJS JSON interoperability probe; never touches Quran app data. */
class ProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val id by lazy { intent.getStringExtra("peerId") ?: "bq-android-${UUID.randomUUID().toString().take(8)}" }
    private lateinit var output: TextView
    private lateinit var factory: PeerConnectionFactory
    private lateinit var socket: WebSocketClient
    private var connection: PeerConnection? = null
    private var channel: DataChannel? = null
    private var remoteId = ""
    private var connectionId = ""
    private var remoteReady = false
    private val candidates = mutableListOf<IceCandidate>()
    private val seen = linkedSetOf<String>()
    private var extraServers = "[]"
    private val heartbeat = object : Runnable {
        override fun run() {
            if (socket.isOpen) socket.send("{\"type\":\"HEARTBEAT\"}")
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(41, 43, 40))
            setPadding(32, 50, 32, 32)
            setTextIsSelectable(true)
        }
        extraServers = intent.getStringExtra("iceServers") ?: "[]"
        val relayUrl = EditText(this).apply {
            hint = "Optional test relay settings URL"
            textSize = 16f
            setSingleLine(true)
            setBackgroundColor(Color.TRANSPARENT)
        }
        val loadRelay = TextView(this).apply {
            text = "Load relay settings"
            textSize = 16f
            setTextColor(Color.rgb(82, 98, 77))
            setPadding(0, 16, 0, 16)
            setOnClickListener { loadRelay(relayUrl.text.toString().trim()) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 40, 24, 0)
            setBackgroundColor(Color.rgb(250, 248, 241))
            addView(relayUrl); addView(loadRelay)
            addView(ScrollView(this@ProbeActivity).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        line("PeerJS native Android trial\n\nPeer ID: $id\nSynthetic samples only; receipts mean memory delivery.\n")
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions())
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val broker = intent.getStringExtra("broker") ?: "wss://0.peerjs.com/peerjs?key=peerjs"
        line("Broker: ${broker.substringBefore('?')}")
        socket = object : WebSocketClient(URI("$broker&id=$id&token=${UUID.randomUUID()}&version=1.5.5")) {
            override fun onOpen(handshake: ServerHandshake) { handler.post(heartbeat) }
            override fun onMessage(text: String) { handler.post { handleSignal(text) } }
            override fun onClose(code: Int, reason: String, remote: Boolean) { line("Signaling closed: $code $reason"); handler.removeCallbacks(heartbeat) }
            override fun onError(error: Exception) { line("Signaling error: ${error.message}") }
        }
        socket.connect()
    }

    private fun line(text: String) {
        Log.i("PeerJsProbe", text)
        handler.post { output.append("$text\n") }
    }

    private fun loadRelay(url: String) {
        if (url.isEmpty()) return line("Enter the lab’s /ice-config URL")
        Thread {
            try {
                val request = URL(url).openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }
                val config = request.getInputStream().bufferedReader().use { it.readText() }
                val servers = JSONObject(config).getJSONArray("iceServers").toString()
                handler.post { extraServers = servers; line("Relay settings loaded · connect again from the browser") }
            } catch (error: Exception) { line("Relay settings error: ${error.message}") }
        }.start()
    }

    private fun handleSignal(text: String) {
        try {
            val message = JSONObject(text)
            val payload = message.optJSONObject("payload")
            when (message.getString("type")) {
                "OPEN" -> line("Ready · connect from the browser")
                "OFFER" -> {
                    if (payload?.optString("type") != "data" || payload.optString("serialization") != "json") {
                        line("Only PeerJS JSON data offers are supported")
                        return
                    }
                    answer(message.getString("src"), payload)
                }
                "CANDIDATE" -> if (payload?.optString("connectionId") == connectionId && message.optString("src") == remoteId) {
                    val candidate = payload.getJSONObject("candidate")
                    val ice = IceCandidate(candidate.optString("sdpMid"), candidate.getInt("sdpMLineIndex"), candidate.getString("candidate"))
                    if (remoteReady) connection?.addIceCandidate(ice) else candidates.add(ice)
                }
                "ERROR", "ID-TAKEN", "EXPIRE", "LEAVE" -> line("Broker: $text")
            }
        } catch (error: Exception) { line("Protocol error: ${error.message}") }
    }

    private fun signal(type: String, payload: JSONObject) {
        payload.put("type", "data").put("connectionId", connectionId)
        if (socket.isOpen) socket.send(JSONObject().put("type", type).put("dst", remoteId).put("payload", payload).toString())
    }

    private fun answer(peerId: String, offer: JSONObject) {
        closeConnection()
        remoteId = peerId
        connectionId = offer.getString("connectionId")
        line("Offer from $remoteId")
        val servers = mutableListOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        if (intent.getStringExtra("turn") == "peerjs") servers.add(PeerConnection.IceServer
            .builder(listOf("turn:eu-0.turn.peerjs.com:3478", "turn:us-0.turn.peerjs.com:3478"))
            .setUsername("peerjs").setPassword("peerjsp").createIceServer())
        run {
            val array = JSONArray(extraServers)
            for (i in 0 until array.length()) {
                val server = array.getJSONObject(i)
                servers.add(PeerConnection.IceServer.builder(server.getString("urls"))
                    .setUsername(server.optString("username")).setPassword(server.optString("credential")).createIceServer())
            }
        }
        val config = PeerConnection.RTCConfiguration(servers)
        if (intent.getBooleanExtra("relay", false)) config.iceTransportsType = PeerConnection.IceTransportsType.RELAY
        val pc = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) { line("ICE: $state") }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onIceCandidate(candidate: IceCandidate) { handler.post {
                signal("CANDIDATE", JSONObject().put("candidate", JSONObject()
                    .put("candidate", candidate.sdp).put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex)))
            } }
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onDataChannel(dataChannel: DataChannel) { handler.post { observe(dataChannel) } }
        }) ?: return line("Could not create WebRTC connection")
        connection = pc
        pc.setRemoteDescription(observer(set = {
            remoteReady = true
            candidates.forEach { pc.addIceCandidate(it) }; candidates.clear()
            pc.createAnswer(observer(create = { sdp ->
                pc.setLocalDescription(observer(set = {
                    signal("ANSWER", JSONObject().put("sdp", JSONObject().put("type", "answer").put("sdp", sdp.description)))
                }), sdp)
            }), MediaConstraints())
        }), SessionDescription(SessionDescription.Type.OFFER, offer.getJSONObject("sdp").getString("sdp")))
    }

    private fun observe(dataChannel: DataChannel) {
        channel = dataChannel
        dataChannel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() { line("Data channel: ${dataChannel.state()}") }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                handler.post {
                    try {
                        val message = JSONObject(String(bytes, Charsets.UTF_8))
                        if (message.has("__peerData")) { closeConnection(); return@post }
                        val payload = message.optJSONObject("payload") ?: return@post
                        if (message.optInt("version") != 1 || payload.optString("kind") !in listOf("personal", "handoff")) return@post
                        val messageId = message.getString("id")
                        if (seen.add(messageId)) {
                            if (seen.size > 1000) seen.remove(seen.first())
                            line("Received ${payload.getString("kind")}: $payload")
                        } else line("Duplicate suppressed: $messageId")
                        val receipt = JSONObject().put("version", 1).put("kind", "receipt").put("id", messageId)
                            .put("receiver", "native-android").put("accepted", "memory-only").toString().toByteArray(Charsets.UTF_8)
                        // PeerJS 1.5.5 JSON mode uses binary UTF-8 frames, not WebSocket text frames.
                        if (!dataChannel.send(DataChannel.Buffer(ByteBuffer.wrap(receipt), true))) line("Receipt send failed")
                    } catch (error: Exception) { line("Data error: ${error.message}") }
                }
            }
        })
        line("Data channel: ${dataChannel.state()}")
    }

    private fun observer(create: (SessionDescription) -> Unit = {}, set: () -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) { handler.post { create(sdp) } }
        override fun onSetSuccess() { handler.post { set() } }
        override fun onCreateFailure(error: String) { line("SDP create failed: $error") }
        override fun onSetFailure(error: String) { line("SDP set failed: $error") }
    }

    private fun closeConnection() {
        channel?.unregisterObserver(); channel?.close(); channel?.dispose(); channel = null
        connection?.close(); connection?.dispose(); connection = null
        remoteReady = false; candidates.clear()
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        socket.close()
        closeConnection()
        factory.dispose()
        super.onDestroy()
    }
}
