package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 使用list类型存入redis
     * @return
     */
    @Override
    public Result queryTypeList() {
        // 查询redis
        List<String> typeJson = stringRedisTemplate.opsForList().range(CACHE_SHOPTYPE_KEY, 0, -1);

        if (CollUtil.isNotEmpty(typeJson)){
            // redis命中，将json转换为List，返回
            return Result.ok(
                    typeJson.stream().map(
                    json -> JSONUtil.toBean(json, ShopType.class)
                    ).collect(Collectors.toList())
            );
        }

        // redis未命中，查询db
        List<ShopType> typeList = query().orderByAsc("sort").list();

        if (CollUtil.isEmpty(typeList)){
            return Result.fail("未查询到数据！");
        }

        // 查询db，存入redis，返回
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOPTYPE_KEY,
                typeList.stream().map(type ->  JSONUtil.toJsonStr(type)).collect(Collectors.toList()));

        return Result.ok(typeList);
    }

    /**
     * 用String类型存入redis
     * @return
     */
    /*@Override
    public Result queryTypeList() {
        // 查询redis
        String typeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOPTYPE_KEY);

        if (StrUtil.isNotBlank(typeJson)){
            // redis命中，将json转换为List，返回
            return Result.ok(JSONUtil.toList(typeJson, ShopType.class));
        }

        // redis未命中，查询db
        List<ShopType> typeList = query().orderByAsc("sort").list();

        if (CollUtil.isEmpty(typeList)){
            return Result.fail("未查询到数据！");
        }

        // 查询db，存入redis，返回
        stringRedisTemplate.opsForValue().set(CACHE_SHOPTYPE_KEY, JSONUtil.toJsonStr(typeList));

        return Result.ok(typeList);
    }*/

}
