package me.tagavari.airmessage.flavor

import android.app.Application
import android.content.Context
import android.widget.Toast

/** FOSS flavor has no Firebase: no login is required and the online features are unavailable */
object FirebaseAuthBridge {
	@JvmStatic
	val isSupported = false
	
	@JvmStatic
	fun getUserSummary(): String? = null
	
	@JvmStatic
	fun isSignedIn(): Boolean = true
	
	@JvmStatic
	fun init(app: Application) = Unit
	
	@JvmStatic
	fun signOut(context: Context) = Unit
	
	@JvmStatic
	fun launchNobar(context: Context) = unsupported(context)
	
	@JvmStatic
	fun launchChats(context: Context) = unsupported(context)
	
	private fun unsupported(context: Context) {
		Toast.makeText(context, "Fitur ini butuh build flavor \"full\" (Firebase)", Toast.LENGTH_LONG).show()
	}
}
