package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    /**
     * 根据商铺id查询商铺信息
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        //基于互斥锁实现的缓存击穿优化
        // return cacheBreak1(id);
        //基于逻辑过期时间实现的缓存击穿优化
        // return cacheBreak2(id);


        //缓存穿透,cacheClient工具类
        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //缓存击穿,cacheClient工具类
        // Shop shop = cacheClient.cacheBreak(RedisConstants.CACHE_SHOP_KEY, RedisConstants.LOCK_SHOP_KEY, id, Shop.class, 60L, TimeUnit.SECONDS, this::getById);



        if(shop == null) {
            return Result.fail("店铺信息不存在!");
        }
        return Result.ok(shop);
    }

    /**
     * 1.基于互斥锁实现的缓存击穿优化
     * @param id
     * @return
     */
    public Result cacheBreak1(Long id) {
        //1.查询redis
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //2.redis存在数据,直接返回
        if(StrUtil.isNotBlank(shopJson)) {
            //将json序列化为shop对象并返回结果
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //若命中redis中的空数据则返回错误信息
        if("".equals(shopJson)) {
            return Result.fail("店铺信息不存在!");
        }
        Shop shop = null;
        try {
            //3.redis中不存在,查询数据库   获取互斥锁,防止在查询数据库期间其他线程也进入,导致数据库压力过大
            boolean isLock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
            //缓存重建
            if(!isLock) {
                //未获取到互斥锁,表示有其他线程获取到了互斥锁,休眠一段时间后再次查询Redis
                Thread.sleep(50);
                return queryById(id);
            }
            shop = this.getById(id);
            //模拟缓存重建延时
            Thread.sleep(500);
            //4.数据库中不存在,将空信息写入redis中,并设置超时时间,防止缓存穿透,再返回错误信息
            if(shop == null) {
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return Result.fail("商铺信息不存在!");
            }
            //5.数据库中存在,存入redis中,设置过期时间,并返回数据
            shopJson = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,shopJson,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放互斥锁
            unLock(RedisConstants.LOCK_SHOP_KEY + id);
        }
        return Result.ok(shop);
    }


    //线程池,用户执行缓存重建任务
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 2.基于逻辑过期时间实现的缓存击穿优化(需提前预热缓存数据)
     * @param id
     * @return
     */
    public Result cacheBreak2(Long id) {
        //1.查询redis
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //2.缓存未命中,直接返回空信息(未提前预热)
        if(StrUtil.isBlank(shopJson)) {
            return Result.fail("暂无数据");
        }
        //3.若缓存命中,则把当前缓存json数据序列化为对应对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4判断缓存数据是否过期
        //4.1 若逻辑时间未过期,直接返回店铺数据
        if(expireTime.isAfter(LocalDateTime.now())) {
            return Result.ok(shop);
        }
        //4.2 逻辑时间过期,尝试获取互斥锁
        //5 获取互斥锁
        boolean isLock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
        if(isLock) {
            //5.1 获取锁成功,开启新的线程进行缓存重建
            //此处注意,DoubleCheck,因为你成功获取互斥锁有一种可能是有一个线程刚释放完互斥锁，
            // 也就证明此时缓存中的数据是新鲜热乎的，此时不需要再去重建缓存，刚刚有线程才重建完(说白了,就是有其他线程也进入了缓存逻辑时间过期代码)
            redisData = JSONUtil.toBean(shopJson, RedisData.class);
            shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
            expireTime = redisData.getExpireTime();
            if(expireTime.isAfter(LocalDateTime.now())) {
                return Result.ok(shop);
            }

            //提交缓存重建任务
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShopToRedis(id,20L);
                }catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unLock(RedisConstants.LOCK_SHOP_KEY + id);
                }
            });
        }
        //5.2 获取锁失败,说明有其他线程正在缓存重建,最后返回旧数据即可
        //6 返回旧数据
        return Result.ok(shop);
    }

    /**
     * 保存shop信息到Redis中,提前缓存热点数据(逻辑时间方式)
     * @param id
     * @param expireSeconds
     */
    public void saveShopToRedis(Long id,Long expireSeconds) throws InterruptedException {
        Shop shop = this.getById(id);
        RedisData redisData = new RedisData();
        //模拟缓存重建延迟
        Thread.sleep(200);
        //设置逻辑过期时间和店铺数据
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        redisData.setData(shop);
        //将数据存入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
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

    /**
     * 更新商铺信息
     * @param shop
     */
    @Override
    @Transactional
    public Result updateShopById(Shop shop) {
        if(shop.getId() == null) {
            return Result.fail("商铺编号不能为空!");
        }
        //1.先更新数据库
        this.updateById(shop);
        //2.再删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
