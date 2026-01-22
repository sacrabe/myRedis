package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {
    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.100.128:6379").setPassword("123456").setDatabase(1);
        return Redisson.create( config );
    }

    /*
    @Bean
    public RedissonClient redissonClient1() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.100.128:6380").setPassword("123456");
        return Redisson.create( config );
    }
    @Bean
    public RedissonClient redissonClient2() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.100.128:6381").setPassword("123456");
        return Redisson.create( config );
    }
*/
}
