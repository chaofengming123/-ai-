package com.example.aiknowledge.mapper;

import com.example.aiknowledge.model.DocumentInfo;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface DocumentMapper {
    @Insert("INSERT INTO document(knowledge_base_id,file_name,object_key,file_type,file_size,storage_backend,storage_bucket) VALUES(#{baseId},#{name},#{key},#{type},#{size},#{backend},#{bucket})")
    int insert(@Param("baseId") long baseId, @Param("name") String name,
               @Param("key") String key, @Param("type") String type, @Param("size") long size,
               @Param("backend") String backend, @Param("bucket") String bucket);

    @Select("SELECT id,storage_backend,storage_bucket,object_key,file_size FROM document WHERE id=#{id} FOR UPDATE")
    com.example.aiknowledge.model.DocumentObject lockObject(long id);

    @Select("SELECT id FROM document WHERE storage_backend='LOCAL' ORDER BY id")
    List<Long> localDocumentIds();

    @Update("UPDATE document SET object_key=#{key},storage_backend='MINIO',storage_bucket=#{bucket} WHERE id=#{id} AND storage_backend='LOCAL'")
    int migrate(@Param("id") long id,@Param("key") String key,@Param("bucket") String bucket);

    @Select("SELECT id,knowledge_base_id,file_name,file_type,file_size,status,created_at FROM document WHERE object_key=#{key}")
    DocumentInfo findByKey(String key);

    @Select("SELECT id,knowledge_base_id,file_name,file_type,file_size,status,created_at FROM document WHERE id=#{id}")
    DocumentInfo findById(long id);

    @Select("SELECT id,storage_backend,storage_bucket,object_key,file_size FROM document WHERE id=#{id}")
    com.example.aiknowledge.model.DocumentObject object(long id);

    @Select("SELECT id,knowledge_base_id,file_name,file_type,file_size,status,created_at FROM document WHERE knowledge_base_id=#{baseId} ORDER BY id DESC")
    List<DocumentInfo> list(long baseId);
}
