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
import jakarta.annotation.PostConstruct;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
        proxy.createVoucherOrder(voucherOrder);   // 创建新订单
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        RLock redisLock = redissonClient.getLock("lock:order:" + userId);

        if (!redisLock.tryLock()) {
            log.error("不允许重复下单！");
            return;
        }
        try {
            if (query().eq("user_id", userId).eq("voucher_id", voucherId).count() > 0) {
                log.error("当前用户已经购买过！");
                return;
            }
            if (!seckillVoucherService.update().setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId).gt("stock", 0).update()){
                log.error("下单失败！");
                return;
            }
            save(voucherOrder);
        } finally {
            redisLock.unlock();
        }
    }

    @Override
    public Result seckillVouchers(Long voucherId) {
        Long userId = UserHolder.get().getId();
        long orderId = redisIdWorker.nextId("order");

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        //assert result != null;

        for (int res = result.intValue(); res != 0; ) {
            return Result.fail(res == 1 ? "库存不足" : "不能重复下单");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIdWorker.nextId("order"));
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        orderTasks.add(voucherOrder);

        return Result.ok(orderId);
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.get().getId();

        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        if (!redisLock.tryLock()) {
            return Result.fail("不允许重复下单！");
        }
        try {
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
        } finally {
            redisLock.unlock();
        }
    }


    /*  使用 Redis 加分布式锁

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

    */

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
