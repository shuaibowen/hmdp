-- 获取参数
-- 优惠卷id
local voucherId = ARGV[1]
-- 用户id
local userId = ARGV[2]
-- 订单id
local orderId = ARGV[3]

-- 数据key
-- 库存key
local stockKey = "seckill:stock:" .. voucherId
-- 订单key
local orderKey = "seckill:order:" .. voucherId

-- 业务脚本
-- 判断库存是否充足 get stockKey
if (tonumber(redis.call("get", stockKey)) <= 0) then
    -- 库存不足，返回1
    return 1
end

-- 判断用户是否下单
if (tonumber(redis.call("get", orderKey)) == 1) then
    -- 重复下单，返回2
    return 2
end
-- 库存减一
redis.call("incrby", stockKey, -1)
-- 下单，保存下单用户
redis.call("sadd", orderKey, userId)
-- 3.6.发送消息到队列中， XADD stream.orders * k1 v1 k2 v2 ...
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0
