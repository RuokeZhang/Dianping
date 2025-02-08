package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Autowired
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog->{
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 2.查询blog有关的用户
        queryBlogUser(blog);
        //3. 查询 blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }


    private void isBlogLiked(Blog blog) {
        //获取登录用户
        UserDTO user=UserHolder.getUser();
        if(user==null){
            return;
        }
        Long userId=user.getId();
        String key="blog:liked:"+blog.getId();
        //判断当前用户是否已经点赞
        Double score=stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
    }

    @Override
    public Result likeBlog(Long id) {
        //获取登录用户
        Long userId=UserHolder.getUser().getId();
        String key="blog:liked:"+id;
        //判断当前用户是否已经点赞

        Double score=stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score==null){
            //如果未点赞，可以点赞
            //数据库点赞数量+1
            boolean isSuccess=  update().setSql("liked = liked +1").eq("id", id).update();
            if(isSuccess){
                //保存用户到 Redis zadd key value score
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }else{
            //如果已点赞，取消点赞
            boolean isSuccess=  update().setSql("liked = liked -1").eq("id", id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //查询 top 5的点赞用户
        String key="blog:liked:"+id;
        Set<String> top5=stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5==null||top5.size()==0){
            return Result.ok(Collections.emptyList());
        }
        //解析出其中的用户 id
        List<Long> ids=top5.stream().map(Long::valueOf).collect(Collectors.toList());
        //根据用户 id查用户
        List<UserDTO> usersDTO=userService.listByIds(ids).stream()
                .map(user-> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //返回
        return Result.ok(usersDTO);
    }

    private void queryBlogUser(Blog blog){
        Long userId=blog.getUserId();
        User user=userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
