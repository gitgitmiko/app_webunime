package com.webunime.tv.ui.auth

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.webunime.tv.R
import com.webunime.tv.WebunimeApp
import com.webunime.tv.data.api.ApiException
import kotlinx.coroutines.launch

/**
 * Login opsional (dibuka dari Settings). Sukses → gabung library device ke akun,
 * set RESULT_OK, kembali ke layar sebelumnya.
 */
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
        loginInput.requestFocus()
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
        val app = application as WebunimeApp
        lifecycleScope.launch {
            val result = runCatching { app.authRepository.login(login, password) }
            result.onSuccess { user ->
                runCatching { app.libraryRepository.mergeLocalIntoAccount() }
                busy = false
                if (isFinishing) return@launch
                Toast.makeText(
                    this@LoginActivity,
                    getString(R.string.login_success, user.displayLabel()),
                    Toast.LENGTH_SHORT,
                ).show()
                setResult(Activity.RESULT_OK)
                finish()
            }.onFailure { err ->
                busy = false
                if (isFinishing) return@launch
                progress.visibility = View.GONE
                loginButton.isEnabled = true
                showError((err as? ApiException)?.message ?: getString(R.string.login_failed))
                passwordInput.requestFocus()
            }
        }
    }

    private fun showError(message: String) {
        errorView.text = message
        errorView.visibility = View.VISIBLE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_BACK)
        ) {
            if (!busy) finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
