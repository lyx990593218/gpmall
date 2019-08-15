package com.gpmall.order.scheduletask;

import com.gpmall.order.OrderCoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@EnableScheduling   // 1.开启定时任务
@EnableAsync        // 2.开启多线程
public class MultithreadScheduleTask {

    @Autowired
    OrderCoreService orderCoreService;

    /**
    * @Description: first 描述-定时任务每分钟执行
    * @Author: Douglas Lai(990593218)
    * @Date: 2019/08/15
    */
    @Async
    //TODO 需要改造成nacos配置中心取值
    @Scheduled(cron = "0 */1 * * * ?")  //间隔一分钟
    public void first() {
        log.info("第一个定时任务开始 : " + LocalDateTime.now().toLocalTime() + "\r\n线程 : " + Thread.currentThread().getName());
        orderCoreService.closeOrder();
        log.info("第一个定时任务结束");
    }

}