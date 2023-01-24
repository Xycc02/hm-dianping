package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {
    //抢购秒杀券
    Result seckillVoucher(Long voucherId);
    //创建订单事务方法
    Result createVoucherOrder(Long voucherId);

    //创建订单事务方法(异步)
    void asyncCreateVoucherOrder(VoucherOrder voucherOrder);
}
