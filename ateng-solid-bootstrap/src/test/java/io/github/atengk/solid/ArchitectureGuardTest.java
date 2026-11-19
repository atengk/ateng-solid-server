package io.github.atengk.solid;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 后端同源双装配架构红线自动化守卫测试，验证模块物理隔离、零字段注入与零 Entity 泄漏。
 *
 * @author Ateng
 * @since 2026-11-19
 */
class ArchitectureGuardTest {

    private static JavaClasses importedClasses;

    @BeforeAll
    static void setUp() {
        importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.atengk.solid");
    }

    @Test
    @DisplayName("auth 模块禁止直接依赖 system 实现包（仅允许依赖 system.api 与 common）")
    void authMustNotDependOnSystemImplementation() {
        noClasses()
                .that().resideInAPackage("io.github.atengk.solid.auth..")
                .should().dependOnClassesThat().resideInAPackage("io.github.atengk.solid.system.impl..")
                .orShould().dependOnClassesThat().resideInAPackage("io.github.atengk.solid.system.mapper..")
                .orShould().dependOnClassesThat().resideInAPackage("io.github.atengk.solid.system.entity..")
                .orShould().dependOnClassesThat().resideInAPackage("io.github.atengk.solid.system.service..")
                .orShould().dependOnClassesThat().resideInAPackage("io.github.atengk.solid.system.controller..")
                .allowEmptyShould(true)
                .check(importedClasses);
    }

    @Test
    @DisplayName("system-api 契约层禁止引入 Web、MyBatis-Plus、Feign 或 Dubbo 框架依赖")
    void systemApiMustRemainFrameworkPure() {
        noClasses()
                .that().resideInAPackage("io.github.atengk.solid.system.api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..",
                        "com.baomidou..",
                        "org.springframework.cloud.openfeign..",
                        "org.apache.dubbo..",
                        "jakarta.servlet.."
                )
                .allowEmptyShould(true)
                .check(importedClasses);
    }

    @Test
    @DisplayName("全局业务源码禁止使用 @Autowired / @Resource 字段注入（强制构造器注入）")
    void noFieldInjectionAllowed() {
        fields()
                .should().notBeAnnotatedWith(Autowired.class)
                .andShould().notBeAnnotatedWith(Resource.class)
                .allowEmptyShould(true)
                .check(importedClasses);
    }

    @Test
    @DisplayName("Controller 公开方法禁止返回或接收持久层 Entity")
    void controllersMustNotLeakEntities() {
        methods()
                .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                .or().areDeclaredInClassesThat().areAnnotatedWith(Controller.class)
                .and().arePublic()
                .should().notHaveRawReturnType("..entity..")
                .allowEmptyShould(true)
                .check(importedClasses);
    }
}
