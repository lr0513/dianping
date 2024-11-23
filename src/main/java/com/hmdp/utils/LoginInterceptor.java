package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @program: hm-dianping
 * @description: 拦截器
 * @author: lr
 * @create: 2024-11-03 18:56
 **/
public class LoginInterceptor implements HandlerInterceptor {
	/**
	 * @Description: 只判断ThreadLocal中是否有用户
	 * @Param: [javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, java.lang.Object]
	 * @return: boolean
	 * @Author: lr
	 * @Date: 2024-11-04
	 */

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		// 判断是否需要拦截(ThreadLocal中是否有用户)
		if (UserHolder.getUser() == null) {
			response.setStatus(401);
			return false;
		}
		// 有用户，放行
		return true;
	}
}
