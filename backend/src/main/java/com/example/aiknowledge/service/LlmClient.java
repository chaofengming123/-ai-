package com.example.aiknowledge.service;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import com.example.aiknowledge.exception.ChatException;
import com.example.aiknowledge.model.ChatMessage;

@Component
public class LlmClient {
    private final String endpoint,key,model,tokenParameter,thinking;
    private final int timeout;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json=JsonMapper.builder().build();
    public LlmClient(@Value("${app.llm.endpoint:}") String endpoint,@Value("${app.llm.api-key:}") String key,
            @Value("${app.llm.model:}") String model,@Value("${app.llm.timeout-seconds:45}") int timeout,
            @Value("${app.llm.token-parameter:max_tokens}") String tokenParameter,
            @Value("${app.llm.thinking:disabled}") String thinking) {
        this.endpoint=normalize(endpoint); this.key=normalize(key); this.model=normalize(model);
        this.timeout=timeout; this.tokenParameter=normalize(tokenParameter); this.thinking=normalize(thinking);
    }
    // IDEA imports .env as properties, which preserves shell-style surrounding quotes.
    private static String normalize(String value) {
        String text=value.strip();
        if(text.length()>=2 && ((text.startsWith("'") && text.endsWith("'"))
                || (text.startsWith("\"") && text.endsWith("\""))))
            return text.substring(1,text.length()-1).strip();
        return text;
    }
    public record Configuration(boolean configured,String model) {}
    public record Reply(String content,String model,boolean truncated) {}
    public Configuration configuration() { return new Configuration(configured(),model); }
    private boolean configured() {
        if(endpoint.isBlank() || key.isBlank() || model.isBlank() || timeout<1 || timeout>120
                || (!tokenParameter.equals("max_tokens") && !tokenParameter.equals("max_completion_tokens"))
                || !Set.of("","enabled","disabled").contains(thinking)) return false;
        try {
            URI uri=URI.create(endpoint);
            boolean local=Set.of("localhost","127.0.0.1","[::1]").contains(Objects.toString(uri.getHost(),""));
            return uri.getHost()!=null && uri.getUserInfo()==null && uri.getFragment()==null && uri.getQuery()==null
                    && (uri.getScheme().equals("https") || (uri.getScheme().equals("http") && local));
        } catch(IllegalArgumentException e) { return false; }
    }
    public Reply complete(List<ChatMessage> messages) {
        if(!configured()) throw new ChatException(503,"模型尚未配置。请在后端设置模型地址、模型名和 API Key，然后重启后端。");
        var body=new LinkedHashMap<String,Object>();
        body.put("model",model); body.put("messages",messages); body.put("stream",false);
        body.put(tokenParameter,800);
        if(!thinking.isBlank()) body.put("thinking",Map.of("type",thinking));
        var request=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(timeout))
                .header("Authorization","Bearer "+key).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var future=http.sendAsync(request,info->new BoundedBody());
        try {
            var response=future.get(timeout,TimeUnit.SECONDS);
            int code=response.statusCode();
            if(code==401 || code==403) throw new ChatException(502,"模型服务拒绝访问，请检查后端 API Key 和模型权限。");
            if(code==429) throw new ChatException(503,"模型服务暂时限流或额度不足，请稍后重试并检查模型账户额度。");
            if(code<200 || code>=300) throw new ChatException(502,"模型服务请求失败，请检查模型名、接口地址和服务状态。");
            var data=json.readTree(response.body());
            var choice=data.path("choices").path(0);
            var answer=choice.path("message").path("content");
            if(!answer.isString() || answer.asText().isBlank() || answer.asText().length()>6000)
                throw new ChatException(502,"模型没有返回有效文本，请检查接口兼容性或选择普通对话模型。");
            return new Reply(answer.asText(),model,"length".equals(choice.path("finish_reason").asText()));
        } catch(TimeoutException error) {
            future.cancel(true);
            throw new ChatException(504,"等待模型回复超时。请求可能已经产生用量，系统没有自动重试。");
        } catch(InterruptedException error) {
            future.cancel(true); Thread.currentThread().interrupt();
            throw new ChatException(503,"对话请求已中断，请稍后重试。");
        } catch(ChatException error) { throw error; }
        catch(ExecutionException error) {
            if(error.getCause() instanceof HttpTimeoutException)
                throw new ChatException(504,"等待模型回复超时。请求可能已经产生用量，系统没有自动重试。");
            throw new ChatException(502,"无法取得模型回复，请检查网络和接口配置；系统没有自动重试。");
        }
        catch(Exception error) {
            throw new ChatException(502,"无法取得模型回复，请检查网络和接口配置；系统没有自动重试。");
        }
    }
    // 限制上游响应大小，并把整个响应体纳入 future 超时，而不只等待响应头。
    private static class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for(var chunk:chunks) {
                if(bytes.size()+chunk.remaining()>256*1024) {
                    subscription.cancel(); result.completeExceptionally(new IOException("Response too large")); return;
                }
                byte[] part=new byte[chunk.remaining()]; chunk.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
