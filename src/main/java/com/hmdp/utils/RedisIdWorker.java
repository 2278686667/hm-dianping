package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;


import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    public static final Long BEGIN_TIMESTAMP=1640995200L;
    //二进制移动位数
    private static final int COUNT_BITS=32;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    public long nextId(String keyStr){
        //获取当前时间
        LocalDateTime now = LocalDateTime.now();
        long nowTime = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp=nowTime-BEGIN_TIMESTAMP;
        //获取当前年月日
        LocalDate dateTime = LocalDate.now();
        long count = stringRedisTemplate.opsForValue().increment("icr:"  + keyStr + ":" + dateTime);
        //左移32位
        return timeStamp<<COUNT_BITS|count;
    }

    public static void main(String[] args) {
//        LocalDateTime of = LocalDateTime.of(2022, 1, 1, 0, 0, 0, 0);
//        long l = of.toEpochSecond(ZoneOffset.UTC);
//        System.out.println(l);

//        System.out.println(now1);
    }
}
