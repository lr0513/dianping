package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	@Autowired
	private CacheClient cacheClient;

	@Autowired
	private IShopService shopService;

	@Override
	public Result queryById(Long id) {
		// 缓存穿透
		// Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

		// 互斥锁解决缓存击穿
		Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);
		// Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

		// 逻辑过期解决缓存击穿
		// Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
		if (shop == null) {
			return Result.fail("店铺不存在！！");
		}
		return Result.ok(shop);
	}

	// 缓存击穿
	/*public Shop queryWithMutex(Long id) {
		// 先从Redis中查，这里的常量值屎固定的前缀+店铺id
		String key = CACHE_SHOP_KEY + id;
		String shopJson = stringRedisTemplate.opsForValue().get(key); // "cache:shop"
		// 非空，转换为Shop类型直接返回
		if (StrUtil.isNotBlank(shopJson)) {
			return JSONUtil.toBean(shopJson, Shop.class);
		}
		// 如果查询到的屎空字符串，说明是我们缓存的空数据
		if (shopJson != null) {
			return null;
		}
		// 实现缓存重建
		// 获取互斥锁
		String lockKey = LOCK_SHOP_KEY + id; // "lock:shop:"
		Shop shop = null;
		try {
			boolean isLock = tryLock(lockKey);
			// 判断是否获取成功
			if (!isLock) {
				// 失败，则休眠并重试
				Thread.sleep(50);
				queryWithPassThrough(id); // 递归调用
			}
			// 成功，根据id查询数据库
			shop = getById(id);

			// 模拟重建的延时
			Thread.sleep(200);

			if (shop == null) {
				// 空字符串的TTL设置两分钟
				stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
				return null;
			}
			// 查到了，则转为json字符串
			String jsonStr = JSONUtil.toJsonStr(shop);
			// 并存入Redis
			stringRedisTemplate.opsForValue().set(key, jsonStr, CACHE_SHOP_TTL, TimeUnit.MINUTES);
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			// 释放互斥锁
			unlock(lockKey);
		}
		// 最终把查询到的商户信息返回给前端
		return shop;
	}*/

	// 缓存穿透
	/*public Shop queryWithPassThrough(Long id) {
		// 先从Redis中查，这里的常量值屎固定的前缀+店铺id
		String key = CACHE_SHOP_KEY + id;
		String shopJson = stringRedisTemplate.opsForValue().get(key); // "cache:shop"
		// 非空，转换为Shop类型直接返回
		if (StrUtil.isNotBlank(shopJson)) {
			return JSONUtil.toBean(shopJson, Shop.class);
		}
		// 如果查询到的屎空字符串，说明是我们缓存的空数据
		if (shopJson != null) {
			return null;
		}
		// 否则去数据库中查找
		Shop shop = getById(id);
		if (shop == null) {
			// 空字符串的TTL设置两分钟
			stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
			return null;
		}
		// 查到了则转为json字符串
		String jsonStr = JSONUtil.toJsonStr(shop);
		// 并存入Redis
		stringRedisTemplate.opsForValue().set(key, jsonStr, CACHE_SHOP_TTL, TimeUnit.MINUTES);
		// 最终把查询到的商户信息返回给前端
		return shop;
	}*/

	/*
	// 这里需要声明一个线程池，因为下面我们需要新建一个线程来完成重构
	private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

	public Shop queryWithLogicalExpire(Long id) {
		// 1. 从 Redis 中查询商铺缓存
		String shopKey = CACHE_SHOP_KEY + id;
		String lockKey = LOCK_SHOP_KEY + id;
		String json = stringRedisTemplate.opsForValue().get(shopKey);

		// 2. 如果未命中，则返回空
		if (StrUtil.isBlank(json)) {
			return null;
		}

		// 3. 命中，将 JSON 反序列化为 RedisData 对象
		RedisData redisData = JSONUtil.toBean(json, RedisData.class);
		// 3.1 将 data 转换为 Shop 对象
		JSONObject shopJson = (JSONObject) redisData.getData();
		Shop shop = JSONUtil.toBean(shopJson, Shop.class);
		// 3.2 获取过期时间
		LocalDateTime expireTime = redisData.getExpireTime();

		// 4. 判断是否过期
		// 双重检查——第一次检查过期状态
		if (LocalDateTime.now().isBefore(expireTime)) {
			// 5. 未过期，直接返回商铺信息
			return shop;
		}

		// 6. 过期，尝试获取互斥锁
		boolean flag = tryLock(lockKey);
		// 7. 获取到了锁
		if (flag) {
			try {
				// 双重检查——第二次检查 (确保其他线程没有在此期间重建缓存)
				json = stringRedisTemplate.opsForValue().get(shopKey);
				redisData = JSONUtil.toBean(json, RedisData.class);
				expireTime = redisData.getExpireTime();
				if (LocalDateTime.now().isAfter(expireTime)) {
					// 8. 开启独立线程重建缓存
					CACHE_REBUILD_EXECUTOR.submit(() -> {
						try {
							saveShop2Redis(id, LOCK_SHOP_TTL);
						} catch (Exception e) {
							throw new RuntimeException(e);
						} finally {
							// 释放锁
							unlock(lockKey);
						}
					});
				}
			} catch (Exception e) {
				throw new RuntimeException(e);
			} finally {
				// 确保锁被释放
				unlock(lockKey);
			}
		}

		// 10. 未获取到锁或已重建缓存，直接返回商铺信息
		return shop;
	}
	*/

	/*// 获取锁
	private boolean tryLock(String key) {
		Boolean flage = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
		// 避免返回值为null，这里使用BooleanUtil工具类
		return BooleanUtil.isTrue(flage);
	}

	// 释放锁
	private void unlock(String key) {
		stringRedisTemplate.delete(key);
	}
*/
	public void saveShop2Redis(Long id, Long expireSeconds) {
		// 1. 查询店铺数据
		Shop shop = getById(id);
		// 2. 封装逻辑过期时间
		RedisData<Shop> redisData = new RedisData<>();
		redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
		redisData.setData(shop);
		// 3. 写入Redis
		stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
	}

	@Override
	@Transactional
	public Result update(Shop shop) {
		Long id = shop.getId();
		if (id == null) {
			return Result.fail("店铺id不能为空");
		}
		// 1. 更新数据库
		updateById(shop);
		// 2. 删除缓存
		String key = CACHE_SHOP_KEY + id;
		stringRedisTemplate.delete(key);
		return Result.ok();
	}

	@Override
	public Result queryByType(Integer typeId, Integer current, Double x, Double y) {
		// 1. 判断是否需要按照坐标查询
		if (x == null || y == null) {
			// 根据类型分页查询
			Page<Shop> page = shopService.query()
					.eq("type_id", typeId)
					.page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
			// 返回数据
			return Result.ok(page.getRecords());
		} 
		// 2. 计算分页查询参数
		int from = (current - 1) * SystemConstants.MAX_PAGE_SIZE;
		int end = current * SystemConstants.MAX_PAGE_SIZE;
		String key = SHOP_GEO_KEY + typeId;
		// 3. 查询redis，按照距离排序、分页 结果：shopId、distance
		// GEOSEARCH key FROMLONLAT x y BYRADIUS 5000 m WITHDIST
		GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(key, 
				GeoReference.fromCoordinate(x, y), 
				new Distance(5000),
				RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
		// 4. 解析出id
		if (results == null) {
			return Result.ok(Collections.emptyList());
		}
		List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
		if (from > list.size()) {
			// 起始查询位置大于数据总量，说明没有数据了，返回空集合
			return Result.ok(Collections.emptyList());
		}
		ArrayList<Long> ids = new ArrayList<>(list.size());
		HashMap<String, Distance> distanceMap = new HashMap<>(list.size());
		list.stream().skip(from).forEach(result -> {
			String shopIdStr = result.getContent().getName();
			ids.add(Long.valueOf(shopIdStr));
			Distance distance = result.getDistance();
			distanceMap.put(shopIdStr,distance);
		});
		// 5. 根据id查Shop
		String idsStr = StrUtil.join(",", ids);
		List<Shop> shops = query().in("id", ids).last("order by field(id," + idsStr + ")").list();
		for (Shop shop : shops) {
			// 设置shop的距离属性，从distanceMap中根据shopId查询
			shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
		}
		return Result.ok(shops);
	}
}
