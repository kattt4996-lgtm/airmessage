package me.tagavari.airmessage.fb

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
import me.tagavari.airmessage.R

/** One-to-one real-time chat with call buttons */
class ChatRoom : AppCompatActivity() {
	companion object {
		private const val EXTRA_UID = "uid"
		private const val EXTRA_NAME = "name"
		
		fun intent(context: Context, peerUid: String, peerName: String) =
			Intent(context, ChatRoom::class.java).putExtra(EXTRA_UID, peerUid).putExtra(EXTRA_NAME, peerName)
	}
	
	private lateinit var peerUid: String
	private lateinit var peerName: String
	private lateinit var chatId: String
	private lateinit var adapter: MessageAdapter
	private lateinit var list: RecyclerView
	private lateinit var query: Query
	
	private val listener = object : ChildEventListener {
		override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
			adapter.add(ChatMessage(
				snapshot.child("uid").getValue(String::class.java) ?: "",
				snapshot.child("name").getValue(String::class.java) ?: "",
				snapshot.child("text").getValue(String::class.java) ?: "",
				snapshot.child("ts").getValue(Long::class.java) ?: 0L
			))
			list.scrollToPosition(adapter.lastIndex)
		}
		
		override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = Unit
		override fun onChildRemoved(snapshot: DataSnapshot) = Unit
		override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
		override fun onCancelled(error: DatabaseError) = Unit
	}
	
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		peerUid = intent.getStringExtra(EXTRA_UID) ?: return finish()
		peerName = intent.getStringExtra(EXTRA_NAME) ?: "?"
		chatId = Fb.chatId(Fb.uid, peerUid)
		
		setContentView(R.layout.activity_chatroom)
		setSupportActionBar(findViewById(R.id.toolbar))
		supportActionBar?.setDisplayHomeAsUpEnabled(true)
		title = peerName
		
		adapter = MessageAdapter(showSender = false)
		list = findViewById(R.id.list)
		list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
		list.adapter = adapter
		
		val input = findViewById<EditText>(R.id.input_chat)
		val send = { sendMessage(input) }
		findViewById<ImageButton>(R.id.button_send).setOnClickListener { send() }
		input.setOnEditorActionListener { _, actionId, _ ->
			if(actionId == EditorInfo.IME_ACTION_SEND) {
				send(); true
			} else false
		}
		
		query = Fb.db.getReference("chats/$chatId/messages").orderByChild("ts").limitToLast(200)
	}
	
	override fun onStart() {
		super.onStart()
		adapter.clear()
		query.addChildEventListener(listener)
	}
	
	override fun onStop() {
		super.onStop()
		query.removeEventListener(listener)
	}
	
	private fun sendMessage(input: EditText) {
		val text = input.text.toString().trim()
		if(text.isEmpty()) return
		input.setText("")
		
		val messages = Fb.db.getReference("chats/$chatId/messages")
		val key = messages.push().key ?: return
		val ts = ServerValue.TIMESTAMP
		val updates = HashMap<String, Any?>()
		updates["chats/$chatId/messages/$key"] = mapOf("uid" to Fb.uid, "name" to Fb.name, "text" to text, "ts" to ts)
		updates["userChats/${Fb.uid}/$chatId"] = mapOf("peerUid" to peerUid, "peerName" to peerName, "lastText" to text, "ts" to ts)
		updates["userChats/$peerUid/$chatId"] = mapOf("peerUid" to Fb.uid, "peerName" to Fb.name, "lastText" to text, "ts" to ts)
		Fb.db.reference.updateChildren(updates)
	}
	
	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.menu_chatroom, menu)
		return true
	}
	
	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		return when(item.itemId) {
			android.R.id.home -> { finish(); true }
			R.id.action_call -> { startActivity(CallActivity.outgoing(this, peerUid, peerName, false)); true }
			R.id.action_videocall -> { startActivity(CallActivity.outgoing(this, peerUid, peerName, true)); true }
			else -> super.onOptionsItemSelected(item)
		}
	}
}
