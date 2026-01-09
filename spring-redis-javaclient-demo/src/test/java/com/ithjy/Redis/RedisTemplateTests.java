package com.ithjy;

import com.ithjy.pojo.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

@SpringBootTest
class RedisTemplateTests {
    @Autowired
    private RedisTemplate redisTemplate;
    @Test
    void contextLoads() {
        redisTemplate.opsForValue().set("name1","dasda");
        redisTemplate.opsForValue().get("name1");
        System.out.println(redisTemplate.opsForValue().get("name1"));
    }

    @Test
    void objectTest1(){

        redisTemplate.opsForValue().set("user:1",new User("tom",18));
        redisTemplate.opsForValue().set("user:2",new User("bob",33));
        User o = (User) redisTemplate.opsForValue().get("user:1");
        System.out.println(o);
        System.out.println();
    }
    @Test
    void objectTest2(){
        System.out.println("hello");
    }
}
