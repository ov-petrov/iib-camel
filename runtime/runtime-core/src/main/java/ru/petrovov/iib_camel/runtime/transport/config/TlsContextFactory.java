package ru.petrovov.iib_camel.runtime.transport.config;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

final class TlsContextFactory {
    private TlsContextFactory() {
    }

    static SSLSocketFactory socketFactory(TlsProperties properties) throws Exception {
        if (properties.getKeyStore() == null && properties.getTrustStore() == null) {
            return SSLContext.getDefault().getSocketFactory();
        }
        KeyManagerFactory keys = null;
        TrustManagerFactory trust = null;
        String keyStore = properties.getKeyStore();
        String keyStoreType = properties.getKeyStoreType();
        String keyStorePassword = properties.getKeyStorePassword();
        if (keyStore == null) {
            // SSLContext.init(null, ...) does not inherit the JVM client key managers.
            keyStore = System.getProperty("javax.net.ssl.keyStore");
            keyStoreType = System.getProperty("javax.net.ssl.keyStoreType", KeyStore.getDefaultType());
            keyStorePassword = System.getProperty("javax.net.ssl.keyStorePassword");
        }
        if (keyStore != null && !keyStore.isBlank()) {
            keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            var store = load(keyStore, keyStoreType, keyStorePassword);
            var password = properties.getKeyPassword() == null
                ? keyStorePassword
                : properties.getKeyPassword();
            keys.init(store, chars(password));
        }
        if (properties.getTrustStore() != null) {
            trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(load(
                properties.getTrustStore(),
                properties.getTrustStoreType(),
                properties.getTrustStorePassword()));
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(
            keys == null ? null : keys.getKeyManagers(),
            trust == null ? null : trust.getTrustManagers(),
            null);
        return context.getSocketFactory();
    }

    private static KeyStore load(String path, String type, String password) throws Exception {
        KeyStore store = KeyStore.getInstance(type);
        if ("NONE".equals(path)) {
            store.load(null, chars(password));
            return store;
        }
        try (var input = Files.newInputStream(Path.of(path))) {
            store.load(input, chars(password));
        }
        return store;
    }

    private static char[] chars(String value) {
        return value == null ? null : value.toCharArray();
    }
}
