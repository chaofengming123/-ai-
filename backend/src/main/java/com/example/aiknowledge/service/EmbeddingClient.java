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

@Component
public class EmbeddingClient {
    private final String endpoint,model,key;
    private final int timeout;
    private final JsonMapper json=JsonMapper.builder().build();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    public EmbeddingClient(@Value("${app.embedding.endpoint:}") String endpoint,@Value("${app.embedding.model:}") String model,
            @Value("${app.embedding.timeout-seconds:90}") int timeout,
            @Value("${app.embedding.api-key:}") String key) {
        this.endpoint=normalize(endpoint); this.model=normalize(model); this.timeout=timeout;
        this.key=normalize(key);
    }
    private static String normalize(String value) {
        String text=value.strip();
        if(text.length()>1 && ((text.startsWith("'") && text.endsWith("'")) || (text.startsWith("\"") && text.endsWith("\""))))
            return text.substring(1,text.length()-1).strip();
        return text;
    }
    public record Configuration(boolean configured,String model) {}
    public Configuration configuration() {
        boolean valid=false;
        try {
            var uri=URI.create(endpoint);
            boolean local=Set.of("127.0.0.1","localhost","[::1]").contains(Objects.toString(uri.getHost(),""));
            // HTTP 回环地址用于模拟上游测试，部署配置使用 HTTPS。
            boolean address=uri.getHost()!=null && !key.isBlank()
                && ("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme()));
            valid=address && uri.getUserInfo()==null && uri.getQuery()==null && uri.getFragment()==null
                && !model.isBlank() && timeout>=1 && timeout<=120;
        } catch(IllegalArgumentException ignored) { }
        return new Configuration(valid,model);
    }
    HttpRequest request(List<String> input) {
        if(!configuration().configured()) throw new ChatException(503,"Embedding 配置不正确，请检查后端的服务商、地址、模型和密钥配置。");
        var body=new LinkedHashMap<String,Object>(); body.put("model",model); body.put("input",input);
        body.put("encoding_format","float");
        var builder=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(timeout))
            .header("Content-Type","application/json");
        builder.header("Authorization","Bearer "+key);
        return builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }
    public List<double[]> embed(List<String> input) {
        var request=request(input);
        var future=http.sendAsync(request,info->new BoundedBody());
        try {
            var response=future.get(timeout,TimeUnit.SECONDS);
            if(response.statusCode()==404) throw new ChatException(503,"向量模型或接口不存在，请核对地址和模型名。");
            if(response.statusCode()==401 || response.statusCode()==403) throw new ChatException(502,"向量服务拒绝访问，请检查后端 Embedding 密钥与模型权限。");
            if(response.statusCode()==429) throw new ChatException(503,"向量服务限流或额度不足，请稍后手动重试。");
            if(response.statusCode()==400) throw new ChatException(400,"向量模型拒绝输入，请缩短文字并检查模型是否支持 Embedding。");
            if(response.statusCode()!=200) throw new ChatException(502,"向量服务返回错误，请检查配置与服务状态。");
            return decode(json.readTree(response.body()),input.size());
        } catch(TimeoutException error) {
            future.cancel(true); throw new ChatException(504,"向量计算超时，请稍后手动重试。请求可能已被处理，系统不会自动重试。");
        } catch(InterruptedException error) {
            future.cancel(true); Thread.currentThread().interrupt(); throw new ChatException(503,"向量实验已中断。");
        } catch(ChatException error) { throw error; }
        catch(ExecutionException error) {
            if(error.getCause() instanceof HttpTimeoutException) throw new ChatException(504,"向量计算超时，请稍后手动重试。");
            throw new ChatException(503,"无法完成向量服务请求，请检查网络与服务配置。");
        } catch(Exception error) { throw new ChatException(502,"无法读取有效向量，服务没有自动重试。"); }
    }
    List<double[]> decode(tools.jackson.databind.JsonNode response,int count) {
            var data=response.path("data");
            if(!data.isArray() || data.size()!=count) throw new ChatException(502,"向量数量与输入文字不一致。");
            var rows=new ArrayList<tools.jackson.databind.JsonNode>();
            {
                rows.addAll(Collections.nCopies(count,null));
                for(var item:data) {
                    var index=item.path("index");
                    if(!index.isIntegralNumber() || !index.canConvertToInt()) throw new ChatException(502,"向量响应缺少有效输入索引。");
                    int at=index.asInt();
                    if(at<0 || at>=count || rows.get(at)!=null) throw new ChatException(502,"向量响应索引重复或越界。");
                    rows.set(at,item.path("embedding"));
                }
            }
            var vectors=new ArrayList<double[]>(); int dimensions=0;
            for(var row:rows) {
                if(!row.isArray() || row.size()<1 || row.size()>4096 || (dimensions!=0 && dimensions!=row.size()))
                    throw new ChatException(502,"向量维度无效或不一致。");
                dimensions=row.size(); var vector=new double[dimensions]; double norm=0;
                for(int i=0;i<dimensions;i++) {
                    if(!row.get(i).isNumber()) throw new ChatException(502,"向量包含非数值内容。");
                    vector[i]=row.get(i).asDouble(); norm=Math.hypot(norm,vector[i]);
                }
                if(!Double.isFinite(norm) || norm==0) throw new ChatException(502,"向量包含无效数值或零向量。");
                vectors.add(vector);
            }
            return List.copyOf(vectors);
    }
    private static class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for(var chunk:chunks) {
                if(bytes.size()+chunk.remaining()>2*1024*1024) {
                    subscription.cancel(); result.completeExceptionally(new IOException("Embedding response too large")); return;
                }
                byte[] part=new byte[chunk.remaining()]; chunk.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
