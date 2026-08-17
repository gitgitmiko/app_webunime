package com.webunime.tv.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.webunime.tv.R
import com.webunime.tv.WebunimeApp
import com.webunime.tv.data.api.ApiException
import com.webunime.tv.ui.browse.MainActivity
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var loginInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: MaterialButton
    private lateinit var errorView: TextView
    private lateinit var progress: ProgressBar

    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        loginInput = findViewById(R.id.loginInput)
        passwordInput = findViewById(R.id.loginPassword)
        loginButton = findViewById(R.id.loginButton)
        errorView = findViewById(R.id.loginError)
        progress = findViewById(R.id.loginProgress)

        loginButton.setOnClickListener { submit() }
        passwordInput.setOnEditorActionListener { _, _, _ ->
            submit()
            true
        }

        progress.visibility = View.VISIBLE
        loginButton.isEnabled = false
        lifecycleScope.launch {
            val user = runCatching { (application as WebunimeApp).authRepository.restoreSession() }.getOrNull()
            if (isFinishing) return@launch
            if (user != null) {
                openMain()
                return@launch
            }
            progress.visibility = View.GONE
            loginButton.isEnabled = true
            loginInput.requestFocus()
        }
    }

    private fun submit() {
        if (busy || isFinishing) return
        val login = loginInput.text?.toString().orEmpty().trim()
        val password = passwordInput.text?.toString().orEmpty()
        if (login.isBlank() || password.isBlank()) {
            showError(getString(R.string.login_required))
            return
        }
        busy = true
        progress.visibility = View.VISIBLE
        loginButton.isEnabled = false
        errorView.visibility = View.GONE
        lifecycleScope.launch {
            val result = runCatching {
                (application as WebunimeApp).authRepository.login(login, password)
            }
            busy = false
            if (isFinishing) return@launch
            result.onSuccess {
                openMain()
            }.onFailure { err ->
                progress.visibility = View.GONE
                loginButton.isEnabled = true
                showError(
                    (err as? ApiException)?.message
                        ?: getString(R.string.login_failed),
                )
                passwordInput.requestFocus()
            }
        }
    }

    private fun openMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }

    private fun showError(message: String) {
        errorView.text = message
        errorView.visibility = View.VISIBLE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_BACK)
        ) {
            finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
