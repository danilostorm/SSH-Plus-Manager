package hev.sockstun;

import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Local SOCKS5 server whose CONNECT requests are transported through one SSH
 * session using direct-tcpip channels. The native tun2socks engine connects to
 * this proxy at 127.0.0.1:1080.
 */
public final class SshSocksProxy {
    private final String sshHost;
    private final int sshPort;
    private final String username;
    private final String password;
    private final int localPort;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile boolean running;
    private volatile Session session;
    private ServerSocket serverSocket;

    public SshSocksProxy(String sshHost, int sshPort, String username, String password, int localPort) {
        this.sshHost = sshHost;
        this.sshPort = sshPort;
        this.username = username;
        this.password = password;
        this.localPort = localPort;
    }

    public synchronized void connect() throws Exception {
        if (session != null && session.isConnected()) return;

        if (session != null) {
            try { session.disconnect(); } catch (Exception ignored) {}
            session = null;
        }

        JSch jsch = new JSch();
        Session newSession = jsch.getSession(username, sshHost, sshPort);
        newSession.setPassword(password);

        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", "password,keyboard-interactive");
        newSession.setConfig(config);
        newSession.setServerAliveInterval(30_000);
        newSession.setServerAliveCountMax(3);
        newSession.connect(15_000);
        session = newSession;
    }

    public synchronized void start() throws Exception {
        connect();
        if (running) return;

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort));
        running = true;
        executor.execute(this::acceptLoop);
    }

    public boolean isConnected() {
        Session s = session;
        return running && s != null && s.isConnected();
    }

    public synchronized void reconnectIfNeeded() throws Exception {
        if (!isConnected()) connect();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                executor.execute(() -> handleClient(client));
            } catch (IOException e) {
                if (running) {
                    try { Thread.sleep(250); } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private void handleClient(Socket client) {
        ChannelDirectTCPIP channel = null;
        try {
            client.setSoTimeout(20_000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            int version = in.read();
            if (version != 0x05) return;
            int methodsCount = in.read();
            if (methodsCount <= 0) return;
            byte[] methods = new byte[methodsCount];
            readFully(in, methods);
            out.write(new byte[]{0x05, 0x00});
            out.flush();

            int requestVersion = in.read();
            int command = in.read();
            int reserved = in.read();
            int addressType = in.read();
            if (requestVersion != 0x05 || reserved < 0) return;
            if (command != 0x01) {
                sendReply(out, 0x07);
                return;
            }

            String destination;
            if (addressType == 0x01) {
                byte[] addr = new byte[4];
                readFully(in, addr);
                destination = InetAddress.getByAddress(addr).getHostAddress();
            } else if (addressType == 0x03) {
                int len = in.read();
                if (len <= 0) throw new EOFException("Invalid domain length");
                byte[] domain = new byte[len];
                readFully(in, domain);
                destination = new String(domain, StandardCharsets.UTF_8);
            } else if (addressType == 0x04) {
                byte[] addr = new byte[16];
                readFully(in, addr);
                destination = InetAddress.getByAddress(addr).getHostAddress();
            } else {
                sendReply(out, 0x08);
                return;
            }

            int p1 = in.read();
            int p2 = in.read();
            if (p1 < 0 || p2 < 0) throw new EOFException("Missing destination port");
            int destinationPort = (p1 << 8) | p2;

            reconnectIfNeeded();
            Session activeSession = session;
            if (activeSession == null || !activeSession.isConnected()) {
                sendReply(out, 0x01);
                return;
            }

            channel = (ChannelDirectTCPIP) activeSession.openChannel("direct-tcpip");
            channel.setHost(destination);
            channel.setPort(destinationPort);
            String origin = client.getInetAddress() != null
                    ? client.getInetAddress().getHostAddress() : "127.0.0.1";
            channel.setOrgIPAddress(origin);
            channel.setOrgPort(client.getPort());

            InputStream sshIn = channel.getInputStream();
            OutputStream sshOut = channel.getOutputStream();
            channel.connect(12_000);

            sendReply(out, 0x00);
            client.setSoTimeout(0);

            CountDownLatch latch = new CountDownLatch(2);
            ChannelDirectTCPIP finalChannel = channel;
            executor.execute(() -> relay(in, sshOut, latch));
            executor.execute(() -> relay(sshIn, out, latch));
            latch.await();
            try { finalChannel.disconnect(); } catch (Exception ignored) {}
        } catch (Exception e) {
            try {
                OutputStream out = client.getOutputStream();
                sendReply(out, 0x01);
            } catch (Exception ignored) {}
        } finally {
            if (channel != null) {
                try { channel.disconnect(); } catch (Exception ignored) {}
            }
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private void relay(InputStream in, OutputStream out, CountDownLatch latch) {
        byte[] buffer = new byte[32 * 1024];
        try {
            int read;
            while (running && (read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (Exception ignored) {
        } finally {
            latch.countDown();
        }
    }

    private static void readFully(InputStream in, byte[] data) throws IOException {
        int offset = 0;
        while (offset < data.length) {
            int n = in.read(data, offset, data.length - offset);
            if (n < 0) throw new EOFException();
            offset += n;
        }
    }

    private static void sendReply(OutputStream out, int code) throws IOException {
        out.write(new byte[]{
                0x05, (byte) code, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00
        });
        out.flush();
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception ignored) {}
            serverSocket = null;
        }
        Session s = session;
        session = null;
        if (s != null) {
            try { s.disconnect(); } catch (Exception ignored) {}
        }
        executor.shutdownNow();
        try { executor.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
