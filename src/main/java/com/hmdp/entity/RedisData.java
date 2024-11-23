package com.hmdp.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 逻辑过期解决缓存击穿问题 新增的含expireTime的实体类
 */
@Data
public class RedisData<T> {
	private LocalDateTime expireTime;  // 过期时间
	private T data;                    // 存储的原始数据
}
