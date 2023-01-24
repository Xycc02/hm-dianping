package com.hmdp.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Author: xuyuchao
 * @Date: 2022-09-14-23:02
 * @Description: 用于利用缓存的逻辑过期时间进行的缓存击穿优化
 */
@Data
public class RedisData {
    //逻辑过期时间
    private LocalDateTime expireTime;
    //存入的数据
    private Object data;
}
