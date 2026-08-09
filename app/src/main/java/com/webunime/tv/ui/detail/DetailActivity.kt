package com.webunime.tv.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.webunime.tv.R
import com.webunime.tv.WebunimeApp
import com.webunime.tv.data.CatalogItem
import com.webunime.tv.data.Episode
import com.webunime.tv.data.PlayerRouter
import com.webunime.tv.data.PlayerServer
import com.webunime.tv.data.WatchSessionStore
import com.webunime.tv.ui.player.PlayerActivity

class DetailActivity : AppCompatActivity() {

    private lateinit var item: CatalogItem
    private var selectedEpisode: Episode? = null
    private var selectedPlayer: PlayerServer? = null

    /** Indeks awal rentang aktif (0, 50, 100, …). Null = mode daftar penuh (&lt; threshold). */
    private var episodeRangeStart: Int? = null

    private lateinit var episodeJumpScroll: HorizontalScrollView
    private lateinit var episodeJumpContainer: LinearLayout
    private lateinit var episodeRangeScroll: HorizontalScrollView
    private lateinit var episodeRangeContainer: LinearLayout
    private lateinit var episodeContainer: LinearLayout
    private lateinit var episodeSection: View
    private lateinit var serverContainer: LinearLayout
    private lateinit var playButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        val slug = intent.getStringExtra(EXTRA_SLUG).orEmpty()
        val preferEpisode = intent.getIntExtra(EXTRA_EPISODE, -1).takeIf { it > 0 }
        val preferSeason = intent.getIntExtra(EXTRA_SEASON, -1).takeIf { it > 0 }
        val found = (application as WebunimeApp).catalogRepository.snapshot.findBySlug(slug)
        if (found == null) {
            Toast.makeText(this, "Judul tidak ditemukan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        item = found

        val poster = findViewById<ImageView>(R.id.detailPoster)
        val backdrop = findViewById<ImageView>(R.id.detailBackdrop)
        val title = findViewById<TextView>(R.id.detailTitle)
        val meta = findViewById<TextView>(R.id.detailMeta)
        val synopsis = findViewById<TextView>(R.id.detailSynopsis)
        episodeSection = findViewById(R.id.episodeSection)
        episodeJumpScroll = findViewById(R.id.episodeJumpScroll)
        episodeJumpContainer = findViewById(R.id.episodeJumpContainer)
        episodeRangeScroll = findViewById(R.id.episodeRangeScroll)
        episodeRangeContainer = findViewById(R.id.episodeRangeContainer)
        episodeContainer = findViewById(R.id.episodeContainer)
        serverContainer = findViewById(R.id.serverContainer)
        playButton = findViewById(R.id.playButton)

        title.text = item.displayTitle()
        meta.text = item.displayMeta()
        synopsis.text = item.sinopsis ?: ""
        val thumb = item.thumbnail
        val thumbAlt = item.thumbnailAlt
        val posterReq = Glide.with(this).load(thumb).centerCrop()
        val backdropReq = Glide.with(this).load(thumb).centerCrop()
        if (!thumbAlt.isNullOrBlank() && thumbAlt != thumb) {
            posterReq.error(Glide.with(this).load(thumbAlt).centerCrop())
            backdropReq.error(Glide.with(this).load(thumbAlt).centerCrop())
        }
        posterReq.into(poster)
        backdropReq.into(backdrop)

        val episodesSorted = sortedEpisodes()
        val continueEp = continueEpisode(episodesSorted)
        selectedEpisode = when {
            preferEpisode != null ->
                episodesSorted.firstOrNull { ep ->
                    ep.episode == preferEpisode &&
                        (preferSeason == null || ep.season == null || ep.season == preferSeason)
                }
                    ?: episodesSorted.firstOrNull { it.episode == preferEpisode }
                    ?: episodesSorted.firstOrNull()
            continueEp != null -> continueEp
            usesEpisodeRanges(episodesSorted) -> episodesSorted.lastOrNull()
            else -> episodesSorted.firstOrNull()
        }
        initEpisodeRange()
        bindEpisodes()
        bindServers()
        updatePlayButtonLabel()

        playButton.setOnClickListener { startPlayback() }
        playButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (::item.isInitialized && ::episodeContainer.isInitialized) {
            bindEpisodes()
            updatePlayButtonLabel()
        }
    }

    private fun updatePlayButtonLabel() {
        val slug = item.slug?.takeIf { it.isNotBlank() }
            ?: item.anime_slug?.takeIf { it.isNotBlank() }
            ?: return
        val session = (application as WebunimeApp).watchSessions.get(slug, selectedEpisode?.episode)
        playButton.text = if (session != null && !session.isFinished() && session.positionMs >= 30_000L) {
            getString(R.string.resume_play)
        } else {
            getString(R.string.play)
        }
    }

    /** Emulator Esc + remote TCL OK (ENTER/A). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_BACK)
        ) {
            finish()
            return true
        }
        val normalized = when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_SELECT ->
                KeyEvent(
                    event.downTime,
                    event.eventTime,
                    event.action,
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    event.repeatCount,
                    event.metaState,
                    event.deviceId,
                    event.scanCode,
                    event.flags,
                    event.source
                )
            else -> event
        }
        return super.dispatchKeyEvent(normalized)
    }

    private fun contentSlug(): String =
        item.slug?.takeIf { it.isNotBlank() }
            ?: item.anime_slug?.takeIf { it.isNotBlank() }
            ?: ""

    private fun sortedEpisodes(): List<Episode> =
        item.episodes.orEmpty().sortedWith(
            compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 },
        )

    private fun usesEpisodeRanges(episodes: List<Episode>): Boolean =
        episodes.size >= EPISODE_RANGE_THRESHOLD

    private fun initEpisodeRange() {
        val episodes = sortedEpisodes()
        if (!usesEpisodeRanges(episodes)) {
            episodeRangeStart = null
            return
        }
        val selectedIdx = selectedEpisode?.let { sel ->
            episodes.indexOfFirst {
                it.slug == sel.slug && it.episode == sel.episode && it.season == sel.season
            }.takeIf { it >= 0 }
                ?: episodes.indexOfFirst { it.episode == sel.episode }.takeIf { it >= 0 }
        }
        episodeRangeStart = if (selectedIdx != null) {
            (selectedIdx / EPISODE_RANGE_SIZE) * EPISODE_RANGE_SIZE
        } else {
            // Judul panjang tanpa prefer: rentang terakhir (sering yang dicari).
            ((episodes.size - 1) / EPISODE_RANGE_SIZE) * EPISODE_RANGE_SIZE
        }
    }

    private fun continueEpisode(episodes: List<Episode>): Episode? {
        val slug = contentSlug()
        if (slug.isBlank()) return null
        val sessions = (application as WebunimeApp).watchSessions
        val session = sessions.continueWatching(limit = 200)
            .firstOrNull { it.slug == slug }
            ?: return null
        val epNum = session.episode ?: return null
        return episodes.firstOrNull { it.episode == epNum }
    }

    private fun bindEpisodes() {
        episodeJumpContainer.removeAllViews()
        episodeRangeContainer.removeAllViews()
        episodeContainer.removeAllViews()

        val episodes = sortedEpisodes()
        val episodeLabel = findViewById<TextView>(R.id.episodeLabel)
        if (episodes.isEmpty()) {
            episodeLabel.visibility = View.GONE
            episodeSection.visibility = View.GONE
            episodeJumpScroll.visibility = View.GONE
            episodeRangeScroll.visibility = View.GONE
            return
        }
        episodeLabel.visibility = View.VISIBLE
        episodeSection.visibility = View.VISIBLE

        val ranged = usesEpisodeRanges(episodes)
        if (ranged) {
            if (episodeRangeStart == null) initEpisodeRange()
            bindJumpButtons(episodes)
            bindRangeChips(episodes)
            episodeJumpScroll.visibility = View.VISIBLE
            episodeRangeScroll.visibility = View.VISIBLE
        } else {
            episodeRangeStart = null
            episodeJumpScroll.visibility = View.GONE
            episodeRangeScroll.visibility = View.GONE
        }

        val visible = if (ranged) {
            val start = episodeRangeStart ?: 0
            val end = (start + EPISODE_RANGE_SIZE).coerceAtMost(episodes.size)
            episodes.subList(start, end)
        } else {
            episodes
        }

        val slug = contentSlug()
        val sessions = (application as WebunimeApp).watchSessions
        visible.forEach { ep ->
            val btn = makeEpisodeButton(ep, slug, sessions)
            btn.tag = ep
            episodeContainer.addView(btn)
        }
    }

    private fun bindJumpButtons(episodes: List<Episode>) {
        val continueEp = continueEpisode(episodes)
        if (continueEp != null) {
            episodeJumpContainer.addView(
                makeChromeButton(
                    text = getString(R.string.episode_jump_continue),
                    selected = selectedEpisode?.episode == continueEp.episode &&
                        selectedEpisode?.season == continueEp.season,
                ) {
                    selectEpisode(continueEp, focusEpisode = true)
                },
            )
        }
        val latest = episodes.lastOrNull()
        if (latest != null) {
            episodeJumpContainer.addView(
                makeChromeButton(
                    text = getString(R.string.episode_jump_latest),
                    selected = selectedEpisode?.slug == latest.slug &&
                        selectedEpisode?.episode == latest.episode,
                ) {
                    selectEpisode(latest, focusEpisode = true)
                },
            )
        }
    }

    private fun bindRangeChips(episodes: List<Episode>) {
        val total = episodes.size
        var start = 0
        while (start < total) {
            val endInclusive = (start + EPISODE_RANGE_SIZE).coerceAtMost(total)
            val rangeStart = start
            val label = getString(
                R.string.episode_range,
                start + 1,
                endInclusive,
            )
            val selected = episodeRangeStart == rangeStart
            episodeRangeContainer.addView(
                makeChromeButton(text = label, selected = selected) {
                    episodeRangeStart = rangeStart
                    // Jangan ganti selectedEpisode kecuali di luar rentang baru
                    val stillVisible = selectedEpisode?.let { sel ->
                        val idx = episodes.indexOfFirst {
                            it.slug == sel.slug && it.episode == sel.episode
                        }
                        idx in rangeStart until (rangeStart + EPISODE_RANGE_SIZE).coerceAtMost(total)
                    } == true
                    if (!stillVisible) {
                        selectedEpisode = episodes.getOrNull(rangeStart)
                    }
                    bindEpisodes()
                    bindServers()
                    updatePlayButtonLabel()
                    focusFirstEpisodeButton()
                },
            )
            start += EPISODE_RANGE_SIZE
        }
    }

    private fun selectEpisode(ep: Episode, focusEpisode: Boolean) {
        selectedEpisode = ep
        val episodes = sortedEpisodes()
        if (usesEpisodeRanges(episodes)) {
            val idx = episodes.indexOfFirst {
                it.slug == ep.slug && it.episode == ep.episode && it.season == ep.season
            }.takeIf { it >= 0 }
                ?: episodes.indexOfFirst { it.episode == ep.episode }.takeIf { it >= 0 }
                ?: 0
            episodeRangeStart = (idx / EPISODE_RANGE_SIZE) * EPISODE_RANGE_SIZE
        }
        bindEpisodes()
        bindServers()
        updatePlayButtonLabel()
        if (focusEpisode) focusSelectedEpisodeButton()
    }

    private fun makeEpisodeButton(
        ep: Episode,
        slug: String,
        sessions: WatchSessionStore,
    ): MaterialButton {
        val watched = slug.isNotBlank() && sessions.isWatched(slug, ep.episode)
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = if (watched) {
                getString(R.string.episode_watched, ep.displayTitle())
            } else {
                ep.displayTitle()
            }
            isFocusable = true
            isAllCaps = false
            if (watched) {
                setTextColor(getColor(R.color.wu_text_dim))
            }
            setOnClickListener {
                selectedEpisode = ep
                bindEpisodes()
                bindServers()
                updatePlayButtonLabel()
            }
            if (selectedEpisode?.slug == ep.slug && selectedEpisode?.episode == ep.episode) {
                setBackgroundColor(getColor(R.color.wu_accent))
                setTextColor(getColor(R.color.wu_text))
            }
        }
    }

    private fun makeChromeButton(
        text: String,
        selected: Boolean,
        onClick: () -> Unit,
    ): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            isFocusable = true
            isAllCaps = false
            setOnClickListener { onClick() }
            if (selected) {
                setBackgroundColor(getColor(R.color.wu_accent))
                setTextColor(getColor(R.color.wu_text))
            }
        }

    private fun focusFirstEpisodeButton() {
        episodeContainer.post {
            if (episodeContainer.childCount > 0) {
                episodeContainer.getChildAt(0).requestFocus()
            }
        }
    }

    private fun focusSelectedEpisodeButton() {
        episodeContainer.post {
            val sel = selectedEpisode
            for (i in 0 until episodeContainer.childCount) {
                val child = episodeContainer.getChildAt(i)
                val ep = child.tag as? Episode
                if (sel != null && ep != null &&
                    ep.episode == sel.episode && ep.season == sel.season &&
                    (ep.slug == null || sel.slug == null || ep.slug == sel.slug)
                ) {
                    child.requestFocus()
                    return@post
                }
            }
            focusFirstEpisodeButton()
        }
    }

    private fun bindServers() {
        serverContainer.removeAllViews()
        val players = PlayerRouter.preferredPlayers(item, selectedEpisode)
        selectedPlayer = players.firstOrNull { it.url == selectedPlayer?.url } ?: players.firstOrNull()
        if (players.isEmpty()) {
            Toast.makeText(this, R.string.error_no_players, Toast.LENGTH_SHORT).show()
            return
        }
        players.forEachIndexed { index, server ->
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = server.displayName()
                isFocusable = true
                isAllCaps = false
                // Geser kanan di baris server; atas kembali ke episode / play
                if (index == 0) {
                    nextFocusUpId = R.id.playButton
                }
                setOnClickListener {
                    selectedPlayer = server
                    bindServers()
                }
                setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        // Scroll agar baris server masuk viewport (TV + emulator)
                        v.post {
                            findViewById<android.widget.ScrollView>(R.id.detailScroll)
                                ?.requestChildRectangleOnScreen(
                                    findViewById(R.id.serverScroll),
                                    android.graphics.Rect(0, 0, v.width, v.height + 40),
                                    false
                                )
                        }
                    }
                }
                if (selectedPlayer?.url == server.url) {
                    setBackgroundColor(getColor(R.color.wu_accent))
                }
            }
            serverContainer.addView(btn)
        }
    }

    private fun startPlayback() {
        val players = PlayerRouter.preferredPlayers(item, selectedEpisode)
        val player = selectedPlayer?.takeIf { p -> players.any { it.url == p.url } }
            ?: players.firstOrNull()
        val url = player?.url
        if (url.isNullOrBlank() || players.isEmpty()) {
            Toast.makeText(this, R.string.error_no_players, Toast.LENGTH_SHORT).show()
            return
        }
        val slug = contentSlug()
        val episodeNum = selectedEpisode?.episode
        val title = buildString {
            append(item.displayTitle())
            selectedEpisode?.let { append(" · ").append(it.displayTitle()) }
        }
        val resume = (application as WebunimeApp).watchSessions
            .get(slug, episodeNum)
            ?.takeIf { !it.isFinished() }
            ?.positionMs
            ?: 0L
        // Urutkan agar server terpilih (jika user ganti manual) tetap dicoba dulu.
        val ordered = buildList {
            add(player!!)
            players.filter { it.url != player.url }.forEach { add(it) }
        }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URL, url)
                .putExtra(PlayerActivity.EXTRA_TITLE, title)
                .putExtra(PlayerActivity.EXTRA_SERVER, player.displayName())
                .putExtra(PlayerActivity.EXTRA_SERVER_URLS, ordered.mapNotNull { it.url }.toTypedArray())
                .putExtra(PlayerActivity.EXTRA_SERVER_LABELS, ordered.map { it.displayName() }.toTypedArray())
                .putExtra(PlayerActivity.EXTRA_SLUG, slug)
                .putExtra(PlayerActivity.EXTRA_EPISODE, episodeNum ?: -1)
                .putExtra(PlayerActivity.EXTRA_THUMBNAIL, item.thumbnail)
                .putExtra(PlayerActivity.EXTRA_RESUME_MS, resume)
        )
    }

    companion object {
        const val EXTRA_SLUG = "slug"
        const val EXTRA_EPISODE = "episode"
        const val EXTRA_SEASON = "season"

        private const val EPISODE_RANGE_THRESHOLD = 40
        private const val EPISODE_RANGE_SIZE = 50
    }
}
