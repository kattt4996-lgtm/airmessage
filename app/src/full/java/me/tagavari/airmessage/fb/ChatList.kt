package me.tagavari.airmessage.fb

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import me.tagavari.airmessage.R

/** Recent online conversations */
class ChatList : AppCompatActivity() {
	private lateinit var adapter: UserAdapter
	private lateinit var textEmpty: TextView
	private val ref get() = Fb.db.getReference("userChats/${Fb.uid}")
	
	private val listener = object : ValueEventListener {
		override fun onDataChange(snapshot: DataSnapshot) {
			val list = snapshot.children.mapNotNull { c ->
				val peer = c.child("peerUid").getValue(String::class.java) ?: return@mapNotNull null
				UserItem(
					peer,
					c.child("peerName").getValue(String::class.java) ?: "?",
					c.child("lastText").getValue(String::class.java) ?: "",
					c.child("ts").getValue(Long::class.java) ?: 0L
				)
			}.sortedByDescending { it.ts }
			adapter.submit(list)
			textEmpty.visibility = if(list.isEmpty()) View.VISIBLE else View.GONE
		}
		
		override fun onCancelled(error: DatabaseError) = Unit
	}
	
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_chatlist)
		setSupportActionBar(findViewById(R.id.toolbar))
		supportActionBar?.setDisplayHomeAsUpEnabled(true)
		title = getString(R.string.chat_title)
		
		textEmpty = findViewById(R.id.text_empty)
		textEmpty.setText(R.string.chat_empty)
		
		adapter = UserAdapter { item -> startActivity(ChatRoom.intent(this, item.uid, item.name)) }
		findViewById<RecyclerView>(R.id.list).apply {
			layoutManager = LinearLayoutManager(this@ChatList)
			adapter = this@ChatList.adapter
		}
		findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
			startActivity(Intent(this, UserPicker::class.java))
		}
	}
	
	override fun onStart() {
		super.onStart()
		ref.addValueEventListener(listener)
	}
	
	override fun onStop() {
		super.onStop()
		ref.removeEventListener(listener)
	}
	
	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		if(item.itemId == android.R.id.home) {
			finish()
			return true
		}
		return super.onOptionsItemSelected(item)
	}
}
