package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

//拦截器
@Slf4j
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate redisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        log.debug("preHandle,时间:{}",new Date());
        System.out.println("preHandle"+new Date()+"-----------------------");
        //1、从header中获取token
        String header = request.getHeader("authorization");
//        HttpSession session = request.getSession();
//        Object key = session.getAttribute("user");
        //判断token是否存在
        if(StrUtil.isBlank(header)){
            return true;
        }
        //2、基于token获取redis中用户
        Map<Object, Object> userMap = redisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + header);
        //3、判断用户是否存在
        if (userMap.isEmpty()){
            return true;
        }
        //4、将map转成UserDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //5、春促用户信息到threadlocal
        UserHolder.saveUser(userDTO);
        //6、刷新token
        redisTemplate.expire(RedisConstants.LOGIN_USER_KEY + header,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
        HandlerInterceptor.super.postHandle(request, response, handler, modelAndView);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        log.debug("afterCompletion执行了,时间:{}",new Date());
        System.out.println("afterCompletion"+new Date()+"-----------------------");
        UserHolder.removeUser();
    }
}
