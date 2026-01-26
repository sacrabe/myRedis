package com.hmdp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
public class HypyerLoglogTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
     void hllTest(){
        String[] value = new String[1000];

        for (int i = 0; i < 1000000; i++) {
            int j = i % 1000;

            value[j]= "user_"+i;
            if(j==999){
                stringRedisTemplate.opsForHyperLogLog().add("hll",value);
            }

        }
        Long hll = stringRedisTemplate.opsForHyperLogLog().size("hll");
        System.out.println(hll);
    }
}
