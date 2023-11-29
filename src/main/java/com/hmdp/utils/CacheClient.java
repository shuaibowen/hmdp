package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;


/**
 * @Author 帅博文
 * @Date 2023/11/23 17:03
 * @Version 1.0
 */
@Component
@Slf4j
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    private static final ScheduledThreadPoolExecutor CACHE_REBUILD_POOL;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    static {
        int corePoolSize = 10;
        int maximumPoolSize = 10;
        long keepAliveTime = 60L;
        TimeUnit unit = TimeUnit.SECONDS;

        CACHE_REBUILD_POOL = new ScheduledThreadPoolExecutor(10);
        CACHE_REBUILD_POOL.setMaximumPoolSize(maximumPoolSize);
        CACHE_REBUILD_POOL.setKeepAliveTime(keepAliveTime, unit);
    }


    /**
     * 存入redis，带有ttl
     *
     * @param key
     * @param value
     * @param expireTime
     * @param unit
     */
    public void set(String key, Object value, Long expireTime, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), expireTime, unit);
    }

    /**
     * 存入reids，带有逻辑过期时间
     *
     * @param key
     * @param value
     * @param expireTime
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long expireTime, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(expireTime)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 获取redis数据，未命中时返回空对象
     * 避免缓存穿透
     *
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallBack
     * @param expireTime
     * @param unit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack,
            Long expireTime, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 命中redis，且不为空
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 命中为空时
        if (json != null) {
            return null;
        }

        R r = dbFallBack.apply(id);
        // 查询db，不存在，缓存空对象
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", expireTime, unit);
            return null;
        }

        // 存在，数据重建
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(r), expireTime, unit);
        return r;
    }


    /**
     * 获取redis 热点数据
     * 通过逻辑过期时间，避免缓存击穿问题
     * 前提：热点key会提前放到redis中
     * 默认：认为未命中redis的，不是本次的热点key
     *
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallBack
     * @param time       逻辑过期时长
     * @param unit       逻辑过期时间单位
     * @param lockKey
     * @param lockTime   redis锁过期时间
     * @param lockUnit   redis锁过期时间单位
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack,
            Long time, TimeUnit unit,
            String lockKey, Long lockTime, TimeUnit lockUnit
    ) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isBlank(json)) {
            return null;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);

        // 未过期，直接返回
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }

        // 过期，数据重建
        boolean lock = tryLock(lockKey, lockTime, lockUnit);
        // 获取锁失败，直接返回旧数据
        if (!lock) {
            return r;
        }
        // 获取锁成功，DoubleCheck
        json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        redisData = JSONUtil.toBean(json, RedisData.class);
        expireTime = redisData.getExpireTime();
        r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }

        // 数据重建
        CACHE_REBUILD_POOL.submit(() -> {
            try {
                saveObject2Redis(key, id, time, dbFallBack, unit);
            } catch (InterruptedException e) {
                log.error("saveShop2Redis error: ", e);
                throw new RuntimeException(e);
            } finally {
                unLock(lockKey);
            }

        });
        return r;
    }

    /**
     * 保存对象到redis中，并设置逻辑过期时间
     *
     * @param key
     * @param id
     * @param expireSeconds
     * @param dbFallBack
     * @param unit
     * @param <ID>
     * @param <R>
     * @throws InterruptedException
     */
    private <ID, R> void saveObject2Redis(
            String key, ID id, Long expireSeconds,
            Function<ID, R> dbFallBack, TimeUnit unit) throws InterruptedException {
        // 查询数据库
        R r = dbFallBack.apply(id);
        // 模拟重建数据延迟
        Thread.sleep(200);
        // 创建redisData对象
        RedisData redisData = new RedisData();
        redisData.setData(r);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(expireSeconds)));
        // 存入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 通过setnx，来限制单线程重建数据
     * 根据lockKey，设置锁过期时间
     *
     * @param lockKey
     * @param expireTime
     * @param unit
     * @return
     */
    private boolean tryLock(String lockKey, Long expireTime, TimeUnit unit) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", expireTime, unit);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放redis锁
     *
     * @param lockKey
     */
    private void unLock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }

}
