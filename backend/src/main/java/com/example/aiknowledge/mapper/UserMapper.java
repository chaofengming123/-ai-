package com.example.aiknowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiknowledge.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {}
