package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 获取首页店铺类型数据
     * @return
     */
    @Override
    public List<ShopType> queryShopTypeList() {
        //1.查询Redis
        List<String> shopTypeList = stringRedisTemplate.opsForList().range(RedisConstants.CACHE_SHOP_TYPE_KEY, 0, -1);
        //2.Redis存在直接返回数据
        if(shopTypeList.size() > 0) {
            List<ShopType> shopTypes = shopTypeList.stream().map(type -> {
                ShopType shopType = JSONUtil.toBean(type, ShopType.class);
                return shopType;
            }).collect(Collectors.toList());
            return shopTypes;
        }
        //3.Redis中不存在,查询数据库
        List<ShopType> shopTypes = this.list(
                new QueryWrapper<ShopType>().orderByAsc("sort")
        );
        //4.数据库中不存在,返回错误信息
        if(shopTypes.isEmpty()) {
            return null;
        }
        //5.数据库中存在,将数据存入redis中并返回
        shopTypes.forEach(item -> {
            stringRedisTemplate.opsForList().rightPush(RedisConstants.CACHE_SHOP_TYPE_KEY,JSONUtil.toJsonStr(item));
        });
        //6.设置过期时间
        stringRedisTemplate.expire(RedisConstants.CACHE_SHOP_TYPE_KEY, RedisConstants.CACHE_SHOP_TYPE_TTL,TimeUnit.SECONDS);
        return shopTypes;
    }
}
