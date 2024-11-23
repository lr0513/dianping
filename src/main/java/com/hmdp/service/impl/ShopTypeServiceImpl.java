package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

	@Autowired
	private StringRedisTemplate stringRedisTemplate;
	
	@Override
	public Result queryList() {
		String key = CACHE_SHOP_TYPE_KEY ;
		List<String> shopTypes = stringRedisTemplate.opsForList().range(key, 0, -1);
		// 非空，则转为ShopType类型直接返回
		if (!shopTypes.isEmpty()) { // != null 不可以
			List<ShopType> types = shopTypes.stream().map(type -> JSONUtil.toBean(type, ShopType.class)).collect(Collectors.toList());
			return Result.ok(types);
		}
		// 否则去数据库中查询
		List<ShopType> types = query().orderByAsc("sort").list(); // 将查询结果按 sort 字段升序排序
		if (types == null) {
			return Result.fail("店铺类型不存在！！");
		}
		// 查到了转为json字符串，并存入Redis中
		shopTypes = types.stream().map(type -> JSONUtil.toJsonStr(type)).collect(Collectors.toList());
		stringRedisTemplate.opsForList().leftPushAll(key, shopTypes);
		return Result.ok(types);
	}
}
