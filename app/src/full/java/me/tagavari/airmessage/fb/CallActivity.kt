package me.tagavari.airmessage.fb

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import me.tagavari.airmessage.R
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Voice / video call. Media is peer-to-peer WebRTC; the offer, answer and ICE candidates are
 * exchanged through Firebase Realtime Database under calls/{callId}.
 */
class CallActivity : AppCompatActivity() {
	companion object {
		private const val EXTRA_PEER_UID = "peerUid"
		private const val EXTRA_PEER_NAME = "peerName"
		private const val EXTRA_VIDEO = "video"
		private const val EXTRA_CALL_ID = "callId"
		private const val RING_TIMEOUT_MS = 45_000L
		
		fun outgoing(context: Context, peerUid: String, peerName: String, video: Boolean) =
			Intent(context, CallActivity::class.java)
				.putExtra(EXTRA_PEER_UID, peerUid).putExtra(EXTRA_PEER_NAME, peerName).putExtra(EXTRA_VIDEO, video)
		
		fun incoming(context: Context, callId: String, peerUid: String, peerName: String, video: Boolean) =
			outgoing(context, peerUid, peerName, video).putExtra(EXTRA_CALL_ID, callId)
	}
	
	private lateinit var peerUid: String
	private var peerName = ""
	private var video = false
	private var isCaller = true
	private var callId = ""
	
	private lateinit var viewRemote: SurfaceViewRenderer
	private lateinit var viewLocal: SurfaceViewRenderer
	private lateinit var textStatus: TextView
	
	private val handler = Handler(Looper.getMainLooper())
	private var started = false
	private var ended = false
	private var remoteDescriptionSet = false
	private val pendingCandidates = ArrayList<IceCandidate>()
	
	private var egl: EglBase? = null
	private var factory: PeerConnectionFactory? = null
	private var pc: PeerConnection? = null
	private var capturer: CameraVideoCapturer? = null
	private var surfaceHelper: SurfaceTextureHelper? = null
	private var videoSource: VideoSource? = null
	private var videoTrack: VideoTrack? = null
	private var audioSource: AudioSource? = null
	private var audioTrack: AudioTrack? = null
	
	private lateinit var callRef: DatabaseReference
	private val valueListeners = ArrayList<Pair<DatabaseReference, ValueEventListener>>()
	private val childListeners = ArrayList<Pair<Query, ChildEventListener>>()
	
	private var audioManager: AudioManager? = null
	private var previousAudioMode = AudioManager.MODE_NORMAL
	private var muted = false
	private var cameraOff = false
	
