package com.example.aiknowledge.service;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.example.aiknowledge.exception.ChatException;

@Component
public class RerankClient {
    private final String endpoint,model,key;
    private final int timeout;
    private final JsonMapper json=JsonMapper.builder().build();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    public RerankClient(@Value("${app.rerank.endpoint:}") String endpoint,@Value("${app.rerank.model:}") String model,
        @Value("${app.rerank.timeout-seconds:45}") int timeout,@Value("${app.rerank.api-key:}") String key) {
        this.endpoint=normalize(endpoint); this.model=normalize(model); this.timeout=timeout; this.key=normalize(key);
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
            valid=uri.getHost()!=null && ("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme()))
                && uri.getUserInfo()==null && uri.getQuery()==null && uri.getFragment()==null
                && !key.isBlank() && !model.isBlank() && timeout>=1 && timeout<=120;
        } catch(IllegalArgumentException ignored) {}
        return new Configuration(valid,model);
    }
    /** 返回服务商提供的零基数组下标，调用者据此匹配自己的候选原文。 */
    public List<Integer> select(String question,List<String> texts) {
        if(question==null || question.isBlank() || question.length()>1000 || texts==null || texts.size()>6
            || texts.stream().anyMatch(t->t==null || t.isBlank() || t.length()>800))
            throw new ChatException(400,"重排问题或候选超出本课限制。");
        if(texts.size()<2) return texts.isEmpty()?List.of():List.of(0);
        if(!configuration().configured()) throw new ChatException(503,"重排配置不正确，请检查 RERANK_ENDPOINT、RERANK_MODEL 与 RERANK_API_KEY 并重启后端。");
        var request=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(timeout))
            .header("Content-Type","application/json").header("Authorization","Bearer "+key)
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("model",model,"query",question,
                "documents",texts,"top_n",Math.min(3,texts.size()),"return_documents",false)))).build();
        var future=http.sendAsync(request,info->new EmbeddingClient.BoundedBody());
        try {
            var response=future.get(timeout,TimeUnit.SECONDS);
            if(response.statusCode()==401 || response.statusCode()==403) throw new ChatException(502,"重排服务拒绝访问，请检查硅基流动密钥和模型权限。");
            if(response.statusCode()==429) throw new ChatException(503,"重排服务限流或额度不足，请稍后手动重试。");
            if(response.statusCode()==404) throw new ChatException(503,"重排接口或模型不存在，请检查地址与模型名。");
            if(response.statusCode()!=200) throw new ChatException(502,"重排服务返回错误，请检查输入和服务配置。");
            return decode(json.readTree(response.body()),texts.size());
        } catch(TimeoutException error) {
            future.cancel(true); throw new ChatException(504,"重排请求超时，系统未自动重试。");
        } catch(InterruptedException error) {
            future.cancel(true); Thread.currentThread().interrupt(); throw new ChatException(503,"重排请求已中断。");
        } catch(ChatException error) { throw error; }
        catch(ExecutionException error) {
            if(error.getCause() instanceof HttpTimeoutException) throw new ChatException(504,"重排请求超时，系统未自动重试。");
            throw new ChatException(503,"无法完成重排服务请求，请检查网络与配置。");
        } catch(Exception error) { throw new ChatException(502,"重排响应格式无效，系统未自动重试。"); }
    }
    List<Integer> decode(JsonNode response,int count) {
        var rows=response.path("results");
        if(!rows.isArray() || rows.size()!=Math.min(3,count)) throw new ChatException(502,"重排返回的候选数量不正确。");
        var indices=new ArrayList<Integer>(); double previous=Double.POSITIVE_INFINITY;
        for(var row:rows) {
            var index=row.path("index"); var score=row.path("relevance_score");
            if(!index.isIntegralNumber() || !index.canConvertToInt() || !score.isNumber()) throw new ChatException(502,"重排响应缺少有效下标或分数。");
            int at=index.asInt(); double value=score.asDouble();
            if(at<0 || at>=count || indices.contains(at) || !Double.isFinite(value) || value>previous)
                throw new ChatException(502,"重排下标重复、越界或分数顺序无效。");
            indices.add(at); previous=value;
        }
        return List.copyOf(indices);
    }
}
