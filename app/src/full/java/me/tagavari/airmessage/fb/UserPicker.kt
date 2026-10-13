package me.tagavari.airmessage.fb

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import me.tagavari.airmessage.R

/** Lists everyone who signed in with Google so a chat can be started */
class UserPicker : AppCompatActivity() {
	private lateinit var adapter: UserAdapter
	private lateinit var textEmpty: TextView
	private var all: List<UserItem> = emptyList()
	
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_chatlist)
		setSupportActionBar(findViewById(R.id.toolbar))
		supportActionBar?.setDisplayHomeAsUpEnabled(true)
		title = getString(R.string.chat_new)
		
		findViewById<FloatingActionButton>(R.id.fab).visibility = View.GONE
		findViewById<View>(R.id.layout_search).visibility = View.VISIBLE
		textEmpty = findViewById(R.id.text_empty)
		textEmpty.setText(R.string.chat_no_users)
		
		adapter = UserAdapter { item ->
			startActivity(ChatRoom.intent(this, item.uid, item.name))
			finish()
		}
		findViewById<RecyclerView>(R.id.list).apply {
			layoutManager = LinearLayoutManager(this@UserPicker)
			adapter = this@UserPicker.adapter
		}
		
		findViewById<TextInputEditText>(R.id.input_search).addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
			override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
			override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
		})
		
		Fb.db.getReference("users").addListenerForSingleValueEvent(object : ValueEventListener {
			override fun onDataChange(snapshot: DataSnapshot) {
				all = snapshot.children.mapNotNull { c ->
					val uid = c.key ?: return@mapNotNull null
					if(uid == Fb.uid) return@mapNotNull null
					UserItem(
						uid,
						c.child("name").getValue(String::class.java) ?: "Pengguna",
						c.child("email").getValue(String::class.java) ?: ""
					)
				}.sortedBy { it.name.lowercase() }
				applyFilter("")
			}
			
			override fun onCancelled(error: DatabaseError) = Unit
		})
	}
	
	private fun applyFilter(query: String) {
		val q = query.trim().lowercase()
		val list = if(q.isEmpty()) all else all.filter { it.name.lowercase().contains(q) || it.sub.lowercase().contains(q) }
		adapter.submit(list)
		textEmpty.visibility = if(list.isEmpty()) View.VISIBLE else View.GONE
	}
	
	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		if(item.itemId == android.R.id.home) {
			finish()
			return true
		}
		return super.onOptionsItemSelected(item)
	}
}
