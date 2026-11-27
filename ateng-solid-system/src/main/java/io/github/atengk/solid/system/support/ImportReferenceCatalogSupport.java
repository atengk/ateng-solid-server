package io.github.atengk.solid.system.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.excel.model.ExcelErrorRow;
import io.github.atengk.boot.excel.model.ExcelRowData;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysPost;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.excel.UserImportDTO;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysPostMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.support.ImportDeptPathResolver.DeptLookupIndex;
import io.github.atengk.solid.system.support.UserContactAndDictSupport.GenderDictionary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户批量导入阶段 1 的批量参考目录装载器与岗位/角色编码解析支撑（杜绝 N+1 查询）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
@RequiredArgsConstructor
public class ImportReferenceCatalogSupport {

    private final SysUserMapper sysUserMapper;
    private final SysDeptMapper sysDeptMapper;
    private final SysPostMapper sysPostMapper;
    private final SysRoleMapper sysRoleMapper;
    private final UserContactAndDictSupport userContactAndDictSupport;

    /**
     * 一次性批量装载租户下既定账号/密文手机/密文邮箱集合、部门树索引、岗位/角色编码映射与动态性别字典。
     *
     * @param tenantId   工作租户主键
     * @param rowRecords 解析后的导入数据行列表
     * @return 只读参考目录快照
     */
    public ReferenceCatalog loadCatalog(Long tenantId, List<ExcelRowData<UserImportDTO>> rowRecords) {
        LookupKeys keys = collectLookupKeys(rowRecords);
        CatalogQueryData queryData = queryCatalog(tenantId, keys);
        DeptLookupIndex deptIndex = ImportDeptPathResolver.buildIndex(queryData.allDepts());
        GenderDictionary genderDict = userContactAndDictSupport.loadGenderDictionary();
        return new ReferenceCatalog(
                queryData.existingUsernames(),
                queryData.existingPhones(),
                queryData.existingEmails(),
                deptIndex,
                queryData.postsByCode(),
                queryData.rolesByCode(),
                genderDict
        );
    }

