package com.socks5

import android.app.Application
import com.jcraft.jsch.JSch
import com.socks5.socks.Socks5Server
import com.socks5.ssh.SshConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class Socks5Application : Application() {

    val jSch: JSch by lazy { JSch() }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val sharedSshManager: SshConnectionManager by lazy {
        SshConnectionManager(appScope)
    }

    @Volatile
    var activeSshManager: SshConnectionManager? = null

    @Volatile
    private var activeSocksServer: Socks5Server? = null

    @Synchronized
    fun startSocksServer(port: Int) {
        activeSocksServer?.stop()
        activeSocksServer = Socks5Server(
            sshManager = sharedSshManager,
            port = port
        ).also { server ->
            appScope.launch { server.start() }
        }
    }

    @Synchronized
    fun stopSocksServer() {
        activeSocksServer?.stop()
        activeSocksServer = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    companion object {
        lateinit var instance: Socks5Application
            private set
    }
}
