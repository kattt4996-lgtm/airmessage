package me.tagavari.airmessage.flavor

import android.app.Application
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import me.tagavari.airmessage.fb.ChatList
import me.tagavari.airmessage.fb.Fb
import me.tagavari.airmessage.fb.IncomingCallWatcher
import me.tagavari.airmessage.fb.WatchParty

object FirebaseAuthBridge {
	@JvmStatic
	val isSupported = true
	
	@JvmStatic
	fun getUserSummary(): String? = FirebaseAuth.getInstance().currentUser?.run { "$displayName ($email)" }
	
	/** Whether the user is signed in with Google (required to reach the main screen) */
	@JvmStatic
	fun isSignedIn(): Boolean = FirebaseAuth.getInstance().currentUser != null
	
	/** Called once from the application class */
	@JvmStatic
	fun init(app: Application) {
		Fb.startOffsetListener()
		IncomingCallWatcher.start(app)
	}
	
	@JvmStatic
	fun signOut(context: Context) {
		FirebaseAuth.getInstance().signOut()
		GoogleSignIn.getClient(context, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut()
	}
	
	@JvmStatic
	fun launchNobar(context: Context) = context.startActivity(Intent(context, WatchParty::class.java))
	
	@JvmStatic
	fun launchChats(context: Context) = context.startActivity(Intent(context, ChatList::class.java))
}
