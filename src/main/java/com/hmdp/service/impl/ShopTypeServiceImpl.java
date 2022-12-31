package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Override
    public List<ShopType> queryTypeList() {
        List<ShopType> query=new ArrayList<>();
        //1、查询redis中是否存在
        String shopTypeJson = redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_TYPE_KEY);
        if (StrUtil.isNotBlank(shopTypeJson)){
             return JSONUtil.toList(shopTypeJson, ShopType.class);
        }
        //2、redis中不存在，查询Mysql
        QueryWrapper queryWrapper=new QueryWrapper();
        queryWrapper.orderByAsc("sort");
        query = this.list(queryWrapper);
        //3、缓存到数据库中
        redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(query));
        for (ShopType shopType : query) {
            System.out.println(shopType);
        }
        return query;
    }
}
