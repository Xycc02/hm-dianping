package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @Author: xuyuchao
 * @Date: 2022-09-20-18:14
 * @Description:
 */
@Component
public class RedisIDWorker {
    /**
     * 开始时间戳  2022/1/1开始时间
     */
    private static final long BEGIN_TIMESTAMP = 1640995200l;
    /**
     * 序列号位数
     */
    private static final int COUNT_BIT = 32;

    private StringRedisTemplate stringRedisTemplate;

    /**
     * 构造函数注入
     * @param stringRedisTemplate
     */
    public RedisIDWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String prefixKey) {
        /**
         * 符号位(1位) + 时间戳(31位) + 序列号(32位)
         */
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        //2.生成序列号
        //2.1 获取当前日期,精确到天,用冒号分隔在Redis中会分开储存,便于统计
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2 自增长(后面增加日期的原因是可能随着业务的扩大,key的数量会不断增多,因此加上每天的日期)
        long count = stringRedisTemplate.opsForValue().increment("icr:" + prefixKey + ":" + date);
        return timestamp << 32 | count;
    }


    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2022,1,1,0,0,0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println("second=" + second);
    }
}
