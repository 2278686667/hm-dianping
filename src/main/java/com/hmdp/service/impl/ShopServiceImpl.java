package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONNull;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private CacheClient cacheClient;
    //创建线程池
    private static final ExecutorService EXECUTOR_SERVICE= Executors.newFixedThreadPool(10);
    @Override
    public Result queryById(Long id) {
        //1、缓存穿透解决方案
//         queryWithPassThrough(id);
//        Shop shop = queryWithPassThrough(id);

        //1.2工具类
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, id2 -> getById(id2),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //2、缓存穿透 逻辑过期
        //        Shop shop = queryWithLogicalExpire(id);
        //2.2工具类
//        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, id2 -> getById(id2), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop==null){
            return Result.fail("不存在");
        }
        return Result.ok(shop);
    }
    //逻辑过期（热点数据）
    private Shop queryWithLogicalExpire(Long id){
        //1、查询redis获取数据
        String key= RedisConstants.CACHE_SHOP_KEY+id;
        String shopJson = redisTemplate.opsForValue().get(key);
        //2、判断是否为空
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        //3、不为空判断是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        log.debug("dd{}",redisData.getExpireTime());
        log.debug("ee{}",LocalDateTime.now());
        if(expireTime.isAfter(LocalDateTime.now())) {
            //没有过期
            return shop;
        }
        //4、表示数据已经过期，进行重构
        String tryKey=LOCK_SHOP_KEY+id;
        //5、互斥获取锁，没有锁占用进行重构
        boolean tryB = tryLock(tryKey);
        if (tryB){
            //重构
            EXECUTOR_SERVICE.submit(()->{
                try {
                    saveShop2Redis(id,20L);
                } catch (Exception e) {
                    e.printStackTrace();
                }finally {
                    unlock(tryKey);
                }

            });
        }
        //6、资源已经互斥，直接返回旧数据
        return shop;
    }

    //缓存击穿解决方案（热点数据大量请求）
    private Shop queryWithMutex(Long id){

        //1、查询redis获取数据
        String key= RedisConstants.CACHE_SHOP_KEY+id;
        String shopJson = redisTemplate.opsForValue().get(key);
        //2、非空存在
        if (StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson,Shop.class);
        }
        //3、redis中不存在，查询mysql，使用互斥锁
        String lockKey="lock:shop:"+id;  //互斥key
        Shop shop = null;
        try {
            boolean lock = tryLock(lockKey);
            if (!lock){
                //为假key已经存在了，正在构建，重试
                TimeUnit.SECONDS.sleep(50);
                return queryWithMutex(id);
            }
            //4、从mysql中查询
            shop = getById(id);
            if (shop==null){
                //将空值写入redis
                redisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }
            //5、redis中存在
            redisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_NULL_TTL,TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
            throw new RuntimeException();
        } finally {
            //释放互斥锁
            unlock(lockKey);
        }

        return shop;

    }
    //缓存穿透解决方案
    private Shop queryWithPassThrough(Long id) {
        String key= RedisConstants.CACHE_SHOP_KEY;
        //1、从Redis查询商铺缓存
        String shop = redisTemplate.opsForValue().get(key + id);
        //2、判断是否命中
        if (StrUtil.isNotBlank(shop)){
            //命中直接返回
//            return Result.ok(JSONUtil.toBean(shop,Shop.class));
            return null;
        }
        //防止缓存穿透，继续判断redis中是否是无效值
        if(shop!=null){
            //!=null表示取反，是""空值返回
//            return Result.fail("店铺不存在");
            return null;
        }
        //3、缓存没有命中查询Mysql
        Shop byId = getById(id);
        //4、判断是否存在
        if (byId==null){
            //不存在，防止出现缓存穿透问题，返回一个空值,两分钟过期时间
            redisTemplate.opsForValue().set(key+ id,"", CACHE_NULL_TTL,TimeUnit.MINUTES);
//            return Result.ok("店铺不存在");
            return null;
        }
        //5、MySql存在写入到Redis
        redisTemplate.opsForValue().set(key+ id,JSONUtil.toJsonStr(byId),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //6、返回信息
//        return Result.ok(byId);
        return byId;
    }
    //获取锁false表示已经存在
    private boolean tryLock(String key){

        Boolean aBoolean = redisTemplate.opsForValue().setIfAbsent(key,"1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(aBoolean);
    }
    //释放说
    private void unlock(String key){
        redisTemplate.delete(key);

    }

    //使用事务保证一致性
    @Override
    @Transactional
    public Result update(Shop shop) {
        if (shop.getId()==null){
            return Result.fail("商铺id不能为空");
        }
        //先更新数据库在更新缓存
        updateById(shop);
        redisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }
    //缓存重建
    public void saveShop2Redis(Long id, Long localDateTime) throws InterruptedException {
        TimeUnit.SECONDS.sleep(5);
        Shop shop = getById(id);
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(localDateTime));
        redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
}
