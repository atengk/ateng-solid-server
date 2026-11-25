package io.github.atengk.solid.system.mapper;

import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.solid.system.entity.SysDictData;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 动态字典数据表持久层 Mapper。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Mapper
public interface SysDictDataMapper extends AtengBaseMapper<SysDictData> {

    /**
     * 批量统计指定字典类型集合下未逻辑删除的字典明细数量（防删除校验 N+1 查询）。
     *
     * @param dictTypes 字典类型编码集合
     * @return 按字典类型分组的未删除明细计数列表
     */
    @Select("""
            <script>
            SELECT dict_type AS dictType, COUNT(1) AS total
            FROM sys_dict_data
            WHERE deleted = 0
              <choose>
                <when test="dictTypes != null and !dictTypes.isEmpty()">
                  AND dict_type IN
                  <foreach collection="dictTypes" item="dictType" open="(" separator="," close=")">
                    #{dictType}
                  </foreach>
                </when>
                <otherwise>
                  AND 1 = 0
                </otherwise>
              </choose>
            GROUP BY dict_type
            </script>
            """)
    List<DictTypeDataCount> countActiveByDictTypes(@Param("dictTypes") List<String> dictTypes);

    /**
     * 字典类型明细统计投影结果。
     */
    @Data
    class DictTypeDataCount {

        /**
         * 字典类型编码
         */
        private String dictType;

        /**
         * 未删除明细数量
         */
        private Long total;
    }
}
