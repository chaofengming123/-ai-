package com.example.aiknowledge.service;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.example.aiknowledge.exception.ChatException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class QdrantClient {
    private final String endpoint;
    private final JsonMapper json=JsonMapper.builder().build();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    public QdrantClient(@Value("${app.qdrant.endpoint:http://127.0.0.1:6333}") String endpoint) {
        this.endpoint=endpoint.strip().replaceAll("/+$", "");
    }
    JsonNode call(String method,String path,Object body,boolean allowMissing) {
        // 本课只接本机 Docker Qdrant；云端鉴权与部署留到后续课程。
        var uri=URI.create(endpoint);
        if(!"http".equals(uri.getScheme()) || !Set.of("127.0.0.1","localhost","[::1]").contains(Objects.toString(uri.getHost(),""))
            || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !uri.getPath().isEmpty())
            throw new ChatException(503,"Qdrant 地址需为本机 HTTP 服务根地址。");
        var request=HttpRequest.newBuilder(URI.create(endpoint+path)).timeout(Duration.ofSeconds(10))
            .header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():
                HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var future=http.sendAsync(request,info->new EmbeddingClient.BoundedBody());
        try {
            var response=future.get(10,TimeUnit.SECONDS);
            if(response.statusCode()==404 && allowMissing) return null;
            if(response.statusCode()/100!=2) throw new ChatException(502,"Qdrant 操作失败，请检查服务状态与集合配置。");
            return json.readTree(response.body()).path("result");
        } catch(InterruptedException e) {
            future.cancel(true); Thread.currentThread().interrupt(); throw new ChatException(503,"向量存储请求已中断。");
        } catch(TimeoutException e) {
            future.cancel(true); throw new ChatException(504,"Qdrant 请求超时，写入可能已完成，请检查状态后再操作。");
        } catch(ChatException e) { throw e; }
        catch(Exception e) { throw new ChatException(503,"无法完成 Qdrant 请求，请确认本机 Qdrant 已启动。"); }
    }
    public JsonNode info(String collection) { return call("GET","/collections/"+collection,null,true); }
    public void remove(String collection) { call("DELETE","/collections/"+collection,null,true); }
    public void ensure(String collection,int dimensions) {
        var info=info(collection);
        if(info==null) {
            call("PUT","/collections/"+collection,Map.of("vectors",Map.of("size",dimensions,"distance","Cosine")),false);
            info=info(collection);
        }
        verify(info,dimensions);
    }
    public void verify(JsonNode info,int dimensions) {
        if(info==null || info.path("config").path("params").path("vectors").path("size").asInt()!=dimensions
            || !info.path("config").path("params").path("vectors").path("distance").asText().equals("Cosine"))
            throw new ChatException(409,"已有集合的维度或距离规则不匹配，请使用新的模型索引，不能混用旧向量。");
    }
    public void upsert(String collection,List<Map<String,Object>> points) {
        var result=call("PUT","/collections/"+collection+"/points?wait=true",Map.of("points",points),false);
        if(!"completed".equals(result.path("status").asText())) throw new ChatException(503,"向量写入尚未确认完成，请稍后检查状态。");
    }
    public JsonNode search(String collection,double[] vector) {
        return call("POST","/collections/"+collection+"/points/query",
            Map.of("query",vector,"limit",3,"with_payload",true,"with_vector",false),false).path("points");
    }
    // 第 27 课单文档最多 12 块；不允许把第一页误当成完整文档。
    public JsonNode scanDocument(String collection) {
        var result=call("POST","/collections/"+collection+"/points/scroll",
            Map.of("limit",12,"with_payload",true,"with_vector",false),false);
        if(!result.has("next_page_offset") || !result.path("next_page_offset").isNull())
            throw new ChatException(502,"文档索引扫描未完整返回，请检查索引块数。");
        return result.path("points");
    }
}
