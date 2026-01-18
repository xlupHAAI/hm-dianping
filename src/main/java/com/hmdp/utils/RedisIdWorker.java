package com.hmdp.utils;

import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 全局唯一ID, gen by redis.
 *  ---------------------------------------------
 *  | 1'b |     31'b        |       32'b        |
 *  ---------------------------------------------
 *     ↑          ↑                   ↑
 *    ≡0     ts by second        一秒内的序列号
 */
@Component
public class RedisIdWorker {
    /**
     * 开始时间戳 2026.01.17 19:48:29
     */
    private static final long BEGIN_SECOND_TIMESTAMP = 1768679309L;

    private static final int TS_SEQ_PIVOT = 32; // 序列号位数

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix) {
        LocalDateTime currentLocalDateTime = LocalDateTime.now();
        long currentSecondTS = currentLocalDateTime.toEpochSecond(ZoneOffset.UTC);

        String date = currentLocalDateTime.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long seq = stringRedisTemplate.opsForValue().increment("id:" + keyPrefix + ":" + date);

        return currentSecondTS - BEGIN_SECOND_TIMESTAMP << TS_SEQ_PIVOT | seq;
    }
}
