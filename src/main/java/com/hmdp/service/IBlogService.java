package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    //查询首页热点博客
    Result queryHotBlog(Integer current);
    //根据博客id查询博客信息
    Result queryBlogById(Long id);
    //点赞博客
    Result likeBlog(Long id);
    //点赞排行榜
    Result queryBlogLikes(Long id);
    //保存博客
    Result saveBolg(Blog blog);
    //滚动分页查询
    Result queryBlogOfFollow(Long max, Integer offset);
}
