package com.webunime.tv.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
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
import com.webunime.tv.ui.player.PlayerActivity

class DetailActivity : AppCompatActivity() {

    private lateinit var item: CatalogItem
    private var selectedEpisode: Episode? = null
    private var selectedPlayer: PlayerServer? = null

    private lateinit var episodeContainer: LinearLayout
    private lateinit var serverContainer: LinearLayout
    private lateinit var playButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        val slug = intent.getStringExtra(EXTRA_SLUG).orEmpty()
        val preferEpisode = intent.getIntExtra(EXTRA_EPISODE, -1).takeIf { it > 0 }
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

        selectedEpisode = when {
            preferEpisode != null ->
                item.episodes?.firstOrNull { it.episode == preferEpisode }
                    ?: item.episodes?.firstOrNull()
            else -> item.episodes?.firstOrNull()
        }
        bindEpisodes()
        bindServers()
        updatePlayButtonLabel()

        playButton.setOnClickListener { startPlayback() }
        playButton.requestFocus()
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

    private fun bindEpisodes() {
        episodeContainer.removeAllViews()
        val episodes = item.episodes.orEmpty()
        val episodeLabel = findViewById<TextView>(R.id.episodeLabel)
        val episodeScroll = findViewById<View>(R.id.episodeScroll)
        if (episodes.isEmpty()) {
            episodeLabel.visibility = View.GONE
            episodeScroll.visibility = View.GONE
            return
        }
        episodeLabel.visibility = View.VISIBLE
        episodeScroll.visibility = View.VISIBLE
        episodes.forEach { ep ->
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = ep.displayTitle()
                isFocusable = true
                isAllCaps = false
                setOnClickListener {
                    selectedEpisode = ep
                    bindEpisodes()
                    bindServers()
                    updatePlayButtonLabel()
                }
                if (selectedEpisode?.slug == ep.slug && selectedEpisode?.episode == ep.episode) {
                    setBackgroundColor(getColor(R.color.wu_accent))
                }
            }
            episodeContainer.addView(btn)
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
        val slug = item.slug?.takeIf { it.isNotBlank() }
            ?: item.anime_slug?.takeIf { it.isNotBlank() }
            ?: ""
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
    }
}