	private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
		if(result.values.all { it }) startCall() else {
			Toast.makeText(this, R.string.call_permission_denied, Toast.LENGTH_LONG).show()
			finish()
		}
	}
	
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		peerUid = intent.getStringExtra(EXTRA_PEER_UID) ?: return finish()
		peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "?"
		video = intent.getBooleanExtra(EXTRA_VIDEO, false)
		callId = intent.getStringExtra(EXTRA_CALL_ID) ?: ""
		isCaller = callId.isEmpty()
		
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		setContentView(R.layout.activity_call)
		
		viewRemote = findViewById(R.id.view_remote)
		viewLocal = findViewById(R.id.view_local)
		textStatus = findViewById(R.id.text_call_status)
		findViewById<TextView>(R.id.text_peer).text = peerName
		textStatus.setText(if(isCaller) R.string.call_calling else R.string.call_connecting)
		
		val buttonCamera = findViewById<FloatingActionButton>(R.id.button_camera)
		val buttonFlip = findViewById<FloatingActionButton>(R.id.button_flip)
		if(!video) {
			buttonCamera.visibility = View.GONE
			buttonFlip.visibility = View.GONE
			viewLocal.visibility = View.GONE
		}
		findViewById<FloatingActionButton>(R.id.button_hangup).setOnClickListener { endCall(true) }
		findViewById<FloatingActionButton>(R.id.button_mute).setOnClickListener {
			muted = !muted
			audioTrack?.setEnabled(!muted)
			it.alpha = if(muted) 0.5f else 1f
		}
		buttonCamera.setOnClickListener {
			cameraOff = !cameraOff
			videoTrack?.setEnabled(!cameraOff)
			viewLocal.visibility = if(cameraOff) View.INVISIBLE else View.VISIBLE
			it.alpha = if(cameraOff) 0.5f else 1f
		}
		buttonFlip.setOnClickListener { capturer?.switchCamera(null) }
		
		val needed = ArrayList<String>()
		needed.add(Manifest.permission.RECORD_AUDIO)
		if(video) needed.add(Manifest.permission.CAMERA)
		if(needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startCall()
		else permissionLauncher.launch(needed.toTypedArray())
	}
	
	override fun onDestroy() {
		super.onDestroy()
		if(!ended) endCall(true) else release()
	}
	
	@Deprecated("Deprecated in Java")
	override fun onBackPressed() {
		endCall(true)
	}
	
	//---- Setup ----
	
	private fun startCall() {
		if(started) return
		started = true
		
		audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
		previousAudioMode = audioManager!!.mode
		audioManager!!.mode = AudioManager.MODE_IN_COMMUNICATION
		audioManager!!.isSpeakerphoneOn = video
		
		initFactory()
		createLocalTracks()
		createPeerConnection()
		if(isCaller) placeCall() else answerCall()
	}
	
	private fun initFactory() {
		PeerConnectionFactory.initialize(
			PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions()
		)
		val eglBase = EglBase.create().also { egl = it }
		factory = PeerConnectionFactory.builder()
			.setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
			.setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
			.createPeerConnectionFactory()
		
		viewRemote.init(eglBase.eglBaseContext, null)
		viewRemote.setEnableHardwareScaler(true)
		viewLocal.init(eglBase.eglBaseContext, null)
		viewLocal.setEnableHardwareScaler(true)
		viewLocal.setMirror(true)
		viewLocal.setZOrderMediaOverlay(true)
	}
	
	private fun createLocalTracks() {
		val f = factory!!
		audioSource = f.createAudioSource(MediaConstraints())
		audioTrack = f.createAudioTrack("audio0", audioSource)
		
		if(video) {
			val cap = createCapturer() ?: run {
				Toast.makeText(this, "Kamera tidak ditemukan", Toast.LENGTH_LONG).show()
				return
			}
			capturer = cap
			surfaceHelper = SurfaceTextureHelper.create("CaptureThread", egl!!.eglBaseContext)
			videoSource = f.createVideoSource(cap.isScreencast)
			cap.initialize(surfaceHelper, this, videoSource!!.capturerObserver)
			cap.startCapture(960, 540, 30)
			videoTrack = f.createVideoTrack("video0", videoSource)
			videoTrack!!.addSink(viewLocal)
		}
	}
	
	private fun createCapturer(): CameraVideoCapturer? {
		val enumerator = Camera2Enumerator(this)
		val names = enumerator.deviceNames
		val front = names.firstOrNull { enumerator.isFrontFacing(it) }
		val chosen = front ?: names.firstOrNull() ?: return null
		return enumerator.createCapturer(chosen, null)
	}
	
	private fun createPeerConnection() {
		val servers = listOf(
			PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
			PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
			//Add your own TURN server here for users behind strict NATs
		)
		val config = PeerConnection.RTCConfiguration(servers).apply {
			sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
		}
		pc = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
			override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
			override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
			override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
			override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
			override fun onAddStream(stream: MediaStream?) = Unit
			override fun onRemoveStream(stream: MediaStream?) = Unit
			override fun onDataChannel(channel: DataChannel?) = Unit
			override fun onRenegotiationNeeded() = Unit
			
			override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
				runOnUiThread {
					when(state) {
						PeerConnection.IceConnectionState.CONNECTED,
						PeerConnection.IceConnectionState.COMPLETED -> textStatus.setText(R.string.call_connected)
						PeerConnection.IceConnectionState.FAILED -> endCall(true)
						else -> Unit
					}
				}
			}
			
			override fun onIceCandidate(candidate: IceCandidate?) {
				candidate ?: return
				val node = if(isCaller) "callerCandidates" else "calleeCandidates"
				callRef.child(node).push().setValue(mapOf(
					"sdpMid" to candidate.sdpMid,
					"sdpMLineIndex" to candidate.sdpMLineIndex,
					"candidate" to candidate.sdp
				))
			}
			
			override fun onTrack(transceiver: RtpTransceiver?) {
				val track = transceiver?.receiver?.track()
				if(track is VideoTrack) track.addSink(viewRemote)
			}
		})
		
		audioTrack?.let { pc?.addTrack(it, listOf("stream0")) }
		videoTrack?.let { pc?.addTrack(it, listOf("stream0")) }
	}
	
	//---- Signalling ----
	
	private fun placeCall() {
		callId = Fb.db.getReference("calls").push().key ?: return finish()
		callRef = Fb.db.getReference("calls/$callId")
		callRef.child("status").onDisconnect().setValue("ended")
		callRef.updateChildren(mapOf(
			"caller" to Fb.uid, "callerName" to Fb.name, "callee" to peerUid,
			"video" to video, "status" to "ringing", "ts" to ServerValue.TIMESTAMP
		))
		Fb.db.getReference("incomingCalls/$peerUid/$callId").setValue(mapOf(
			"from" to Fb.uid, "fromName" to Fb.name, "video" to video, "ts" to ServerValue.TIMESTAMP
		))
		
		pc!!.createOffer(object : SimpleSdp() {
			override fun onCreateSuccess(sdp: SessionDescription?) {
				sdp ?: return
				pc?.setLocalDescription(SimpleSdp(), sdp)
				callRef.child("offer").setValue(mapOf("type" to sdp.type.canonicalForm(), "sdp" to sdp.description))
			}
		}, MediaConstraints())
		
		//Wait for the answer
		listenValue(callRef.child("answer")) { snap ->
			if(!snap.exists() || remoteDescriptionSet) return@listenValue
			val type = snap.child("type").getValue(String::class.java) ?: return@listenValue
			val sdp = snap.child("sdp").getValue(String::class.java) ?: return@listenValue
			setRemote(SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp), null)
		}
		listenCandidates("calleeCandidates")
		listenStatus()
		
		handler.postDelayed({
			if(!ended && textStatus.text.toString() == getString(R.string.call_calling)) {
				Toast.makeText(this, R.string.call_no_answer, Toast.LENGTH_SHORT).show()
				endCall(true)
			}
		}, RING_TIMEOUT_MS)
	}
	
	private fun answerCall() {
		callRef = Fb.db.getReference("calls/$callId")
		Fb.db.getReference("incomingCalls/${Fb.uid}/$callId").removeValue()
		callRef.child("status").onDisconnect().setValue("ended")
		
		listenValue(callRef.child("offer")) { snap ->
			if(!snap.exists() || remoteDescriptionSet) return@listenValue
			val type = snap.child("type").getValue(String::class.java) ?: return@listenValue
			val sdp = snap.child("sdp").getValue(String::class.java) ?: return@listenValue
			setRemote(SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)) {
				pc?.createAnswer(object : SimpleSdp() {
					override fun onCreateSuccess(answer: SessionDescription?) {
						answer ?: return
						pc?.setLocalDescription(SimpleSdp(), answer)
						callRef.child("answer").setValue(mapOf("type" to answer.type.canonicalForm(), "sdp" to answer.description))
						callRef.child("status").setValue("accepted")
					}
				}, MediaConstraints())
			}
		}
		listenCandidates("callerCandidates")
		listenStatus()
	}
	
	private fun setRemote(desc: SessionDescription, afterSet: (() -> Unit)?) {
		remoteDescriptionSet = true
		pc?.setRemoteDescription(object : SimpleSdp() {
			override fun onSetSuccess() {
				runOnUiThread {
					pendingCandidates.forEach { pc?.addIceCandidate(it) }
					pendingCandidates.clear()
				}
				afterSet?.invoke()
			}
		}, desc)
	}
	
	private fun listenCandidates(node: String) {
		val q = callRef.child(node)
		val l = object : ChildEventListener {
			override fun onChildAdded(snap: DataSnapshot, previousChildName: String?) {
				val mid = snap.child("sdpMid").getValue(String::class.java)
				val index = snap.child("sdpMLineIndex").getValue(Int::class.java) ?: 0
				val sdp = snap.child("candidate").getValue(String::class.java) ?: return
				val candidate = IceCandidate(mid, index, sdp)
				if(remoteDescriptionSet) pc?.addIceCandidate(candidate) else pendingCandidates.add(candidate)
			}
			
			override fun onChildChanged(snap: DataSnapshot, previousChildName: String?) = Unit
			override fun onChildRemoved(snap: DataSnapshot) = Unit
			override fun onChildMoved(snap: DataSnapshot, previousChildName: String?) = Unit
			override fun onCancelled(error: DatabaseError) = Unit
		}
		q.addChildEventListener(l)
		childListeners.add(q to l)
	}
	
	private fun listenStatus() {
		listenValue(callRef.child("status")) { snap ->
			when(snap.getValue(String::class.java)) {
				"accepted" -> if(isCaller) textStatus.setText(R.string.call_connecting)
				"declined" -> {
					Toast.makeText(this, R.string.call_declined, Toast.LENGTH_SHORT).show()
					endCall(false)
				}
				"ended" -> {
					Toast.makeText(this, R.string.call_ended, Toast.LENGTH_SHORT).show()
					endCall(false)
				}
			}
		}
	}
	
	private fun listenValue(ref: DatabaseReference, onData: (DataSnapshot) -> Unit) {
		val l = object : ValueEventListener {
			override fun onDataChange(snapshot: DataSnapshot) = onData(snapshot)
			override fun onCancelled(error: DatabaseError) = Unit
		}
		ref.addValueEventListener(l)
		valueListeners.add(ref to l)
	}
	
	//---- Teardown ----
	
	private fun endCall(notifyPeer: Boolean) {
		if(ended) return
		ended = true
		handler.removeCallbacksAndMessages(null)
		
		if(started) {
			if(notifyPeer) callRef.child("status").setValue("ended")
			callRef.child("status").onDisconnect().cancel()
			if(isCaller) Fb.db.getReference("incomingCalls/$peerUid/$callId").removeValue()
		}
		release()
		finish()
	}
	
	private fun release() {
		valueListeners.forEach { (ref, l) -> ref.removeEventListener(l) }
		valueListeners.clear()
		childListeners.forEach { (q, l) -> q.removeEventListener(l) }
		childListeners.clear()
		
		try { capturer?.stopCapture() } catch(e: Exception) { }
		capturer?.dispose()
		capturer = null
		videoTrack?.removeSink(viewLocal)
		videoTrack?.dispose(); videoTrack = null
		videoSource?.dispose(); videoSource = null
		surfaceHelper?.dispose(); surfaceHelper = null
		audioTrack?.dispose(); audioTrack = null
		audioSource?.dispose(); audioSource = null
		pc?.close(); pc?.dispose(); pc = null
		factory?.dispose(); factory = null
		
		if(started) {
			viewRemote.release()
			viewLocal.release()
		}
		egl?.release(); egl = null
		
		audioManager?.let {
			it.mode = previousAudioMode
			it.isSpeakerphoneOn = false
		}
		audioManager = null
	}
	
	/** SdpObserver with empty defaults */
	private open class SimpleSdp : SdpObserver {
		override fun onCreateSuccess(sdp: SessionDescription?) = Unit
		override fun onSetSuccess() = Unit
		override fun onCreateFailure(error: String?) = Unit
		override fun onSetFailure(error: String?) = Unit
	}
}
