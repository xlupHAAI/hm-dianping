package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Override
    @Transactional
    public Result seckillVouchers(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        LocalDateTime currentTime = LocalDateTime.now();

        if (voucher.getBeginTime().isAfter(currentTime)
                || voucher.getEndTime().isBefore(currentTime)) {
            return Result.fail("不在秒杀活动时间内!");
        }

        if (voucher.getStock() < 1 ||       // 库存不足或扣减失败
                !seckillVoucherService.update().setSql("stock = stock - 1")
                        .eq("voucher_id", voucherId).gt("stock",0).update())    // 乐观锁
            return Result.fail("库存不足！");

        /**
         * 创建新订单
         */
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIdWorker.nextId("order"));
        voucherOrder.setUserId(UserHolder.get().getId());
        voucherOrder.setVoucherId(voucherId);

        save(voucherOrder);
        return Result.ok(voucherOrder.getId());
    }
}
