package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/follow")
public class FollowController {
	@Resource
	private IFollowService followService;

	// 判断当前用户是否关注了博主
	@GetMapping("/or/not/{id}")
	public Result isFollow(@PathVariable("id") Long followUserId) {
		return followService.isFollow(followUserId);
	}
	
	// 实现关注/取关
	@PutMapping("/{id}/{isFollow}")
	public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow) {
		return followService.follow(followUserId, isFollow);
	}

	// 共同关注
	@GetMapping("/common/{id}")
	public Result followCommons(@PathVariable Long id){
		return followService.followCommons(id);
	}
}
