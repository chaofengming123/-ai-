package com.example.aiknowledge.mapper;

import org.apache.ibatis.annotations.*;
import com.example.aiknowledge.model.DocumentIndex;

@Mapper
public interface DocumentIndexMapper {
    @Select("SELECT * FROM document_index WHERE document_id=#{id}")
    DocumentIndex find(long id);
    @Insert("INSERT INTO document_index(document_id) VALUES(#{id}) ON DUPLICATE KEY UPDATE document_id=document_id")
    int initialize(long id);
    // 十分钟租约让进程意外退出后的任务可以由用户重新发起；每次尝试有独立版本。
    @Update("UPDATE document_index SET state='PROCESSING',attempt_id=#{attempt},error_message=NULL,updated_at=CURRENT_TIMESTAMP WHERE document_id=#{id} AND (state!='PROCESSING' OR updated_at < CURRENT_TIMESTAMP - INTERVAL 10 MINUTE)")
    int claim(@Param("id") long id,@Param("attempt") String attempt);
    @Update("UPDATE document_index SET state='READY',active_collection=#{collection},active_model=#{model},active_space=#{space},source_sha256=#{sha},chunk_count=#{count},dimensions=#{dimensions},source_characters=#{characters},note=#{note},error_message=NULL,updated_at=CURRENT_TIMESTAMP,indexed_at=CURRENT_TIMESTAMP WHERE document_id=#{id} AND state='PROCESSING' AND attempt_id=#{attempt}")
    int publish(@Param("id") long id,@Param("attempt") String attempt,@Param("collection") String collection,
        @Param("model") String model,@Param("space") String space,@Param("sha") String sha,
        @Param("count") int count,@Param("dimensions") int dimensions,@Param("characters") int characters,@Param("note") String note);
    @Update("UPDATE document_index SET state='FAILED',error_message=#{message},updated_at=CURRENT_TIMESTAMP WHERE document_id=#{id} AND state='PROCESSING' AND attempt_id=#{attempt}")
    int fail(@Param("id") long id,@Param("attempt") String attempt,@Param("message") String message);
}
