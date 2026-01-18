package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static com.baomidou.mybatisplus.extension.parser.JsqlParserGlobal.executorService;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

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
}
