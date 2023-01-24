package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

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
    IUserService userService;
    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    IFollowService followService;

    /**
     * 分页查询首页热点博客
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 根据id查询博客
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = this.getById(id);
        if(blog == null) {
            return Result.fail("博客不存在!");
        }
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        //获取当前登录用户
        UserDTO user = UserHolder.getUser();
        //用户未登录,无需查询用户是否是否点过赞
        if(user == null) {
            return;
        }
        Long userId = user.getId();
        //判断当前博客是否被点赞,填充字段 isLike
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + blog.getId(), userId.toString());
        if(score != null) {
            blog.setIsLike(Boolean.TRUE);
        }else {
            blog.setIsLike(Boolean.FALSE);
        }
    }

    /**
     * 点赞博客
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        //1.获取当前用户id
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;
        //2.判断用户是否点赞过了
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //2.1 该用户没有点赞过,则点赞数 + 1 ,且将该用户id添加到zset集合中(zset能按score排序,便于做排行榜)
        if(score == null) {
            boolean isSuccess = this.update().setSql("liked = liked + 1").eq("id", id).update();
            if(isSuccess) {
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
            //3.该用户点赞过了,则点赞数 - 1 ,且将该用户id从set集合中移除
            boolean isSuccess = this.update().setSql("liked = liked - 1").eq("id", id).update();
            if(isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 点赞排行榜
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        //1.获取当前博客的前五名点赞用户id
        Set<String> userIdSet = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        if(userIdSet == null || userIdSet.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //2.根据id获取用户信息
        List<Long> ids = userIdSet.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //where id in(1010,1) order by field(id,1010,1)
        List<User> users = userService.query().in("id",ids).last("order by field(id,"+ idStr +")").list();
        //3.将user对象转为userdto
        List<UserDTO> userDTOList = users.stream().map(user -> {
            UserDTO userDTO = new UserDTO();
            BeanUtils.copyProperties(user, userDTO);
            return userDTO;
        }).collect(Collectors.toList());
        return Result.ok(userDTOList);
    }

    /**
     * 保存博客并实现feed流推模式(将博客信息推送到粉丝收件箱)
     * @param blog
     * @return
     */
    @Override
    public Result saveBolg(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = this.save(blog);
        if(!isSuccess) {
            return Result.fail("保存博客信息失败!");
        }
        //获取当前登录用户的所有粉丝
        List<Follow> follows = followService.list(
                new LambdaQueryWrapper<Follow>()
                        .eq(Follow::getFollowUserId, user.getId())
        );
        //将博客id推送到所有粉丝收件箱中
        follows.forEach(follow -> {
            stringRedisTemplate.opsForZSet().add(FEED_KEY + follow.getUserId(),blog.getId().toString(), System.currentTimeMillis());
        });
        // 返回id
        return Result.ok(blog.getId());
    }

    /**
     * 滚动分页查询(将收件箱中关注的用户博客id做分页查询)
     * @param max
     * @param offset
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询当前用户收件箱(ZREVRANGEBYSCORE key max min limit offset count)
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(FEED_KEY + userId, 0, max, offset, 2);
        if(typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        //3.解析数据:blogId,minTime,offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0; // 5 4 2
        int os = 1; // 1 2 1 2
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
            //3.1获取id
            String idStr = tuple.getValue();
            ids.add(Long.valueOf(idStr));
            //3.2获取score(时间戳)
            long time = tuple.getScore().longValue();
            if(time == minTime) {
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }
        //4.根据id查询blog
        String idStr = StrUtil.join(",",ids);
        List<Blog> blogs = this.query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        //5.查询每个博客的关联用户以及是否点过赞
        blogs.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        //6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setMinTime(minTime);
        r.setOffset(os);
        return Result.ok(r);
    }

    private void queryBlogUser(Blog blog) {
        //根据博客的用户id获取用户信息
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        //填充博客的用户姓名以及用户头像字段(便于前端显示)
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
