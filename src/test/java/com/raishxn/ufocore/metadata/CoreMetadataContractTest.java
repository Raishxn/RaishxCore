package com.raishxn.ufocore.metadata;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CoreMetadataContractTest {
    @Test
    void declaresCoreIdentityAndAe2Dependency() throws IOException {
        try (InputStream stream = CoreMetadataContractTest.class.getResourceAsStream(
                "/META-INF/neoforge.mods.toml")) {
            String metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            String version = System.getProperty("raishxcore.modVersion");
            assertNotNull(version, "the Gradle test task must pass raishxcore.modVersion");
            assertTrue(metadata.contains("modId=\"raishxcore\""));
            assertTrue(metadata.contains("version=\"" + version + "\""),
                    () -> "generated metadata must declare the built version " + version);
            assertTrue(metadata.contains("modId=\"ae2\""));
            assertTrue(metadata.contains("versionRange=\"[19.2.17,20)\""));
        }
    }
}
