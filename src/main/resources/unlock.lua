-- 这里的 KEYS[1] 就是锁的key，这里的ARGV[1] 就是当前线程标示
-- 获取锁中的标示，判断是否与当前线程标示一致
if (redis.call("get", KEYS[1]) == ARGV[1]) then
    -- 释放锁 del key
    return redis.call("del", KEYS[1])
end
-- 不一致，则直接返回
return 0