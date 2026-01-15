package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * @usage 异步地 1.更新 hotshop 的逻辑过期时间； 2.在服务启动时预热 hotshops 到 redis 中。
     */
    private static final ExecutorService Executor4HotshopEnsurance = Executors.newFixedThreadPool(5);

    private final Set<Long> hotShops = new HashSet<>();

    @PostConstruct
    public void init() {
        log.debug("ShopServiceImpl初始化完成，加载热点商户到redis中。");
        new Thread(() -> {
            try {
                Thread.sleep(500); // 确保Redis连接等基础设施就绪
                preheatHotShops();
            } catch (Exception e) {
                log.error("商户缓存预热失败", e);
            }
        }, "CachePreheatThread").start();
    }

    private void preheatHotShops() {
        for (Shop shop : query().ge("comments", HOTSHOP_CMTS_BASELINE).list()) {
            hotShops.add(shop.getId());
            saveShop2Redis(shop, -1L);
        }
    }

    private boolean shopIsHot(Long id) {
        return hotShops.contains(id);
    }

    @Override
    public Result queryById(Long id) {

        Shop shop = shopIsHot(id)
                ? queryWithLogicalExpire(id)
                : queryWithPassThrough(id);
        
        return shop == null ? Result.fail("不存在的店铺信息!") : Result.ok(shop);
    }

    private Shop queryWithLogicalExpire(Long id) {
        String shopKey = CACHE_SHOP_PREFIX + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        assert (shopJson != null);
//        if (StrUtil.isBlank(shopJson)) {
//            return null;
//        }
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        if (expireTime.isAfter(LocalDateTime.now())) {   // 逻辑ttl未过期
            return shop;
        }
        boolean isLock = tryLock(LOCK_SHOP_PREFIX + id);
        if (isLock) {
            expireTime = JSONUtil.toBean(stringRedisTemplate.opsForValue().get(shopKey), RedisData.class)
                    .getExpireTime();
            if (expireTime.isAfter(LocalDateTime.now())) {   // double-check locking
                unlock(LOCK_SHOP_PREFIX + id);
                return shop;
            }

            Executor4HotshopEnsurance.submit(() -> {    // 异步地更新逻辑过期时间，直接返回旧数据
                try {
                    saveShop2Redis(shop, LOGICAL_EXPIRE_LEASE);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(LOCK_SHOP_PREFIX + id);
                }
            });
        }
        return shop;
    }

    public Shop queryWithPassThrough(Long id) {
        String shopKey = CACHE_SHOP_PREFIX + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        if (shopJson == null) {
            Shop shop = getById(id);
            stringRedisTemplate.opsForValue().set(
                    shopKey,
                    shop == null ? "" : JSONUtil.toJsonStr(shop),   // ^^^防缓存穿透
                    shop == null ? CACHE_NULL_TTL : CACHE_SHOP_TTL,
                    TimeUnit.MINUTES
            );
            return shop;
        } else if (shopJson.isEmpty()) {
            return null;
        }
        return JSONUtil.toBean(shopJson, Shop.class);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺ID为空!");
        }
        // ^^^先更新数据库，后删缓存，原子性由事务保证
        updateById(shop);
        if (shopIsHot(shop.getId())) {
            saveShop2Redis(shop, -1L);  // hotshop要一直保存在redis中，设置为逻辑已过期
        } else stringRedisTemplate.delete(CACHE_SHOP_PREFIX + shop.getId());
        return Result.ok();
    }

    /**
     * @param id            ID for shop
     * @param expireSeconds 逻辑过期时间
     * @brief util func, put <shop, logicalTTL> into Redis.
     */
    public void saveShop2Redis(Long id, Long expireSeconds) {
        Shop shop = getById(id);
        saveShop2Redis(shop, expireSeconds);
    }

    public void saveShop2Redis(Shop shop, Long expireSeconds) {
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_PREFIX + shop.getId(), JSONUtil.toJsonStr(redisData));
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
