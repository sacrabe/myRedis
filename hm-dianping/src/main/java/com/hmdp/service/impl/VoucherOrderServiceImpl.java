package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
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
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    @Lazy
    private IVoucherOrderService self;

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    public RedissonClient redissonClient;

    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    public static final DefaultRedisScript<Long> SECKILL_SCRIPT_STREAM;

    static{
        SECKILL_SCRIPT_STREAM = new DefaultRedisScript<>();
        SECKILL_SCRIPT_STREAM.setLocation(new ClassPathResource("seckillMessage.lua"));
        SECKILL_SCRIPT_STREAM.setResultType(Long.class);
    }
    // 阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    // 线程池
    public static final  ExecutorService SECKILL_ORDER_EXCUTOR = Executors.newSingleThreadExecutor();

    // 初始化 任务提交
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXCUTOR.submit(new VoucherOrderHandler());
    }

    // 任务类 负责获取队列中的订单信息  通过 redis Stream 消息队列
    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            while(true){
                try {
                    // 1.获取消息队列中的订单信息 xreadgroup group g1 c1 count 1 block 2000 stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    if(list == null || list.isEmpty()){
                        // 2.判断消息获取是否成功
                        // 2.1.如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 3.如果获取成功，可以下单
                    MapRecord<String, Object, Object> entries = list.get(0);
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    // 创建订单
                    handleVoucherOrder(voucherOrder);
                    // 4.ACK确认  sack stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1", entries.getId());

                } catch (Exception e) {
                    // 说明 可能创建订单但是未确认  即 pending-list 中有消息
                    log.error("创建订单异常",e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while(true){
                try {
                    // 1.获取pending-List中的订单信息 xreadgroup group g1 c1 count 1  stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    if(list == null || list.isEmpty()){
                        // 2.判断消息获取是否成功
                        // 2.1.如果获取失败，说明pending-list没有消息，结束循环
                        break;
                    }
                    // 3.如果获取成功，可以下单
                    MapRecord<String, Object, Object> entries = list.get(0);
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    // 创建订单
                    handleVoucherOrder(voucherOrder);
                    // 4.ACK确认  sack stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1", entries.getId());

                } catch (Exception e) {
                    log.error("创建订单异常",e);
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }

                }
            }
        }
    }

    //阻塞队列线程
    private class VoucherOrderHandler1 implements Runnable{
        @Override
        public void run() {
            while(true){
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("创建订单异常",e);
                }
            }
        }
    }
    // 将代理对象放在成员变量初， 使得子线程也能获取id
    private volatile IVoucherOrderService proxy;

    // 异步进程  创建订单
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1. 获取用户
        Long userId = voucherOrder.getUserId();
        //SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        // 2. 获取锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 3.判断
        boolean ifLock = lock.tryLock();
        if(!ifLock){
            log.error("不允许重复下单");
            // 获取锁失败。返回错误或者重试
            return ;
        }
        // 获取代理对象 调用方法才能使事务生效
        try {
            // 子线程无法获取 主线程代理对象
            //IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            //proxy.createVoucherOrderAsync(voucherOrder);
            self.createVoucherOrderAsync(voucherOrder);
        } finally {
             lock.unlock();
       }
    }

    @Override
    @Transactional
    public void createVoucherOrderAsync(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();

        // 4 .1 一人一单
        int count = lambdaQuery()
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getVoucherId, voucherOrder.getVoucherId())
                .count();
        if (count > 0) {
            log.error("用户已经下过单");
            return ;
        }

        // 5. 扣减库存
        boolean success = seckillVoucherService
                .lambdaUpdate()
                .eq(SeckillVoucher::getVoucherId, voucherOrder.getVoucherId())
                .setSql("stock = stock-1").gt(SeckillVoucher::getStock, 0)
                .update();
        if (!success) {
            log.error("库存不足");
            return ;
        }

        save(voucherOrder);
        // 7. 返回订单id

    }
    // 利用 redis stream消息队列实现
    @Override
    public Result seckillVoucherByStream(Long voucherId) {
        // TODO: 秒杀券实现

        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1. 执行lua脚本  判断购买资格  +  将消息发送到 stream 队列
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT_STREAM,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId));
        // 2. 判断
        int r = result.intValue();
        if(r != 0){
            return  Result.fail(r==1 ? "库存不足" : "不能重复下单");
        }

        //proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4.

        return Result.ok(orderId);
    }


    /**
     * 异步优化
     * 利用 jvm  阻塞队列实现
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // TODO: 秒杀券实现

        Long userId = UserHolder.getUser().getId();

        // 1. 执行lua脚本  判断用户有无购买资格  +   在redis中 添加 用户购买与否信息 + 简略订单信息  +  扣除redis中库存
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString());
        // 2. 判断
        int r = result.intValue();
        if(r != 0){
            return  Result.fail(r==1 ? "库存不足" : "不能重复下单");
        }
        // 3. 为0 ，保存下单信息到阻塞队列

        // todo
        // 3.1 生成订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 3.2 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 3.3 用户id

        voucherOrder.setUserId(userId);
        // 3.4 代金券id
        voucherOrder.setVoucherId(voucherId);
        // 3.5 创建 利用阻塞队列
        orderTasks.add(voucherOrder);
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4.

        return Result.ok(orderId);
    }


    /**
     * 未异步优化的秒杀
     *
     * 利用乐观锁锁 + 分布式锁
     *
     */


    /*
    @Override
    public Result seckillVoucher(Long voucherId) {
        // TODO: 秒杀券实现
        // 1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {

            return Result.fail("秒杀尚未开始");
        }
        // 3. 秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束");
        }

        // 4. 库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }


        *//**
         * 单体模式锁
         *//*

        *//*
        synchronized (userId.toString().intern()) {
            // 获取代理对象 调用方法才能使事务生效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
        *//*
        *//**
         *
         * 分布式锁
         *//*
         Long userId = UserHolder.getUser().getId();
        //SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        boolean ifLock = lock.tryLock();
        if(!ifLock){
            // 获取锁失败。返回错误或者重试
            return Result.fail("您已购买过");
        }
        // 获取代理对象 调用方法才能使事务生效
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }

    }*/

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        // 4 .1 一人一单
        int count = lambdaQuery()
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getVoucherId, voucherId)
                .count();
        if (count > 0) {
            return Result.fail("您已经购买过一次了");
        }

        // 5. 扣减库存
        boolean success = seckillVoucherService
                .lambdaUpdate()
                .eq(SeckillVoucher::getVoucherId, voucherId)
                .setSql("stock = stock-1").gt(SeckillVoucher::getStock, 0)
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }
        // 6. 生成订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 6.1订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 6.2 用户id

        voucherOrder.setUserId(userId);
        // 6.3 代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        // 7. 返回订单id
        return Result.ok(orderId);
    }


}
