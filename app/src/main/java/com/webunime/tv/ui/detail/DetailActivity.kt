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
        Glide.with(this).load(item.thumbnail).centerCrop().into(poster)
        Glide.with(this).load(item.thumbnail).centerCrop().into(backdrop)

        selectedEpisode = when {
            preferEpisode != null ->
                item.episodes?.firstOrNull { it.episode == preferEpisode }
                    ?: item.episodes?.firstOrNull()
            else -> item.episodes?.firstOrNull()
        }
        bindEpisodes()
        bindServers()

        playButton.setOnClickListener { startPlayback() }
        playButton.requestFocus()
    }

    /** Emulator: Esc sering tidak jadi Back — tangkap Escape. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_BACK)
        ) {
            finish()
            return true
        }
        return super.dispatchKeyEvent(event)
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
        val player = selectedPlayer ?: PlayerRouter.pickDefault(item, selectedEpisode)
        val url = player?.url
        if (url.isNullOrBlank()) {
            Toast.makeText(this, R.string.error_no_players, Toast.LENGTH_SHORT).show()
            return
        }
        val title = buildString {
            append(item.displayTitle())
            selectedEpisode?.let { append(" · ").append(it.displayTitle()) }
        }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URL, url)
                .putExtra(PlayerActivity.EXTRA_TITLE, title)
                .putExtra(PlayerActivity.EXTRA_SERVER, player?.displayName().orEmpty())
        )
    }

    companion object {
        const val EXTRA_SLUG = "slug"
        const val EXTRA_EPISODE = "episode"
    }
}
