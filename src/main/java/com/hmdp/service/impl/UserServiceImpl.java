package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.MailUtils;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.mail.MessagingException;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	/**
	 * @Description: 发送手机验证码
	 * @Param: [java.lang.String, javax.servlet.http.HttpSession]
	 * @return: com.hmdp.dto.Result
	 * @Author: lr
	 * @Date: 2024-11-03
	 */
	@Override
	public Result senCode(String phone, HttpSession session) throws MessagingException {
		// 发送短信验证码并保存验证码
		if (RegexUtils.isEmailInvalid(phone)) {
			return Result.fail("邮箱格式不正确！");
		}
		String code = MailUtils.achieveCode();
		log.info("验证码：{}", code);
		/*session.setAttribute(phone, code);
		MailUtils.sendTestMail(phone, code);*/
		// 保存验证码——字符串
		stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
		// 等价于stringRedisTemplate.opsForValue().set("login:code:" + phone, code, 2, TimeUnit.MINUTES);
		return Result.ok();
	}

	/**
	 * @Description: 登录功能
	 * @Param: [com.hmdp.dto.LoginFormDTO, javax.servlet.http.HttpSession]
	 * @return: com.hmdp.dto.Result
	 * @Author: lr
	 * @Date: 2024-11-03
	 */

	@Override
	public Result login(LoginFormDTO loginForm, HttpSession session) {
		String phone = loginForm.getPhone();
		String code = loginForm.getCode();
		// 获取session中验证码
		// Object cacheCode = session.getAttribute(phone);
		// 获取redis中的验证码
		String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
		// 1. 校验邮箱格式
		if (RegexUtils.isEmailInvalid(phone)) {
			return Result.fail("邮箱格式不正确！");
		}

		// 2. 验证校验码
		log.info("code:{}, cacheCode:{}", code, cacheCode);
		if (cacheCode == null || !cacheCode.equals(code)) { // 如果是session需要toString().equals
			// 3. 不一致，报错
			return Result.fail("验证码错误");
		}
		// 4. 一致，根据手机号查询用户
		User user = query().eq("phone", phone).one();
		/*
		LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
		queryWrapper.eq(User::getPhone, phone);
		log.info("QueryWrapper: {}", queryWrapper.getExpression());
		User user = userMapper.selectOne(queryWrapper); 不晓得为什么这个方法报错
		*/
		// 5. 判断用户是否存在
		if (user == null) {
			// 6. 不存在，创建新用户并保存
			user = createUserWithPhone(phone);
		}
		/*// 7. 保存用户信息到session中
		session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));*/
		// 7. 保存用户信息到Redis中——Hash
		// 7.1 随机生成token，作为登陆令牌
		String token = UUID.randomUUID().toString();
		// 7.2 将UserDto对象转为Hash存储
		UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
		Map<String, String> userMap = new HashMap<>();
		userMap.put("icon", userDTO.getIcon());
		userMap.put("id", String.valueOf(userDTO.getId()));
		userMap.put("nickName", userDTO.getNickName());
		// 7.3 存储
		String tokenKey = LOGIN_USER_KEY + token;
		stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
		// 7.4 设置token的有效期为30min
		stringRedisTemplate.expire(tokenKey, 30, TimeUnit.MINUTES);
		// 7.5 登陆成功则删除验证码信息
		stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);
		// 8. 返回key
		return Result.ok(token);
	}

	private User createUserWithPhone(String phone) {
		User user = new User().setPhone(phone).setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(8));
		save(user);
		return user;
	}

	/**
	 * 实现签到功能
	 *
	 * @return
	 */
	@Override
	public Result sign() {
		// 1. 获取当前用户
		UserDTO user = UserHolder.getUser();
		// 2. 获取日期
		LocalDateTime now = LocalDateTime.now();
		// 3. 拼接key
		String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
		String key = USER_SIGN_KEY + user.getId() + keySuffix;
		// 4. 获取今天是当月第几天（1~31）
		int dayOfMonth = now.getDayOfMonth();
		// 5. 写入Redis BITSET key offset 1
		stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
		return Result.ok();
	}

	/**
	 * 实现签到统计
	 *
	 * @return
	 */
	@Override
	public Result signCount() {
		// 1. 统计当前用户id
		Long userId = UserHolder.getUser().getId();
		// 2. 获取日期
		LocalDateTime now = LocalDateTime.now();
		// 3. 拼接key
		String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
		String key = USER_SIGN_KEY + userId + keySuffix;
		// 4. 获取今天是当月的第几天（1~31）
		int dayOfMonth = now.getDayOfMonth();
		// 5. 获取截至至今日的签到记录 BITFIELD key Get uDay 0
		List<Long> result = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create()
				.get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)); // 数据少时只返回一个Long数字
		if (result == null || result.isEmpty()) {
			return Result.ok(0);
		}
		// 6. 循环遍历
		int count = 0;
		Long num = result.get(0); // 获取位图的第一个值（在本例中为 1899）
		while (true) {
			if ((num & 1) == 0) { // 检查最右边的一位（0 或 1）
				break; // 如果是 0，则退出循环
			} else {
				count++; // 如果是 1，则签到天数加 1
			}
			num >>>= 1; // 无符号右移，去掉最右边的一位
		}
		return Result.ok(count);
	}
}
