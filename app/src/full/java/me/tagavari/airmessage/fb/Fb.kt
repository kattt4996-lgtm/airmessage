package me.tagavari.airmessage.fb

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/** Shared Firebase helpers */
object Fb {
	val db: FirebaseDatabase get() = FirebaseDatabase.getInstance()
	val user get() = FirebaseAuth.getInstance().currentUser
	val uid: String get() = user?.uid ?: ""
	val name: String get() = user?.displayName ?: user?.email ?: "Aku"
	
	@Volatile
	private var serverOffset = 0L
	private var offsetStarted = false
	
	/** Current time on the Firebase server's clock; used so every device agrees on playback position */
	fun serverNow(): Long = System.currentTimeMillis() + serverOffset
	
	fun startOffsetListener() {
		if(offsetStarted) return
		offsetStarted = true
		db.getReference(".info/serverTimeOffset").addValueEventListener(object : ValueEventListener {
			override fun onDataChange(snapshot: DataSnapshot) {
				serverOffset = snapshot.getValue(Long::class.java) ?: 0L
			}
			
			override fun onCancelled(error: DatabaseError) = Unit
		})
	}
	
	/** Publishes the signed-in user's profile so others can find them */
	fun saveProfile() {
		val u = user ?: return
		db.getReference("users/${u.uid}").updateChildren(mapOf(
			"name" to (u.displayName ?: u.email ?: "Pengguna"),
			"email" to (u.email ?: ""),
			"photo" to (u.photoUrl?.toString() ?: "")
		))
	}
	
	fun chatId(a: String, b: String): String = listOf(a, b).sorted().joinToString("_")
}

data class ChatMessage(val uid: String, val name: String, val text: String, val ts: Long)
