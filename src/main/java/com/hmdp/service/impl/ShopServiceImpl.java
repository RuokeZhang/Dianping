package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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
    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 7.返回
        return Result.ok(shop);
    }

    @Transactional
    @Override
    public Result update(Shop shop) {
        Long id=shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        //1. 更新数据库
        updateById(shop);
        //2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+ id);
        return Result.ok();
    }

    private boolean tryLock(String key){
       Boolean flag= stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10, TimeUnit.SECONDS);
       return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete (key);
    }

    public Shop queryWithMutex(Long id) {
        //解决缓存击穿
        String key=CACHE_SHOP_KEY+ id;
        //1. 从 Redis 查询商铺缓存
        String shopJson=   stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在.null和""都会被算作 blank
        if(StrUtil.isNotBlank(shopJson)){
            //3. 存在。直接返回。
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的是否是空值
        if(shopJson!=null){
            return null;
        }
        //4. shopJson为null,说明缓存里不存在。实现缓存重建。
        //4.1 获取互斥锁
        String lockKey="lock:shop:"+id;
        Shop shop= null;
        try {
            boolean isLock=tryLock(lockKey);

            //4.2 判断是否获取成功
            if(!isLock){
                //4.3 失败。休眠并且重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //4.4 获取锁成功 根据 ID 查询数据
            shop = getById(id);
            //模拟查询数据库延时
            //Thread.sleep(200);
            if(shop==null){
                //防止缓存穿透，将空值写入 redis
                stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
                 return null;
            }

            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        finally {
            //释放互斥锁
            unlock(lockKey);
        }

        return shop;
    }
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        Shop shop=getById(id);
        //Thread.sleep(200L);
        //封装逻辑过期时间
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds)) ;
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id, JSONUtil.toJsonStr(redisData));
    }

    //新建一个线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id) {
        String key=CACHE_SHOP_KEY+ id;
        //1. 从 Redis 查询商铺缓存
        String shopJson=   stringRedisTemplate.opsForValue().get(key);
        //2. null和""都会被算作 blank
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        //3. 命中，需要先把 JSON 反序列化为对象
        RedisData redisData= JSONUtil.toBean(shopJson,RedisData.class);
        JSONObject data= (JSONObject)redisData.getData();
        Shop shop= JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime=redisData.getExpireTime();
        //4. 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //4.1 没有过期，直接返回店铺信息
            return shop;
        }
        //5 已经过期，需要重建缓存。
        String lockKey=LOCK_SHOP_KEY+id;
        boolean isLock=tryLock(lockKey);
        if(isLock) {
            //获取lock 成功，则开启独立线程去实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try{
                    this.saveShop2Redis(id, 20L);
                }catch(Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }
            });
        }
        //返回旧缓存里的店铺信息
        return shop;
    }


}

