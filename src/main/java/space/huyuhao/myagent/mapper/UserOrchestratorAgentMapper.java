package space.huyuhao.myagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import space.huyuhao.myagent.entity.UserAgent;
import space.huyuhao.myagent.entity.UserOrchestratorAgent;

import java.util.List;

@Mapper
public interface UserOrchestratorAgentMapper extends BaseMapper<UserOrchestratorAgent> {

    /** 删除某编排器的全部子 agent 绑定（全量替换时用） */
    int deleteByUserIdAndOrchestratorId(@Param("userId") Long userId, @Param("orchestratorId") Long orchestratorId);

    /** 查某编排器绑定的子 agent 详情（只返回启用中的 agent） */
    List<UserAgent> selectAgentsByOrchestratorId(@Param("userId") Long userId, @Param("orchestratorId") Long orchestratorId);
}