package com.hmdp.utils.distributedLock;

/**
 * @Author: xuyuchao
 * @Date: 2022-09-25-17:33
 * @Description: 分布式锁实现接口
 */
public interface ILock {
    /**
     * 尝试获取锁
     * @param timeoutSec 过期时间
     * @return
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unLock();
}
