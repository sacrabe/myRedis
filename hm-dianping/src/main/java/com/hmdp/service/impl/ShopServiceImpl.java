package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheUtil;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheUtil cacheUtil;
    // 线程池用于缓存重建
    public static final ExecutorService CACHE_REBUILD_EXCUTOR = Executors.newFixedThreadPool(10);
    @Override
    public Object queryById(Long id) {

        //Shop shop = getByIdWithoutLock(id);
        //Shop shop = getByIdWithMutexLock(id);
        //Shop shop = getByIdWithLogicExpire(id);
        //包装的工具类
//        Shop shop = cacheUtil.getByIdWithoutLock(RedisConstants.CACHE_SHOP_KEY,
//                id, Shop.class,
//                id2 -> this.getById(id2),
//                RedisConstants.CACHE_SHOP_TTL,
//                TimeUnit.MINUTES);
//
        Shop shop = cacheUtil.getByIdWithLogicExpire(
                RedisConstants.CACHE_SHOP_KEY,
                RedisConstants.LOCK_SHOP_KEY,
                id,
                Shop.class,
                id1 -> this.getById(id1),
                20L,
                TimeUnit.SECONDS);

        if(shop==null){
            return "店铺不存在";
        }
        return shop;
    }

    /**
     *
     *  todo 逻辑过期解决  热点数据 缓存击穿
     *
     */
    private Shop getByIdWithLogicExpire(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1. 获取缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 是否命中，未命中
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        // 只有 空字符串""和null两种类型

        // 3.命中获取数据
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        // 4. 未过期 返回  数据
        if(expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        // 5. 过期 尝试获取锁 缓存重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        // 6.1  获取锁
        boolean isLock = tryLock(lockKey);
        // 6.2 未获取锁 返回旧信息
        if(!isLock){
            return shop;
        }
        // 6.2 获取锁成功 开启独立线程 返回旧信息 释放锁
        CACHE_REBUILD_EXCUTOR.submit(() -> {
            //设置为20s为了观测缓存效果 实际应该设为30min
            try {
                this.saveShop2Redis(id,20L);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                unLock(lockKey);

            }
        });
        // 数据库中也没有 缓存中添加 ，避免缓存穿透

        return shop;
    }


    /**
     *
     * 互斥锁解决缓存击穿
     * @param id
     * @return
     */
    private Shop getByIdWithMutexLock(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 只有 空字符串""和null两种类型
        //  缓存命中 但是为空字符串 “”
        if(shopJson!=null){
            return null;
        }
        // 缓存未命中
        // 1.获取锁 判断
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;

        Shop shop = null;
        try {
            boolean ifLock = tryLock(lockKey);
            // 2.失败 休眠重试
            if (!ifLock){
                Thread.sleep(50);
                return getByIdWithMutexLock(id);
            }
            // 4. 若获取锁成功应该再次检测缓存是否存在（因为其他线程等待后，可能redis已经更新）
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(shopJson)){
                shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }
            // 4.成功 根据id查询数据库
            shop = this.getById(id);
            // 模拟延迟
            Thread.sleep(200);
            if(shop==null){
                // 数据库中也没有 缓存中添加 ，避免缓存穿透
                stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            // 5. 存在 写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lockKey);

        }
        return shop;
    }
    private Shop getByIdWithoutLock(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 只有 空字符串""和null两种类型
        //  缓存命中 但是为空字符串 “”
        if(shopJson!=null){
            return null;
        }
        // 缓存未命中
        Shop shop = this.getById(id);
        if(shop!=null){
            // 写入缓存
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return shop;
        }
        // 数据库中也没有 缓存中添加 ，避免缓存穿透
        stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
        return null;
    }
    // 缓存预热
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        Shop shop = this.getById(id);
        Thread.sleep(200);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));


        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));

    }
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        this.updateById(shop);
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);

        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }
}
