package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
	@Autowired
	private StringRedisTemplate stringRedisTemplate;
	@Resource
	private IUserService userService;

	@Override
	public Result isFollow(Long followUserId) { // 判断当前用户userId是否关注了followUserId用户
		// 获取当前登录的userId
		Long userId = UserHolder.getUser().getId();
		/*LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
		// 查询当前用户是否关注了该笔记的博主
		queryWrapper.eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followUserId);
		int count = this.count(queryWrapper);*/
		Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
		return Result.ok(count > 0);
	}

	@Override
	public Result follow(Long followUserId, Boolean isFollow) {
		// 获取当前登录的userId
		Long userId = UserHolder.getUser().getId();
		String key = "follows:" + userId;

		// 判断是否关注
		if (isFollow) {
			// 关注，则将信息保存到数据库
			Follow follow = new Follow().setUserId(userId).setFollowUserId(followUserId);
			boolean success = save(follow);
			// 如果保存成功
			if (success) {
				// 则将数据写入Redis
				stringRedisTemplate.opsForSet().add(key, followUserId.toString());
			}
		} else {
			// 取关，则从数据库中移除
			/*LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
			queryWrapper.eq(Follow::getUserId, userId)
					.eq(Follow::getFollowUserId, followUserId);
			boolean success = remove(queryWrapper);*/
			boolean success = remove(new QueryWrapper<Follow>()
					.eq("user_id", userId).eq("follow_user_id", followUserId));
			if (success) {
				stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
			}
		}
		return Result.ok();
	}

	// 共同关注
	@Override
	public Result followCommons(Long id) {
		// 1. 获取当前用户
		Long userId = UserHolder.getUser().getId();
		String key1 = "follows:" + id;
		String key2 = "follows:" + userId;

		// 打印出两个用户的关注列表
		Set<String> followsUser1 = stringRedisTemplate.opsForSet().members(key1);
		Set<String> followsUser2 = stringRedisTemplate.opsForSet().members(key2);
		System.out.println("Follows of user " + id + ": " + followsUser1);  // 检查用户 id 的关注列表
		System.out.println("Follows of user " + userId + ": " + followsUser2);  // 检查当前用户的关注列表
		
		// 求交集
		Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
		System.out.println("Intersection: " + intersect);  // 打印交集结果
		
		if (intersect == null || intersect.isEmpty()) {
			return Result.ok(Collections.emptyList());
		}
		// 将结果转为list，类型为Long
		List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
		// 根据ids去查询共同关注的用户，封装成UserDto再返回
		List<UserDTO> userDTOS = userService.listByIds(ids).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
		return Result.ok(userDTOS);
	}
}
