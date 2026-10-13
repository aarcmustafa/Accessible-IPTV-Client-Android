package dev.serrebi.accessibleiptv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A show's episodes, in season order. */
class EpisodesActivity : ListScreen() {
	private lateinit var adapter: RowAdapter<Channel>
	private var all: List<Channel> = emptyList()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val show = app.channel(intent.getStringExtra("key").orEmpty()) ?: return finish()
		title = show.name
		adapter = RowAdapter { ep, row, l1, _ ->
			l1.text = ep.name
			val actions = mutableListOf(
				RowAction(getString(R.string.play_external)) { Player.open(this, ep, status, external = true) },
				RowAction(getString(R.string.download)) {
					Recorder.download(this, ep.url, show.name, ep.name)
					status.status(getString(R.string.downloading, ep.name))
				},
			)
			ep.attrs["plot"]?.takeIf { it.isNotBlank() }?.let { plot -> actions.add(0, RowAction(getString(R.string.programme_details)) { alert(ep.name, plot) }) }
			bindRow(row, ep.name, { Player.open(this, ep, status) }, actions)
		}
		list.adapter = adapter
		enableFilter { q -> show(q) }
		status.status(getString(R.string.loading))
		lifecycleScope.launch {
			val result = withContext(Dispatchers.IO) { runCatching { app.episodes(show) } }
			all = result.getOrDefault(emptyList())
			show("")
			status.status(result.exceptionOrNull()?.let { getString(R.string.load_errors, it.message ?: "") }.orEmpty())
		}
	}

	private fun show(q: String) {
		val shown = if (q.isEmpty()) all else all.filter { it.name.contains(q, true) }
		adapter.submit(shown)
		heading.text = resources.getQuantityString(R.plurals.episode_count, shown.size, shown.size)
	}

	companion object {
		fun intent(c: Context, show: Channel) = Intent(c, EpisodesActivity::class.java).putExtra("key", show.key)
	}
}
