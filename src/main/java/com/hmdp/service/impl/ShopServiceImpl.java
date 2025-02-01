package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
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
    @Override
    public Result queryById(Long id) {


        //缓存穿透
        //Shop shop=queryCachingPenetration(id);
        //互斥锁解决缓存击穿
        Shop shop=queryWithMutex(id);
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
    public Shop queryCachingPenetration(Long id) {
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
        //4. shopJson为null,说明缓存里不存在。根据 id茶数据库
        Shop shop=getById(id);
        if(shop==null){
            //防止缓存穿透，将空值写入 redis
            stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
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
            Thread.sleep(200);
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


}

