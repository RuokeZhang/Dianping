package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    private static final long BEGIN_TIMESTAMP=1640995200L;
    private static final long COUNT_BITS=32L;
    private StringRedisTemplate stringRedisTemplate;
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    //符号位0+时间戳31bit+序列号32bit
    public long nextId(String keyPrefix){
        //生成时间戳
        LocalDateTime now=LocalDateTime.now();
        long nowSecond=now.toEpochSecond(ZoneOffset.UTC);
        long timestamp=nowSecond-BEGIN_TIMESTAMP;
        //生成序列号. 每一天下的单使用同一个Key
        String date=now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count=  stringRedisTemplate.opsForValue().increment("icr:"+keyPrefix+":"+date);
        //拼接并返回
        return timestamp<<COUNT_BITS|count;
    }
}
