package dev.serrebi.accessibleiptv

import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AlertDialog

/** Scheduled recordings first, then saved files in Movies/AccessibleIPTV. */
class RecordingsActivity : ListScreen() {
	private sealed interface Item
	private data class Sched(val s: Recorder.Scheduled) : Item
	private data class Saved(val uri: Uri, val name: String, val added: Long) : Item

	private lateinit var adapter: RowAdapter<Item>

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		title = getString(R.string.recordings)
		addButton(getString(R.string.stop_all_recordings)) {
			Recorder.stopAll(this)
			status.status(getString(R.string.recordings_stopped))
		}
		adapter = RowAdapter { item, row, l1, l2 ->
			when (item) {
				is Sched -> {
					l1.text = listOf(item.s.title, item.s.name).filter { it.isNotBlank() }.joinToString(", ")
					l2.text = getString(R.string.scheduled_for, Format.dateTime(item.s.start), Format.time(item.s.end))
					bindRow(row, "${l1.text}, ${l2.text}", { confirmCancel(item.s) }, listOf(RowAction(getString(R.string.cancel_recording)) { confirmCancel(item.s) }))
				}
				is Saved -> {
					l1.text = item.name
					l2.text = getString(R.string.saved_on, Format.dateTime(item.added))
					bindRow(row, "${item.name}, ${l2.text}", { Player.play(this, item.uri.toString(), item.name, "", catchup = true) },
						listOf(RowAction(getString(R.string.delete)) { confirmDelete(item) }))
				}
			}
		}
		list.adapter = adapter
	}

	override fun onResume() {
		super.onResume()
		refresh()
	}

	private fun refresh() {
		val items = Recorder.scheduled(this).map { Sched(it) } + saved()
		adapter.submit(items)
		heading.text = getString(R.string.recordings_heading, Recorder.scheduled(this).size, items.size - Recorder.scheduled(this).size)
	}

	private fun saved(): List<Saved> {
		val out = ArrayList<Saved>()
		val coll = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
		contentResolver.query(
			coll, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED),
			"${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("${Recorder.FOLDER}%"), "${MediaStore.Video.Media.DATE_ADDED} DESC",
		)?.use { c ->
			while (c.moveToNext()) out += Saved(ContentUris.withAppendedId(coll, c.getLong(0)), c.getString(1), c.getLong(2) * 1000)
		}
		return out
	}

	private fun confirmCancel(s: Recorder.Scheduled) {
		AlertDialog.Builder(this).setTitle(R.string.cancel_recording).setMessage(getString(R.string.cancel_recording_q, s.title.ifBlank { s.name }))
			.setPositiveButton(R.string.cancel_recording) { _, _ -> Recorder.cancel(this, s.id); refresh(); status.status(getString(R.string.recording_cancelled)) }
			.setNegativeButton(R.string.keep, null).show()
	}

	private fun confirmDelete(s: Saved) {
		AlertDialog.Builder(this).setTitle(R.string.delete).setMessage(getString(R.string.delete_recording_q, s.name))
			.setPositiveButton(R.string.delete) { _, _ ->
				contentResolver.delete(s.uri, null, null)
				refresh()
				status.status(getString(R.string.recording_deleted, s.name))
			}
			.setNegativeButton(android.R.string.cancel, null).show()
	}
}
