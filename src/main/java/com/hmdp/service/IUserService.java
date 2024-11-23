package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.mail.MessagingException;
import javax.servlet.http.HttpSession;


public interface IUserService extends IService<User> {
	/**
	 * 发送手机验证码
	 * @param phone
	 * @param session
	 * @return
	 * @throws MessagingException
	 */
	Result senCode(String phone, HttpSession session) throws MessagingException;


	/** 发送手机验证码
	* @Description: 
	* @Param: [com.hmdp.dto.LoginFormDTO, javax.servlet.http.HttpSession]
	* @return: com.hmdp.dto.Result
	* @Author: lr
	* @Date: 2024-11-03
	*/
	
	
	/** 
	* @Description: 登录功能
	* @Param: [com.hmdp.dto.LoginFormDTO, javax.servlet.http.HttpSession]
	* @return: com.hmdp.dto.Result
	* @Author: lr
	* @Date: 2024-11-03
	*/
	
	Result login(LoginFormDTO loginForm, HttpSession session);

	/**
	 * 实现签到功能
	 * @return
	 */
	Result sign();

	/**
	 * 实现签到统计
	 * @return
	 */
	Result signCount();
}
