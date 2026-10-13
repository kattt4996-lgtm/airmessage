package me.tagavari.airmessage.fb

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import me.tagavari.airmessage.R
import java.security.SecureRandom

/**
 * Watch party. Playback state lives in Firebase Realtime Database:
 *   rooms/{code}/media   what is being played (url, or the name of a local file)
 *   rooms/{code}/state   playing / position / server timestamp / who changed it
 *   rooms/{code}/members who is here (removed automatically on disconnect)
 *   rooms/{code}/chat    room chat
 * Every device derives the expected position from the Firebase server clock, so late joiners
 * and drift are handled without a dedicated relay server.
 * Local files are never uploaded: everybody picks the same file on their own device.
 */
class WatchParty : AppCompatActivity() {
	private data class RoomState(val playing: Boolean, val pos: Long, val ts: Long, val by: String)
	
	private companion object {
		const val SUPPRESS_MS = 900L //Ignore player events caused by remote commands for this long
		const val DRIFT_LIMIT_MS = 1500L
		const val DRIFT_CHECK_MS = 3000L
	}
	
	//Views
	private lateinit var playerView: StyledPlayerView
	private lateinit var textStatus: TextView
	private lateinit var textRoom: TextView
	private lateinit var groupSetup: View
	private lateinit var groupRoom: View
	private lateinit var inputRoom: TextInputEditText
	private lateinit var inputUrl: TextInputEditText
	private lateinit var chatList: RecyclerView
	private val chatAdapter = MessageAdapter(showSender = true)
	
	//State
	private val handler = Handler(Looper.getMainLooper())
	private var player: ExoPlayer? = null
	private var inRoom = false
	private var roomCode = ""
	private lateinit var roomRef: DatabaseReference
	private var memberCount = 1
	private var suppressUntil = 0L
	
	private var lastState: RoomState? = null
	private var mediaReady = false
	private var loadedMediaTs = 0L
	private var firstMediaDelivered = false
	private var firstStateDelivered = false
	
	//Local file handling
	private var localUri: Uri? = null
	private var localName: String? = null
	private var pendingLocalTitle: String? = null
	private var pendingLocalFrom: String = ""
	private var localDialogShown = false
	
