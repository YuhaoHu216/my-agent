package space.huyuhao.myagent.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import space.huyuhao.myagent.dto.ResponseResult;
import space.huyuhao.myagent.dto.UserLoginDto;
import space.huyuhao.myagent.dto.UserRegisterDto;
import space.huyuhao.myagent.entity.User;
import space.huyuhao.myagent.mapper.UserMapper;
import space.huyuhao.myagent.service.UserService;
import space.huyuhao.myagent.util.JwtUtil;

import java.time.LocalDateTime;

@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    public ResponseResult<String> register(UserRegisterDto userRegisterDto) {
        // 检查用户名是否已存在
        if (existsByUsername(userRegisterDto.getUsername())) {
            return ResponseResult.error("用户名已存在");
        }

        // 检查邮箱是否已存在
        if (existsByEmail(userRegisterDto.getEmail())) {
            return ResponseResult.error("邮箱已被注册");
        }

        // 创建用户实体
        User user = new User();
        BeanUtils.copyProperties(userRegisterDto, user);
        // 密码加密
        user.setPassword(BCrypt.hashpw(userRegisterDto.getPassword()));
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        user.setStatus(1); // 启用状态

        // 保存用户
        int result = userMapper.insert(user);
        if (result > 0) {
            return ResponseResult.success("注册成功");
        } else {
            return ResponseResult.error("注册失败");
        }
    }

    @Override
    public ResponseResult<String> login(UserLoginDto userLoginDto) {
        // 根据用户名查找用户
        User user = findByUsername(userLoginDto.getUsername());
        if (user == null) {
            return ResponseResult.error("用户不存在");
        }

        // 验证密码
        if (!BCrypt.checkpw(userLoginDto.getPassword(), user.getPassword())) {
            return ResponseResult.error("密码错误");
        }

        // 生成JWT令牌
        String token = jwtUtil.generateToken(user.getUsername());

        return ResponseResult.success("登录成功", token);
    }

    @Override
    public User findByUsername(String username) {
        return userMapper.findByUsername(username);
    }

    @Override
    public boolean existsByUsername(String username) {
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("username", username);
        return userMapper.selectCount(wrapper) > 0;
    }

    @Override
    public boolean existsByEmail(String email) {
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("email", email);
        return userMapper.selectCount(wrapper) > 0;
    }
}