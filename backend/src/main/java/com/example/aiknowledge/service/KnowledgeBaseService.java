package com.example.aiknowledge.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import com.example.aiknowledge.model.KnowledgeBase;
import com.example.aiknowledge.exception.KnowledgeBaseException;
import static com.example.aiknowledge.exception.KnowledgeBaseException.Kind.*;

@Service
public class KnowledgeBaseService {
    private final Map<Long, KnowledgeBase> records = new LinkedHashMap<>();
    private long nextId = 3;

    public KnowledgeBaseService() {
        records.put(1L, new KnowledgeBase(1, "公司制度知识库", "员工手册、考勤制度与差旅报销政策。", 3, "人力资源"));
        records.put(2L, new KnowledgeBase(2, "工程技术知识库", "开发规范、系统架构与技术协作文档。", 10, "工程技术"));
    }

    public synchronized List<KnowledgeBase> list() {
        return List.copyOf(records.values());
    }

    public synchronized KnowledgeBase get(long id) {
        KnowledgeBase record = records.get(id);
        if (record == null) {
            throw new KnowledgeBaseException(NOT_FOUND, "知识库不存在。");
        }
        return record;
    }

    public synchronized KnowledgeBase create(String name, String description) {
        String normalizedName = name == null ? "" : name.strip();
        String normalizedDescription = description == null ? "" : description.strip();
        if (normalizedName.isEmpty()) {
            throw new KnowledgeBaseException(INVALID_INPUT, "请输入知识库名称，不能只填写空格。");
        }
        if (normalizedName.length() > 60 || normalizedDescription.length() > 300) {
            throw new KnowledgeBaseException(INVALID_INPUT, "名称最多 60 个字符，描述最多 300 个字符。");
        }
        if (records.values().stream().anyMatch(item -> item.name().equals(normalizedName))) {
            throw new KnowledgeBaseException(CONFLICT, "这个名称已经存在，请换一个名称。");
        }
        KnowledgeBase record = new KnowledgeBase(nextId++, normalizedName,
                normalizedDescription.isEmpty() ? "暂无描述" : normalizedDescription, 0, "自建知识库");
        records.put(record.id(), record);
        return record;
    }
}
