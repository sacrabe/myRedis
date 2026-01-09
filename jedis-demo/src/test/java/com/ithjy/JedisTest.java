package com.ithjy;

import com.ithjy.jedis.util.JedisConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.util.HashMap;
import java.util.Map;

public class JedisTest {
    private Jedis jedis ;

    @BeforeEach
    public void init(){
        //jedis = new Jedis("192.168.100.128",6379);
        jedis = JedisConnectionFactory.getJedis();
        jedis.auth("123456");
        jedis.select(0);

    }

    @Test
    public void test1(){
        jedis.set("name6","jyh");
        System.out.println(jedis.get("name6"));
    }
    @Test
    public void Hashtest2(){

        Map<String,String> hm = new HashMap<>();
        hm.put("name","yom");
        hm.put("age","18");
        //jedis.hset("ithjy:user:5","name","jyh");
        jedis.hmset("ithjy:user:5",hm);
        System.out.println(jedis.hget("ithjy:user:5","name"));
        Map<String, String> stringStringMap = jedis.hgetAll("ithjy:user:4");
        System.out.println(stringStringMap);
    }

    @AfterEach
    public void destory(){
        if(jedis!=null)
           jedis.close();
    }
}
