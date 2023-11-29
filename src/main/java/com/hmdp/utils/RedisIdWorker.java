package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 全局id生成器
 *
 * @Author 帅博文
 * @Date 2023/11/27 10:14
 * @Version 1.0
 */
@Component
public class RedisIdWorker {

    /**
     * 开始的时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1672531200L;

    /**
     * 序列号的位数
     */
    private static int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 生成下一个唯一ID
     *
     * @param prefix ID的前缀
     * @return 生成的唯一ID
     */
    public long nextId(String prefix){
        // 1.获取时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 2.1获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2自增长
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + prefix + ":" + date);

        // 拼接返回
        return timeStamp << COUNT_BITS | count;
    }


    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2023, 1, 1, 0, 0, 0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println("second = " + second);
    }
}
