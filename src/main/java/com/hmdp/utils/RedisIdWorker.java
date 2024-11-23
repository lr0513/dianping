package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @description: Redis实现全局唯一ID
 * @author: lr
 * @create: 2024-11-07 20:02
 **/
@Component
public class RedisIdWorker {
	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	// 起始时间戳，用于生成相对的秒数
	public static final long BEGIN_TIMESTAMP = LocalDateTime.of(2023, 1, 1, 0, 0, 0).toEpochSecond(ZoneOffset.UTC);
	// 序列号长度
	public static final Long COUNT_BIT = 32L;

	public Long nextId(String keyPrefix) {
		// 1. 生成时间戳
		LocalDateTime now = LocalDateTime.now();
		long currentSecond = now.toEpochSecond(ZoneOffset.UTC);
		long timeStamp = currentSecond - BEGIN_TIMESTAMP;
		// 2. 生成序列号
		String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
		long count = stringRedisTemplate.opsForValue().increment("inc:" + keyPrefix + ":" + date);
		// 3. 拼接并返回，简单位运算
		return timeStamp << COUNT_BIT | count;
	}
}
