package space.huyuhao.myagent.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import space.huyuhao.myagent.context.UserContext;
import space.huyuhao.myagent.dto.ResponseResult;
import space.huyuhao.myagent.dto.UserSkillDto;
import space.huyuhao.myagent.dto.UserSkillRequest;
import space.huyuhao.myagent.entity.UserSkill;
import space.huyuhao.myagent.mapper.UserSkillMapper;
import space.huyuhao.myagent.service.UserSkillService;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserSkillServiceImpl implements UserSkillService {

    private final UserSkillMapper userSkillMapper;

    public UserSkillServiceImpl(UserSkillMapper userSkillMapper) {
        this.userSkillMapper = userSkillMapper;
    }

    @Override
    public ResponseResult<List<UserSkillDto>> list() {
        Long userId = UserContext.getUserId();
        List<UserSkillDto> dtos = userSkillMapper.selectByUserId(userId).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        return ResponseResult.success(dtos);
    }

    @Override
    public ResponseResult<String> add(UserSkillRequest request) {
        Long userId = UserContext.getUserId();
        if (userSkillMapper.selectByUserIdAndName(userId, request.getSkillName()) != null) {
            return ResponseResult.error(400, "技能名称已存在");
        }
        UserSkill skill = new UserSkill();
        skill.setUserId(userId);
        skill.setSkillName(request.getSkillName());
        skill.setSkillContent(request.getSkillContent());
        skill.setEnabled(request.getEnabled() != null ? request.getEnabled() : 1);
        userSkillMapper.insert(skill);
        return ResponseResult.success("新增成功", null);
    }

    @Override
    public ResponseResult<String> update(UserSkillRequest request) {
        Long userId = UserContext.getUserId();
        if (request.getId() == null) {
            return ResponseResult.error(400, "缺少技能ID");
        }
        UserSkill skill = userSkillMapper.selectByUserIdAndId(userId, request.getId());
        if (skill == null) {
            return ResponseResult.error(404, "技能不存在");
        }
        // 仅改名时做重名校验
        if (!skill.getSkillName().equals(request.getSkillName())
                && userSkillMapper.selectByUserIdAndName(userId, request.getSkillName()) != null) {
            return ResponseResult.error(400, "技能名称已存在");
        }
        UserSkill update = new UserSkill();
        update.setId(skill.getId());
        update.setSkillName(request.getSkillName());
        update.setSkillContent(request.getSkillContent());
        userSkillMapper.updateById(update);
        return ResponseResult.success("修改成功", null);
    }

    @Override
    public ResponseResult<String> delete(Long id) {
        Long userId = UserContext.getUserId();
        if (userSkillMapper.selectByUserIdAndId(userId, id) == null) {
            return ResponseResult.error(404, "技能不存在");
        }
        userSkillMapper.deleteById(id);
        return ResponseResult.success("删除成功", null);
    }

    @Override
    public ResponseResult<String> toggle(Long id) {
        Long userId = UserContext.getUserId();
        UserSkill skill = userSkillMapper.selectByUserIdAndId(userId, id);
        if (skill == null) {
            return ResponseResult.error(404, "技能不存在");
        }
        UserSkill update = new UserSkill();
        update.setId(skill.getId());
        update.setEnabled(skill.getEnabled() != null && skill.getEnabled() == 1 ? 0 : 1);
        userSkillMapper.updateById(update);
        return ResponseResult.success("操作成功", null);
    }

    private UserSkillDto toDto(UserSkill skill) {
        UserSkillDto dto = new UserSkillDto();
        BeanUtils.copyProperties(skill, dto);
        return dto;
    }
}