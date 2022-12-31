package com.hmdp.service.impl;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import com.hmdp.service.Ilock;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁
 */
public class SimpleRedisLock implements Ilock {
    private StringRedisTemplate redisTemplate;
    private String name;

    public SimpleRedisLock(StringRedisTemplate redisTemplate, String name) {
        this.redisTemplate = redisTemplate;
        this.name = name;
    }

    private static final String KEY_PREFIX="lock:";
    //使用houtu中toString()取出下划线
    private static final String ID_PREFIX= UUID.randomUUID().toString(true)+"-";
    private static final DefaultRedisScript<Long> UNLOCAL_SCRIPT;
    static {
        UNLOCAL_SCRIPT=new DefaultRedisScript<>();
        //加载lua脚本 使用ClassPathResource加载资源
        UNLOCAL_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        //返回值类型
        UNLOCAL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Boolean tryLock(Long timeSec) {
        //获取当前线程id作为标识 拼接uuid防止多个jvm进行id相同
        String id = ID_PREFIX+Thread.currentThread().getId();
        //获取说
        Boolean aBoolean = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, id, timeSec, TimeUnit.SECONDS);
        //true表示获取锁 false表示获取失败
        return BooleanUtil.isTrue(aBoolean);
    }


    //释放锁  第一版分布式锁，没有原子性(查询 判断)
//    @Override
//    public void unlock() {
//        //判断是否是当前线程
//        String id = ID_PREFIX+Thread.currentThread().getId();
//        //获取redis中枷锁的线程id
//        String redisId = redisTemplate.opsForValue().get(KEY_PREFIX + name);
//        if (id.equals(redisId)){
//            //释放锁
//            redisTemplate.delete(KEY_PREFIX + name);
//        }
//    }

    /**
     * 基于lua脚本实现原子性（判断和删除在一个文件中）
     */
    @Override
    public void unlock() {
        //脚本 ，key  value
        redisTemplate.execute(UNLOCAL_SCRIPT, Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX+Thread.currentThread().getId());
    }
}
