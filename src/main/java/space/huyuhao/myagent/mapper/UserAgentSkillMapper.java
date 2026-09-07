package space.huyuhao.myagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import space.huyuhao.myagent.dto.UserSkillDto;
import space.huyuhao.myagent.entity.UserAgentSkill;

import java.util.List;

@Mapper
public interface UserAgentSkillMapper extends BaseMapper<UserAgentSkill> {

    /** 删除某 agent 的全部技能绑定（全量替换时用） */
    int deleteByUserIdAndAgentId(@Param("userId") Long userId, @Param("agentId") Long agentId);

    /** 查某 agent 绑定的 skill 详情（只返回启用中的技能） */
    List<UserSkillDto> selectSkillsByAgentId(@Param("userId") Long userId, @Param("agentId") Long agentId);
}