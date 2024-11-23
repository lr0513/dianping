package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @program: hm-dianping
 * @description: 拦截器生效
 * @author: lydms
 * @create: 2024-11-03 19:18
 **/
@Configuration
public class MvcConfig implements WebMvcConfigurer {
	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		// 登录拦截器
		registry.addInterceptor(new LoginInterceptor())
				.excludePathPatterns(
						"/user/code",
						"/user/login",
						"/blog/hot",
						"/shop/**",
						"/shop-type/**",
						"/upload/**",
						"/voucher/**"
				).order(1);
		// order值越大，越后执行，或者直接把RefreshTokenInterceptor放在LoginInterceptor上面
		// token刷新拦截器
		registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0); // 默认拦截所有请求
	}
}
