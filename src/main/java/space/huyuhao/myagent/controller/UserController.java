package space.huyuhao.myagent.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import space.huyuhao.myagent.context.UserContext;
import space.huyuhao.myagent.dto.ResponseResult;
import space.huyuhao.myagent.dto.UserInfoDto;
import space.huyuhao.myagent.dto.UserLoginDto;
import space.huyuhao.myagent.dto.UserRegisterDto;
import space.huyuhao.myagent.service.UserService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private UserService userService;

    @PostMapping("/register")
    @Operation(summary = "用户注册")
    public ResponseResult<String> register(@Valid @RequestBody UserRegisterDto userRegisterDto) {
        return userService.register(userRegisterDto);
    }

    @PostMapping("/login")
    @Operation(summary = "用户登录")
    public ResponseResult<String> login(@Valid @RequestBody UserLoginDto userLoginDto) {
        return userService.login(userLoginDto);
    }

    @GetMapping("/me")
    @Operation(summary = "获取当前用户信息")
    public ResponseResult<UserInfoDto> getCurrentUser() {
        return userService.getCurrentUser(UserContext.getUserId());
    }

    @PostMapping("/logout")
    @Operation(summary = "用户登出")
    public ResponseResult<String> logout() {
        return userService.logout();
    }

}