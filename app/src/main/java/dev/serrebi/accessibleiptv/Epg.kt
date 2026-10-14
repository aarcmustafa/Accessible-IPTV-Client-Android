package dev.serrebi.accessibleiptv

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/**
 * Guide storage. Programmes are keyed by the XMLTV channel id; a channel finds its
 * guide by tvg-id first, then by a normalised name.
 */
class Epg(context: Context) : SQLiteOpenHelper(context, "epg.db", null, 1) {
	override fun onCreate(db: SQLiteDatabase) {
		db.execSQL("CREATE TABLE prog (ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT, descr TEXT)")
		db.execSQL("CREATE INDEX prog_ch ON prog (ch, start)")
		db.execSQL("CREATE INDEX prog_time ON prog (start, stop)")
		db.execSQL("CREATE TABLE gch (id TEXT PRIMARY KEY, namekey TEXT)")
	}

	override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

	@Volatile private var byId: Map<String, String> = emptyMap()
	@Volatile private var byName: Map<String, String> = emptyMap()

	/** Reloads the channel lookup tables; call after an import or on start. */
	fun loadIndex() {
		val ids = HashMap<String, String>()
		val names = HashMap<String, String>()
		readableDatabase.rawQuery("SELECT id, namekey FROM gch", null).use { c ->
			while (c.moveToNext()) {
				ids[c.getString(0).lowercase()] = c.getString(0)
				c.getString(1)?.takeIf { it.isNotEmpty() }?.let { names.putIfAbsent(it, c.getString(0)) }
			}
		}
		byId = ids
		byName = names
	}

	fun guideId(ch: Channel): String? =
		ch.tvgId.takeIf { it.isNotEmpty() }?.let { byId[it.lowercase()] } ?: byName[Xmltv.nameKey(ch.name)]

	val hasGuide get() = byId.isNotEmpty()

	/** The programme on now and the one after it. */
	fun nowNext(ch: Channel, now: Long = System.currentTimeMillis()): List<Programme> {
		val id = guideId(ch) ?: return emptyList()
		return query("SELECT start, stop, title, descr FROM prog WHERE ch=? AND stop>? ORDER BY start LIMIT 2", arrayOf(id, now.toString()), ch)
	}

	fun schedule(ch: Channel, from: Long, to: Long): List<Programme> {
		val id = guideId(ch) ?: return emptyList()
		return query("SELECT start, stop, title, descr FROM prog WHERE ch=? AND stop>? AND start<? ORDER BY start", arrayOf(id, from.toString(), to.toString()), ch)
	}

	/** Everything airing now, paired with the first playlist channel that uses each guide. */
	fun onNow(channels: List<Channel>, now: Long = System.currentTimeMillis()): List<Pair<Programme, Channel>> {
		val chByGuide = HashMap<String, Channel>()
		for (c in channels) guideId(c)?.let { chByGuide.putIfAbsent(it, c) }
		val out = ArrayList<Pair<Programme, Channel>>()
		readableDatabase.rawQuery("SELECT ch, start, stop, title, descr FROM prog WHERE start<=? AND stop>?", arrayOf(now.toString(), now.toString())).use { c ->
			while (c.moveToNext()) {
				val ch = chByGuide[c.getString(0)] ?: continue
				out += Programme(ch.key, c.getLong(1), c.getLong(2), c.getString(3).orEmpty(), c.getString(4).orEmpty(), ch.name) to ch
			}
		}
		return out.sortedBy { it.first.title.lowercase() }
	}

	/** Guide search: programmes not yet over whose title or description holds [q]. */
	fun search(q: String, channels: List<Channel>, now: Long = System.currentTimeMillis()): List<Pair<Programme, Channel>> {
		val chByGuide = HashMap<String, Channel>()
		for (c in channels) guideId(c)?.let { chByGuide.putIfAbsent(it, c) }
		val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
		val out = ArrayList<Pair<Programme, Channel>>()
		readableDatabase.rawQuery(
			"SELECT ch, start, stop, title, descr FROM prog WHERE stop>? AND (title LIKE ? ESCAPE '\\' OR descr LIKE ? ESCAPE '\\') ORDER BY start LIMIT 500",
			arrayOf(now.toString(), like, like),
		).use { c ->
			while (c.moveToNext()) {
				val ch = chByGuide[c.getString(0)] ?: continue
				out += Programme(ch.key, c.getLong(1), c.getLong(2), c.getString(3).orEmpty(), c.getString(4).orEmpty(), ch.name) to ch
			}
		}
		return out
	}