	private val pickLocal = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
		if(uri != null) onLocalFilePicked(uri)
	}
	
	private val driftCheck = object : Runnable {
		override fun run() {
			if(!inRoom) return
			correctDrift()
			handler.postDelayed(this, DRIFT_CHECK_MS)
		}
	}
	
	//---- Firebase listeners ----
	
	private val mediaListener = object : ValueEventListener {
		override fun onDataChange(snap: DataSnapshot) {
			val wasFirst = !firstMediaDelivered
			firstMediaDelivered = true
			if(!snap.exists()) return
			
			val by = snap.child("by").getValue(String::class.java)
			if(by == Fb.uid && !wasFirst) return //Our own change, already applied locally
			
			val ts = snap.child("ts").getValue(Long::class.java) ?: 0L
			if(ts != 0L && ts == loadedMediaTs) return
			loadedMediaTs = ts
			
			val kind = snap.child("kind").getValue(String::class.java)
			val title = snap.child("title").getValue(String::class.java) ?: ""
			if(kind == "url") {
				val url = snap.child("url").getValue(String::class.java) ?: return
				loadUrl(url)
			} else if(kind == "local") {
				val uri = localUri
				if(uri != null && localName == title) {
					playLocal(uri)
				} else {
					mediaReady = false
					pendingLocalTitle = title
					pendingLocalFrom = snap.child("byName").getValue(String::class.java) ?: ""
					promptForLocalFile()
				}
			}
			applyStateIfReady()
		}
		
		override fun onCancelled(error: DatabaseError) = Unit
	}
	
	private val stateListener = object : ValueEventListener {
		override fun onDataChange(snap: DataSnapshot) {
			val wasFirst = !firstStateDelivered
			firstStateDelivered = true
			if(!snap.exists()) return
			
			val by = snap.child("by").getValue(String::class.java) ?: ""
			lastState = RoomState(
				snap.child("playing").getValue(Boolean::class.java) ?: false,
				snap.child("pos").getValue(Long::class.java) ?: 0L,
				snap.child("ts").getValue(Long::class.java) ?: Fb.serverNow(),
				by
			)
			if(by == Fb.uid && !wasFirst) return //Our own change
			applyStateIfReady()
		}
		
		override fun onCancelled(error: DatabaseError) = Unit
	}
	
	private val memberListener = object : ValueEventListener {
		override fun onDataChange(snap: DataSnapshot) {
			memberCount = snap.childrenCount.toInt().coerceAtLeast(1)
			textRoom.text = getString(R.string.watchparty_participants, roomCode, memberCount)
			textStatus.text = snap.children.mapNotNull { it.getValue(String::class.java) }.joinToString(", ")
		}
		
		override fun onCancelled(error: DatabaseError) = Unit
	}
	
	private var chatQuery: Query? = null
	private val chatListener = object : ChildEventListener {
		override fun onChildAdded(snap: DataSnapshot, previousChildName: String?) {
			chatAdapter.add(ChatMessage(
				snap.child("uid").getValue(String::class.java) ?: "",
				snap.child("name").getValue(String::class.java) ?: "",
				snap.child("text").getValue(String::class.java) ?: "",
				snap.child("ts").getValue(Long::class.java) ?: 0L
			))
			chatList.scrollToPosition(chatAdapter.lastIndex)
		}
		
		override fun onChildChanged(snap: DataSnapshot, previousChildName: String?) = Unit
		override fun onChildRemoved(snap: DataSnapshot) = Unit
		override fun onChildMoved(snap: DataSnapshot, previousChildName: String?) = Unit
		override fun onCancelled(error: DatabaseError) = Unit
	}
	
	//---- Lifecycle ----
	
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_watchparty)
		setSupportActionBar(findViewById(R.id.toolbar))
		supportActionBar?.setDisplayHomeAsUpEnabled(true)
		
		playerView = findViewById(R.id.player)
		textStatus = findViewById(R.id.text_status)
		textRoom = findViewById(R.id.text_room)
		groupSetup = findViewById(R.id.group_setup)
		groupRoom = findViewById(R.id.group_room)
		inputRoom = findViewById(R.id.input_room)
		inputUrl = findViewById(R.id.input_url)
		chatList = findViewById(R.id.list_chat)
		chatList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
		chatList.adapter = chatAdapter
		
		findViewById<TextView>(R.id.text_account).text = getString(R.string.watchparty_signed_in_as, Fb.name)
		
		findViewById<Button>(R.id.button_create).setOnClickListener {
			inputRoom.setText(randomCode())
			join()
		}
		findViewById<Button>(R.id.button_join).setOnClickListener { join() }
		findViewById<Button>(R.id.button_leave).setOnClickListener { leave() }
		findViewById<Button>(R.id.button_load_url).setOnClickListener {
			var url = inputUrl.text?.toString()?.trim().orEmpty()
			if(url.isEmpty()) return@setOnClickListener
			if(!url.contains("://")) url = "https://$url"
			loadUrl(url)
			publishMedia("url", url, url, 0)
		}
		findViewById<Button>(R.id.button_pick_local).setOnClickListener { pickLocal.launch(arrayOf("video/*", "audio/*")) }
		textRoom.setOnClickListener {
			if(roomCode.isEmpty()) return@setOnClickListener
			val send = Intent(Intent.ACTION_SEND).setType("text/plain")
				.putExtra(Intent.EXTRA_TEXT, getString(R.string.watchparty_share, roomCode))
			startActivity(Intent.createChooser(send, null))
		}
		
		val chatInput = findViewById<EditText>(R.id.input_chat)
		val sendChat = { sendRoomChat(chatInput) }
		findViewById<ImageButton>(R.id.button_send).setOnClickListener { sendChat() }
		chatInput.setOnEditorActionListener { _, actionId, _ ->
			if(actionId == EditorInfo.IME_ACTION_SEND) {
				sendChat(); true
			} else false
		}
		
		//Player
		val exo = ExoPlayer.Builder(this).build()
		player = exo
		playerView.player = exo
		exo.addListener(object : Player.Listener {
			override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
				if(canSend()) writeState(playWhenReady, exo.currentPosition)
			}
			
			override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
				if(reason == Player.DISCONTINUITY_REASON_SEEK && canSend()) writeState(exo.playWhenReady, newPosition.positionMs)
			}
			
			override fun onPlayerError(error: PlaybackException) {
				Toast.makeText(this@WatchParty, "Gagal memutar: ${error.message}", Toast.LENGTH_LONG).show()
			}
		})
	}
	
	override fun onStop() {
		super.onStop()
		//Pause locally without telling the room
		suppressUntil = SystemClock.elapsedRealtime() + SUPPRESS_MS
		player?.pause()
	}
	
	override fun onDestroy() {
		super.onDestroy()
		if(inRoom) leave()
		handler.removeCallbacksAndMessages(null)
		player?.release()
		player = null
	}
	
	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		if(item.itemId == android.R.id.home) {
			finish()
			return true
		}
		return super.onOptionsItemSelected(item)
	}
	
	@Deprecated("Deprecated in Java")
	override fun onBackPressed() {
		if(inRoom) {
			MaterialAlertDialogBuilder(this)
				.setMessage(R.string.watchparty_leave)
				.setPositiveButton(android.R.string.ok) { _, _ ->
					leave()
					finish()
				}
				.setNegativeButton(android.R.string.cancel, null)
				.show()
		} else super.onBackPressed()
	}
	
	//---- Room ----
	
	private fun join() {
		val code = inputRoom.text?.toString().orEmpty().uppercase().filter { it.isLetterOrDigit() }
		if(code.isEmpty()) {
			Toast.makeText(this, R.string.watchparty_error_room, Toast.LENGTH_SHORT).show()
			return
		}
		roomCode = code
		roomRef = Fb.db.getReference("rooms/$code")
		inRoom = true
		firstMediaDelivered = false
		firstStateDelivered = false
		mediaReady = false
		lastState = null
		loadedMediaTs = 0
		chatAdapter.clear()
		
		groupSetup.visibility = View.GONE
		groupRoom.visibility = View.VISIBLE
		playerView.visibility = View.VISIBLE
		textStatus.visibility = View.VISIBLE
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		textRoom.text = getString(R.string.watchparty_participants, code, 1)
		
		val me = roomRef.child("members/${Fb.uid}")
		me.setValue(Fb.name)
		me.onDisconnect().removeValue()
		
		roomRef.child("media").addValueEventListener(mediaListener)
		roomRef.child("state").addValueEventListener(stateListener)
		roomRef.child("members").addValueEventListener(memberListener)
		chatQuery = roomRef.child("chat").orderByChild("ts").limitToLast(100).also { it.addChildEventListener(chatListener) }
		handler.postDelayed(driftCheck, DRIFT_CHECK_MS)
	}
	
	private fun leave() {
		if(!inRoom) return
		inRoom = false
		handler.removeCallbacks(driftCheck)
		roomRef.child("media").removeEventListener(mediaListener)
		roomRef.child("state").removeEventListener(stateListener)
		roomRef.child("members").removeEventListener(memberListener)
		chatQuery?.removeEventListener(chatListener)
		chatQuery = null
		
		//The last person out removes the room
		if(memberCount <= 1) roomRef.removeValue() else roomRef.child("members/${Fb.uid}").removeValue()
		
		suppressUntil = SystemClock.elapsedRealtime() + SUPPRESS_MS
		player?.stop()
		player?.clearMediaItems()
		pendingLocalTitle = null
		wasWaitingForLocal = false
		localDialogShown = false
		
		groupSetup.visibility = View.VISIBLE
		groupRoom.visibility = View.GONE
		playerView.visibility = View.GONE
		textStatus.visibility = View.GONE
		window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
	}
	
	//---- Writing state ----
	
	private fun canSend() = inRoom && SystemClock.elapsedRealtime() > suppressUntil
	
	private fun writeState(playing: Boolean, pos: Long) {
		roomRef.child("state").setValue(mapOf(
			"playing" to playing, "pos" to pos, "ts" to ServerValue.TIMESTAMP, "by" to Fb.uid
		))
	}
	
	private fun publishMedia(kind: String, url: String, title: String, size: Long) {
		val now = ServerValue.TIMESTAMP
		roomRef.updateChildren(mapOf(
			"media" to mapOf("kind" to kind, "url" to url, "title" to title, "size" to size,
				"by" to Fb.uid, "byName" to Fb.name, "ts" to now),
			"state" to mapOf("playing" to false, "pos" to 0L, "ts" to now, "by" to Fb.uid)
		))
	}
	
	private fun sendRoomChat(input: EditText) {
		val text = input.text.toString().trim()
		if(text.isEmpty() || !inRoom) return
		input.setText("")
		roomRef.child("chat").push().setValue(mapOf(
			"uid" to Fb.uid, "name" to Fb.name, "text" to text, "ts" to ServerValue.TIMESTAMP
		))
	}
	
	//---- Applying remote state ----
	
	private fun expectedPosition(s: RoomState): Long =
		if(s.playing) s.pos + (Fb.serverNow() - s.ts).coerceAtLeast(0) else s.pos
	
	private fun applyStateIfReady() {
		val s = lastState ?: return
		if(!mediaReady) return
		applyPosition(expectedPosition(s), s.playing)
	}
	
	private fun applyPosition(pos: Long, playing: Boolean) {
		val p = player ?: return
		suppressUntil = SystemClock.elapsedRealtime() + SUPPRESS_MS
		if(Math.abs(p.currentPosition - pos) > 400) p.seekTo(pos.coerceAtLeast(0))
		p.playWhenReady = playing
	}
	
	private fun correctDrift() {
		val p = player ?: return
		val s = lastState ?: return
		if(!mediaReady || !s.playing || s.by == Fb.uid) return
		if(p.playbackState == Player.STATE_BUFFERING) return
		val expected = expectedPosition(s)
		if(Math.abs(p.currentPosition - expected) > DRIFT_LIMIT_MS || !p.playWhenReady) applyPosition(expected, true)
	}
	
	//---- Media ----
	
	private fun loadUrl(url: String) {
		val p = player ?: return
		suppressUntil = SystemClock.elapsedRealtime() + SUPPRESS_MS
		p.setMediaItem(MediaItem.fromUri(url))
		p.prepare()
		p.playWhenReady = false
		mediaReady = true
		pendingLocalTitle = null
		wasWaitingForLocal = false
	}
	
	private fun playLocal(uri: Uri) {
		val p = player ?: return
		suppressUntil = SystemClock.elapsedRealtime() + SUPPRESS_MS
		p.setMediaItem(MediaItem.fromUri(uri))
		p.prepare()
		p.playWhenReady = false
		mediaReady = true
		pendingLocalTitle = null
	}
	
	private fun onLocalFilePicked(uri: Uri) {
		localUri = uri
		localName = queryName(uri)
		localDialogShown = false
		playLocal(uri)
		if(wasWaitingForLocal) {
			//Joining a session someone else started: catch up with the room
			wasWaitingForLocal = false
			applyStateIfReady()
		} else {
			publishMedia("local", "", localName ?: "video", querySize(uri))
		}
	}
	
	private var wasWaitingForLocal = false
	
	private fun promptForLocalFile() {
		wasWaitingForLocal = true
		if(localDialogShown) return
		localDialogShown = true
		MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.watchparty_need_local, pendingLocalFrom.ifEmpty { "Peserta lain" }, pendingLocalTitle ?: ""))
			.setPositiveButton(R.string.watchparty_pick_local) { _, _ -> pickLocal.launch(arrayOf("video/*", "audio/*")) }
			.setNegativeButton(android.R.string.cancel) { _, _ -> localDialogShown = false }
			.setOnCancelListener { localDialogShown = false }
			.show()
	}
	
	//---- Helpers ----
	
	private fun randomCode(): String {
		val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
		val random = SecureRandom()
		return (1..5).map { chars[random.nextInt(chars.length)] }.joinToString("")
	}
	
	private fun queryName(uri: Uri): String? = try {
		contentResolver.query(uri, null, null, null, null)?.use { c ->
			val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
			if(c.moveToFirst() && i >= 0) c.getString(i) else null
		} ?: uri.lastPathSegment
	} catch(e: Exception) {
		uri.lastPathSegment
	}
	
	private fun querySize(uri: Uri): Long = try {
		contentResolver.query(uri, null, null, null, null)?.use { c ->
			val i = c.getColumnIndex(OpenableColumns.SIZE)
			if(c.moveToFirst() && i >= 0) c.getLong(i) else 0L
		} ?: 0L
	} catch(e: Exception) {
		0L
	}
}
