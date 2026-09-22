package com.example.aiknowledge.service;

import java.util.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import com.example.aiknowledge.exception.ChatException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingClientContractsTests {
    final JsonMapper json=JsonMapper.builder().build();
    @Test void cloudAdapterUsesIndependentKeyAndStandardEmbeddingPayload() throws Exception {
        var client=new EmbeddingClient("https://api.example.com/v1/embeddings","paid-or-free-model",90,"embedding-test-key");
        var request=client.request(List.of("query","document"));
        assertEquals("Bearer embedding-test-key",request.headers().firstValue("Authorization").orElseThrow());
        var result=new CompletableFuture<String>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) { var part=new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part); }
            public void onError(Throwable error) { result.completeExceptionally(error); }
            public void onComplete() { result.complete(bytes.toString(java.nio.charset.StandardCharsets.UTF_8)); }
        });
        var body=json.readTree(result.get(1,TimeUnit.SECONDS));
        assertEquals("float",body.path("encoding_format").asText());
        assertFalse(body.has("truncate")); assertFalse(body.has("messages"));
        assertEquals(2,body.path("input").size());
    }
    @Test void cloudVectorsAreReorderedByInputIndexAndInvalidVectorsAreRejected() {
        var client=new EmbeddingClient("https://api.example.com/v1/embeddings","model",90,"key");
        var vectors=client.decode(json.readTree("{\"data\":[{\"index\":1,\"embedding\":[0,1]},{\"index\":0,\"embedding\":[1,0]}]}"),2);
        assertArrayEquals(new double[]{1,0},vectors.get(0));
        for(String data:List.of(
            "{\"data\":[{\"index\":0,\"embedding\":[1,0]},{\"index\":0,\"embedding\":[0,1]}]}",
            "{\"data\":[{\"index\":0,\"embedding\":[0,0]},{\"index\":1,\"embedding\":[0,1]}]}",
            "{\"data\":[{\"index\":0,\"embedding\":[1,0]},{\"index\":1,\"embedding\":[1]}]}",
            "{\"data\":[{\"index\":0,\"embedding\":[1e400,0]},{\"index\":1,\"embedding\":[1,0]}]}"))
            assertThrows(ChatException.class,()->client.decode(json.readTree(data),2));
    }
    @Test void remoteRequiresHttpsAndIndependentKeyAndAcceptsQuotedConfiguration() {
        assertFalse(new EmbeddingClient("https://remote.example/v1/embeddings","m",90,"").configuration().configured());
        assertFalse(new EmbeddingClient("http://remote.example/v1/embeddings","m",90,"k").configuration().configured());
        var configured=new EmbeddingClient("'https://api.siliconflow.cn/v1/embeddings'","'BAAI/bge-m3'",90,"'test-key'");
        assertTrue(configured.configuration().configured());
        assertEquals("Bearer test-key",configured.request(List.of("text")).headers().firstValue("Authorization").orElseThrow());
    }
}
