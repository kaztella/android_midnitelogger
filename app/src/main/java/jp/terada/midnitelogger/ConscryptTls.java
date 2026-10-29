package jp.terada.midnitelogger;

import org.conscrypt.Conscrypt;

import java.security.Provider;
import java.security.Security;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/**
 * Creates a modern TLS stack inside the APK for old Android devices.
 * Certificate and hostname verification remain enabled by HttpsURLConnection.
 */
public final class ConscryptTls {
    private static Provider provider;
    private static SSLSocketFactory socketFactory;

    private ConscryptTls() { }

    public static synchronized SSLSocketFactory getSocketFactory() throws Exception {
        if (socketFactory != null) return socketFactory;

        if (!Conscrypt.isAvailable()) {
            throw new IllegalStateException("Conscrypt native library is not available");
        }

        provider = Conscrypt.newProvider();
        // Install for this process. Explicit-provider SSLContext below ensures
        // this connection uses Conscrypt even if another provider is first.
        if (Security.getProvider(provider.getName()) == null) {
            Security.insertProviderAt(provider, 1);
        }

        SSLContext context = SSLContext.getInstance("TLS", provider);
        context.init(null, null, null); // system trust store; no trust bypass
        socketFactory = context.getSocketFactory();
        return socketFactory;
    }

    public static synchronized String providerInfo() {
        if (provider == null) return "Conscrypt(not initialized)";
        return provider.getName() + " " + provider.getVersion();
    }
}
