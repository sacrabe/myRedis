package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
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
        Long userId = UserHolder.getUser().getId();

        /**
         * 单体模式锁
         */

        /*
        synchronized (userId.toString().intern()) {
            // 获取代理对象 调用方法才能使事务生效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
        */

        /**
         *
         * 分布式锁
         */
        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        boolean ifLock = lock.tryLock(1200);
        if(!ifLock){
            // 获取锁失败。返回错误或者重试
            return Result.fail("您已购买过");
        }
        // 获取代理对象 调用方法才能使事务生效
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unLock();
        }

    }

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
