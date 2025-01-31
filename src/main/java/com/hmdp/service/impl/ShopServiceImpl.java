package com.hmdp.service.impl;

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
        String key=CACHE_SHOP_KEY+ id;
        //1. 从 Redis 查询商铺缓存
     String shopJson=   stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在.null和""都会被算作 blank
     if(StrUtil.isNotBlank(shopJson)){
            //3. 存在。直接返回。
            Shop shop= JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
     //判断命中的是否是空值
        if(shopJson!=null){
return Result.fail("店铺信息不存在。");
        }
     //4. shopJson为null,说明缓存里不存在。根据 id茶数据库
        Shop shop=getById(id);
     if(shop==null){
         //防止缓存穿透，将空值写入 redis
         stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
         return Result.fail("店铺不存在");
     }
     stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
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
}
