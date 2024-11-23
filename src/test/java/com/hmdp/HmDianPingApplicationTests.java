package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

	@Resource
	private ShopServiceImpl shopService;

	@Resource
	private CacheClient cacheClient;

	@Resource
	private RedisIdWorker redisIdWorker;

	private ExecutorService es = Executors.newFixedThreadPool(500);

	@Resource
	private RedissonClient redissonClient;

	@Resource
	private StringRedisTemplate stringRedisTemplate;

	@Test
	void testSaveShop() {
		Shop shop = shopService.getById(1L);
		cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
	}

	@Test
	void testIdWorker() throws InterruptedException {
		// 定义一个任务，每个任务生成100个ID并打印
		Runnable task = () -> {
			for (int i = 0; i < 100; i++) {
				Long id = redisIdWorker.nextId("order");
				System.out.println("Generated ID=" + id);
			}
		};

		// 记录测试开始时间
		long begin = System.currentTimeMillis();

		// 提交300个并发任务
		for (int i = 0; i < 300; i++) {
			es.submit(task);
		}

		// 关闭线程池，不再接受新任务
		es.shutdown();

		// 等待线程池中的任务完成，最多等待10分钟
		es.awaitTermination(10, TimeUnit.MINUTES);

		// 记录结束时间并打印总耗时
		long end = System.currentTimeMillis();
		System.out.println("Total time taken: " + (end - begin) + " ms");
	}

	@Test
	void testRedisson() throws InterruptedException {
		// 获取锁（可重入），指定锁的名称
		RLock lock = redissonClient.getLock("anyLock");
		// 尝试获取锁，参数分别是：获取锁的最大等待时间（期间会重试），锁自动释放时间，时间单位
		boolean isLock = lock.tryLock(1, 10, TimeUnit.SECONDS);
		// 判断释放获取成功
		if (isLock) {
			try {
				System.out.println("执行业务");
			} finally {
				// 释放锁
				lock.unlock();
			}
		}
	}

	@Test
	void loadShopData() {
		// 1. 查询店铺信息
		List<Shop> shopList = shopService.list();
		// 2. 把店铺按照typeId分组
		Map<Long, List<Shop>> map = shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));
		// 3. 逐个写入Redis
		for (Map.Entry<Long, List<Shop>> shopEntry : map.entrySet()) {
			// 3.1 获取类型id
			Long typeId = shopEntry.getKey();
			// 3.2 获取同类型店铺的集合
			List<Shop> shops = shopEntry.getValue();
			String key = SHOP_GEO_KEY + typeId;
			List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());
			for (Shop shop : shops) {
				// 3.3 写入redis GEOADD key 经度 纬度 member
				// stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
				// 将当前type的商铺都添加到locations集合中
				locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY())));
			}
			// 批量写入
			stringRedisTemplate.opsForGeo().add(key, locations);
		}
	}

	@Test
	void testHyperLogLog() {
		// 准备数组，装用户数据
		String[] users = new String[1000];
		int index = 0;
		for (int i = 1; i < 1000000; i++) {
			users[index++] = "user_" + i;
			// 每1000条发送一次
			if (i % 1000 == 0) {
				stringRedisTemplate.opsForHyperLogLog().add("hll1", users);
				index = 0;
			}
		}
		// 统计数量
		Long size = stringRedisTemplate.opsForHyperLogLog().size("hll1");
		System.out.println("size =" + size);
	}
}
