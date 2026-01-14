package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE_TTL;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        List<String> result = stringRedisTemplate.opsForList().range(CACHE_SHOPTYPE_KEY, 0, -1);
        List<String> shopTypeJsons = result == null ? new ArrayList<>() : result;

        if (!shopTypeJsons.isEmpty()) { // redis命中
            List<ShopType> shopTypeList = new ArrayList<>();
            shopTypeJsons.forEach(
                    json -> shopTypeList.add(JSONUtil.toBean(json, ShopType.class))
            );
            return Result.ok(shopTypeList);
        }
        // 未命中
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        if (CollectionUtil.isEmpty(shopTypeList)) {
            return Result.fail("商户分类信息不存在！");
        }
        shopTypeList.forEach(shopType -> shopTypeJsons.add(JSONUtil.toJsonStr(shopType)));

        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOPTYPE_KEY, shopTypeJsons);
        stringRedisTemplate.expire(CACHE_SHOPTYPE_KEY, CACHE_SHOPTYPE_TTL, TimeUnit.MINUTES);

        return Result.ok(shopTypeList);
    }
}
