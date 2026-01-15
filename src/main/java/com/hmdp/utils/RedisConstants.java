package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_VERICODE_PREFIX = "login:vericode:";
    public static final Long LOGIN_VERICODE_TTL = 2L;

    public static final String LOGIN_TOKEN_PREFIX = "login:token:";
    public static final Long LOGIN_TOKEN_TTL = 300L;

    public static final String CACHE_SHOP_PREFIX = "cache:shop:";
    public static final Long CACHE_SHOP_TTL = 30L;

    public static final Long HOTSHOP_CMTS_BASELINE = 2500L;

    public static final Long CACHE_NULL_TTL = 5L;

    public static final Long LOGICAL_EXPIRE_LEASE = 30L;    // 'seconds

    public static final String CACHE_SHOPTYPE_KEY = "cache:shoptypes";
    public static final Long CACHE_SHOPTYPE_TTL = 60L;

    public static final String LOCK_SHOP_PREFIX = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
