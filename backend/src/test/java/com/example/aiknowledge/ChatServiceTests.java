package com.example.aiknowledge;

import java.util.List;
import java.util.concurrent.*;
import com.example.aiknowledge.service.*;
import com.example.aiknowledge.model.ChatMessage;
import com.example.aiknowledge.exception.ChatException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatServiceTests {
    final List<ChatMessage> question=List.of(new ChatMessage("user","hello"));
    @Test void concurrentSendForSameUserIsRejectedAndSlotIsReleasedAfterFailure() throws Exception {
        var client=mock(LlmClient.class);
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        when(client.complete(anyList())).thenAnswer(invocation->{
            entered.countDown();
            if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("test did not release model");
            throw new ChatException(502,"test upstream failure");
        });
        var service=new ChatService(client);
        var worker=Executors.newSingleThreadExecutor();
        try {
            var pending=worker.submit(()->service.send(1,question));
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            assertEquals(429,assertThrows(ChatException.class,()->service.send(1,question)).status());
            release.countDown();
            assertThrows(ExecutionException.class,()->pending.get(3,TimeUnit.SECONDS));
            assertEquals(502,assertThrows(ChatException.class,()->service.send(1,question)).status());
            verify(client,times(2)).complete(anyList());
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @Test void malformedHistoryAndExcessiveContextNeverReachProvider() {
        var client=mock(LlmClient.class);
        var service=new ChatService(client);
        var u=new ChatMessage("user","x".repeat(2000));
        var a=new ChatMessage("assistant","x".repeat(6000));
        for(var messages:List.of(List.of(u,a),List.of(u,u,u),List.of(u,a,u,a,u))) {
            assertEquals(400,assertThrows(ChatException.class,()->service.send(1,messages)).status());
        }
        verifyNoInteractions(client);
    }
}
