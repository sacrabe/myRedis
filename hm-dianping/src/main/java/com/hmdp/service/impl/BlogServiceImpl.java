package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
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
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private FollowServiceImpl followService;

    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }

        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
        // 查询是否点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 判断当前用户是否已经点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score != null) {
            // 已经点赞
            // 数据减一
            boolean ifSucess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (ifSucess) {
                // 删除
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
            // 点赞数-1

        } else {
            // 未点赞
            // 数据库+1
            boolean ifSucess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (ifSucess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }

        }


        return Result.ok();
    }
    // 点赞排行榜
    @Override
    public Result queryBlogLikes(Long id) {
        // 获取top5 点赞用户  zrange key 0 5
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        // 解析用户
        if (top5 == null || top5.isEmpty()) {
            return Result.ok();
        }

        List<Long> ids = top5.stream().map(Long::parseLong).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);

        List<UserDTO> userDTOs = userService.query().in("id", ids).last("order by field (id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOs);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店笔记
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败!");
        }
        // 3.查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 4.推送笔记id给所有粉丝 的收件箱
        for (Follow follow : follows) {
            // 4.1.获取粉丝id
            Long userId = follow.getUserId();
            // 4.2.推送
            String key = FEED_KEY + userId;

            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 5.返回id
        return Result.ok(blog.getId());
    }
    // 查看关注的人 发的博客
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 查询收件箱
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        long minTime = 0;
        int os = 1;
        ArrayList<Long> ids = new ArrayList<>(typedTuples.size());
        //解析数据 blogid minTime时间戳  offset
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {  // 5 4 4 2 2
            String blogId = tuple.getValue();
            ids.add(Long.valueOf(blogId));
            if (minTime == tuple.getScore().longValue()) {
                os++;
            } else {
                minTime = tuple.getScore().longValue();
                os = 1;
            }
        }
        String idStr = StrUtil.join(",", ids);

        // 为什么不能直接listbyids  因为 用了 in  in在sql中是没有顺序的
        List<Blog> blogs = query().in("id", ids).last("order by field (id," + idStr + "   )").list();

        for (Blog blog : blogs) {
            // 查询 blog 用户  给Blog 在数据库中没有的字段   赋值
            Long userIdd = blog.getUserId();
            User user = userService.getById(userIdd);
            blog.setIcon(user.getIcon());
            blog.setName(user.getNickName());
            // 查询是否点赞
            isBlogLiked(blog);
        }

        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }

    // 查询当前用户是否点赞 blog
    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录
            return;
        }
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }
}
