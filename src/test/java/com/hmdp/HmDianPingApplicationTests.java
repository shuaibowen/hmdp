package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    ShopServiceImpl shopService;

    @Resource
    CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    private ExecutorService es = Executors.newFixedThreadPool(500);

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
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));

    }

    /**
     * 商品信息预热
     */
    @Test
    void testSaveShop() throws InterruptedException {
//        shopService.saveShop2Redis(1L, 10L);

        Shop shop = shopService.getById(1L);

        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);

    }

    @Test
    void testRedisson() throws InterruptedException {
        // 获取锁(可重入)，指定锁的名称
        RLock lock = redissonClient.getLock("anyLock");
        // 尝试获取锁，参数分别是：获取锁的最大等待时间(期间会重试)，锁自动释放时间，时间单位
        boolean success = lock.tryLock(1, 10, TimeUnit.SECONDS);
        if (success) {
            try {
                System.out.printf("执行业务");
            } finally {
                lock.unlock();
            }
        }
    }

    @Test
    void testTokens() {
        String token;
        Long i = 1L;
        Map<String, Object> userMap;
        while (i <= 1001) {
            // 生成token，不带-
            token = UUID.randomUUID().toString(true);
            User user = userService.getById(i);
            if (user == null ){
                i++;
                continue;
            }
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                    CopyOptions.create().setIgnoreNullValue(true)
                            .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
            );
            stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
            System.out.println(token);
            i++;
        }
    }

}
