package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @program: hm-dianping
 * @description: 拦截器
 * @author: lr
 * @create: 2024-11-03 18:56
 **/
public class RefreshTokenInterceptor implements HandlerInterceptor {
	private StringRedisTemplate stringRedisTemplate;

	public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
		this.stringRedisTemplate = stringRedisTemplate;
	}

	/**
	 * @Description: 前置拦截器：用于登陆之前的权限校验
	 * @Param: [javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, java.lang.Object]
	 * @return: boolean
	 * @Author: lr
	 * @Date: 2024-11-03
	 */
	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		// 1. 获取请求头中的token
		String token = request.getHeader("authorization");
		// 2. 如果token为空，则未登录，直接放行
		if (StrUtil.isBlank(token)) {
			return true;
		}
		// 3.基于token获取Redis中的用户数据
		String key = RedisConstants.LOGIN_USER_KEY + token;
		Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
		// 4. 判断用户是否存在，不存在，直接放行
		if (userMap.isEmpty()) {
			return true;
		}
		// 5. 将查询到的Hash数据转换为UserDto对象
		UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
		// 6. 将用户信息保存到ThreadLocal
		UserHolder.saveUser(userDTO);
		// 7. 刷新tokenTTL
		stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES); // 30min
		// 8. 放行
		return true;
	}

	/**
	 * @Description: 完成处理方法：用于处理登录后的信息，避免内存泄露
	 * @Param: [javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, java.lang.Object, java.lang.Exception]
	 * @return: void
	 * @Author: lr
	 * @Date: 2024-11-03
	 */
	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
		// 移除用户
		UserHolder.removeUser();
	}
}
