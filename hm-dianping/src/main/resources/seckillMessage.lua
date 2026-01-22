-- 参数1 优惠券id
local voucherId = ARGV[1];

-- 参数2 用户id
local userId = ARGV[2];

-- param 3 订单id
local orderId = ARGV[3];
-- 数据key
-- 1.库存key
local stockKey = "seckill:stock:".. voucherId;
-- 2. 订单key
local orderKey = "seckill:order:".. voucherId;

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

-- 发送消息到队列  xadd stream.orders  * k1 v1 k2 v2     // *代表消息id 由redis 自动生成
redis.call("xadd", "stream.orders", "*", "userId", userId, "voucherId", voucherId, "id", orderId);


return 0;
