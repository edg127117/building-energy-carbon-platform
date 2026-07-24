<#-- V1 Mapper 模板：复用 MyBatis-Plus BaseMapper 提供单表 CRUD。 -->
package ${basePackage}.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ${basePackage}.model.entity.${table.className};
import org.apache.ibatis.annotations.Mapper;

/** ${table.className} 的 MyBatis-Plus 数据访问接口。 */
@Mapper
public interface ${table.className}Mapper extends BaseMapper<${table.className}> {}
