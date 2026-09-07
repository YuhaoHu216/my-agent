package space.huyuhao.myagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import space.huyuhao.myagent.entity.UserSkill;

import java.util.List;

@Mapper
public interface UserSkillMapper extends BaseMapper<UserSkill> {

    List<UserSkill> selectByUserId(Long userId);

    List<UserSkill> selectEnabledByUserId(Long userId);

    UserSkill selectByUserIdAndId(@Param("userId") Long userId, @Param("id") Long id);

    UserSkill selectByUserIdAndName(@Param("userId") Long userId, @Param("skillName") String skillName);
}