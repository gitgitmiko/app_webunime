package com.webunime.tv.ui

import android.view.View
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.webunime.tv.R
import com.webunime.tv.WebunimeApp
import com.webunime.tv.data.BgmController

/** Tampilkan judul BGM di pojok; otomatis lepas saat lifecycle destroy. */
fun bindBgmNowPlaying(owner: LifecycleOwner, app: WebunimeApp, view: TextView) {
    val listener = BgmController.Listener { title ->
        view.post {
            if (title.isNullOrBlank()) {
                view.visibility = View.GONE
            } else {
                val label = if (app.bgm.isMuted()) {
                    view.context.getString(R.string.bgm_now_playing_muted, title)
                } else {
                    view.context.getString(R.string.bgm_now_playing, title)
                }
                view.text = label
                view.visibility = View.VISIBLE
            }
        }
    }
    app.bgm.addListener(listener)
    owner.lifecycle.addObserver(
        object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                app.bgm.removeListener(listener)
            }
        },
    )
}
