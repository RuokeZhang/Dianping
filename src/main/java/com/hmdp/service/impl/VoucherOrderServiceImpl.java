package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IVoucherOrderService proxy;  // 直接注入代理


    @Resource
    private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks=new LinkedBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();


    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while(true){
                try {
                    //获取队列中的订单信息
                    VoucherOrder voucherOrder=orderTasks.take();
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId=voucherOrder.getUserId();
        //这里加个锁。预防多 JVM 情况下，来自同一用户的同一时间的多个请求，分别占用了不同 JVM 的 synchronized 锁。
        //SimpleRedisLock lock=new SimpleRedisLock("order:"+userId, stringRedisTemplate);
        RLock lock=redissonClient.getLock("lock:order:"+userId);
        boolean isLock=lock.tryLock();
        if(!isLock){
            return;
        }
        try{
            proxy.createVoucherOrder(voucherOrder);
        }finally{
            lock.unlock();
        }
    }
    @Override
    public Result seckillVoucher(Long voucherId) {
       //1. lua script
        Long userId=UserHolder.getUser().getId();
        Long result=stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());
        //2. check if 拥有购买资格
        int r=result.intValue();
        //2.1 没有资格
        if(r!=0){
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        //2.2 有资格购买，把下单信息保存到阻塞队列中
        long orderId=redisIdWorker.nextId("order");
        VoucherOrder voucherOrder=new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);

        boolean added = orderTasks.offer(voucherOrder);
        log.info("Attempted to add order to queue. Success: {}, Queue size: {}", added, orderTasks.size());

        //2. 返回订单 id
        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId=voucherOrder.getUserId();
        int count=query().eq("user_id",userId).eq("voucher_id", voucherOrder.getVoucherId()).count();

        if(count>0){
            return;
        }
        //扣减库存
        boolean success= seckillVoucherService.update().setSql("stock=stock-1")
                .eq("voucher_id",voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if(!success){
            log.info("库存不足");
            return;
        }
        save(voucherOrder);
    }
    //    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //查询优惠券
//        SeckillVoucher voucher=seckillVoucherService.getById(voucherId);
//        //判断秒杀是否开始/结束
//        if(voucher.getBeginTime().isAfter(LocalDateTime.now()) || voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("不在秒杀活动时间范围内！");
//        }
//        //判断库存是否充足
//        if(voucher.getStock()<1){
//            return Result.fail("库存不足");
//        }
//        Long userId=UserHolder.getUser().getId();
//        //这里加个锁。预防多 JVM 情况下，来自同一用户的同一时间的多个请求，分别占用了不同 JVM 的 synchronized 锁。
//        //SimpleRedisLock lock=new SimpleRedisLock("order:"+userId, stringRedisTemplate);
//        RLock lock=redissonClient.getLock("lock:order:"+userId);
//        boolean isLock=lock.tryLock();
//        if(!isLock){
//            return Result.fail("不允许重复下单");
//        }
//        try{
//            IVoucherOrderService proxy=(IVoucherOrderService) AopContext.currentProxy();
//            return  proxy.createVoucherOrder(voucherId);
//        }finally{
//            lock.unlock();
//        }
//    }
}
