package com.ithjy.Redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ithjy.pojo.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
public class StringRedisTemplateTest {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Test
    void contextLoads() {
        stringRedisTemplate.opsForValue().set("name1","avc");
        stringRedisTemplate.opsForValue().get("name1");
        System.out.println(stringRedisTemplate.opsForValue().get("name1"));
    }
    public static final ObjectMapper mapper = new ObjectMapper();

    @Test
    void valueTest() throws JsonProcessingException {
        User tom = new User("tommm", 18);
        User bob = new User("bobbb", 33);
        String tomStr = mapper.writeValueAsString(tom);
        String bobStr = mapper.writeValueAsString(bob);
        stringRedisTemplate.opsForValue().set("user:1",tomStr);
        stringRedisTemplate.opsForValue().set("user:2",bobStr);
        String jsonTom = stringRedisTemplate.opsForValue().get("user:1");
        User o = mapper.readValue(jsonTom, User.class);
        System.out.println(o);
        System.out.println();
    }
    @Test
    void HashTest(){
        stringRedisTemplate.opsForHash().put("user:3","name","tom");
        stringRedisTemplate.opsForHash().put("user:3","age","18");
        String name = (String) stringRedisTemplate.opsForHash().get("user:3", "name");
        System.out.println(name);
    }
}
