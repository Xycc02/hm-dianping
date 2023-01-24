package com.hmdp;

import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIDWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private RedisIDWorker redisIDWorker;

    @Test
    public void test1() throws InterruptedException {
        shopService.saveShopToRedis(1L,60L);
    }


    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    public void test2() throws InterruptedException {
        //信号枪
        CountDownLatch latch = new CountDownLatch(300);
        //线程任务
        Runnable task = () -> {
            for(int i = 0;i < 100;i++) {
                long id = redisIDWorker.nextId("order");
                System.out.println("id=" + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for(int i = 0;i < 300;i++) {
            es.submit(task);
        }
        //阻塞主线程,等CountDownLatch内部维护的数值减为0放行
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("创建30000个key用时:" + (end - begin));
    }


}
