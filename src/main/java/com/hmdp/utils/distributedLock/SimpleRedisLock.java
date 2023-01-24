package com.hmdp.utils.distributedLock;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @Author: xuyuchao
 * @Date: 2022-09-25-17:39
 * @Description: 基于Redis的简单分布式锁的实现
 */
public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;
    private String name;//key的名称,业务名称
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    //释放锁Lua脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    //初始化
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unLock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    /**
     * 尝试获取锁,利用setnx实现
     * @param timeoutSec 过期时间
     * @return
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        //设置当前线程标识,跨虚拟机
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 释放锁
     * 此处释放锁若判断线程标识和删除锁不保证原子性,极端情况下还是存在线程安全问题.因为当线程1若刚判断完锁标识一致,还未来得及释放锁就发生了阻塞(例如STW),而此时
     * 锁因为过期时间自动过期,此时线程2获取锁成功,在正常执行业务的同时,线程1恢复运行,执行释放锁的动作,则将线程2的锁给释放掉了,此时线程3一来,并发安全问题就产生了
     */
    @Override
    public void unLock() {
        //获取当前想要释放锁的线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        /**
         *         //获取Redis中的锁的标识
         *         String redisId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
         *         //若标识一致,则释放锁
         *         if(threadId.equals(redisId)) {
         *             stringRedisTemplate.delete(KEY_PREFIX + name);
         *         }
         */

        //利用Lua脚本执行Redis命令,保证判断标识以及释放锁操作为原子操作  KEYS[1] : 锁的key  ARGV[1] : 当前线程的标识
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX + name), threadId);
    }
}
