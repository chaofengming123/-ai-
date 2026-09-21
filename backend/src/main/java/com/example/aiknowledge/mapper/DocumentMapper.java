package com.example.aiknowledge.mapper;

import com.example.aiknowledge.model.DocumentInfo;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface DocumentMapper {
    @Insert("INSERT INTO document(knowledge_base_id,file_name,object_key,file_type,file_size) VALUES(#{baseId},#{name},#{key},#{type},#{size})")
    int insert(@Param("baseId") long baseId, @Param("name") String name,
               @Param("key") String key, @Param("type") String type, @Param("size") long size);

    @Select("SELECT id,knowledge_base_id,file_name,file_type,file_size,status,created_at FROM document WHERE object_key=#{key}")
    DocumentInfo findByKey(String key);

    @Select("SELECT id,knowledge_base_id,file_name,file_type,file_size,status,created_at FROM document WHERE knowledge_base_id=#{baseId} ORDER BY id DESC")
    List<DocumentInfo> list(long baseId);
}
