package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Override
    public Result seckillVoucher(Long voucherId) {

        // 查询秒杀券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        // 判断是否再秒杀时间内
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        LocalDateTime endTime = seckillVoucher.getEndTime();
        LocalDateTime now = LocalDateTime.now();
        // 当前时间在开始时间之前
        if (now.isBefore(beginTime)) {
            // 非秒杀时间，返回异常结果
            return Result.fail("秒杀尚未开始！");
        }
        // 当前时间在结束时间之后
        if (now.isAfter(endTime)) {
            // 非秒杀时间，返回异常结果
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是否充足
        if (seckillVoucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足！");
        }

        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock(1200);
//        boolean isLock = lock.tryLock();
        // 判断是否获取成功
        if (!isLock) {
            // 获取锁失败，返回错误或重试
            return Result.fail("不允许重复下单！");
        }
        try {
            // 获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 一人一订单
        Long userId = UserHolder.getUser().getId();
        // 查询用户订单数量
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 判断是否存在订单
        if (count > 0) {
            return Result.fail("用户已经购买过一次！");
        }

        // 库存减一
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        // 库存减少失败，返回异常结果
        if (!success) {
            return Result.fail("库存不足！");
        }
        // 生成订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 1.订单id
        long orderId = redisIdWorker.nextId(SECKILL_STOCK_KEY);
        voucherOrder.setId(orderId);
        // 2.用户id
        voucherOrder.setUserId(userId);
        // 3.代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 返回订单ID
        return Result.ok(orderId);
    }
}
