package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //查询优惠券
        SeckillVoucher voucher=seckillVoucherService.getById(voucherId);
        //判断秒杀是否开始/结束
        if(voucher.getBeginTime().isAfter(LocalDateTime.now()) || voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("不在秒杀活动时间范围内！");
        }
        //判断库存是否充足
        if(voucher.getStock()<1){
            return Result.fail("库存不足");
        }
        Long userId=UserHolder.getUser().getId();
        //这里加个锁。预防多 JVM 情况下，来自同一用户的同一时间的多个请求，分别占用了不同 JVM 的 synchronized 锁。
        SimpleRedisLock lock=new SimpleRedisLock("order:"+userId, stringRedisTemplate);
        boolean isLock=lock.tryLock(1200);
        if(!isLock){
            return Result.fail("不允许重复下单");
        }
        try{
            IVoucherOrderService proxy=(IVoucherOrderService) AopContext.currentProxy();
            return  proxy.createVoucherOrder(voucherId);
        }finally{
            lock.unlock();
        }

    }
    @Transactional
    public Result createVoucherOrder(Long voucherId){
        Long userId=UserHolder.getUser().getId();
        int count=query().eq("user_id",userId).eq("voucher_id", voucherId).count();

        if(count>0){
            return Result.fail("用户已经购买过一次了～");
        }
        //扣减库存
        boolean success= seckillVoucherService.update().setSql("stock=stock-1")
                .eq("voucher_id",voucherId)
                .gt("stock", 0)
                .update();
        if(!success){
            return Result.fail("库存不足，数据库更新失败");
        }

        //创建订单
        VoucherOrder voucherOrder=new VoucherOrder();
        //返回订单id
        Long orderId= redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
