package com.example.aiknowledge.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;

/** 解析上游 SSE 帧；网络读取块不等于一条完整事件。 */
final class ModelStreamReader {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    static LlmClient.Reply read(InputStream input,String model,Consumer<String> delta) throws IOException {
        // 上限包括没有换行的恶意长帧，避免 readLine 无限制分配内存。
        var bounded=new FilterInputStream(input) {
            int count;
            @Override public int read() throws IOException {
                int value=super.read();
                if(value!=-1 && ++count>256*1024) throw new IOException("Stream too large");
                return value;
            }
            @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                int size=in.read(bytes,offset,length);
                if(size>0 && (count+=size)>256*1024) throw new IOException("Stream too large");
                return size;
            }
        };
        var reader=new BufferedReader(new InputStreamReader(bounded,StandardCharsets.UTF_8));
        var frame=new StringBuilder();
        var answer=new StringBuilder();
        String finish=null;
        for(String line;(line=reader.readLine())!=null;) {
            if(line.startsWith("data:")) {
                String value=line.substring(5);
                if(value.startsWith(" ")) value=value.substring(1);
                if(!frame.isEmpty()) frame.append('\n');
                frame.append(value);
            } else if(line.isEmpty() && !frame.isEmpty()) {
                String data=frame.toString(); frame.setLength(0);
                if(data.equals("[DONE]")) {
                    if(finish==null || answer.toString().isBlank()) throw new IOException("Incomplete answer");
                    return new LlmClient.Reply(answer.toString(),model,finish.equals("length"));
                }
                var event=JSON.readTree(data);
                if(event.has("error")) throw new IOException("Upstream stream error");
                var choices=event.path("choices");
                if(!choices.isArray()) throw new IOException("Invalid choices");
                if(choices.isEmpty()) continue; // 可选用量帧。
                var choice=choices.path(0);
                var content=choice.path("delta").path("content");
                if(!content.isMissingNode() && !content.isNull()) {
                    if(!content.isString() || finish!=null) throw new IOException("Invalid delta");
                    String text=content.asText();
                    if(answer.length()+text.length()>6000) throw new IOException("Answer too large");
                    answer.append(text);
                    if(!text.isEmpty()) delta.accept(text);
                }
                var reason=choice.path("finish_reason");
                if(reason.isString()) {
                    finish=reason.asText();
                    if(!finish.equals("stop") && !finish.equals("length")) throw new IOException("Unsupported finish");
                }
            }
        }
        throw new IOException("Stream ended without DONE");
    }
}
