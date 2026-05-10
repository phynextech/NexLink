package com.phynex.NexLink.webrtc

import android.content.Context
import android.util.Log
import com.phynex.NexLink.websocket.NexLinkSocketClient
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.PeerConnection.*

class WebRtcManager(
    private val context: Context,
    private val socketClient: NexLinkSocketClient
) {
    private val TAG = "WebRtcManager"

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    val eglBase: EglBase = EglBase.create()
    
    private var videoTrack: VideoTrack? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    init {
        initializePeerConnectionFactory()
    }

    private fun initializePeerConnectionFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext, true, true
        )
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(
            eglBase.eglBaseContext
        )

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .createPeerConnectionFactory()
    }

    fun setRemoteRenderer(renderer: SurfaceViewRenderer) {
        remoteRenderer = renderer
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setEnableHardwareScaler(true)
        renderer.setMirror(false)
        videoTrack?.addSink(renderer)
    }

    private fun createPeerConnection() {
        if (peerConnectionFactory == null) initializePeerConnectionFactory()

        val rtcConfig = RTCConfiguration(
            listOf(
                IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
        )
        rtcConfig.sdpSemantics = SdpSemantics.UNIFIED_PLAN

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: SignalingState?) {
                Log.d(TAG, "SignalingState: $newState")
            }

            override fun onIceConnectionChange(newState: IceConnectionState?) {
                Log.d(TAG, "IceConnectionState: $newState")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(newState: IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    val payload = JSONObject().apply {
                        put("candidate", candidate.sdp)
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                    }
                    socketClient.sendRaw("webrtc_ice", payload)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {}

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(dataChannel: DataChannel?) {
                Log.d(TAG, "DataChannel received: ${dataChannel?.label()}")
            }

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    Log.d(TAG, "Received remote VideoTrack")
                    videoTrack = track
                    remoteRenderer?.let { track.addSink(it) }
                }
            }
        })
    }

    fun handleOffer(sdp: String) {
        if (peerConnection == null) createPeerConnection()

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        peerConnection?.setLocalDescription(this, desc)
                        val payload = JSONObject().apply {
                            put("sdp", desc?.description)
                        }
                        socketClient.sendRaw("webrtc_answer", payload)
                    }

                    override fun onSetSuccess() {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, MediaConstraints())
            }

            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    fun handleAnswer(sdp: String) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun handleIceCandidate(candidateStr: String) {
        // If candidate comes as JSON string, we should parse it. 
        // For now assuming the Windows side sends a serialized RTCIceCandidateInit
        try {
            val json = JSONObject(candidateStr)
            val candidate = IceCandidate(
                json.optString("sdpMid", "0"),
                json.optInt("sdpMLineIndex", 0),
                json.optString("candidate", candidateStr)
            )
            peerConnection?.addIceCandidate(candidate)
        } catch (e: Exception) {
            // fallback if it's just a raw candidate string
            val candidate = IceCandidate("0", 0, candidateStr)
            peerConnection?.addIceCandidate(candidate)
        }
    }

    fun dispose() {
        remoteRenderer?.release()
        peerConnection?.dispose()
        peerConnectionFactory?.dispose()
        eglBase.release()
    }
}
