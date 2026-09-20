package com.example.aiknowledge.service;

import java.util.List;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import com.example.aiknowledge.entity.KnowledgeBaseEntity;
import com.example.aiknowledge.mapper.KnowledgeBaseMapper;

import org.springframework.stereotype.Service;
import com.example.aiknowledge.model.KnowledgeBase;
import com.example.aiknowledge.exception.KnowledgeBaseException;
import static com.example.aiknowledge.exception.KnowledgeBaseException.Kind.*;

@Service
public class KnowledgeBaseService {
    private final KnowledgeBaseMapper mapper;

    public KnowledgeBaseService(KnowledgeBaseMapper mapper) {
        this.mapper = mapper;
    }

    public List<KnowledgeBase> list() {
        return mapper.selectList(new LambdaQueryWrapper<KnowledgeBaseEntity>()
                .orderByAsc(KnowledgeBaseEntity::getId)).stream().map(this::toResponse).toList();
    }

    public KnowledgeBase get(long id) {
        KnowledgeBaseEntity record = mapper.selectById(id);
        if (record == null) throw new KnowledgeBaseException(NOT_FOUND, "知识库不存在。");
        return toResponse(record);
    }

    private KnowledgeBase toResponse(KnowledgeBaseEntity entity) {
        return new KnowledgeBase(entity.getId(), entity.getName(), entity.getDescription(),
                entity.getDocumentCount(), entity.getCategory());
    }

    public KnowledgeBase create(String name, String description) {
        String normalizedName = name == null ? "" : name.strip();
        String normalizedDescription = description == null ? "" : description.strip();
        if (normalizedName.isEmpty()) {
            throw new KnowledgeBaseException(INVALID_INPUT, "请输入知识库名称，不能只填写空格。");
        }
        if (normalizedName.length() > 60 || normalizedDescription.length() > 300) {
            throw new KnowledgeBaseException(INVALID_INPUT, "名称最多 60 个字符，描述最多 300 个字符。");
        }
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setName(normalizedName);
        entity.setDescription(normalizedDescription.isEmpty() ? "暂无描述" : normalizedDescription);
        entity.setDocumentCount(0);
        entity.setCategory("自建知识库");
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException error) {
            throw new KnowledgeBaseException(CONFLICT, "这个名称已经存在，请换一个名称。");
        }
        return toResponse(entity);
    }
}
