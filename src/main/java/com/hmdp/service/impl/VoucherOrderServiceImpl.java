package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate redisTemplate;
    //多redis集群锁实现
    @Autowired
    private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    //阻塞队列
   // private BlockingQueue<VoucherOrder> orders=new ArrayBlockingQueue<>(1024*1024);
    //异步线程池
    private static final ExecutorService EXECUTOR_SERVICE= Executors.newSingleThreadExecutor();

    //线程池
    static {
        SECKILL_SCRIPT =new DefaultRedisScript<>();
        //加载lua脚本 使用ClassPathResource加载资源
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        //返回值类型
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    @PostConstruct
    public void init(){
        EXECUTOR_SERVICE.submit(new VoucherOrderHandler());

    }
    private IVoucherOrderService proxy;
    public class VoucherOrderHandler implements Runnable{
        String key="stream.orders";
        String consumer="c1";
        String group="g1";
        @Override
        public void run() {
            while (true){
                try {
                    //读取队列中的消息 xreadgroup group g1 c1 count 1 block 2000 streams stream.order >
                    List<MapRecord<String, Object, Object>> read = redisTemplate.opsForStream().read(
                            Consumer.from(group,consumer),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(key, ReadOffset.lastConsumed()));
                    if (read.isEmpty()||read==null){
                        continue;
                    }
                    //解析数据
                    MapRecord<String, Object, Object> entries = read.get(0);
                    RecordId id = entries.getId();
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(),true);
                    //处理订单
                    createVoucherOrder(voucherOrder);
                    //确认
                    redisTemplate.opsForStream().acknowledge(key, group, id);

                } catch (Exception e) {
                    handlePendingList();

                    e.printStackTrace();
                    log.error("处理订单异常",e);
                }
            }
        }

        private void handlePendingList() {
            while (true){
                try {
                    //异常从pend-list中获取
                    List<MapRecord<String, Object, Object>> read = redisTemplate.opsForStream().read(
                            Consumer.from(group,consumer),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(key, ReadOffset.from("0")));
                    //解析数据
                    MapRecord<String, Object, Object> entries = read.get(0);
                    RecordId id = entries.getId();
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(),true);
                    //处理订单
                    createVoucherOrder(voucherOrder);
                    //确认
                    redisTemplate.opsForStream().acknowledge(key, group, id);
                }catch (Exception e){
                    log.error("处理pendding订单异常",e);
                    try {
                        Thread.sleep(20);
                    } catch (Exception e1) {
                        e1.printStackTrace();
                    }
                }
            }

        }

        //处理订单
        private void handlerVoucherOrder(VoucherOrder voucherOrder) {
            Long userId = voucherOrder.getUserId();
            RLock ilock = redissonClient.getLock("lock:order:" + userId);
            Boolean aBoolean = ilock.tryLock();
            //表示获取锁失败
            if (!aBoolean){
                log.error("一人一单");
                return;
            }
            try {
                //创建订单
                 proxy.createVoucherOrder(voucherOrder);
            }  finally {
                ilock.unlock();
            }


        }
//        @Override
//        public void run() {
//            while (true){
//                try {
//                    VoucherOrder take = orders.take();
//                    handlerVoucherOrder(take);
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                    log.error("处理订单异常",e);
//                }
//            }
//        }
//        //处理订单
//        private void handlerVoucherOrder(VoucherOrder voucherOrder) {
//            Long userId = voucherOrder.getUserId();
//            RLock ilock = redissonClient.getLock("lock:order:" + userId);
//            Boolean aBoolean = ilock.tryLock();
//            //表示获取锁失败
//            if (!aBoolean){
//                log.error("一人一单");
//                return;
//            }
//            try {
//                //创建订单
//                 proxy.createVoucherOrder(voucherOrder);
//            }  finally {
//                ilock.unlock();
//            }
//
//
//        }
    }
    @Override
    public Result seckKillVourcher(Long voucherId) {
        //获取用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        //订单id
        long orderid = redisIdWorker.nextId("order");
        //1、执行lua脚本
        Long result = redisTemplate.execute(
                SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderid));
        int r = result.intValue();
        //2、判断结果是否为0
        if (r!=0){
            return Result.fail(r==1?"库存不足":"一人一单");
        }
        long orderId = redisIdWorker.nextId("order");

        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //3、返回订单id
        return Result.ok(orderId);

    }
    //基于阻塞队列抢购优惠券（多线程）
