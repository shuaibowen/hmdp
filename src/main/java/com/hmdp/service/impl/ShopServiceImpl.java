package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
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

    @Resource
    private CacheClient cacheClient;

    private static final ExecutorService CACHE_REBUILD_EXECUTORS = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {

        // 缓存穿透
//        Shop shop = cacheClient
//                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //Shop shop = queryWithPassThrough(id);

        // 互斥锁解决缓存击穿，热点key
        //Shop shop = queryWithMutex(id);

        // 逻辑过期时间解决缓存击穿，热点key
        //Shop shop = queryWithLogicalExpire(id);
        Shop shop = cacheClient
                .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS, LOCK_SHOP_KEY, LOCK_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("商铺不存在！");
        }

        return Result.ok(shop);
    }

    /**
     * 通过逻辑过期时间，避免缓存击穿问题
     * 前提：热点key会提前放到redis中
     * 默认：认为未命中redis的，不是本次的热点key
     *
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id) {
        String shopKey = CACHE_SHOP_KEY + id;

        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);

        // redis未命中，非热点key，直接返回null
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }

        // 命中redis
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        // 判断逻辑过期时间，是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期，直接返回商铺信息
            return shop;
        }
        // 已过期，重建数据
        String lockKey = LOCK_SHOP_KEY + id;
        // 尝试获取互斥锁
        boolean isLock = tryLock(lockKey);
        // 判断是否获取锁成功
        if (isLock){
            // 获取锁成功，DoubleCheck检查redis 是否存在
            shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if (StrUtil.isNotBlank(shopJson)){
                redisData = JSONUtil.toBean(shopJson, RedisData.class);
                expireTime = redisData.getExpireTime();
                shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
                if (expireTime.isAfter(LocalDateTime.now())) {
                    // 未过期，直接返回商铺信息
                    return shop;
                }
            }
            // 开启新线程
            CACHE_REBUILD_EXECUTORS.submit(() -> {
                try {
                    // 重建数据
                    this.saveShop2Redis(id, LOCK_SHOP_TTL);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unLock(lockKey);
                }

            });
            // 返回旧数据
            return shop;
        }
        // 获取互斥锁失败，返回旧数据
        return shop;
    }


    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 查询数据库
        Shop shop = getById(id);
        // 模拟重建数据延迟
        Thread.sleep(200);
        // 创建redisData对象
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 存入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 通过互斥锁，避免缓存击穿，热点key问题
     *
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        String shopKey = CACHE_SHOP_KEY + id;
        // 查询redis，是否命中
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);

        // shopJson != null 且 shopJson != "" 命中
        if (StrUtil.isNotBlank(shopJson)) {
            // 不为空对象，将json转为Obj，成功返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // redis命中，判断是否为空对象
        if (shopJson != null) {
            return null;
        }

        // redis命中判断
        // 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean lock = tryLock(lockKey);
            // 获取互斥锁失败
            if (!lock) {
                // 等待一段时间后，重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // 获取互斥锁成功
            // DoubleCheck，再次检测redis 是否命中
            shopJson = stringRedisTemplate.opsForValue().get(shopKey);

            if (StrUtil.isNotBlank(shopJson)) {
                // 不为空对象，将json转为Obj，成功返回
                return JSONUtil.toBean(shopJson, Shop.class);
            }

            // redis命中，判断是否为空对象
            if (shopJson != null) {
                return null;
            }

            // 模拟重建的延迟
            Thread.sleep(200);

            // redis未命中，查询db
            shop = getById(id);

            // db未命中，返回失败
            if (shop == null) {
                // 未命中缓存空对象
                stringRedisTemplate.opsForValue().set(shopKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 返回错误信息
                return null;
            }
            // db命中，存入redis，成功返回
            stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放锁
            unLock(lockKey);
        }

        return shop;


    }

    /**
     * 通过缓存空对象，避免缓存穿透
     *
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id) {
        // 查询redis，是否命中
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        Shop shop = null;

        // shopJson != null 且 shopJson != "" 命中
        if (StrUtil.isNotBlank(shopJson)) {
            // 不为空对象，将json转为Obj，成功返回
            shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        // redis命中，判断是否为空对象
        if (shopJson != null) {
            return null;
        }

        // redis未命中，查询db
        shop = getById(id);

        // db未命中，返回失败
        if (shop == null) {
            // 未命中缓存空对象
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }
        // db命中，存入redis，成功返回
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }


    /**
     * 通过循环实现的 互斥锁 解决缓存击穿问题
     *
     * @param id
     * @return
     */
    public Result queryById1(Long id) {
        // 查询redis，是否命中
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        Shop shop = null;

        while (shopJson == null) {
            // 查看是否命中，未命中尝试获取互斥锁
            String lockKey = LOCK_SHOP_KEY + id;
            Boolean lock = tryLock(lockKey);
            if (lock) {
                // 获取互斥锁成功
                // 模拟重建的延迟
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                // redis未命中，查询db
                shop = getById(id);

                // db未命中，返回失败
                if (shop == null) {
                    // 未命中缓存空对象
                    stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    // 返回错误信息
                    return Result.fail("商铺不存在！");
                }
                // db命中，存入redis，成功返回
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

                // 释放互斥锁
                unLock(lockKey);

                return Result.ok(shop);
            } else {
                // TODO 获取互斥锁失败，休眠一段时间，继续尝试获取redis数据
                try {
                    Thread.sleep(50);
                    shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        // shopJson != null 且 shopJson != "" 命中
        if (StrUtil.isNotBlank(shopJson)) {
            // 不为空对象，将json转为Obj，成功返回
            shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        } else {
            // redis命中，为空对象
            return Result.fail("商铺不存在！");
        }

    }

    private boolean tryLock(String lockKey) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空！");
        }
        // 更新数据库
        updateById(shop);

        // 删除redis缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }
}
