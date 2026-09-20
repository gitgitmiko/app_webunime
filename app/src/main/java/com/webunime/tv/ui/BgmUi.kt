package com.webunime.tv.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.webunime.tv.R
import com.webunime.tv.WebunimeApp
import com.webunime.tv.data.BgmController

/** Tampilkan judul BGM + equalizer di pojok; otomatis lepas saat lifecycle destroy. */
fun bindBgmNowPlaying(owner: LifecycleOwner, app: WebunimeApp, root: View) {
    val container = when {
        root.id == R.id.bgmNowPlaying && root is ViewGroup -> root
        else -> root.findViewById(R.id.bgmNowPlaying) ?: root
    }
    val titleView = container.findViewById<TextView>(R.id.bgmNowPlayingTitle)
        ?: (container as? TextView)
        ?: return
    val equalizer = container.findViewById<BgmEqualizerView>(R.id.bgmEqualizer)

    val listener = BgmController.Listener { title ->
        container.post {
            if (title.isNullOrBlank()) {
                equalizer?.setAnimating(false)
                container.visibility = View.GONE
            } else {
                val muted = app.bgm.isMuted()
                titleView.text = if (muted) {
                    container.context.getString(R.string.bgm_now_playing_muted, title)
                } else {
                    container.context.getString(R.string.bgm_now_playing, title)
                }
                container.visibility = View.VISIBLE
                equalizer?.setAnimating(!muted)
            }
        }
    }
    app.bgm.addListener(listener)
    owner.lifecycle.addObserver(
        object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                // Sync animasi jika mute berubah di Settings lalu kembali.
                val title = app.bgm.currentTitle()
                if (!title.isNullOrBlank()) {
                    equalizer?.setAnimating(!app.bgm.isMuted())
                }
            }

            override fun onPause(owner: LifecycleOwner) {
                equalizer?.setAnimating(false)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                equalizer?.setAnimating(false)
                app.bgm.removeListener(listener)
            }
        },
    )
}
