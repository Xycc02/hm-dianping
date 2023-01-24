package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.USER_FOLLOW_KEY;

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

    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //获取当前登录用户id
        Long userId = UserHolder.getUser().getId();
        //判断当前是要关注用户还是取关用户
        if(isFollow) {
            //关注用户
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = this.save(follow);
            if(isSuccess) {
                stringRedisTemplate.opsForSet().add(USER_FOLLOW_KEY + userId,followUserId.toString());
            }
        }else{
            boolean isSuccess = this.remove(
                    new LambdaQueryWrapper<Follow>()
                            .eq(Follow::getUserId, userId)
                            .eq(Follow::getFollowUserId, followUserId)
            );
            if(isSuccess) {
                stringRedisTemplate.opsForSet().remove(USER_FOLLOW_KEY + userId,followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollowed(Long followUserId) {
        //获取当前登录用户id
        Long userId = UserHolder.getUser().getId();
        int count = this.count(
                new LambdaQueryWrapper<Follow>()
                        .eq(Follow::getUserId, userId)
                        .eq(Follow::getFollowUserId, followUserId)
        );
        return Result.ok(count > 0);
    }

    @Override
    public Result commonFollow(Long id) {
        //获取当前登录用户id
        Long userId = UserHolder.getUser().getId();
        String key1 = USER_FOLLOW_KEY + userId;
        String key2 = USER_FOLLOW_KEY + id;

        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(intersect == null || intersect.isEmpty()) {
            return Result.ok("您和该用户无共同关注~");
        }
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOList = userService.listByIds(ids).stream().map(user -> {
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            return userDTO;
        }).collect(Collectors.toList());
        return Result.ok(userDTOList);
    }
}
