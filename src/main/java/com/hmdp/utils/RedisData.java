package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用来额外存储逻辑过期时间的 redisData实体
 * @author 帅
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
