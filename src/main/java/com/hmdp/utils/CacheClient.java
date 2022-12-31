package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    //创建线程池
    private static final ExecutorService EXECUTOR_SERVICE= Executors.newFixedThreadPool(10);
    //set存储任意类型
    public void set(String key,Object value, TimeUnit unit,Long time){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    //逻辑过期用到得设置永不过期
    public void setWithLogicalExpire(String key,Object value,TimeUnit unit,Long time){
        RedisData redisData=new RedisData();
        redisData.setData(value);
        //将time转换为秒，与当前时间进行相加
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    //缓存穿透解决方案
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> function,Long time, TimeUnit unit){
        String key= keyPrefix;
        //1、从Redis查询商铺缓存
        String shop = stringRedisTemplate.opsForValue().get(key + id);
        //2、判断是否命中
        if (StrUtil.isNotBlank(shop)){
            //命中直接返回
//            return Result.ok(JSONUtil.toBean(shop,Shop.class));
            return JSONUtil.toBean(shop,type);
        }
        //防止缓存穿透，继续判断redis中是否是无效值
        if(shop!=null){
            //!=null表示取反，是""空值返回
//            return Result.fail("店铺不存在");
            return null;
        }
        //3、缓存没有命中查询Mysql
        R apply = function.apply(id);
        //4、判断是否存在
        if (apply==null){
            //不存在，防止出现缓存穿透问题，返回一个空值,两分钟过期时间
            stringRedisTemplate.opsForValue().set(key+ id,"", CACHE_NULL_TTL,TimeUnit.MINUTES);
//            return Result.ok("店铺不存在");
            return null;
        }
        //5、MySql存在写入到Redis
        this.set(key,apply,unit,time);
        //6、返回信息
//        return Result.ok(byId);
        return apply;
    }
    //缓存穿透 逻辑过期（热点数据）
    public <R,ID> R queryWithLogicalExpire(String keyStr,ID id,Class<R> type,Function<ID,R> function,Long time, TimeUnit unit){
        //1、查询redis获取数据
        String key= keyStr+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2、判断是否为空
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        //3、不为空判断是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())) {
            //没有过期
            return r;
        }
        //4、表示数据已经过期，进行重构
        String tryKey=LOCK_SHOP_KEY+id;
        //5、互斥获取锁，没有锁占用进行重构
        boolean tryB = tryLock(tryKey);
        if (tryB){
            //重构
            EXECUTOR_SERVICE.submit(()->{
                try {
                    R newR = function.apply(id);
                    this.setWithLogicalExpire(key,newR,unit,time);
                } catch (Exception e) {
                    e.printStackTrace();
                }finally {
                    unlock(tryKey);
                }

            });
        }
        //6、资源已经互斥，直接返回旧数据
        return r;
    }

    //获取锁false表示已经存在
    private boolean tryLock(String key){

        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(key,"1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(aBoolean);
    }
    //释放说
    private void unlock(String key){
        stringRedisTemplate.delete(key);

    }

}
