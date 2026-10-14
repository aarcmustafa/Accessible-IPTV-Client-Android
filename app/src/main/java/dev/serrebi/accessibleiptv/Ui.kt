package dev.serrebi.accessibleiptv

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.RecyclerView

/** A row action: a TalkBack custom action and the same entry in the long-press menu. */
data class RowAction(val label: String, val run: () -> Unit)

/**
 * One row = one focus stop. TalkBack reads [speech]; the secondary actions live in
 * the TalkBack Actions menu and in a long-press / Menu-key popup for touch and remotes,
 * so nobody swipes past a strip of small buttons on every row.
 */
fun bindRow(row: View, speech: String, onClick: () -> Unit, actions: List<RowAction>) {
	row.contentDescription = speech
	row.setOnClickListener { onClick() }
	val old = row.getTag(R.id.tag_actions) as? List<*>
	old?.forEach { (it as? Int)?.let { id -> ViewCompat.removeAccessibilityAction(row, id) } }
	row.setTag(R.id.tag_actions, actions.map { a -> ViewCompat.addAccessibilityAction(row, a.label) { _, _ -> a.run(); true } })
	row.setOnLongClickListener {
		if (actions.isEmpty()) return@setOnLongClickListener false
		PopupMenu(row.context, row).apply {
			actions.forEachIndexed { i, a -> menu.add(0, i, i, a.label) }
			setOnMenuItemClickListener { actions[it.itemId].run(); true }
		}.show()
		true
	}
	// The Menu key on remotes and keyboards opens the same list.
	row.setOnKeyListener { v, keyCode, event ->
		if (keyCode == android.view.KeyEvent.KEYCODE_MENU && event.action == android.view.KeyEvent.ACTION_UP) v.performLongClick() else false
	}
}

fun View.heading() = ViewCompat.setAccessibilityHeading(this, true)

/**
 * The keyboard's Search key (or Enter on a hardware keyboard or remote): put the keyboard
 * away and run [search] now. The reader is moved to [results] so the count is heard.
 */
fun android.widget.EditText.onSearchKey(results: View, search: suspend () -> Unit) {
	imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
	setOnEditorActionListener { v, actionId, event ->
		val enter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN
		if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH && actionId != android.view.inputmethod.EditorInfo.IME_ACTION_DONE && !enter) {
			return@setOnEditorActionListener false
		}
		v.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
		(v.context as androidx.lifecycle.LifecycleOwner).lifecycleScope.launch {
			search()
			results.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
		}
		true
	}
}

/** Status line updates are a polite live region in the layout; this also keeps them short. */
fun TextView.status(text: String) { this.text = text }

fun Activity.alert(title: String, message: String) {
	AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton(android.R.string.ok, null).show()
}

/** Generic two-line list for channels, groups, programmes, sources and recordings. */
class RowAdapter<T>(private val bind: (T, View, TextView, TextView) -> Unit) : RecyclerView.Adapter<RowAdapter.VH>() {
	var items: List<T> = emptyList()
		private set

	fun submit(list: List<T>) {
		items = list
		notifyDataSetChanged() // ponytail: full rebind; DiffUtil if large lists ever stutter.
	}

	class VH(v: View) : RecyclerView.ViewHolder(v) {
		val line1: TextView = v.findViewById(R.id.line1)
		val line2: TextView = v.findViewById(R.id.line2)
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
		VH(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

	override fun getItemCount() = items.size

	override fun onBindViewHolder(h: VH, position: Int) {
		bind(items[position], h.itemView, h.line1, h.line2)
		h.line2.visibility = if (h.line2.text.isNullOrEmpty()) View.GONE else View.VISIBLE
	}
}
