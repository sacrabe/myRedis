package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements RLock {
    private StringRedisTemplate stringRedisTemplate;
    private String key;
    public static final String LOCK_KEY_PREFIX = "lock:";
    public static final String UUID_PREFIX = UUID.randomUUID().toString() + "-";

    // 释放锁的脚本  脚本初始化放在静态代码块中 ， 避免 重复加载 导致io流的 阻塞压力
    public static final DefaultRedisScript<Long> UNLOCK_SCRIPT ;

    static{
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String key) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.key = key;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = UUID_PREFIX + Thread.currentThread().getId();
        Boolean succeaa = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY_PREFIX + key, threadId, timeoutSec, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(succeaa);
    }

    /**
     *
     * lua 脚本实现  判断和删除的原子性
     *  由原来的两行代码  变为 一行代码   确保原子性
     *
     */

    @Override
    public void unLock() {
        // 获取线程标识
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(LOCK_KEY_PREFIX + key),
                UUID_PREFIX+Thread.currentThread().getId());
    }
    /*
    @Override
    public void unLock() {
        // 获取线程标识
        String threadId = UUID_PREFIX + Thread.currentThread().getId();
        // 获取锁中的线程标识
        String lockThreadId = stringRedisTemplate.opsForValue().get(LOCK_KEY_PREFIX + key);
        if (lockThreadId.equals(threadId)) {
            stringRedisTemplate.delete(LOCK_KEY_PREFIX + key);
        }
    }
    */
}
