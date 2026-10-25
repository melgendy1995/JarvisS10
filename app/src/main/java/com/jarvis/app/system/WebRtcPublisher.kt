package com.jarvis.app.system

import android.content.Context
import android.util.Log
import android.view.Surface
import androidx.camera.core.Preview
import com.jarvis.core.camera.CameraProfile
import com.jarvis.core.camera.normalizeSdp
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription
import org.webrtc.SdpObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Video-only WebRTC. No audio track, so the wake-word microphone stays with the other service.
 * ICE uses host candidates only: no STUN and no TURN. On one LAN that is enough.
 * Tailscale's 100.64.0.0/10 address is also a host candidate, so a viewer on the
 * tailnet connects without a relay. A viewer that is on neither LAN nor Tailscale will not.
 */
internal class WebRtcPublisher(context: Context) {
    private val app = context.applicationContext
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "JarvisWebRtc")
    }
    private val ready = CompletableFuture<Boolean>()
    private val peers = ConcurrentLinkedDeque<PeerConnection>()
    private val liveness = PeerLiveness<PeerConnection>(
        nowMillis = { System.currentTimeMillis() },
        graceMillis = DISCONNECT_GRACE_MS,
    )
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var helper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    /** Peer count for the camera auto-shutdown; only set ever gets one assignment before peers exist. */
    @Volatile
    var onPeersChanged: ((Int) -> Unit)? = null

    @Volatile
    var surfaceProvider: Preview.SurfaceProvider? = null
        private set

    init {
        executor.execute {
            try {
                initOnThread()
                ready.complete(true)
            } catch (error: Throwable) {
                Log.w(TAG, "webrtc init failed: ${error.javaClass.simpleName}")
                ready.complete(false)
            }
        }
    }

    fun awaitReady(timeoutMs: Long): Boolean {
        return try {
            ready.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            false
        }
    }

    fun adapt(profile: CameraProfile) {
        executor.execute {
            videoSource?.adaptOutputFormat(profile.width, profile.height, profile.fpsMax)
        }
    }

    fun setRotation(degrees: Int) {
        val capture = helper ?: return
        capture.handler.post { capture.setFrameRotation(degrees) }
    }

    fun answer(offerSdp: String): String? {
        val future = CompletableFuture<String?>()
        executor.execute {
            try {
                future.complete(answerOnThread(offerSdp))
            } catch (error: Throwable) {
                Log.w(TAG, "webrtc answer failed: ${error.javaClass.simpleName}")
                future.complete(null)
            }
        }
        return try {
            future.get(8, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            Log.w(TAG, "webrtc answer timed out")
            null
        } catch (_: Throwable) {
            null
        }
    }

    fun dispose() {
        val done = CompletableFuture<Unit>()
        executor.execute {
            peers.forEach { peer -> runCatching { peer.close() } }
            peers.forEach { peer -> runCatching { peer.dispose() } }
            peers.clear()
            notifyPeers()
            runCatching { videoTrack?.dispose() }
            runCatching { videoSource?.dispose() }
            runCatching { helper?.stopListening() }
            runCatching { helper?.dispose() }
            runCatching { factory?.dispose() }
            runCatching { eglBase?.release() }
            videoTrack = null
            videoSource = null
            helper = null
            factory = null
            eglBase = null
            done.complete(Unit)
        }
        runCatching { done.get(2, TimeUnit.SECONDS) }
        executor.shutdownNow()
    }

    private fun initOnThread() {
        ensureFactoryInit(app)
        val egl = EglBase.create()
        eglBase = egl
        val created = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        factory = created
        val capture = SurfaceTextureHelper.create("JarvisCapture", egl.eglBaseContext)
            ?: throw IllegalStateException("surface texture")
        helper = capture
        val source = created.createVideoSource(false)
        videoSource = source
        source.capturerObserver.onCapturerStarted(true)
        capture.startListening { frame ->
            source.capturerObserver.onFrameCaptured(frame)
        }
        val track = created.createVideoTrack("jarvis-camera", source)
        track.setEnabled(true)
        videoTrack = track
        surfaceProvider = providerFor(capture)
        Log.i(TAG, "webrtc video only, ice=host-only, no stun")
    }

    private fun providerFor(capture: SurfaceTextureHelper): Preview.SurfaceProvider {
        return Preview.SurfaceProvider { request ->
            capture.handler.post {
                try {
                    val size = request.resolution
                    capture.setTextureSize(size.width, size.height)
                    val texture = capture.surfaceTexture
                    texture.setDefaultBufferSize(size.width, size.height)
                    val surface = Surface(texture)
                    request.provideSurface(surface, executor) {
                        surface.release()
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "preview surface failed: ${error.javaClass.simpleName}")
                    runCatching { request.willNotProvideSurface() }
                }
            }
        }
    }

    private fun answerOnThread(offerSdp: String): String? {
        val created = factory ?: return null
        val track = videoTrack ?: return null
        val normalized = normalizeSdp(offerSdp)
        if (!normalized.contains("m=video")) {
            Log.w(TAG, "webrtc offer has no video")
            return null
        }
        val candidates = ArrayList<IceCandidate>()
        val gathering = CompletableFuture<Unit>()
        // The observer needs the peer for liveness bookkeeping; ICE events can only arrive
        // after createPeerConnection returns sinks it, so a holder is enough.
        val holder = arrayOfNulls<PeerConnection>(1)
        val peer = created.createPeerConnection(rtcConfig(), observer(candidates, gathering) { state ->
            holder[0]?.let { onIceState(it, state) }
        }) ?: return null
        holder[0] = peer
        peers.add(peer)
        while (peers.size > 3) {
            val old = peers.pollFirst() ?: break
            drop(old)
        }
        peer.addTrack(track, listOf("jarvis"))
        notifyPeers()
        val offer = SessionDescription(SessionDescription.Type.OFFER, normalized)
        if (!setDescription(peer, offer, remote = true)) {
            drop(peer)
            return null
        }
        val answer = createAnswer(peer) ?: run {
            drop(peer)
            return null
        }
        if (!setDescription(peer, answer, remote = false)) {
            drop(peer)
            return null
        }
        try {
            gathering.get(4, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            Log.i(TAG, "webrtc gather timeout candidates=${candidates.size}")
        }
        val local = peer.localDescription?.description ?: answer.description
        val sdp = withCandidates(local, candidates)
        val host = sdp.split("typ host").size - 1
        Log.i(TAG, "webrtc answer bytes=${sdp.length} hostCandidates=$host")
        return sdp
    }

    private fun observer(
        candidates: MutableList<IceCandidate>,
        gathering: CompletableFuture<Unit>,
        onIce: (PeerConnection.IceConnectionState?) -> Unit,
    ): PeerConnection.Observer {
        return object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                try {
                    Log.i(TAG, "webrtc ice=$newState")
                    onIce(newState)
                } catch (error: Throwable) {
                    Log.w(TAG, "webrtc ice change handling failed: ${error.javaClass.simpleName}")
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                runCatching {
                    if (newState == PeerConnection.IceGatheringState.COMPLETE && !gathering.isDone) {
                        gathering.complete(Unit)
                    }
                }
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                runCatching {
                    if (candidate != null) candidates.add(candidate)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

            override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit

            override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit

            override fun onDataChannel(dataChannel: org.webrtc.DataChannel?) = Unit

            override fun onRenegotiationNeeded() = Unit

            override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) = Unit

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                Log.i(TAG, "webrtc connection=$newState")
            }
        }
    }

    private fun rtcConfig(): PeerConnection.RTCConfiguration {
        return PeerConnection.RTCConfiguration(emptyList<PeerConnection.IceServer>()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
    }

    private fun setDescription(peer: PeerConnection, description: SessionDescription, remote: Boolean): Boolean {
        val done = CompletableFuture<Boolean>()
        val callback = object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) = Unit
            override fun onSetSuccess() {
                if (!done.isDone) done.complete(true)
            }
            override fun onCreateFailure(error: String?) {
                if (!done.isDone) done.complete(false)
            }
            override fun onSetFailure(error: String?) {
                Log.w(TAG, "webrtc set description failed")
                if (!done.isDone) done.complete(false)
            }
        }
        if (remote) peer.setRemoteDescription(callback, description) else peer.setLocalDescription(callback, description)
        return try {
            done.get(5, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            false
        }
    }

    private fun createAnswer(peer: PeerConnection): SessionDescription? {
        val done = CompletableFuture<SessionDescription?>()
        peer.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                if (!done.isDone) done.complete(sessionDescription)
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) {
                Log.w(TAG, "webrtc create answer failed")
                if (!done.isDone) done.complete(null)
            }
            override fun onSetFailure(error: String?) = Unit
        }, MediaConstraints())
        return try {
            done.get(5, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            null
        }
    }

    private fun withCandidates(sdp: String, candidates: List<IceCandidate>): String {
        val lines = normalizeSdp(sdp).trimEnd().lines().toMutableList()
        val have = lines.filter { it.startsWith("a=candidate:") }.toSet()
        val extra = candidates.map { candidate ->
            val body = candidate.sdp.trim()
            if (body.startsWith("a=")) body else "a=$body"
        }.filter { it.startsWith("a=candidate:") && it !in have }
        if (extra.isNotEmpty()) {
            val at = lines.indexOfLast { it == "a=end-of-candidates" }.let { index ->
                if (index >= 0) index else lines.size
            }
            lines.addAll(at, extra)
        }
        if (lines.none { it == "a=end-of-candidates" }) lines.add("a=end-of-candidates")
        return lines.joinToString("\r\n") + "\r\n"
    }

    /**
     * ICE state bookkeeping. Runs on the WebRTC signaling thread: it must never touch
     * `PeerConnection.close()`/dispose() or any close-triggered callback, because natives
     * fire onIceConnectionChange(CLOSED) synchronously during close(), and re-entrant
     * close/dispose from here is what recursed to StackOverflowError. It only observes
     * liveness and posts the actual drops onto [executor].
     */
    private fun onIceState(peer: PeerConnection, state: PeerConnection.IceConnectionState?) {
        when (liveness.onIceState(peer, toIceState(state))) {
            PeerLiveness.Verdict.IGNORE -> Unit
            PeerLiveness.Verdict.DROP_NOW -> postDrop(peer)
            PeerLiveness.Verdict.DROP_LATER -> scheduleGraceCheck(peer)
        }
    }

    /** DISCONNECTED gets a grace window; after it, drop only if the state never recovered. */
    private fun scheduleGraceCheck(peer: PeerConnection) {
        try {
            executor.schedule({
                if (liveness.stillFailing(peer)) drop(peer)
            }, liveness.graceMillis, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            Log.i(TAG, "webrtc grace check skipped: executor stopped")
        }
    }

    private fun toIceState(state: PeerConnection.IceConnectionState?): PeerLiveness.IceState {
        return when (state) {
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED,
            -> PeerLiveness.IceState.CONNECTED
            PeerConnection.IceConnectionState.DISCONNECTED -> PeerLiveness.IceState.DISCONNECTED
            PeerConnection.IceConnectionState.FAILED -> PeerLiveness.IceState.FAILED
            PeerConnection.IceConnectionState.CLOSED -> PeerLiveness.IceState.CLOSED
            else -> PeerLiveness.IceState.OTHER
        }
    }

    private fun postDrop(peer: PeerConnection) {
        try {
            executor.execute { drop(peer) }
        } catch (_: RejectedExecutionException) {
            Log.i(TAG, "webrtc drop skipped: executor stopped")
        }
    }

    /** Only ever runs on [executor]. Closes at most once per peer, and only while tracked. */
    private fun drop(peer: PeerConnection) {
        if (!liveness.claimDrop(peer)) return
        if (!peers.remove(peer)) return
        runCatching { peer.close() }
        runCatching { peer.dispose() }
        notifyPeers()
    }

    /** Dead peers stop counting as viewers so the camera can idle out. Must not throw into JNI. */
    private fun notifyPeers() {
        try {
            onPeersChanged?.invoke(peers.size)
        } catch (error: Throwable) {
            Log.w(TAG, "webrtc peers changed callback failed: ${error.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "JarvisCamera"

        /** How long a DISCONNECTED peer gets to reconnect before it is dropped. */
        private const val DISCONNECT_GRACE_MS = 10_000L

        private val initialized = AtomicBoolean(false)

        private fun ensureFactoryInit(context: Context) {
            if (!initialized.compareAndSet(false, true)) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions(),
            )
        }
    }
}
