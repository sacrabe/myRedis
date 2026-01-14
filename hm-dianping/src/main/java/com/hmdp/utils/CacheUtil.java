package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
public   class CacheUtil {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    public static final ExecutorService CACHE_REBUILD_EXCUTOR = Executors.newFixedThreadPool(10);
    public void set(String key, Object value, Long  time, TimeUnit unit){

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr( value),time,unit);
    }

    public  void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds( time)) );
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr( redisData));

    }

    public  <R,ID>R getByIdWithoutLock(String keyPrefix, ID id , Class<R> type , Function<ID,R> dbSelector, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)){

            return JSONUtil.toBean(json, type);
        }
        // 只有 空字符串""和null两种类型
        //  缓存命中 但是为空字符串 “”
        if(json!=null){
            return null;
        }
        // 缓存未命中
        R r = dbSelector.apply(id);
        if(r!=null){
            // 写入缓存
            this.set(key,r,time,unit);
            return r;
        }
        // 数据库中也没有 缓存中添加 ，避免缓存穿透
        stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
        return null;
    }

    public  <R,ID> R getByIdWithLogicExpire(
            String keyPrefix,
            String lockKeyPrefix,
            ID id,
            Class<R> type,
            Function<ID,R> dbSelector,
            Long time,
            TimeUnit unit){
        String key = keyPrefix + id;
        // 1. 获取缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 是否命中，未命中
        if(StrUtil.isBlank(json)){
            return null;
        }
        // 只有 空字符串""和null两种类型

        // 3.命中获取数据
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        // 4. 未过期 返回  数据
        if(expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        // 5. 过期 尝试获取锁 缓存重建
        String lockKey = lockKeyPrefix + id;
        // 6.1  获取锁
        boolean isLock = tryLock(lockKey);
        // 6.2 未获取锁 返回旧信息
        if(!isLock){
            return r;
        }
        // 6.2 获取锁成功 开启独立线程 返回旧信息 释放锁
        CACHE_REBUILD_EXCUTOR.submit(() -> {
            //设置为20s为了观测缓存效果 实际应该设为30min
            try {
                // 查询数据库
                R rdb = dbSelector.apply(id);
                // 写入redis
                this.setWithLogicalExpire(key,rdb,time,unit);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                unLock(lockKey);

            }
        });
        // 数据库中也没有 缓存中添加 ，避免缓存穿透

        return r;
    }
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }


}
