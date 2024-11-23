package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
	@Autowired
	private IUserService userService;
	@Autowired
	private StringRedisTemplate stringRedisTemplate;
@Autowired
private IFollowService followService;
	@Override
	public Result queryById(Long id) {
		Blog blog = getById(id);
		if (blog == null) {
			return Result.fail("笔记不存在！");
		}
		queryBlogUser(blog);
		// 追加判断blog是否被当前用户点赞，逻辑封装到isBlogLiked方法中
		isBolgLiked(blog);
		return Result.ok(blog);
	}

	@Override
	public Result queryHotBlog(Integer current) {
		// 根据用户查询
		Page<Blog> page = query()
				.orderByDesc("liked")
				.page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
		// 获取当前页数据
		List<Blog> records = page.getRecords();
		// 查询用户&&遍历博客列表，为每篇博客的 isLike 字段赋值
		records.forEach(blog -> {
			queryBlogUser(blog);
			// 追加判断blog是否被当前用户点赞，逻辑封装到isBlogLiked方法中
			isBolgLiked(blog);
		});
		return Result.ok(records);
	}

	private void isBolgLiked(Blog blog) {
		UserDTO user = UserHolder.getUser();
		// 当用户未登录时，就不判断了，直接return结束逻辑
		if (user == null) {
			return;
		}
		// 判断当前用户是否点赞
		String key = BLOG_LIKED_KEY + blog.getId();
		Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());
		blog.setIsLike(score != null);
	}

	@Override
	public Result likeBlog(Long id) {
		// 1. 获取当前用户信息
		Long userId = UserHolder.getUser().getId();
		// 2. 如果当前用户未点赞，则点赞数+1，同时将用户加入set集合
		String key = BLOG_LIKED_KEY + id;
		Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString()); // 时间戳作为分数
		if (score == null) {
			// 点赞数+1
			boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
			// 将用户加入set集合
			if (success) {
				stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis()); // 时间戳作为分数
			}
			// 3. 如果当前用户已点赞，则取消点赞，将用户从set集合中移除
		} else {
			// 点赞数-1
			boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
			if (success) {
				stringRedisTemplate.opsForZSet().remove(key, userId.toString());
			}
		}
		return Result.ok();
	}

	@Override
	public Result queryBlogLikes(Long id) {
		String key = BLOG_LIKED_KEY + id;
		// zrange key 0 4 查询zset中前5个元素
		Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
		// 如果是空的(可能没人点赞)，直接返回一个空集合
		if (top5 == null || top5.isEmpty()) {
			return Result.ok(Collections.emptyList());
		}
		// 将String类型的ID转换为Long类型
		List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
		// 使用逗号连接的字符串构建SQL查询
		String idsStr = StrUtil.join(",", ids);
		// 根据ID查询用户信息并按点赞顺序排序
		List<UserDTO> userDTOList = userService.query().in("id", ids)
				.last("order by field(id," + idsStr + ")")
				.list()
				.stream()
				.map(user -> BeanUtil.copyProperties(user, UserDTO.class))
				.collect(Collectors.toList());
		return Result.ok(userDTOList);
	}

	@Override
	public Result saveBlog(Blog blog) {
		// 1. 获取登录用户
		UserDTO user = UserHolder.getUser();
		blog.setUserId(user.getId());
		// 2. 保存探店笔记
		boolean success = save(blog);
		if (!success) {
			return Result.fail("新增笔记失败！");
		}
		// 3. 查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id = userId
		List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
		// 4. 推送笔记id给所有粉丝
		for (Follow follow : follows) {
			// 4.1 获取粉丝id
			Long userId = follow.getUserId();
			// 4.2 推送
			String key = FEED_KEY + userId;
			stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
		}
		// 5. 返回id
		return Result.ok(blog.getId());
	}

	/**
	 * 个人信息界面的“关注（）”分页查询
	 * @param max
	 * @param offset
	 * @return
	 */
	@Override
	public Result queryBolgOfFollow(Long max, Integer offset) {
		// 1. 获取当前用户
		UserDTO user = UserHolder.getUser();
		// 2. 查询收件箱
		String key = FEED_KEY + user.getId();
		Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2); // 每页查两条
		
		if (typedTuples == null || typedTuples.isEmpty()) {
			return Result.ok(Collections.emptyList());
		}
		// 3. 解析数据：blogId，minTime（时间戳），offset
		List<Object> ids = new ArrayList<>(typedTuples.size());
		long minTime = 0;
		int count = 1;
		for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
			// 3.1 获取id
			String id = typedTuple.getValue();
			ids.add(id);
			// 3.2 获取score（时间戳）
			long time = typedTuple.getScore().longValue();
			if (time == minTime) {
				count++;
			}else {
				minTime = time;
				count = 1;
			}
		}
		// 解决SQL的in不能排序问题，手动指定排序为传入的ids
		String idsStr = StrUtil.join(",", ids);
		// 4. 根据id查询blog
		List<Blog> blogs = query().in("id", ids)
				.last("order by field(id, " + idsStr + ")")
				.list();
		for (Blog blog : blogs) {
			queryBlogUser(blog); // 查询发布者信息
			isBolgLiked(blog); // 查询点赞状态
		}
		// 5. 封装并返回
		ScrollResult scrollResult = new ScrollResult();
		scrollResult.setList(blogs);
		scrollResult.setOffset(count);
		scrollResult.setMinTime(minTime);
		return Result.ok(scrollResult);
	}

	private void queryBlogUser(Blog blog) {
		Long userId = blog.getUserId();
		User user = userService.getById(userId);
		blog.setName(user.getNickName());
		blog.setIcon(user.getIcon());
	}
}
