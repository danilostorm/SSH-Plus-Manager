package com.socks5.ui

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.socks5.R
import com.socks5.data.model.AuthMethodType
import com.socks5.data.model.ConnectionProfile
import com.socks5.ssh.SshConnectionManager
import kotlinx.coroutines.launch

/**
 * Storm SSH client UI.
 *
 * Server details are intentionally fixed so the end user only needs the
 * username and password created by SSH Plus Manager.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val SERVER_HOST = "ssh.hoststorm.cloud"
        private const val SERVER_PORT = 2222
        private const val PREFS_NAME = "storm_ssh_ui"
        private const val PREF_USERNAME = "last_username"
    }

    private lateinit var viewModel: MainViewModel
    private lateinit var usernameInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var connectButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var progress: CircularProgressIndicator

    private var pendingUsername: String? = null
    private var pendingPassword: String? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val username = pendingUsername
            val password = pendingPassword
            if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
                startConnection(username, password)
            }
        } else {
            statusText.text = getString(R.string.storm_vpn_permission_denied)
            Toast.makeText(this, R.string.storm_vpn_permission_denied, Toast.LENGTH_LONG).show()
        }
        pendingUsername = null
        pendingPassword = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        connectButton = findViewById(R.id.connectButton)
        statusText = findViewById(R.id.statusText)
        progress = findViewById(R.id.connectProgress)

        findViewById<TextView>(R.id.serverText).text = "$SERVER_HOST:$SERVER_PORT"

        val uiPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        usernameInput.setText(uiPrefs.getString(PREF_USERNAME, ""))

        connectButton.setOnClickListener {
            when (viewModel.connectionState.value) {
                is SshConnectionManager.ConnectionState.Connected,
                is SshConnectionManager.ConnectionState.Connecting -> viewModel.disconnect()
                else -> connectFromForm()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { renderState(it) }
                }
                launch {
                    viewModel.errorMessage.collect { message ->
                        statusText.text = message
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun connectFromForm() {
        val username = usernameInput.text?.toString().orEmpty().trim()
        val password = passwordInput.text?.toString().orEmpty()

        if (username.isBlank()) {
            usernameInput.error = getString(R.string.storm_username_required)
            usernameInput.requestFocus()
            return
        }

        if (password.isBlank()) {
            passwordInput.error = getString(R.string.storm_password_required)
            passwordInput.requestFocus()
            return
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_USERNAME, username)
            .apply()

        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            pendingUsername = username
            pendingPassword = password
            vpnPermissionLauncher.launch(permissionIntent)
        } else {
            startConnection(username, password)
        }
    }

    private fun startConnection(username: String, password: String) {
        val profile = ConnectionProfile(
            id = 0L,
            name = "Storm SSH",
            host = SERVER_HOST,
            port = SERVER_PORT,
            username = username,
            authMethodType = AuthMethodType.PASSWORD,
            keepAliveInterval = 30_000L,
            autoReconnect = true
        )

        viewModel.connect(profile, password)
    }

    private fun renderState(state: SshConnectionManager.ConnectionState) {
        when (state) {
            is SshConnectionManager.ConnectionState.Disconnected -> {
                statusText.text = getString(R.string.storm_status_disconnected)
                progress.visibility = View.GONE
                connectButton.isEnabled = true
                connectButton.text = getString(R.string.storm_connect)
                usernameInput.isEnabled = true
                passwordInput.isEnabled = true
            }

            is SshConnectionManager.ConnectionState.Connecting -> {
                statusText.text = getString(R.string.storm_status_connecting)
                progress.visibility = View.VISIBLE
                connectButton.isEnabled = true
                connectButton.text = getString(R.string.storm_cancel)
                usernameInput.isEnabled = false
                passwordInput.isEnabled = false
            }

            is SshConnectionManager.ConnectionState.Connected -> {
                statusText.text = getString(R.string.storm_status_connected)
                progress.visibility = View.GONE
                connectButton.isEnabled = true
                connectButton.text = getString(R.string.storm_disconnect)
                usernameInput.isEnabled = false
                passwordInput.isEnabled = false
            }

            is SshConnectionManager.ConnectionState.Error -> {
                statusText.text = getString(R.string.storm_status_error, state.message)
                progress.visibility = View.GONE
                connectButton.isEnabled = true
                connectButton.text = getString(R.string.storm_connect)
                usernameInput.isEnabled = true
                passwordInput.isEnabled = true
            }
        }
    }
}
