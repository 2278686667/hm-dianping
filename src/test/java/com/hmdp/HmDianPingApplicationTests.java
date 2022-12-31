package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.servlet.http.HttpSession;
import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private ShopServiceImpl shopService;

    @Autowired
    private IUserService userService;
    @Test
    void test(){
        stringRedisTemplate.opsForValue().set("ceshi","ceshi");
        System.out.println(stringRedisTemplate.opsForValue().get("ceshi"));
    }
    @Test
    void saveRedis() throws InterruptedException {
        shopService.saveShop2Redis(1L,10L);
    }

    @Test
    void incr(){
        Long increment = stringRedisTemplate.opsForValue().increment("2");
        System.out.println(increment);
    }
    public ExecutorService executorService= Executors.newFixedThreadPool(500);
    @Test
    void testReidIncr() throws InterruptedException {
        CountDownLatch countDownLatch=new CountDownLatch(300);
        long start = System.currentTimeMillis();
        Runnable runnable=()-> {
            for (int i = 0; i < 100; i++) {
                long shop = redisIdWorker.nextId("shop");
                System.out.println("id=" + shop);
            }
            countDownLatch.countDown();
        };
        for (int i = 0; i < 300; i++) {
            executorService.submit(runnable);
        }
        executorService.submit(runnable);
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("总耗时"+(end-start));

    }
    @Test
    void testCreateToken() throws IOException {

        for (int i = 0; i < 1000; i++) {
            String s = UUID.randomUUID().toString(true).substring(1,12);
            Result login = login(s);
            System.out.println(login);
        }


    }
    private static void redisToken(String token){
        token=token+"\n";
        File file=new File("D:\\java\\redis\\hm\\token.txt");
        OutputStream outputStreamWriter=null;
        try {
            outputStreamWriter=new FileOutputStream(file,true);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        try {
            outputStreamWriter.write(token.getBytes());
        } catch (IOException e) {
            e.printStackTrace();

        }finally {
            try {
                outputStreamWriter.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    public Result login(String phone) {
        //不符合
//        if (RegexUtils.isPhoneInvalid(phone)){
//            return Result.fail("手机号有误");
//        }
        //校验验证码,从redis中获取
        //Object cachecode = session.getAttribute("code");

        //查询用户是否存在
        User user = userService.query().eq("phone", phone).one();
        //如果不存在，创建一个
        if (user==null){
            user=createUserByphone(phone);
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //将用户信息存储到redis中
        //token
        String token = UUID.randomUUID().toString(true);
//        session.setAttribute("user",user);
        //将UserDTO转换成map存储到Redis
        Map<String, Object> map = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY+token,map);
        //token过期时间
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        this.redisToken(token);
        return Result.ok(token);
    }
    private User createUserByphone(String phone) {

        User user=new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX+ RandomUtil.randomString(10));
        userService.save(user);
        return user;
    }
}
