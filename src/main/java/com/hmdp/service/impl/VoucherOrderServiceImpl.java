package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

	@Autowired
	private ISeckillVoucherService seckillVoucherService; // 注入秒杀代金券服务

	@Autowired
	private RedisIdWorker redisIdWorker; // 注入Redis自增ID生成器

	@Autowired
	private StringRedisTemplate stringRedisTemplate; // 用于操作Redis

	@Autowired
	private RedissonClient redissonClient; // 用于分布式锁

	// Lua脚本，用于执行秒杀库存操作
	private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

	static {
		SECKILL_SCRIPT = new DefaultRedisScript<>();
		SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
		SECKILL_SCRIPT.setResultType(Long.class);
	}

	// 阻塞队列，用于存储待处理的订单任务
	// private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

	// 创建线程池，用于异步处理订单
	private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

	// 初始化方法，启动线程池处理订单任务
	@PostConstruct
	private void init() {
		SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
	}

	// 线程任务类，用于处理阻塞队列中的订单
	/*private class VoucherOrderHandler implements Runnable {
		@Override
		public void run() {
			while (true) {
				try {
					// 1. 获取队列中的订单信息
					VoucherOrder voucherOrder = orderTasks.take();
					// 2. 创建订单
					handleVoucherOrder(voucherOrder);
				} catch (Exception e) {
					log.error("处理订单异常", e);
				}
			}
		}

		// 处理订单的方法
		private void handleVoucherOrder(VoucherOrder voucherOrder) {
			// 1. 获取用户ID（注意：不能从UserHolder中获取，因为当前是一个新线程）
			Long userId = voucherOrder.getUserId();
			// 2. 创建分布式锁对象，防止同一用户重复下单
			RLock lock = redissonClient.getLock("lock:order" + userId);
			// 3. 尝试获取锁
			boolean isLock = lock.tryLock();
			// 4. 如果获取锁失败，则表示该用户已经下单，返回错误
			if (!isLock) {
				log.error("不允许重复下单");
				return;
			}
			try {
				// 获取代理对象，确保在事务中执行订单创建逻辑
				proxy.createVoucherOrder(voucherOrder);
			} finally {
				// 释放锁
				lock.unlock();
			}
		}
	}*/
	// 修改为获取消息队列中的订单
	private class VoucherOrderHandler implements Runnable {
		String queueName = "stream.orders";

		@Override
		public void run() {
			while (true) {
				try {
					// 1. 获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
					List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
							Consumer.from("g1", "c1"),  // "g1" 是消费者组名，"c1" 是消费者名
							StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),  // 设置阻塞和读取选项
							StreamOffset.create(queueName, ReadOffset.lastConsumed()));  // 从上次消费的位置继续读取
					// 2. 判断消息是否获取成功
					if (records == null || records.isEmpty()) {
						// 2.1 获取失败，说明没有消息，继续下一次循环
						continue;
					}
					// 3. 消息获取成功，可以下单，转换为对象
					MapRecord<String, Object, Object> record = records.get(0);
					Map<Object, Object> values = record.getValue();
					VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

					// 4. 创建订单，保存到数据库
					handleVoucherOrder(voucherOrder);
					// 5. 手动确认消息处理成功 SACK stream.orders g1 id
					stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
				} catch (Exception e) {
					log.error("处理订单异常", e);
					// 异常处理：尝试从待处理列表中获取消息
					handlePendingList();
				}
			}
		}
	}

	// 处理订单的方法
	private void handleVoucherOrder(VoucherOrder voucherOrder) {
		// 1. 获取用户ID（注意：不能从UserHolder中获取，因为当前是一个新线程）
		Long userId = voucherOrder.getUserId();
		// 2. 创建分布式锁对象，防止同一用户重复下单
		RLock lock = redissonClient.getLock("lock:order" + userId);
		// 3. 尝试获取锁
		boolean isLock = lock.tryLock();
		// 4. 如果获取锁失败，则表示该用户已经下单，返回错误
		if (!isLock) {
			log.error("不允许重复下单");
			return;
		}
		try {
			// 获取代理对象，确保在事务中执行订单创建逻辑
			proxy.createVoucherOrder(voucherOrder);
		} finally {
			// 释放锁
			lock.unlock();
		}
	}

	// 处理 Stream 中的“待处理”消息
	private void handlePendingList() {
		String queueName = "stream.orders";

		while (true) {
			try {
				// 获取pending-list中的订单信息
				List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
						Consumer.from("g1", "c1"),
						StreamReadOptions.empty().count(1),
						StreamOffset.create(queueName, ReadOffset.from("0")) // 从0位置读取
				);

				// 判断pending-list中是否有未处理的消息
				if (records == null || records.isEmpty()) {
					break; // 如果没有异常消息，结束循环
				}

				// 消息获取成功，转换为对象
				MapRecord<String, Object, Object> record = records.get(0);
				Map<Object, Object> values = record.getValue();
				VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

				// 执行下单逻辑，保存到数据库
				handleVoucherOrder(voucherOrder);
				
				// 手动确认消息处理成功
				stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
			} catch (Exception e) {
				log.info("处理 pending-list 异常");
				// 异常重试：休眠50ms后再次尝试
				try {
					Thread.sleep(50);
				} catch (InterruptedException ex) {
					throw new RuntimeException(ex);
				}
			}
		}
	}

	private IVoucherOrderService proxy; // 服务接口代理

	// 秒杀代金券方法
	/*@Override
	public Result seckillVoucher(Long voucherId) {
		// 获取当前用户ID
		Long userId = UserHolder.getUser().getId();
		// 1. 执行Lua脚本来判断用户是否有秒杀资格
		Long res = stringRedisTemplate.execute(
				SECKILL_SCRIPT,
				Collections.emptyList(),
				voucherId.toString(), userId.toString()
		);
		// 2. 判断Lua脚本执行结果
		int result = res.intValue();
		if (result != 0) {
			// 2.1 如果返回结果不为0，说明库存不足或用户重复下单
			return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
		}
		// 2.2 如果返回结果为0，说明有购买资格，将下单信息存入阻塞队列
		Long orderId = redisIdWorker.nextId("order");

		VoucherOrder voucherOrder = new VoucherOrder();
		// 设置订单信息
		voucherOrder.setId(orderId); // 设置订单ID
		voucherOrder.setUserId(userId); // 设置用户ID
		voucherOrder.setVoucherId(voucherId); // 设置代金券ID

		// 将订单存入阻塞队列，等待处理
		orderTasks.add(voucherOrder);

		// 获取代理对象（事务）
		proxy = (IVoucherOrderService) AopContext.currentProxy();

		// 返回成功，秒杀请求已进入队列
		return Result.ok();
	}*/
	@Override
	public Result seckillVoucher(Long voucherId) {
		// 获取当前用户ID
		Long userId = UserHolder.getUser().getId();
		Long orderId = redisIdWorker.nextId("order");
		// 1. 执行Lua脚本来判断用户是否有秒杀资格
		Long res = stringRedisTemplate.execute(
				SECKILL_SCRIPT,
				Collections.emptyList(),
				voucherId.toString(), userId.toString(), orderId.toString()
		);
		// 2. 判断Lua脚本执行结果
		int result = res.intValue();
		if (result != 0) {
			// 2.1 如果返回结果不为0，说明库存不足或用户重复下单
			return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
		}

		// 获取代理对象（事务）
		proxy = (IVoucherOrderService) AopContext.currentProxy();

		// 返回成功，秒杀请求已进入队列
		return Result.ok();
	}

	// 创建代金券订单的事务方法
	@Transactional
	public void createVoucherOrder(VoucherOrder voucherOrder) {
		Long userId = voucherOrder.getUserId();
		Long voucherId = voucherOrder.getVoucherId();

		// 5.1 查询用户是否已经购买过该代金券，确保用户只能购买一次
		Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
		if (count > 0) {
			log.error("用户已经购买过一次");
			throw new RuntimeException("用户已经购买过一次"); // 如果已购买，则抛出异常中断事务
		}

		// 6. 扣减库存（乐观锁）
		boolean success = seckillVoucherService.update()
				.setSql("stock = stock - 1") // 扣减库存
				.eq("voucher_id", voucherId)
				.gt("stock", 0) // 确保库存大于0
				.update();

		if (!success) {
			// 如果库存扣减失败，抛出异常
			log.error("库存不足");
			throw new RuntimeException("库存不足");
		}

		// 7. 创建订单
		save(voucherOrder); // 保存订单信息到数据库

		// 日志记录订单创建成功
		log.info("创建订单成功, 订单ID: {}", voucherOrder.getId());
	}

}
