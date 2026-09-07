package space.huyuhao.myagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import space.huyuhao.myagent.dto.UserMcpServerDto;
import space.huyuhao.myagent.entity.UserAgentMcp;

import java.util.List;

@Mapper
public interface UserAgentMcpMapper extends BaseMapper<UserAgentMcp> {

    /** 删除某 agent 的全部 MCP 绑定（全量替换时用） */
    int deleteByUserIdAndAgentId(@Param("userId") Long userId, @Param("agentId") Long agentId);

    /** 查某 agent 绑定的 MCP server 详情（只返回启用中的服务） */
    List<UserMcpServerDto> selectMcpsByAgentId(@Param("userId") Long userId, @Param("agentId") Long agentId);
}