package com.socks5.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.socks5.Socks5Application
import com.socks5.data.db.AppDatabase
import com.socks5.data.model.AuthMethodType
import com.socks5.data.model.ConnectionProfile
import com.socks5.data.model.SshKey
import com.socks5.data.preferences.AppPreferences
import com.socks5.data.repository.KeyRepository
import com.socks5.data.repository.ProfileRepository
import com.socks5.ssh.SshConnectionManager
import com.socks5.ssh.SshConnectionManager.ConnectionState
import com.socks5.util.TrafficStats
import com.socks5.vpn.Socks5VpnService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val preferences = AppPreferences(application)
    private val profileRepository = ProfileRepository(db.profileDao(), preferences)
    val keyRepository = KeyRepository(application, db.keyDao())

    // IMPORTANT: process-wide manager, not viewModelScope-owned.
    // This keeps SSH alive when MainActivity goes to background/recreates.
    val sshManager = (application as Socks5Application).sharedSshManager
    val trafficStats = TrafficStats()

    val connectionState: StateFlow<ConnectionState> = sshManager.state

    private val _vpnActive = MutableStateFlow(false)
    val vpnActive: StateFlow<Boolean> = _vpnActive.asStateFlow()

    val trafficSnapshot = trafficStats.stats

    val profiles: StateFlow<List<ConnectionProfile>> =
        profileRepository.getAllProfiles()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val keys: StateFlow<List<SshKey>> =
        keyRepository.getAllKeys()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selectedProfile = MutableStateFlow<ConnectionProfile?>(null)
    val selectedProfile: StateFlow<ConnectionProfile?> = _selectedProfile.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableSharedFlow<String>()
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    fun connect(profile: ConnectionProfile, password: String? = null, keyPassphrase: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true

            try {
                val authMethod = when (profile.authMethodType) {
                    AuthMethodType.PASSWORD -> {
                        val pass = password
                            ?: preferences.getPassword(profile.id)
                            ?: throw IllegalStateException("No password provided")
                        SshConnectionManager.AuthMethod.Password(pass)
                    }
                    AuthMethodType.PRIVATE_KEY -> {
                        val keyId = profile.keyId
                            ?: throw IllegalStateException("No key selected for profile")
                        val keyData = keyRepository.loadKeyData(keyId)
                        val passphrase = keyPassphrase
                            ?: preferences.getKeyPassphrase(keyId)
                        SshConnectionManager.AuthMethod.PrivateKey(keyData, passphrase)
                    }
                }

                val config = SshConnectionManager.SshConfig(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authMethod = authMethod,
                    keepAliveInterval = profile.keepAliveInterval,
                    reconnectEnabled = profile.autoReconnect
                )

                val result = sshManager.connect(config)
                if (result.isSuccess) {
                    val app = getApplication<Socks5Application>()
                    app.activeSshManager = sshManager

                    if (profile.authMethodType == AuthMethodType.PASSWORD && password != null) {
                        preferences.setPassword(profile.id, password)
                    }
                    if (profile.authMethodType == AuthMethodType.PRIVATE_KEY && keyPassphrase != null) {
                        profile.keyId?.let { preferences.setKeyPassphrase(it, keyPassphrase) }
                    }

                    profileRepository.markUsed(profile.id)
                    startSocks5Server()
                    startVpn()
                } else {
                    _errorMessage.emit(
                        result.exceptionOrNull()?.message ?: "Connection failed"
                    )
                }
            } catch (e: Exception) {
                _errorMessage.emit(e.message ?: "Connection error")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            stopVpn()
            stopSocks5Server()
            sshManager.disconnect()
            getApplication<Socks5Application>().activeSshManager = null
            _vpnActive.value = false
        }
    }

    private fun startVpn() {
        val context = getApplication<Application>()
        val intent = Intent(context, Socks5VpnService::class.java).apply {
            action = Socks5VpnService.ACTION_CONNECT
            putExtra(Socks5VpnService.EXTRA_DNS_SERVER, preferences.dnsServer)
            putExtra(Socks5VpnService.EXTRA_CUSTOM_HOSTS, serializeHosts(preferences.getCustomHosts()))
        }
        // Android O+: explicitly launch as foreground service so going Home/minimizing
        // cannot demote/kill it during the startup window.
        ContextCompat.startForegroundService(context, intent)
        _vpnActive.value = true
    }

    private fun startSocks5Server() {
        getApplication<Socks5Application>().startSocksServer(preferences.localSocksPort)
    }

    private fun stopSocks5Server() {
        getApplication<Socks5Application>().stopSocksServer()
    }

    private fun stopVpn() {
        val context = getApplication<Application>()
        val intent = Intent(context, Socks5VpnService::class.java).apply {
            action = Socks5VpnService.ACTION_DISCONNECT
        }
        context.startService(intent)
        _vpnActive.value = false
    }

    fun selectProfile(profile: ConnectionProfile?) {
        _selectedProfile.value = profile
    }

    suspend fun saveProfile(profile: ConnectionProfile): Long {
        return if (profile.id == 0L) {
            profileRepository.insert(profile)
        } else {
            profileRepository.update(profile)
            profile.id
        }
    }

    fun savePassword(profileId: Long, password: String) {
        preferences.setPassword(profileId, password)
    }

    suspend fun deleteProfile(profile: ConnectionProfile) {
        profileRepository.delete(profile)
        preferences.clearProfileSecrets(profile.id)
    }

    private fun serializeHosts(hosts: Map<String, String>): String {
        val json = JSONObject()
        for ((hostname, ip) in hosts) {
            json.put(hostname, ip)
        }
        return json.toString()
    }

    suspend fun deleteKey(key: SshKey) {
        keyRepository.deleteKey(key.id)
        preferences.clearKeySecrets(key.id)
    }

    suspend fun generateKey(
        name: String,
        algorithm: String,
        keySize: Int,
        comment: String = ""
    ): Long {
        return keyRepository.generateKey(name, algorithm, keySize, comment)
    }

    suspend fun importKey(
        name: String,
        keyData: ByteArray,
        algorithm: String,
        keySize: Int,
        comment: String = ""
    ): Long {
        return keyRepository.importKey(name, keyData, algorithm, keySize, comment)
    }
}
