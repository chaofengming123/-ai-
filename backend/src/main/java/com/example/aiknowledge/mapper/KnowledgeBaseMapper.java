package com.example.aiknowledge.mapper;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiknowledge.entity.KnowledgeBaseEntity;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBaseEntity> {
    @org.apache.ibatis.annotations.Select("SELECT id, name, description, document_count, category FROM knowledge_base WHERE id = #{id} FOR UPDATE")
    KnowledgeBaseEntity findForUpdate(@org.apache.ibatis.annotations.Param("id") long id);
}
