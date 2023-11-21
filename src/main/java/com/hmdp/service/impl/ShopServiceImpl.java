package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        // 查询redis，是否命中
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        Shop shop = null;

        // TODO 查看逻辑过期时间，是否过期

        // TODO 已过期，开启新线程，异步更新数据和逻辑过期时间

        // TODO 未过期，返回redis结果

        // shopJson != null 且 shopJson != "" 命中
        if (StrUtil.isNotBlank(shopJson)){
            // 不为空对象，将json转为Obj，成功返回
            shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        // redis命中，判断是否为空对象
        if (shopJson != null){
            return Result.fail("商铺不存在！");
        }

        // redis未命中，查询db
        shop  = getById(id);

        // db未命中，返回失败
        if (shop == null){
            // 未命中缓存空对象
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return Result.fail("商铺不存在！");
        }
        // db命中，存入redis，成功返回
         stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空！");
        }
        // 更新数据库
        updateById(shop);

        // 删除redis缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }
}
