package io.github.atengk.solid.system.support;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.solid.system.properties.SolidSystemProperties;
import io.github.atengk.solid.system.vo.DictDataVO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 动态数据字典双模缓存：键名 {@code ateng:solid:dict:{dictType}}，TTL 默认 30m；
 * 停用或不存在的字典类型缓存空列表 {@code []} 防穿透，写操作提交后精确驱逐。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
public class DictDataCache {

    /**
     * 字典数据缓存键前缀（完整键：{@code ateng:solid:dict:{dictType}}）
     */
    public static final String CACHE_KEY_PREFIX = "ateng:solid:dict:";

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);
    private static final TypeReference<List<DictDataVO>> LIST_TYPE_REF = new TypeReference<>() {
    };

    private final BoundedDualModeCache<List<DictDataVO>> cache;

    /**
     * 构造动态字典双模缓存组件。
     *
     * @param stringRedisTemplateProvider 可选 Redis 模板提供器
     * @param systemProperties            系统域治理配置（读取 {@code cache.dict-ttl}）
     * @param environmentProvider         Spring 环境提供器
     */
    public DictDataCache(
            ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider,
            SolidSystemProperties systemProperties,
            ObjectProvider<Environment> environmentProvider
    ) {
        this.cache = new BoundedDualModeCache<>(
                stringRedisTemplateProvider,
                environmentProvider,
                () -> systemProperties != null && systemProperties.getCache() != null
                        ? systemProperties.getCache().getDictTtl()
                        : DEFAULT_TTL,
                DEFAULT_TTL,
                DictDataCache::deserializeList
        );
    }

    /**
     * 构建标准动态字典缓存键。
     *
     * @param dictType 字典类型编码
     * @return 缓存键字符串
     */
    public static String buildCacheKey(String dictType) {
        return CACHE_KEY_PREFIX + (dictType != null ? dictType.trim() : "");
    }

    /**
     * 按字典类型读取启用态明细列表；未命中时调用 {@code loader} 回源并写入缓存（空列表同样缓存防穿透）。
     *
     * @param dictType 字典类型编码
     * @param loader   回源加载函数
     * @return 不可变字典明细列表（绝不返回 {@code null}）
     */
    public List<DictDataVO> getOrLoad(String dictType, Function<String, List<DictDataVO>> loader) {
        if (!StringUtils.hasText(dictType)) {
            return Collections.emptyList();
        }
        String normalizedType = dictType.trim();
        List<DictDataVO> result = cache.getOrLoad(
                buildCacheKey(normalizedType),
                () -> immutableCopy(loader.apply(normalizedType))
        );
        return result != null ? result : Collections.emptyList();
    }

    /**
     * 主动驱逐指定字典类型的缓存记录。
     *
     * @param dictType 字典类型编码
     */
    public void evict(String dictType) {
        if (!StringUtils.hasText(dictType)) {
            return;
        }
        cache.evict(buildCacheKey(dictType.trim()));
    }

    /**
     * 返回本地降级缓存当前条目数（供单元测试诊断）。
     *
     * @return 本地缓存大小
     */
    public int localSize() {
        return cache.localSize();
    }

    private static List<DictDataVO> deserializeList(String rawJson) {
        List<DictDataVO> parsed = JsonUtil.parseObject(rawJson, LIST_TYPE_REF);
        if (parsed == null) {
            throw new IllegalArgumentException("字典缓存 JSON 解析结果为空");
        }
        return immutableCopy(parsed);
    }

    private static List<DictDataVO> immutableCopy(List<DictDataVO> source) {
        if (CollectionUtils.isEmpty(source)) {
            return Collections.emptyList();
        }
        return List.copyOf(source);
    }
}
