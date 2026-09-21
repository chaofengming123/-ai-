package com.example.aiknowledge.mapper;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UserAccessMapper {
    @Select("SELECT r.code FROM app_role r JOIN app_user_role ur ON ur.role_id=r.id WHERE ur.user_id=#{userId} ORDER BY r.code")
    List<String> roles(long userId);

    // 多个角色可能授予同一权限，DISTINCT 合并重复结果。
    @Select("SELECT DISTINCT p.code FROM app_permission p JOIN app_role_permission rp ON rp.permission_id=p.id JOIN app_user_role ur ON ur.role_id=rp.role_id WHERE ur.user_id=#{userId} ORDER BY p.code")
    List<String> permissions(long userId);

    @Insert("INSERT INTO app_user_role(user_id,role_id) SELECT #{userId},id FROM app_role WHERE code=#{role}")
    int assignRole(@Param("userId") long userId, @Param("role") String role);
}
