package io.github.atengk.solid.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.util.PageUtil;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysPost;
import io.github.atengk.solid.system.mapper.SysPostMapper;
import io.github.atengk.solid.system.mapper.SysUserPostMapper;
import io.github.atengk.solid.system.request.PostQueryRequest;
import io.github.atengk.solid.system.request.PostSaveRequest;
import io.github.atengk.solid.system.support.SystemContextSupport;
import io.github.atengk.solid.system.vo.PostOptionVO;
import io.github.atengk.solid.system.vo.PostVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 岗位信息管理服务，负责同租户岗位编码与名称唯一性校验、下拉选项输出及被用户引用时的整批保护性拦截。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class SysPostService {

    private final SystemContextSupport contextSupport;
    private final SysPostMapper sysPostMapper;
    private final SysUserPostMapper sysUserPostMapper;

    /**
     * 分页检索当前工作租户下的岗位列表。
     *
     * @param query 分页与筛选条件
     * @return 岗位分页结果
     */
    public PageResult<PostVO> pagePosts(PostQueryRequest query) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        PostQueryRequest safeQuery = query != null ? query : new PostQueryRequest();
        Page<SysPost> pageRequest = PageUtil.toPage(
                safeQuery, SysPost.class, true, SystemContextSupport.DEFAULT_MAX_PAGE_SIZE
        );

        String postCode = SystemContextSupport.trimToNull(safeQuery.getPostCode());
        String postName = SystemContextSupport.trimToNull(safeQuery.getPostName());
        LambdaQueryWrapper<SysPost> wrapper = Wrappers.<SysPost>lambdaQuery()
                .eq(SysPost::getTenantId, tenantId)
                .like(postCode != null, SysPost::getPostCode, postCode)
                .like(postName != null, SysPost::getPostName, postName)
                .eq(safeQuery.getStatus() != null, SysPost::getStatus, safeQuery.getStatus());
        if (CollectionUtils.isEmpty(safeQuery.getSorts())) {
            wrapper.orderByAsc(SysPost::getSort).orderByAsc(SysPost::getId);
        }

        Page<SysPost> result = TenantContextHolder.runWithTenant(
                tenantId,
                () -> sysPostMapper.selectPage(pageRequest, wrapper)
        );
        if (CollectionUtils.isEmpty(result.getRecords())) {
            return PageResult.of(
                    Collections.emptyList(),
                    result.getTotal(),
                    result.getCurrent(),
                    result.getSize(),
                    result.getPages()
            );
        }
        List<PostVO> records = result.getRecords().stream().map(SysPostService::toVO).toList();
        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize(), result.getPages());
    }

    /**
     * 获取当前工作租户下正常启用状态的岗位下拉选项列表。
     *
     * @return 正常岗位选项列表
     */
    public List<PostOptionVO> listActiveOptions() {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        List<SysPost> posts = TenantContextHolder.runWithTenant(tenantId, () -> sysPostMapper.selectList(
                Wrappers.<SysPost>lambdaQuery()
                        .eq(SysPost::getTenantId, tenantId)
                        .eq(SysPost::getStatus, CommonStatusEnum.NORMAL.getCode())
                        .orderByAsc(SysPost::getSort)
                        .orderByAsc(SysPost::getId)
        ));
        if (CollectionUtils.isEmpty(posts)) {
            return Collections.emptyList();
        }
        return posts.stream()
                .map(post -> PostOptionVO.builder()
                        .id(post.getId())
                        .postCode(post.getPostCode())
                        .postName(post.getPostName())
                        .status(post.getStatus())
                        .build())
                .toList();
    }

    /**
     * 获取指定岗位详情。
     *
     * @param id 岗位主键
     * @return 岗位详情
     * @throws BusinessException 目标岗位不存在时抛出 {@code 102001}
     */
    public PostVO getDetail(Long id) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        return toVO(requirePost(tenantId, id));
    }

    /**
     * 新增岗位并校验同租户编码及名称唯一性。
     *
     * @param request 岗位保存请求
     * @return 新建岗位主键
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createPost(PostSaveRequest request) {
        if (request == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "岗位保存参数不能为空");
        }
        Long tenantId = contextSupport.requireEffectiveTenantId();
        PostPayload payload = normalizePostPayload(request, null);

        assertPostUnique(tenantId, payload.postCode(), payload.postName(), null);

        SysPost entity = new SysPost();
        entity.setTenantId(tenantId);
        entity.setPostCode(payload.postCode());
        entity.setPostName(payload.postName());
        entity.setSort(payload.sort());
        entity.setStatus(payload.status());
        entity.setRemark(payload.remark());

        try {
            TenantContextHolder.runWithTenant(tenantId, () -> sysPostMapper.insert(entity));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "岗位编码或岗位名称在当前租户内已存在", ex);
        }
        return entity.getId();
    }

    /**
     * 修改岗位并校验同租户编码及名称唯一性。
     *
     * @param request 岗位修改请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void updatePost(PostSaveRequest request) {
        if (request == null || request.getId() == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "修改岗位时主键不能为空");
        }
        Long tenantId = contextSupport.requireEffectiveTenantId();
        SysPost existing = requirePost(tenantId, request.getId());
        PostPayload payload = normalizePostPayload(request, existing);

        assertPostUnique(tenantId, payload.postCode(), payload.postName(), existing.getId());

        LambdaUpdateWrapper<SysPost> wrapper = Wrappers.<SysPost>lambdaUpdate()
                .eq(SysPost::getTenantId, tenantId)
                .eq(SysPost::getId, existing.getId())
                .set(SysPost::getPostCode, payload.postCode())
                .set(SysPost::getPostName, payload.postName())
                .set(SysPost::getSort, payload.sort())
                .set(SysPost::getStatus, payload.status())
                .set(SysPost::getRemark, payload.remark());
        try {
            TenantContextHolder.runWithTenant(tenantId, () -> sysPostMapper.update(new SysPost(), wrapper));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "岗位编码或岗位名称在当前租户内已存在", ex);
        }
    }

    /**
     * 切换岗位启停状态。
     *
     * @param id     岗位主键
     * @param status 目标状态（0=正常，1=停用）
     */
    @Transactional(rollbackFor = Exception.class)
    public void updatePostStatus(Long id, Integer status) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        Integer targetStatus = SystemContextSupport.requireValidStatus(status);
        requirePost(tenantId, id);

        LambdaUpdateWrapper<SysPost> wrapper = Wrappers.<SysPost>lambdaUpdate()
                .eq(SysPost::getTenantId, tenantId)
                .eq(SysPost::getId, id)
                .set(SysPost::getStatus, targetStatus);
        TenantContextHolder.runWithTenant(tenantId, () -> sysPostMapper.update(new SysPost(), wrapper));
    }

    /**
     * 批量删除岗位；存在任一活跃用户关联引用时整批阻断并点名岗位名称。
     *
     * @param ids 逗号分隔的岗位主键列表
     */
    @Transactional(rollbackFor = Exception.class)
    public void deletePosts(String ids) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        List<Long> idList = SystemContextSupport.parsePathIds(ids);

        List<SysPost> existingPosts = TenantContextHolder.runWithTenant(tenantId, () -> sysPostMapper.selectList(
                Wrappers.<SysPost>lambdaQuery()
                        .eq(SysPost::getTenantId, tenantId)
                        .in(SysPost::getId, idList)
        ));
        if (existingPosts == null || existingPosts.size() != idList.size()) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "部分或全部待删除岗位不存在");
        }

        List<Long> referencedIds = TenantContextHolder.runWithTenant(
                tenantId,
                () -> sysUserPostMapper.selectReferencedPostIds(tenantId, idList)
        );
        if (!CollectionUtils.isEmpty(referencedIds)) {
            Map<Long, String> nameMap = new HashMap<>(existingPosts.size());
            for (SysPost post : existingPosts) {
                nameMap.put(post.getId(), post.getPostName());
            }
            Long firstBlockedId = referencedIds.get(0);
            String blockedName = nameMap.getOrDefault(firstBlockedId, String.valueOf(firstBlockedId));
            throw new BusinessException(
                    SystemResultCode.RESOURCE_IN_USE,
                    "岗位【" + blockedName + "】已分配给用户，请先解除关联后再删除"
            );
        }

        TenantContextHolder.runWithTenant(tenantId, () -> sysPostMapper.deleteByIds(idList));
    }

    private SysPost requirePost(Long tenantId, Long id) {
        if (id == null || id <= 0L) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "目标岗位不存在");
        }
        SysPost post = TenantContextHolder.runWithTenant(tenantId, () -> sysPostMapper.selectOne(
                Wrappers.<SysPost>lambdaQuery()
                        .eq(SysPost::getTenantId, tenantId)
                        .eq(SysPost::getId, id)
        ));
        if (post == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "目标岗位不存在");
        }
        return post;
    }

    private void assertPostUnique(Long tenantId, String postCode, String postName, Long excludeId) {
        Long codeCount = TenantContextHolder.runWithTenant(tenantId, () -> sysPostMapper.selectCount(
                Wrappers.<SysPost>lambdaQuery()
                        .eq(SysPost::getTenantId, tenantId)
                        .eq(SysPost::getPostCode, postCode)
                        .ne(excludeId != null, SysPost::getId, excludeId)
        ));
        if (codeCount != null && codeCount > 0L) {
            throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "岗位编码【" + postCode + "】在当前租户内已存在");
        }
        Long nameCount = TenantContextHolder.runWithTenant(tenantId, () -> sysPostMapper.selectCount(
                Wrappers.<SysPost>lambdaQuery()
                        .eq(SysPost::getTenantId, tenantId)
                        .eq(SysPost::getPostName, postName)
                        .ne(excludeId != null, SysPost::getId, excludeId)
        ));
        if (nameCount != null && nameCount > 0L) {
            throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "岗位名称【" + postName + "】在当前租户内已存在");
        }
    }

    private static PostPayload normalizePostPayload(PostSaveRequest request, SysPost existing) {
        String postCode = SystemContextSupport.trimToNull(request.getPostCode());
        String postName = SystemContextSupport.trimToNull(request.getPostName());
        if (postCode == null || postCode.length() < 2 || postCode.length() > 64) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "岗位编码长度须在 2~64 个字符之间");
        }
        if (postName == null || postName.length() < 2 || postName.length() > 50) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "岗位名称长度须在 2~50 个字符之间");
        }
        Integer status = request.getStatus() != null
                ? SystemContextSupport.normalizeStatus(request.getStatus())
                : (existing != null ? existing.getStatus() : SystemContextSupport.normalizeStatus(null));
        int sort = request.getSort() != null ? request.getSort() : (existing != null && existing.getSort() != null ? existing.getSort() : 0);
        return new PostPayload(postCode, postName, sort, status, SystemContextSupport.trimToNull(request.getRemark()));
    }

    private record PostPayload(String postCode, String postName, int sort, Integer status, String remark) {
    }

    private static PostVO toVO(SysPost post) {
        return PostVO.builder()
                .id(post.getId())
                .postCode(post.getPostCode())
                .postName(post.getPostName())
                .sort(post.getSort())
                .status(post.getStatus())
                .remark(post.getRemark())
                .createTime(post.getCreateTime())
                .build();
    }
}
