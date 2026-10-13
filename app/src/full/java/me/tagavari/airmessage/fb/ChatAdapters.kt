package me.tagavari.airmessage.fb

import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import me.tagavari.airmessage.R

/** Message bubbles, shared by the 1:1 chat and the watch party chat */
class MessageAdapter(private val showSender: Boolean) : RecyclerView.Adapter<MessageAdapter.VH>() {
	private val items = ArrayList<ChatMessage>()
	
	class VH(view: View) : RecyclerView.ViewHolder(view) {
		val group: View = view.findViewById(R.id.group_bubble)
		val sender: TextView = view.findViewById(R.id.text_sender)
		val body: TextView = view.findViewById(R.id.text_body)
	}
	
	fun add(message: ChatMessage) {
		items.add(message)
		notifyItemInserted(items.size - 1)
	}
	
	fun clear() {
		val size = items.size
		items.clear()
		notifyItemRangeRemoved(0, size)
	}
	
	val lastIndex get() = items.size - 1
	
	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
		VH(LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false))
	
	override fun getItemCount() = items.size
	
	override fun onBindViewHolder(holder: VH, position: Int) {
		val m = items[position]
		val mine = m.uid == Fb.uid
		val ctx = holder.itemView.context
		
		(holder.group.layoutParams as FrameLayout.LayoutParams).gravity = if(mine) Gravity.END else Gravity.START
		holder.sender.visibility = if(showSender && !mine) View.VISIBLE else View.GONE
		holder.sender.text = m.name
		holder.body.text = m.text
		holder.body.backgroundTintList = ColorStateList.valueOf(
			ContextCompat.getColor(ctx, if(mine) R.color.colorPrimary else R.color.colorMessageOutgoing)
		)
		if(mine) {
			holder.body.setTextColor(Color.WHITE)
		} else {
			val tv = TypedValue()
			ctx.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)
			holder.body.setTextColor(ContextCompat.getColor(ctx, tv.resourceId))
		}
	}
}

data class UserItem(val uid: String, val name: String, val sub: String, val ts: Long = 0)

/** A simple list of people / conversations */
class UserAdapter(private val onClick: (UserItem) -> Unit) : RecyclerView.Adapter<UserAdapter.VH>() {
	private var items: List<UserItem> = emptyList()
	
	class VH(view: View) : RecyclerView.ViewHolder(view) {
		val avatar: TextView = view.findViewById(R.id.text_avatar)
		val name: TextView = view.findViewById(R.id.text_name)
		val sub: TextView = view.findViewById(R.id.text_sub)
	}
	
	fun submit(list: List<UserItem>) {
		items = list
		notifyDataSetChanged()
	}
	
	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
		VH(LayoutInflater.from(parent.context).inflate(R.layout.item_chat_user, parent, false))
	
	override fun getItemCount() = items.size
	
	override fun onBindViewHolder(holder: VH, position: Int) {
		val item = items[position]
		holder.avatar.text = item.name.trim().take(1).uppercase()
		holder.name.text = item.name
		holder.sub.text = item.sub
		holder.itemView.setOnClickListener { onClick(item) }
	}
}
