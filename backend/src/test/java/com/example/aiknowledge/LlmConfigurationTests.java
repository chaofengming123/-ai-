package com.example.aiknowledge;

import com.example.aiknowledge.service.LlmClient;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LlmConfigurationTests {
    @Test void acceptsPlainAndQuotedEnvValues() {
        for (String quote : new String[]{"", "'", "\""}) {
            var client = new LlmClient(" " + quote + "https://open.bigmodel.cn/api/paas/v4/chat/completions" + quote + " ",
                    quote + "test-key" + quote, quote + "glm-4.7-flash" + quote, 45,
                    quote + "max_tokens" + quote, quote + "disabled" + quote);
            assertTrue(client.configuration().configured());
            assertEquals("glm-4.7-flash", client.configuration().model());
        }
    }

    @Test void quotedEmptyKeyStillMeansUnconfigured() {
        var client = new LlmClient("https://example.com/chat", "''", "model", 45, "max_tokens", "disabled");
        assertFalse(client.configuration().configured());
    }

    @Test void invalidSettingsRemainRejected() {
        assertFalse(new LlmClient("http://example.com/chat", "key", "model", 45, "max_tokens", "disabled")
                .configuration().configured());
        assertFalse(new LlmClient("https://example.com/chat", "key", "model", 45, "invalid", "disabled")
                .configuration().configured());
    }
}
