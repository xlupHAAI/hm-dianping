package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import org.redisson.RedissonLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
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

    @Resource
    private RedissonClient redissonClient;

    @Override
    public Result seckillVouchers(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        Long userId = UserHolder.get().getId();
        LocalDateTime currentTime = LocalDateTime.now();

        if (voucher.getBeginTime().isAfter(currentTime)
                || voucher.getEndTime().isBefore(currentTime)) {
            return Result.fail("不在秒杀活动时间内!");
        }

        if (voucher.getStock() < 1)
            return Result.fail("库存不足！");

        RLock redisLock = redissonClient.getLock("lock:order:" + userId);

        if (!redisLock.tryLock()) {
            return Result.fail("不允许重复下单！");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);   // 创建新订单
        } finally {
            redisLock.unlock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.get().getId();

        long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("当前用户已经购买过！");
        }

        if (!seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0).update())
            return Result.fail("下单失败！");

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIdWorker.nextId("order"));
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        save(voucherOrder);
        return Result.ok(voucherOrder.getId());
    }

//  使用 MySQL 加锁
//    @Override
//    public Result seckillVouchers(Long voucherId) {
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        LocalDateTime currentTime = LocalDateTime.now();
//
//        if (voucher.getBeginTime().isAfter(currentTime)
//                || voucher.getEndTime().isBefore(currentTime)) {
//            return Result.fail("不在秒杀活动时间内!");
//        }
//
//        if (voucher.getStock() < 1)
//            return Result.fail("库存不足！");
//
//        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//        return proxy.createVoucherOrder(voucherId);   // 创建新订单
//    }
//
//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//        Long userId = UserHolder.get().getId();
//
//        QueryWrapper queryWrapper = new QueryWrapper();
//        {
//            queryWrapper.eq("user_id", userId);
//            queryWrapper.eq("voucher_id", voucherId);
//            queryWrapper.last("for update");
//        }
//
//        if (baseMapper.selectCount(queryWrapper) > 0) {
//            return Result.fail("当前用户已经购买过！");
//        }
//
//        if (!seckillVoucherService.update().setSql("stock = stock - 1")
//                .eq("voucher_id", voucherId).gt("stock", 0).update())
//            return Result.fail("下单失败！");
//
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(redisIdWorker.nextId("order"));
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//
//        save(voucherOrder);
//        return Result.ok(voucherOrder.getId());
//    }


    /*  使用 synchronized，不能应对服务器集群。

    @Override
    public Result seckillVouchers(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        LocalDateTime currentTime = LocalDateTime.now();

        if (voucher.getBeginTime().isAfter(currentTime)
                || voucher.getEndTime().isBefore(currentTime)) {
            return Result.fail("不在秒杀活动时间内!");
        }

        if (voucher.getStock() < 1)
            return Result.fail("库存不足！");

        synchronized (UserHolder.get().getId().toString().intern()) {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);   // 创建新订单
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.get().getId();

        long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("当前用户已经购买过！");
        }

        if (!seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0).update())
            return Result.fail("下单失败！");

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIdWorker.nextId("order"));
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        save(voucherOrder);
        return Result.ok(voucherOrder.getId());
    }
*/
}
