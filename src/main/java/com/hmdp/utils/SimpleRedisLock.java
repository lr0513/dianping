package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @description: 实现ILock分布式锁接口
 * @author: lr
 * @create: 2024-11-09 13:29
 **/
public class SimpleRedisLock implements ILock {
	// 具体业务名称，将前缀和业务名拼接之后当做Key
	private String name;
	// 采用的是构造器注入
	private StringRedisTemplate stringRedisTemplate;
	// 锁的前缀
	private static final String KEY_PREFIX = "lock:";

	private static final DefaultRedisScript<Long> UNLOCK_SCRIPE;

	static {
		UNLOCK_SCRIPE = new DefaultRedisScript<>();
		UNLOCK_SCRIPE.setLocation(new ClassPathResource("unlock.lua"));
		UNLOCK_SCRIPE.setResultType(Long.class);
	}

	private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

	public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
		this.name = name;
		this.stringRedisTemplate = stringRedisTemplate;
	}

	@Override
	public boolean tryLock(long timeoutSec) {
		// 获取线程标识
		String threadId = ID_PREFIX + Thread.currentThread().getId();
		// 获取锁
		String key = KEY_PREFIX + name;
		Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, threadId, timeoutSec, TimeUnit.SECONDS);
		// return success; 自动拆箱可能导致 NullPointerException
		return Boolean.TRUE.equals(success);
	}

	/*@Override
	public void unlock() {
		// 获取当前线程的标识
		String threadId = ID_PREFIX + Thread.currentThread().getId();
		String key = KEY_PREFIX + name;
		// 获取锁中的标识
		String id = stringRedisTemplate.opsForValue().get(key);
		// 判断标识是否一致
		if (threadId.equals(id)) {
			stringRedisTemplate.delete(key);
		}
	}*/

	@Override
	public void unlock() {
		// 调用lua脚本
		stringRedisTemplate.execute(
				UNLOCK_SCRIPE,
				Collections.singletonList(KEY_PREFIX + name),
				ID_PREFIX + Thread.currentThread().getId());
	}
}
