package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongFunction;

/**
 * @Author: xuyuchao
 * @Date: 2022-09-15-15:36
 * @Description:
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将任意对象转为json格式存入Redis,并设置TTL
     * @param key
     * @param value
     * @param time
     * @param timeUnit
     */
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,timeUnit);
    }

    /**
     * 将任意对象转为json格式,并设置逻辑过期时间
     * @param key
     * @param value
     * @param time
     * @param timeUnit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        //将redisData转为json存入Redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透 ：缓存穿透是指客户端请求的数据在缓存中和数据库中都不存在，这样缓存永远不会生效，这些请求都会打到数据库。
     * 解决缓存穿透
     * @param keyPrefix key前缀
     * @param id 查询对象的id
     * @param type 查询对象的类型class
     * @param <R>  查询对象的类型
     * @param <ID> 查询对象id的类型
     * @param dbFallBack 根据id查询数据的具体函数
     * @param time ttl
     * @param timeUnit ttl的单位
     * @return
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallBack,Long time,TimeUnit timeUnit) {
        String key = keyPrefix + id;
        //查询Redis
        String json = stringRedisTemplate.opsForValue().get(key);
        //若缓存命中,且不为空值,返回反序列化的对象
        if(StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json,type);
        }
        //若缓存命中的是空字符串,直接返回错误信息(说明命中了解决缓存穿透的无效key)
        if("".equals(json)) {
            return null;
        }

        //若缓存未命中
        //1.根据id查询数据库
        R r = dbFallBack.apply(id);
        //2.若数据库中不存在
        if(r == null) {
            //将空值写入Redis,防止后续同样无效请求缓存击穿
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //3.若数据库中存在,则将数据写入Redis
        this.set(key,r,time, timeUnit);
        //返回数据库中的数据
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 缓存击穿解决
     * 缓存击穿问题也叫热点Key问题，就是一个被高并发访问并且缓存重建业务较复杂的key突然失效了，无数的请求访问会在瞬间给数据库带来巨大的冲击。
     * @param keyPrefix key前缀
     * @param lockPrefix 互斥锁前缀
     * @param id 查询对象的id
     * @param type 查询对象的类型class
     * @param time 缓存重建key的过期时间
     * @param timeUnit 缓存重建key的过期时间单位
     * @param dbFallBack 根据id查询数据的具体函数
     * @param <R> 查询对象的类型
     * @param <ID> 查询对象id的类型
     * @return
     */
    public <R,ID> R cacheBreak(String keyPrefix, String lockPrefix, ID id, Class<R> type, Long time, TimeUnit timeUnit,Function<ID,R> dbFallBack) {
        String key = keyPrefix + id;
        //1.查询Redis
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.若缓存未命中,则直接返回空信息(说明该热点数据未提前预热,避免大量请求打到数据库)
        if(StrUtil.isBlank(json)) {
            return null;
        }
        //3.若缓存命中,则将json反序列化为对应对象(得到对应对象数据和逻辑过期时间)
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4.判断缓存数据是否过期
        //4.1若缓存时间未过期,则直接返回缓存数据
        if(expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        //4.2若缓存时间过期,则开启新的线程进行缓存重建
        //5.获取互斥锁
        String lockKey = lockPrefix + id;
        boolean isLock = tryLock(lockKey);
        if(isLock) {
            //5.1 获取锁成功,开启新的线程进行缓存重建
            //双重检查,DoubleCheck,再次判断当前缓存数据是否过期,因为你成功获取互斥锁有一种可能是有一个线程刚释放完互斥锁，
            //也就证明此时缓存中的数据是新鲜热乎的，此时不需要再去重建缓存，刚刚有线程才重建完(说白了,就是有其他线程也进入了缓存逻辑时间过期代码)
            redisData = JSONUtil.toBean(json, RedisData.class);
            r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
            expireTime = redisData.getExpireTime();
            if(expireTime.isAfter(LocalDateTime.now())) {
                return r;
            }

            //提交缓存重建任务给线程池
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //根据id查询数据库
                    R rebuildR = dbFallBack.apply(id);
                    this.setWithLogicalExpire(key,rebuildR,time,timeUnit);
                }catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unLock(lockKey);
                }
            });
        }
        //5.2 获取锁失败,说明有其他线程获取到了锁正在缓存重建

        //6 本次直接返回旧数据
        return r;
    }

    /**
     * 利用setnx尝试获取互斥锁
     * @param key
     * @return
     */
    public boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key
     */
    public void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}


























