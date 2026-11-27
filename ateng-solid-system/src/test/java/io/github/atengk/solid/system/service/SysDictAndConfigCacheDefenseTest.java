package io.github.atengk.solid.system.service;

import io.github.atengk.solid.system.mapper.SysConfigMapper;
import io.github.atengk.solid.system.mapper.SysDictDataMapper;
import io.github.atengk.solid.system.mapper.SysDictTypeMapper;
import io.github.atengk.solid.system.support.DictDataCache;
import io.github.atengk.solid.system.support.SysConfigCache;
import io.github.atengk.solid.system.support.SystemContextSupport;
import io.github.atengk.solid.system.vo.DictDataVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SysDictQueryService} 与 {@link SysConfigService} 入口格式校验与负向缓存污染防御 Layer 1 单元测试。
 *
 * @author Ateng
 * @since 2026-11-27
 */
class SysDictAndConfigCacheDefenseTest {

    @Test
    @DisplayName("SysDictQueryService 查询非法 dictType 应当直接返回空列表且严禁调用缓存与 Mapper")
    void shouldRejectInvalidDictTypeFormatBeforeDictCacheLookup() {
        SystemContextSupport contextSupport = mock(SystemContextSupport.class);
        SysDictTypeMapper typeMapper = mock(SysDictTypeMapper.class);
        SysDictDataMapper dataMapper = mock(SysDictDataMapper.class);
        DictDataCache dictDataCache = mock(DictDataCache.class);

        SysDictQueryService service = new SysDictQueryService(
                contextSupport, typeMapper, dataMapper, dictDataCache
        );

        for (String invalidType : List.of("a", "UPPER_CASE", "1starts_digit", "bad!type", "bad-hyphen", "a".repeat(65))) {
            assertThat(service.listActiveDataByType(invalidType)).isEmpty();
        }
        verifyNoInteractions(dictDataCache, typeMapper, dataMapper);

        when(dictDataCache.getOrLoad(eq("sys_user_gender"), any()))
                .thenReturn(List.of(DictDataVO.builder().dictValue("0").dictLabel("男").build()));
        assertThat(service.listActiveDataByType(" sys_user_gender ")).hasSize(1);
        verify(dictDataCache).getOrLoad(eq("sys_user_gender"), any());
    }

    @Test
    @DisplayName("SysConfigService 查询非法 configKey 或负数 tenantId 应当直接返回 null 且严禁调用缓存与 Mapper")
    void shouldRejectInvalidConfigKeyAndNegativeTenantBeforeConfigCacheLookup() {
        SystemContextSupport contextSupport = mock(SystemContextSupport.class);
        SysConfigMapper configMapper = mock(SysConfigMapper.class);
        SysConfigCache configCache = mock(SysConfigCache.class);

        SysConfigService service = new SysConfigService(contextSupport, configMapper, configCache);

        for (String invalidKey : List.of("k", "bad key", "bad/key", "key:colon", "k".repeat(101))) {
            assertThat(service.getConfigValue(0L, invalidKey)).isNull();
        }
        assertThat(service.getConfigValue(-1L, "sys.user.initPassword")).isNull();
        assertThat(service.getConfigValue(Long.MIN_VALUE, "sys.user.initPassword")).isNull();
        verifyNoInteractions(configCache, configMapper);

        when(configCache.getOrLoad(eq(0L), eq("sys.user.initPassword"), any())).thenReturn("Ateng@2026");
        assertThat(service.getConfigValue(0L, " sys.user.initPassword ")).isEqualTo("Ateng@2026");
        verify(configCache).getOrLoad(eq(0L), eq("sys.user.initPassword"), any());
    }
}
