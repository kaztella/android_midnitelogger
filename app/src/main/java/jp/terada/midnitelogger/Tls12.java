package jp.terada.midnitelogger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Android 4.1-4.4: TLS 1.2 is supported but may not be enabled by default. */
public final class Tls12 extends SSLSocketFactory {
    private final SSLSocketFactory delegate;

    public Tls12() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, null, null);
        delegate = context.getSocketFactory();
    }

    private Socket patch(Socket socket) {
        if (socket instanceof SSLSocket) {
            SSLSocket ssl = (SSLSocket) socket;
            String[] supported = ssl.getSupportedProtocols();
            boolean has12 = false;
            for (String p : supported) if ("TLSv1.2".equals(p)) has12 = true;
            if (has12) ssl.setEnabledProtocols(new String[]{"TLSv1.2"});
        }
        return socket;
    }

    @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
    @Override public Socket createSocket() throws IOException { return patch(delegate.createSocket()); }
    @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException { return patch(delegate.createSocket(s, host, port, autoClose)); }
    @Override public Socket createSocket(String host, int port) throws IOException { return patch(delegate.createSocket(host, port)); }
    @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException { return patch(delegate.createSocket(host, port, localHost, localPort)); }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException { return patch(delegate.createSocket(host, port)); }
    @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException { return patch(delegate.createSocket(address, port, localAddress, localPort)); }
}
