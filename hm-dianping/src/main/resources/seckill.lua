-- 参数1 优惠券id
local orderId = ARGV[1];

-- 参数2 用户id
local userId = ARGV[2];

-- 数据key
-- 1.库存key
local stockKey = "seckill:stock:".. orderId;
-- 2. 订单key
local orderKey = "seckill:order:".. orderId;

-- 判断库存是否充足
if(tonumber(redis.call("get", stockKey))<=0) then
    return 1;
end

-- 判断用户是否重复抢购
if(redis.call("sismember", orderKey, userId)==1) then
    return 2;
end
-- 扣减库存
redis.call("incrby", stockKey, -1);
-- 添加订单
redis.call("sadd", orderKey, userId);
return 0;
