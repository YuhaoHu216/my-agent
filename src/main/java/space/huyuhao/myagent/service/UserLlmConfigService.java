package space.huyuhao.myagent.service;

import space.huyuhao.myagent.dto.ResponseResult;
import space.huyuhao.myagent.dto.UserLlmConfigDto;
import space.huyuhao.myagent.dto.UserLlmConfigRequest;

import java.util.List;
import java.util.Map;

public interface UserLlmConfigService {

    ResponseResult<List<UserLlmConfigDto>> list();

    ResponseResult<String> save(UserLlmConfigRequest request);

    ResponseResult<String> delete(String provider);

    ResponseResult<String> test(UserLlmConfigRequest request);

    ResponseResult<Map<String, List<String>>> presets();
}