	private fun query(sql: String, args: Array<String>, ch: Channel) = buildList {
		readableDatabase.rawQuery(sql, args).use { c ->
			while (c.moveToNext()) add(Programme(ch.key, c.getLong(0), c.getLong(1), c.getString(2).orEmpty(), c.getString(3).orEmpty(), ch.name))
		}
	}

	/**
	 * Replaces the guide with [urls]. Only guide channels some playlist channel can match
	 * are kept, inside [fromMs, toMs]: a 150 MB national guide stays small on a phone.
	 * [progress] gets a programme count now and then.
	 */
	fun import(urls: List<String>, channels: List<Channel>, fromMs: Long, toMs: Long, progress: (Int) -> Unit): Int {
		val wantIds = channels.mapNotNullTo(HashSet()) { it.tvgId.lowercase().ifEmpty { null } }
		val wantNames = channels.mapTo(HashSet()) { Xmltv.nameKey(it.name) }
		val db = writableDatabase
		// One transaction for the whole guide held ~185 MB of native memory and got the app
		// killed on a 2 GB TV. Small page cache, and commit every 20,000 programmes.
		db.execSQL("PRAGMA cache_size=-4000")
		db.beginTransaction()
		var count = 0
		try {
			db.delete("prog", null, null)
			db.delete("gch", null, null)
			val insProg = db.compileStatement("INSERT INTO prog (ch, start, stop, title, descr) VALUES (?,?,?,?,?)")
			val insCh = db.compileStatement("INSERT OR IGNORE INTO gch (id, namekey) VALUES (?,?)")
			for (url in urls) {
				val kept = HashSet<String>()
				Net.open(url).use { input ->
					val p = Xml.newPullParser()
					p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
					p.setInput(input, null)
					var ev = p.eventType
					while (ev != XmlPullParser.END_DOCUMENT) {
						if (ev == XmlPullParser.START_TAG) when (p.name) {
							"channel" -> {
								val id = p.getAttributeValue(null, "id").orEmpty()
								val names = ArrayList<String>()
								while (!(p.next() == XmlPullParser.END_TAG && p.name == "channel")) {
									if (p.eventType == XmlPullParser.START_TAG && p.name == "display-name") names += Xmltv.nameKey(p.nextText())
								}
								val match = id.lowercase() in wantIds || names.any { it in wantNames }
								if (id.isNotEmpty() && match) {
									kept += id
									insCh.bindString(1, id)
									insCh.bindString(2, names.firstOrNull { it in wantNames } ?: names.firstOrNull().orEmpty())
									insCh.executeInsert()
								}
							}
							"programme" -> {
								val id = p.getAttributeValue(null, "channel").orEmpty()
								// Skip unmatched channels before doing any work on their times.
								val start = if (id in kept) Xmltv.parseTime(p.getAttributeValue(null, "start")) else null
								val stop = if (start != null) Xmltv.parseTime(p.getAttributeValue(null, "stop")) else null
								if (start != null && stop != null && stop > fromMs && start < toMs) {
									var title = ""
									var desc = ""
									while (!(p.next() == XmlPullParser.END_TAG && p.name == "programme")) {
										if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
											"title" -> if (title.isEmpty()) title = p.nextText()
											"desc" -> if (desc.isEmpty()) desc = p.nextText()
										}
									}
									insProg.bindString(1, id); insProg.bindLong(2, start); insProg.bindLong(3, stop)
									insProg.bindString(4, title); insProg.bindString(5, desc)
									insProg.executeInsert()
									if (++count % 20_000 == 0) {
										db.setTransactionSuccessful()
										db.endTransaction()
										db.beginTransaction()
										progress(count)
									}
								}
							}
						}
						ev = p.next()
					}
				}
			}
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}
		loadIndex()
		return count
	}
}
