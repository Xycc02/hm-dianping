package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIDWorker redisIDWorker;
    @Resource
    private RedissonClient redissonClient;

    /**
     * 静态代码块初始化判断秒杀资格的Lua脚本
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    //初始化
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //阻塞队列(用于将用户抢购的秒杀券订单存入其中,后续异步创建订单)
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    //线程池(用于将阻塞队列中的订单信息创建订单)
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //该方法在该类初始化完成后立即运行(不断从阻塞队列中去获取订单信息,并创建订单)
    @PostConstruct
    private void init() {
        log.info("开始提交阻塞队列中的订单信息任务...");
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 线程任务:
     * v1.0 用于异步处理阻塞队列中的订单
     * v2.0 用于异步处理Stream消息队列中的订单消息
     */
    private class VoucherOrderHandler implements Runnable {

        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                //v1.0
                /**
                 * try {
                 *     //1.获取阻塞队列中的订单信息
                 *     VoucherOrder voucherOrder = orderTasks.take();
                 *     //2.保存订单
                 *     handlerVoucherOrder(voucherOrder);
                 * } catch (Exception e) {
                 *     log.error("订单处理异常{}",e);
                 * }
                 */
                try {
                    //1.获取消息队列中的订单信息 xreadgroup group g1 c1 count 1 block 2000 streams stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息是否获取成功
                    //2.1 若获取失败,则说明没有消息,则继续下一次循环获取
                    if(list == null || list.isEmpty()) {
                        continue;
                    }
                    //3.解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> recordValue = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(recordValue, new VoucherOrder(), true);
                    //4.消息获取成功,可以下单
                    handlerVoucherOrder(voucherOrder);
                    //5.ACK确认(防止消息被同一消费者组中的消费者重复消费) SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    //若消息处理异常,则消息会进入pending list
                    handlerPendingList();
                }

            }
        }

        /**
         * 处理pending list中未处理成功的消息
         */
        private void handlerPendingList() {
            while (true) {
                try {
                    //1.获取pending-list中的订单信息 xreadgroup group g1 c1 count 1 streams stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2.判断消息是否获取成功
                    //2.1 若获取失败,则说明pending-list没有消息,结束循环
                    if(list == null || list.isEmpty()) {
                        break;
                    }
                    //3.解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> recordValue = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(recordValue, new VoucherOrder(), true);
                    //4.消息获取成功,可以下单
                    handlerVoucherOrder(voucherOrder);
                    //5.ACK确认(防止消息被同一消费者组中的消费者重复消费) SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list中订单异常",e);
                }

            }
        }
    }


    //该类的代理对象,通过主线程赋值(子线程ThreadLocal)
    private IVoucherOrderService proxy;

    /**
     * 注意:该方法不在主线程中运行,是在子线程中运行,ThreadLocal失效
     * @param voucherOrder
     */
    private void handlerVoucherOrder(VoucherOrder voucherOrder) {
        proxy.asyncCreateVoucherOrder(voucherOrder);
    }

    /**
     * 抢购秒杀券
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // return syncSeckillVoucher(voucherId);
        return asyncSeckillVoucher(voucherId);
    }

    /**
     * 异步秒杀
     * v1.0 使用JDK阻塞队列
     * v2.0 使用Stream消息队列
     * @param voucherId
     * @return
     */
    private Result asyncSeckillVoucher(Long voucherId) {
        //获取用户ID
        Long userId = UserHolder.getUser().getId();
        //生成订单ID
        Long orderId = redisIDWorker.nextId("order");
        //1.执行lua脚本,判断是否有秒杀资格(有秒杀资格并将订单信息发送到消息队列中)
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), orderId.toString()
        );
        //2.判断结果是否为0
        int r = result.intValue();
        if(r != 0) {
            //2.1若结果不为0,则没有资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //3.获取代理对象(用于处理事务)
        proxy = (IVoucherOrderService) AopContext.currentProxy();


        //v1.0 保存到JDK阻塞队列
        /**
         *         //2.2若结果为0,则将下单信息保存到阻塞队列,实现异步秒杀
         *         //2.3封装订单信息
         *         VoucherOrder voucherOrder = new VoucherOrder();
         *         voucherOrder.setId(orderId);
         *         voucherOrder.setUserId(userId);
         *         voucherOrder.setVoucherId(voucherId);
         *
         *         //3.获取代理对象(用于处理事务)
         *         proxy = (IVoucherOrderService) AopContext.currentProxy();
         *
         *         //4. 将下单信息保存到阻塞队列,实现异步下单
         *         orderTasks.add(voucherOrder);
         */

        //5. 返回订单ID
        return Result.ok(orderId);
    }

    /**
     * 同步秒杀
     * @param voucherId
     * @return
     */
    private Result syncSeckillVoucher(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if(LocalDateTime.now().isBefore(seckillVoucher.getBeginTime())) {
            return Result.fail("秒杀还未开始~");
        }
        //3.判断秒杀是否结束
        if(LocalDateTime.now().isAfter(seckillVoucher.getEndTime())) {
            return Result.fail("很抱歉,您错过了秒杀时间~");
        }
        //4.判断库存是否充足
        if(seckillVoucher.getStock() < 1) {
            return Result.fail("手慢无~");
        }
        Long userId = UserHolder.getUser().getId();

        // ***先将事务提交再释放锁***
        //1.创建订单,悲观锁,建议不要在方法上加锁,因为不同用户查询是否抢购过秒杀券可以同时查询,一人一单加悲观锁只是防止同一用户多请求访问造成并发安全问题
        //2.此处用用户id作为锁对象,锁细化,否则锁this影响性能并且不同用户只需锁住自己就行,防止同一用户多请求访问造成并发安全问题(一人多买)
        //3.intern()入池,并返回在串池值中一样String对象,否则同一用户进入请求都是不同的对象
        //4.事务是由Spring管理的,先释放锁再提交事务,因此不能将synchronized锁住createVoucherOrder方法中的代码块
        //5.事务是由Spring管理的,管理了this的代理类(Cglib),本类即IVoucherOrderService接口,故用this调用事务方法会使事务失效
        //(注意,synchronized只能保证单体架构线程安全,集群模式下不能保证,只能用分布式锁)
        /**
         *         synchronized (userId.toString().intern()) {
         *             //@EnableAspectJAutoProxy(exposeProxy = true)//暴露代理对象 获取跟创建订单事务的代理对象
         *             IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
         *             return proxy.createVoucherOrder(voucherId);
         *         }
         */

        //基于Redis的分布式锁
        // SimpleRedisLock redisLock = new SimpleRedisLock(stringRedisTemplate, "voucherOrder:" + userId);
        //基于Redisson的分布式锁(推荐)
        RLock lock = redissonClient.getLock("voucherOrder:" + userId);
        boolean isLock = lock.tryLock();
        if(!isLock) {
            return Result.fail("不允许重复下单!");
        }
        try {
            //@EnableAspectJAutoProxy(exposeProxy = true)//暴露代理对象 获取跟创建订单事务的代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 创建订单,悲观锁,建议不要在方法上加锁,因为不同用户查询是否抢购过秒杀券可以同时查询,一人一单加悲观锁只是防止同一用户多请求访问造成并发安全问题
     * @param voucherId
     * @return
     */
    @Transactional
    @Override
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //一人一单,判断当前用户是否抢购过此秒杀券
        int count = this.query().eq("user_id", UserHolder.getUser().getId())
                .eq("voucher_id", voucherId)
                .count();
        if(count > 0) {
            //该用户已经抢购过此秒杀券
            return Result.fail("您已抢购过该秒杀券!");
        }
        //5.扣减库存
        //在Java层面上,乐观锁,扣减库存,并用stock > 0作为条件避免超卖
        //在MySQL层面上,是利用事务以及MySQL的行锁来保证线程安全的
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherId)
                .gt("stock",0)//where stock > 0 解决超卖问题
                .update();

        if(!success) {
            return Result.fail("手慢无~");
        }
        //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIDWorker.nextId("voucher"));
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);
        this.save(voucherOrder);
        //7.返回订单id
        return Result.ok(voucherOrder.getId());
    }


    /**
     * 异步创建订单
     * @param voucherOrder
     */
    @Transactional
    @Override
    public void asyncCreateVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单,判断当前用户是否抢购过此秒杀券
        int count = this.query().eq("user_id", voucherOrder.getUserId())
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();
        if(count > 0) {
            //该用户已经抢购过此秒杀券
            log.error("用户{}已经抢购过该秒杀券",voucherOrder.getUserId());
            return;
        }
        //5.扣减库存
        //在Java层面上,乐观锁,扣减库存,并用stock > 0作为条件避免超卖
        //在MySQL层面上,是利用事务以及MySQL的行锁来保证线程安全的
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock",0)//where stock > 0 解决超卖问题
                .update();

        if(!success) {
            log.error("用户{}手慢无",voucherOrder.getUserId());
        }

        //6.创建订单
        this.save(voucherOrder);
    }
}
