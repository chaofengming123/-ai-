package com.example.aiknowledge.mapper;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiknowledge.entity.KnowledgeBaseEntity;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBaseEntity> {
    // 旧 document_count 是早期页面示例数；对外改为统计真实文档，不覆盖旧数据。
    @org.apache.ibatis.annotations.Select("SELECT k.id,k.name,k.description,k.category,(SELECT COUNT(*) FROM document d WHERE d.knowledge_base_id=k.id) AS document_count FROM knowledge_base k ORDER BY k.id")
    java.util.List<KnowledgeBaseEntity> listWithDocumentCounts();

    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM document WHERE knowledge_base_id=#{id}")
    int countDocuments(long id);
    @org.apache.ibatis.annotations.Select("SELECT id, name, description, document_count, category FROM knowledge_base WHERE id = #{id} FOR UPDATE")
    KnowledgeBaseEntity findForUpdate(@org.apache.ibatis.annotations.Param("id") long id);
}
