package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryList() {
        String key = "cache:shop:list";
        List<String> range = stringRedisTemplate.opsForList().range(key, 0, -1);
        /*
        List<ShopType> shopTypes = new ArrayList<>();
        if(range!=null&&range.size()>0){
            range.forEach(s->{
                ShopType shopType = JSONUtil.toBean(s, ShopType.class);
                shopTypes.add(shopType);
            });
            return Result.ok(shopTypes);
        }
        */
        //
        if(range!=null&&range.size()>0){
            List<ShopType> shopTypes = range.stream().map(s -> JSONUtil.toBean(s, ShopType.class)).collect(Collectors.toList());

            return Result.ok(shopTypes);
        }


        List<ShopType> shopTypeList = this.query().orderByDesc("sort").list();
        if(shopTypeList==null){
            return Result.fail("查询失败");
        }
        List<String> shopTypeJsons = shopTypeList
                .stream().
                map(shopType -> JSONUtil.toJsonStr(shopType))
                .collect(Collectors.toList());
        stringRedisTemplate.opsForList().leftPushAll(key, shopTypeJsons);
        return Result.ok(shopTypeList);


    }
}