    /**
     * 解析单行逗号分隔的岗位编码列表为有效启用态岗位主键列表。
     *
     * @param rowIndex          1-based 物理行号
     * @param rawPostCodes      原始岗位编码文本
     * @param activePostsByCode 启用岗位编码映射
     * @param errors            错误明细收集容器
     * @return 解析后的岗位主键列表
     */
    public static List<Long> resolvePostIds(
            int rowIndex,
            String rawPostCodes,
            Map<String, SysPost> activePostsByCode,
            List<ExcelErrorRow> errors
    ) {
        List<String> codes = parseCommaTokens(rawPostCodes);
        if (codes.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> postIds = new ArrayList<>(codes.size());
        for (String code : codes) {
            SysPost post = activePostsByCode.get(code);
            if (post == null) {
                errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getPostCodes,
                        rawPostCodes, "岗位编码【" + code + "】不存在或已停用"));
            } else {
                postIds.add(post.getId());
            }
        }
        return postIds;
    }

    /**
     * 解析单行逗号分隔的角色编码列表为有效启用态角色主键列表（阻断 {@code super_admin} 与超出操作人权限边界的角色分配）。
     *
     * @param rowIndex              1-based 物理行号
     * @param rawRoleCodes          原始角色编码文本
     * @param allowSuperAdminAssign 是否允许分配超级管理员角色
     * @param exceededRoleIds       权限或数据范围超出当前操作人的角色主键集合
     * @param activeRolesByCode     启用角色编码映射
     * @param errors                错误明细收集容器
     * @return 解析后的角色主键列表
     */
    public static List<Long> resolveRoleIds(
            int rowIndex,
            String rawRoleCodes,
            boolean allowSuperAdminAssign,
            Set<Long> exceededRoleIds,
            Map<String, SysRole> activeRolesByCode,
            List<ExcelErrorRow> errors
    ) {
        List<String> codes = parseCommaTokens(rawRoleCodes);
        if (codes.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> roleIds = new ArrayList<>(codes.size());
        for (String code : codes) {
            if (SolidConstants.SUPER_ADMIN_ROLE.equalsIgnoreCase(code)) {
                errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getRoleCodes,
                        rawRoleCodes, "禁止通过批量导入分配超级管理员角色 super_admin"));
                continue;
            }
            SysRole role = activeRolesByCode.get(code);
            if (role == null) {
                errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getRoleCodes,
                        rawRoleCodes, "角色编码【" + code + "】不存在或已停用"));
                continue;
            }
            if (!allowSuperAdminAssign && UserRelationSupport.isSuperAdminRole(role)) {
                errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getRoleCodes,
                        rawRoleCodes, "当前视角不允许分配平台超级管理员角色"));
                continue;
            }
            if (exceededRoleIds != null && exceededRoleIds.contains(role.getId())) {
                errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getRoleCodes,
                        rawRoleCodes, "角色【" + code + "】权限或数据范围超出当前操作人边界，禁止越权授予"));
                continue;
            }
            roleIds.add(role.getId());
        }
        return roleIds;
    }

    private static LookupKeys collectLookupKeys(List<ExcelRowData<UserImportDTO>> rowRecords) {
        Set<String> usernames = new LinkedHashSet<>(rowRecords.size());
        Set<String> plainPhones = new LinkedHashSet<>(rowRecords.size());
        Set<String> plainEmails = new LinkedHashSet<>(rowRecords.size());
        Set<String> postCodes = new LinkedHashSet<>();
        Set<String> roleCodes = new LinkedHashSet<>();
        for (ExcelRowData<UserImportDTO> record : rowRecords) {
            if (record == null || record.data() == null) {
                continue;
            }
            UserImportDTO dto = record.data();
            String username = SystemContextSupport.trimToNull(dto.getUsername());
            if (username != null) {
                usernames.add(username);
            }
            String phone = ContactFieldCrypto.normalizePhone(dto.getPhone());
            if (phone != null) {
                plainPhones.add(phone);
            }
            String email = ContactFieldCrypto.normalizeEmail(dto.getEmail());
            if (email != null) {
                plainEmails.add(email);
            }
            postCodes.addAll(parseCommaTokens(dto.getPostCodes()));
            roleCodes.addAll(parseCommaTokens(dto.getRoleCodes()));
        }
        return new LookupKeys(
                usernames,
                ContactFieldCrypto.encryptPhonesForQuery(plainPhones),
                ContactFieldCrypto.encryptEmailsForQuery(plainEmails),
                postCodes,
                roleCodes
        );
    }

    private CatalogQueryData queryCatalog(Long tenantId, LookupKeys keys) {
        Set<String> existingUsernames = new HashSet<>();
        Set<String> existingPhones = new HashSet<>();
        Set<String> existingEmails = new HashSet<>();
        Map<String, SysPost> postsByCode = new HashMap<>();
        Map<String, SysRole> rolesByCode = new HashMap<>();
        List<SysDept> allDepts = DataPermissionContextHolder.runWithoutPermission(() ->
                TenantContextHolder.runWithTenant(tenantId, () -> {
                    loadExistingUserContacts(tenantId, keys, existingUsernames, existingPhones, existingEmails);
                    loadActivePostsAndRoles(tenantId, keys, postsByCode, rolesByCode);
                    return sysDeptMapper.selectList(
                            Wrappers.<SysDept>lambdaQuery()
                                    .select(SysDept::getId, SysDept::getParentId, SysDept::getDeptName, SysDept::getStatus)
                                    .eq(SysDept::getTenantId, tenantId)
                    );
                })
        );
        return new CatalogQueryData(existingUsernames, existingPhones, existingEmails, postsByCode, rolesByCode, allDepts);
    }

    private void loadExistingUserContacts(
            Long tenantId,
            LookupKeys keys,
            Set<String> existingUsernames,
            Set<String> existingPhones,
            Set<String> existingEmails
    ) {
        if (!keys.usernames().isEmpty()) {
            existingUsernames.addAll(SystemContextSupport.safeList(
                    sysUserMapper.selectExistingUsernamesInTenant(tenantId, keys.usernames())
            ));
        }
        if (!keys.encryptedPhones().isEmpty()) {
            existingPhones.addAll(SystemContextSupport.safeList(
                    sysUserMapper.selectExistingPhonesInTenant(tenantId, keys.encryptedPhones())
            ));
        }
        if (!keys.encryptedEmails().isEmpty()) {
            existingEmails.addAll(SystemContextSupport.safeList(
                    sysUserMapper.selectExistingEmailsInTenant(tenantId, keys.encryptedEmails())
            ));
        }
    }

    private void loadActivePostsAndRoles(
            Long tenantId,
            LookupKeys keys,
            Map<String, SysPost> postsByCode,
            Map<String, SysRole> rolesByCode
    ) {
        if (!keys.postCodes().isEmpty()) {
            List<SysPost> posts = sysPostMapper.selectList(
                    Wrappers.<SysPost>lambdaQuery()
                            .eq(SysPost::getTenantId, tenantId)
                            .eq(SysPost::getStatus, CommonStatusEnum.NORMAL.getCode())
                            .in(SysPost::getPostCode, keys.postCodes())
            );
            for (SysPost post : SystemContextSupport.safeList(posts)) {
                postsByCode.put(post.getPostCode(), post);
            }
        }
        if (!keys.roleCodes().isEmpty()) {
            List<SysRole> roles = sysRoleMapper.selectList(
                    Wrappers.<SysRole>lambdaQuery()
                            .eq(SysRole::getTenantId, tenantId)
                            .eq(SysRole::getStatus, CommonStatusEnum.NORMAL.getCode())
                            .in(SysRole::getRoleKey, keys.roleCodes())
            );
            for (SysRole role : SystemContextSupport.safeList(roles)) {
                rolesByCode.put(role.getRoleKey(), role);
            }
        }
    }

    private static List<String> parseCommaTokens(String raw) {
        String trimmed = SystemContextSupport.trimToNull(raw);
        if (trimmed == null) {
            return Collections.emptyList();
        }
        String[] parts = trimmed.split(",");
        Set<String> tokens = new LinkedHashSet<>(parts.length);
        for (String part : parts) {
            String token = SystemContextSupport.trimToNull(part);
            if (token != null) {
                tokens.add(token);
            }
        }
        return tokens.isEmpty() ? Collections.emptyList() : List.copyOf(tokens);
    }

    private record LookupKeys(
            Set<String> usernames,
            List<String> encryptedPhones,
            List<String> encryptedEmails,
            Set<String> postCodes,
            Set<String> roleCodes
    ) {
    }

    private record CatalogQueryData(
            Set<String> existingUsernames,
            Set<String> existingPhones,
            Set<String> existingEmails,
            Map<String, SysPost> postsByCode,
            Map<String, SysRole> rolesByCode,
            List<SysDept> allDepts
    ) {
    }

    /**
     * 阶段 1 批量校验参考目录只读快照。
     *
     * @param existingUsernames       库内既有登录账号集合
     * @param existingEncryptedPhones 库内既有确定性加密手机号集合
     * @param existingEncryptedEmails 库内既有确定性加密邮箱集合
     * @param deptIndex               部门名称与层级路径索引
     * @param activePostsByCode       启用岗位编码映射
     * @param activeRolesByCode       启用角色编码映射
     * @param genderDict              动态性别字典映射
     */
    public record ReferenceCatalog(
            Set<String> existingUsernames,
            Set<String> existingEncryptedPhones,
            Set<String> existingEncryptedEmails,
            DeptLookupIndex deptIndex,
            Map<String, SysPost> activePostsByCode,
            Map<String, SysRole> activeRolesByCode,
            GenderDictionary genderDict
    ) {
    }
}
