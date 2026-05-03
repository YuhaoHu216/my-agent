package space.huyuhao.myagent.service;

import space.huyuhao.myagent.dto.ResponseResult;
import space.huyuhao.myagent.dto.UserInfoDto;
import space.huyuhao.myagent.dto.UserLoginDto;
import space.huyuhao.myagent.dto.UserRegisterDto;
import space.huyuhao.myagent.entity.User;

public interface UserService {
    ResponseResult<String> register(UserRegisterDto userRegisterDto);

    ResponseResult<String> login(UserLoginDto userLoginDto);

    ResponseResult<UserInfoDto> getCurrentUser(String username);

    ResponseResult<String> logout();

    User findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}