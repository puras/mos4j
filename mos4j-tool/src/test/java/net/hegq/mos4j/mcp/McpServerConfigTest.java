package net.hegq.mos4j.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务器配置：校验（fail-fast）与逐工具曝光解析。
 */
class McpServerConfigTest {

    @Test
    void stdioWithoutACommandIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new McpServerConfig("gh", McpTransport.STDIO, null, List.of(), Map.of(), null,
                        null, Map.of(), McpExposure.DEFERRED, Map.of(), false, null));

        assertTrue(error.getMessage().contains("command"), error.getMessage());
    }

    @Test
    void httpWithoutAUrlIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new McpServerConfig("remote", McpTransport.STREAMABLE_HTTP, null, List.of(), Map.of(),
                        null, null, Map.of(), McpExposure.DEFERRED, Map.of(), false, null));

        assertTrue(error.getMessage().contains("url"), error.getMessage());
    }

    @Test
    void theServerNameIsRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> new McpServerConfig(" ", McpTransport.STDIO, "npx", List.of(), Map.of(), null,
                        null, Map.of(), McpExposure.DEFERRED, Map.of(), false, null));
    }

    @Test
    void theDefaultExposureIsDeferred() {
        assertEquals(McpExposure.DEFERRED, McpServerConfig.stdio("gh", "npx", List.of()).exposure());
    }

    @Test
    void anExactToolOverrideBeatsTheWildcard() {
        McpServerConfig config = McpServerConfig.stdio("gh", "npx", List.of())
                .withExposure(McpExposure.DEFERRED)
                .withToolExposure("*", McpExposure.DIRECT)
                .withToolExposure("create_issue", McpExposure.HIDDEN);

        assertEquals(McpExposure.HIDDEN, config.exposureOf("create_issue"));
        assertEquals(McpExposure.DIRECT, config.exposureOf("search_issues"));
    }

    @Test
    void theServerDefaultIsTheLastResort() {
        McpServerConfig config = McpServerConfig.stdio("gh", "npx", List.of())
                .withExposure(McpExposure.DIRECT);

        assertEquals(McpExposure.DIRECT, config.exposureOf("anything"));
    }

    @Test
    void disabledIsVisibleInTheConfig() {
        assertTrue(McpServerConfig.http("remote", "https://example.com/mcp").asDisabled().disabled());
    }
}
