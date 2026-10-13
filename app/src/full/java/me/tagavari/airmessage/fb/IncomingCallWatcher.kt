package me.tagavari.airmessage.fb

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import me.tagavari.airmessage.R
import java.lang.ref.WeakReference

/**
 * Listens to incomingCalls/{myUid} while the app is open and shows an accept / decline dialog.
 * Ringing while the app is closed would need a push notification (FCM) sent by a Cloud Function.
 */
object IncomingCallWatcher : Application.ActivityLifecycleCallbacks {
	private class Incoming(val id: String, val fromUid: String, val fromName: String, val video: Boolean)
	
	private const val MAX_AGE_MS = 60_000L
	
	private var resumed: WeakReference<Activity>? = null
	private var ref: DatabaseReference? = null
	private var listener: ChildEventListener? = null
	private val pending = LinkedHashMap<String, Incoming>()
	private var dialog: AlertDialog? = null
	private var dialogId: String? = null
	private var started = false
	
	fun start(app: Application) {
		if(started) return
		started = true
		app.registerActivityLifecycleCallbacks(this)
		FirebaseAuth.getInstance().addAuthStateListener { auth -> rebind(auth.currentUser?.uid) }
	}
	
	private fun rebind(uid: String?) {
		listener?.let { l -> ref?.removeEventListener(l) }
		ref = null
		listener = null
		pending.clear()
		
		uid ?: return
		val r = Fb.db.getReference("incomingCalls/$uid")
		val l = object : ChildEventListener {
			override fun onChildAdded(snap: DataSnapshot, previousChildName: String?) {
				val id = snap.key ?: return
				val ts = snap.child("ts").getValue(Long::class.java) ?: 0L
				if(ts != 0L && Fb.serverNow() - ts > MAX_AGE_MS) {
					snap.ref.removeValue() //Stale
					return
				}
				pending[id] = Incoming(
					id,
					snap.child("from").getValue(String::class.java) ?: return,
					snap.child("fromName").getValue(String::class.java) ?: "?",
					snap.child("video").getValue(Boolean::class.java) ?: false
				)
				showIfPossible()
			}
			
			override fun onChildRemoved(snap: DataSnapshot) {
				val id = snap.key ?: return
				pending.remove(id)
				if(dialogId == id) { //Caller hung up
					dialog?.dismiss()
					dialog = null
					dialogId = null
				}
			}
			
			override fun onChildChanged(snap: DataSnapshot, previousChildName: String?) = Unit
			override fun onChildMoved(snap: DataSnapshot, previousChildName: String?) = Unit
			override fun onCancelled(error: DatabaseError) = Unit
		}
		r.addChildEventListener(l)
		ref = r
		listener = l
	}
	
	private fun showIfPossible() {
		val activity = resumed?.get() ?: return
		if(activity is CallActivity || activity.isFinishing || dialog?.isShowing == true) return
		val call = pending.values.firstOrNull() ?: return
		
		dialogId = call.id
		dialog = MaterialAlertDialogBuilder(activity)
			.setTitle(activity.getString(
				if(call.video) R.string.call_incoming_video else R.string.call_incoming_voice, call.fromName
			))
			.setCancelable(false)
			.setPositiveButton(R.string.call_accept) { _, _ ->
				pending.remove(call.id)
				dialog = null; dialogId = null
				activity.startActivity(CallActivity.incoming(activity, call.id, call.fromUid, call.fromName, call.video))
			}
			.setNegativeButton(R.string.call_decline) { _, _ ->
				pending.remove(call.id)
				dialog = null; dialogId = null
				Fb.db.getReference("calls/${call.id}/status").setValue("declined")
				Fb.db.getReference("incomingCalls/${Fb.uid}/${call.id}").removeValue()
				showIfPossible()
			}
			.show()
	}
	
	//---- Activity tracking ----
	
	override fun onActivityResumed(activity: Activity) {
		resumed = WeakReference(activity)
		showIfPossible()
	}
	
	override fun onActivityPaused(activity: Activity) {
		if(resumed?.get() === activity) {
			//Avoid leaking the window of the paused activity
			dialog?.dismiss()
			dialog = null
			dialogId = null
			resumed = null
		}
	}
	
	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
	override fun onActivityStarted(activity: Activity) = Unit
	override fun onActivityStopped(activity: Activity) = Unit
	override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
	override fun onActivityDestroyed(activity: Activity) = Unit
}