//    @Override
//    public Result seckKillVourcher(Long voucherId) {
//        //获取用户
//        UserDTO user = UserHolder.getUser();
//        Long userId = user.getId();
//
//        //1、执行lua脚本
//        Long result = redisTemplate.execute(
//                SECKILL_SCRIPT, Collections.emptyList(),
//                voucherId.toString(), userId.toString());
//        int r = result.intValue();
//        //2、判断结果是否为0
//        if (r!=0){
//            return Result.fail(r==1?"库存不足":"一人一单");
//        }
//        long orderId = redisIdWorker.nextId("order");
//        //TODO
//        //3、创建订单，返回订单编号
//        VoucherOrder voucherOrder=new VoucherOrder();
//        //订单id
//        long orderid = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderid);
//        //代金券id
//        voucherOrder.setVoucherId(voucherId);
//        //用户id
//        voucherOrder.setUserId(userId);
//        //放入阻塞队列中
//        orders.add(voucherOrder);
//        //获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        //3、返回订单id
//        return Result.ok(orderId);
//
//    }
//    //抢购优惠券(单线程)
//    @Override
//    public Result seckKillVourcher(Long voucherId) {
//        //判断优惠劵是否存在
//        SeckillVoucher byId = seckillVoucherService.getById(voucherId);
//        if (byId==null){
//            return Result.fail("优惠券不存在");
//        }
//        //判断开始日期
//        if (byId.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("优惠券还未开始");
//        }
//        //判断结束日期
//        if(byId.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("优惠券已经结束");
//        }
//        //判断库存
//        if (byId.getStock()<=0){
//            return Result.fail("已经被抢完了");
//        }
//        //单机操作 一人一单,因为是新增操作，使用悲观锁
//        Long userId = UserHolder.getUser().getId();
////        synchronized (userId.toString().intern()) {
////        集群模式（多个项目） 分布式锁
////        SimpleRedisLock ilock=new SimpleRedisLock(redisTemplate,"order:"+userId);
////        多台redis不能保证所得一致性，需要通过redisson来实现
//        RLock ilock = redissonClient.getLock("lock:order:" + userId);
//        Boolean aBoolean = ilock.tryLock();
//        //表示获取锁失败
//        if (!aBoolean){
//            return  Result.fail("一人一单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId, userId);
//        }  finally {
//            ilock.unlock();
//        }
////        }
//
////    }
//    @Override
//    @Transactional
//    public Result createVoucherOrder(Long voucherId, Long userId) {
//        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        if (count>0){
//            return Result.fail("不可重复购买");
//        }
//
//        boolean voucher_id = seckillVoucherService.update().setSql("stock=stock-1")
//                .eq("voucher_id", voucherId).gt("stock",0)  //只要库存大于0允许下单
//                .update();
//        if (!voucher_id){
//            return Result.fail("库存不足");
//        }
//        //创建订单，返回订单编号
//        VoucherOrder voucherOrder=new VoucherOrder();
//        //订单id
//        long orderid = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderid);
//        //代金券id
//        voucherOrder.setVoucherId(voucherId);
//        //用户id
//
//        voucherOrder.setUserId(userId);
//        save(voucherOrder);
//        return Result.ok(orderid);
//    }


    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count>0){
            log.error("不可重复购买");
            return;
        }

        boolean voucher_id = seckillVoucherService.update().setSql("stock=stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock",0)  //只要库存大于0允许下单
                .update();
        if (!voucher_id){
            log.error("库存不足");
            return;
        }
        save(voucherOrder);
        return;
    }

}
