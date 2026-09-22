package com.example.aiknowledge.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelStreamReaderTests {
    String frame="data: {\"choices\":[{\"delta\":{\"content\":\"中文🙂\"},\"finish_reason\":\"stop\"}]}\r\n\r\n";
    @Test void decodesSplitUtf8AndRecognizesDone() throws Exception {
        byte[] bytes=(": ping\r\n\r\n"+frame+"data: [DONE]\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        var input=new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] b,int off,int len) { return super.read(b,off,Math.min(len,1)); }
        };
        var chunks=new ArrayList<String>();
        var result=ModelStreamReader.read(input,"glm",chunks::add);
        assertEquals(List.of("中文🙂"),chunks);
        assertEquals("中文🙂",result.content()); assertFalse(result.truncated());
    }
    @Test void missingDoneMalformedEventsAndUnboundedLinesFail() {
        for(String text:List.of(frame,"data: invalid\n\n","data: "+"x".repeat(270000),"data: [DONE]\n\n")) {
            assertThrows(Exception.class,()->ModelStreamReader.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),"glm",delta->{}));
        }
    }
}
