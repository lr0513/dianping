package com.hmdp.service.impl;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SeckillVoucherServiceImpl extends ServiceImpl<SeckillVoucherMapper, SeckillVoucher> implements ISeckillVoucherService {
	@Autowired
	private SeckillVoucherMapper seckillVoucherMapper;

	// 悲观锁
	@Override
	public SeckillVoucher getVoucherForUpdate(Long voucherId) {
		return seckillVoucherMapper.selectByIdForUpdate(voucherId);
	}
}
