package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Test
    void testSaveHotShops() {
        shopService.saveShop2Redis(1L, 50L);
        shopService.saveShop2Redis(2L, 50L);
        shopService.saveShop2Redis(3L, 50L);
        shopService.saveShop2Redis(4L, 50L);
        shopService.saveShop2Redis(5L, 50L);
    }

}
