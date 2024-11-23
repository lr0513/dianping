package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * Redis缓存工具类封装，提供了常见的缓存操作方法，包括设置缓存、逻辑过期缓存、缓存穿透的处理等。
 */
@Component
@Slf4j
public class CacheClient {

	@Autowired
	private StringRedisTemplate stringRedisTemplate; // 注入Redis操作类，用于与Redis进行交互

	/**
	 * 将对象序列化为JSON并存储到Redis中，并设置过期时间。
	 *
	 * @param key   Redis中存储的key
	 * @param value 要存储的对象
	 * @param time  过期时间
	 * @param unit  过期时间的单位
	 */
	public void set(String key, Object value, Long time, TimeUnit unit) {
		stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
	}

	/**
	 * 使用逻辑过期的方式将对象序列化为JSON并存储到Redis中。
	 * 逻辑过期是在缓存到期后不会立即删除，而是通过设置过期时间来标识数据是否需要重新加载。
	 *
	 * @param key   Redis中存储的key
	 * @param value 要存储的对象
	 * @param time  逻辑过期时间
	 * @param unit  过期时间的单位
	 */
	public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
		RedisData redisData = new RedisData();
		redisData.setData(value);  // 设置数据
		redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));  // 设置逻辑过期时间

		// 仅存储数据，不设置物理过期时间
		stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
	}

	/**
	 * 处理缓存穿透的问题，使用缓存穿透策略。如果Redis中没有缓存，则从数据库查询，并缓存结果。
	 * 如果数据库查询不到数据，缓存空值，避免缓存穿透。
	 *
	 * @param keyPrefix  Redis的key前缀
	 * @param id         要查询的id
	 * @param type       返回的类型
	 * @param dbFallback 数据库查询的回调函数
	 * @param time       缓存的过期时间
	 * @param unit       缓存的时间单位
	 * @return 查询到的数据，或null
	 */
	public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
		String key = keyPrefix + id;
		String json = stringRedisTemplate.opsForValue().get(key);

		if (StrUtil.isNotBlank(json)) {
			return JSONUtil.toBean(json, type);
		}

		if (json != null) {
			return null;
		}

		R result = dbFallback.apply(id);

		if (result == null) {
			this.set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES); // 缓存空值，防止穿透
			return null;
		}

		this.set(key, result, time, unit);
		return result;
	}

	// 线程池用于缓存重建操作
	private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

	/**
	 * 使用逻辑过期策略来解决缓存击穿问题。数据在Redis中过期后，通过异步重建缓存。
	 *
	 * @param keyPrefix  Redis的key前缀
	 * @param id         要查询的id
	 * @param type       返回的类型
	 * @param dbFallback 数据库查询的回调函数
	 * @param time       缓存逻辑过期时间
	 * @param unit       缓存时间单位
	 * @return 查询到的数据，或null
	 */
	public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
		String key = keyPrefix + id;
		String lockKey = LOCK_SHOP_KEY + id;

		String json = stringRedisTemplate.opsForValue().get(key);

		if (StrUtil.isBlank(json)) {
			return null;
		}

		RedisData redisData = JSONUtil.toBean(json, RedisData.class);
		JSONObject jsonData = (JSONObject) redisData.getData();
		R result = JSONUtil.toBean(jsonData, type);

		LocalDateTime expireTime = redisData.getExpireTime();

		if (LocalDateTime.now().isBefore(expireTime)) {
			return result;
		}

		boolean lockAcquired = tryLock(lockKey);
		if (lockAcquired) {
			try {
				json = stringRedisTemplate.opsForValue().get(key);
				redisData = JSONUtil.toBean(json, RedisData.class);
				expireTime = redisData.getExpireTime();

				if (LocalDateTime.now().isAfter(expireTime)) {
					CACHE_REBUILD_EXECUTOR.submit(() -> {
						try {
							this.setWithLogicalExpire(key, dbFallback.apply(id), time, unit);
						} catch (Exception e) {
							throw new RuntimeException(e);
						} finally {
							unlock(lockKey);
						}
					});
				}
			} catch (Exception e) {
				throw new RuntimeException(e);
			} finally {
				unlock(lockKey);
			}
		}
		return result;
	}

	// 尝试获取锁，超时时间设置为10秒
	private boolean tryLock(String key) {
		Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
		return BooleanUtil.isTrue(flag);
	}

	// 释放锁
	private void unlock(String key) {
		stringRedisTemplate.delete(key);
	}

	/**
	 * 使用互斥锁解决缓存击穿问题。
	 *
	 * @param keyPrefix  Redis的key前缀
	 * @param id         要查询的id
	 * @param type       返回的类型
	 * @param dbFallback 数据库查询的回调函数
	 * @param time       缓存的过期时间
	 * @param unit       缓存的时间单位
	 * @return 查询到的数据，或null
	 */
	public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
		String key = CACHE_SHOP_KEY + id;
		String json = stringRedisTemplate.opsForValue().get(key);

		if (StrUtil.isNotBlank(json)) {
			return JSONUtil.toBean(json, type);
		}

		if (json != null) {
			return null;
		}

		String lockKey = LOCK_SHOP_KEY + id;
		R result = null;
		try {
			boolean isLock = tryLock(lockKey);
			if (!isLock) {
				Thread.sleep(50);
				return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
			}
			result = dbFallback.apply(id);
			Thread.sleep(200);

			if (result == null) {
				stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
				return null;
			}
			this.set(key, result, time, unit);
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			unlock(lockKey);
		}
		return result;
	}
}
