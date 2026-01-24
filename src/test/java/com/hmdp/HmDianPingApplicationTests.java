package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.baomidou.mybatisplus.extension.parser.JsqlParserGlobal.executorService;
import static com.hmdp.utils.RedisConstants.LOGIN_TOKEN_PREFIX;
import static com.hmdp.utils.RedisConstants.LOGIN_TOKEN_TTL;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private UserServiceImpl userService;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testSaveHotShops() {
        shopService.saveShop2Redis(1L, 50L);
        shopService.saveShop2Redis(2L, 50L);
        shopService.saveShop2Redis(3L, 50L);
        shopService.saveShop2Redis(4L, 50L);
        shopService.saveShop2Redis(5L, 50L);
    }

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };

        long begin = System.currentTimeMillis();
        {
            for (int i = 0; i < 300; i++) executorService.execute(task);
            latch.await();
        }
        long end = System.currentTimeMillis();
        System.out.println("time:" + (end - begin));
    }

    @Test
    void testLogin500Usrs() throws IOException {
        List<User> users = userService.query().le("id", 500).list();

        RedisConnection conn = stringRedisTemplate.getConnectionFactory().getConnection();
        conn.openPipeline();    // redis批处理
        FileWriter writer = new FileWriter("assets/tokens.txt");
        writer.flush();

        try {
            for (User usr : users) {
                String token = UUID.randomUUID().toString(true);
                conn.hashCommands().hSet(
                        (LOGIN_TOKEN_PREFIX + token).getBytes(),
                        "nickName".getBytes(),
                        usr.getNickName().getBytes()
                );
                conn.hashCommands().hSet(
                        (LOGIN_TOKEN_PREFIX + token).getBytes(),
                        "id".getBytes(),
                        usr.getId().toString().getBytes()
                );
                conn.hashCommands().hSet(
                        (LOGIN_TOKEN_PREFIX + token).getBytes(),
                        "icon".getBytes(),
                        usr.getIcon().getBytes()
                );

                conn.expire(
                        (LOGIN_TOKEN_PREFIX + token).getBytes(),
                        LOGIN_TOKEN_TTL * 60
                );

                writer.write(token + '\n');
            }
        } finally {
            conn.close();
            writer.close();
        }
    }
}
