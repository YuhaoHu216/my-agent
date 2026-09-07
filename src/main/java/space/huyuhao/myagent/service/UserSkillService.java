package space.huyuhao.myagent.service;

import space.huyuhao.myagent.dto.ResponseResult;
import space.huyuhao.myagent.dto.UserSkillDto;
import space.huyuhao.myagent.dto.UserSkillRequest;

import java.util.List;

public interface UserSkillService {

    ResponseResult<List<UserSkillDto>> list();

    ResponseResult<String> add(UserSkillRequest request);

    ResponseResult<String> update(UserSkillRequest request);

    ResponseResult<String> delete(Long id);

    ResponseResult<String> toggle(Long id);
}