package ru.petrovov.iib_camel.runtime.transport.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.KeyStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TlsContextFactoryTest {
    @Test
    void explicitTrustStoreRetainsJvmClientKeyStoreConfiguration(@TempDir Path directory)
        throws Exception {
        Path trustStore = directory.resolve("synthetic-trust.p12");
        writeEmptyStore(trustStore);
        var properties = new TlsProperties();
        properties.setEnabled(true);
        properties.setTrustStore(trustStore.toString());
        properties.setTrustStorePassword("synthetic-password");
        String[] names = {
            "javax.net.ssl.keyStore",
            "javax.net.ssl.keyStoreType",
            "javax.net.ssl.keyStorePassword"
        };
        String[] original = new String[names.length];
        for (int index = 0; index < names.length; index++) {
            original[index] = System.getProperty(names[index]);
        }
        try {
            Path clientStore = directory.resolve("synthetic-client.p12");
            System.setProperty(names[0], clientStore.toString());
            System.setProperty(names[1], "PKCS12");
            System.setProperty(names[2], "synthetic-password");
            assertThatThrownBy(() -> TlsContextFactory.socketFactory(properties))
                .isInstanceOf(NoSuchFileException.class);
            writeEmptyStore(clientStore);
            assertThat(TlsContextFactory.socketFactory(properties)).isNotNull();
        } finally {
            for (int index = 0; index < names.length; index++) {
                if (original[index] == null) {
                    System.clearProperty(names[index]);
                } else {
                    System.setProperty(names[index], original[index]);
                }
            }
        }
    }

    private static void writeEmptyStore(Path path) throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        try (var output = Files.newOutputStream(path)) {
            store.store(output, "synthetic-password".toCharArray());
        }
    }
}
