package com.hmdp;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;


import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Slf4j
@SpringBootTest
public class RedissonTest {
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RedissonClient redissonClient1;
    @Resource
    private RedissonClient redissonClient2;
    
    private RLock lock;
    @BeforeEach
    void setUp(){
        RLock lock1 = redissonClient.getLock("test:lock:order");
        RLock lock2 = redissonClient1.getLock("test:lock:order");
        RLock lock3 = redissonClient2.getLock("test:lock:order");
        //创建联锁
         lock = redissonClient.getMultiLock(lock1, lock2, lock3);
    }
    @Test
    public void method1() throws InterruptedException {
        boolean ifLock = lock.tryLock(1L, TimeUnit.SECONDS);
        if(!ifLock){
            log.error("获取锁失败");
            return;
        }

        try {
            log.info("获取锁成功");
            method2();
            log.info("开始执行业务。。。");
        } finally {
            log.info("准备释放锁");
            lock.unlock();
        }
    }

    void method2() throws InterruptedException {
        boolean ifLock = lock.tryLock(1L, TimeUnit.SECONDS);
        if(!ifLock){
            log.error("获取锁失败");
            return;
        }

        try {
            log.info("获取锁成功");
            log.info("开始业务");
        }finally {
            log.info("准备释放锁");
            lock.unlock();
        }
    }


}
