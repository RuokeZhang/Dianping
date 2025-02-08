package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    private final StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    public FollowServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result follow(Long followUserId, Boolean isFollowing) {
        Long userId= UserHolder.getUser().getId();
        String key="follow:"+userId;
        //判断到底是关注还是取关
        if(isFollowing){
            //关注：新增一条数据
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean isSuccess=save(follow);
            if(isSuccess){
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        }else{
            //取关：删除一条数据
            remove(new QueryWrapper<Follow>()
                    .eq("follow_user_id", followUserId)
                    .eq("user_id", userId));
            stringRedisTemplate.opsForSet().remove(key , followUserId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId= UserHolder.getUser().getId();
        Integer count=query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count>0);
    }

    @Override
    public Result followCommons(Long id) {
        Long userId= UserHolder.getUser().getId();
        String key="follow:"+userId;
        String key2="follow:"+id;
       Set<String> intersect=stringRedisTemplate.opsForSet().intersect(key, key2);
        List<Long> ids=intersect.stream().map(Long::valueOf).collect(Collectors.toList());

        if(intersect==null||intersect.size()==0){
            return Result.ok(Collections.emptyList());
        }
       List<UserDTO> userDTOs=userService.listByIds(ids).stream()
               .map((user)-> BeanUtil.copyProperties(user, UserDTO.class))
               .collect(Collectors.toList());
       return Result.ok(userDTOs);
    }
}
