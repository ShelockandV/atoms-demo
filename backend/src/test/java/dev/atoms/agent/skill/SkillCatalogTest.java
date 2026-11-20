package dev.atoms.agent.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 Shannon 格式的技能加载，例如按名称选取最新启用版本。 */
class SkillCatalogTest {
    @TempDir Path directory;

    @Test void resolvesLatestEnabledVersionAndExplicitVersion() throws Exception {
        Files.writeString(directory.resolve("README.md"), "# Skills");
        Files.writeString(directory.resolve("original.md"), skill(""));
        Files.writeString(directory.resolve("minor.md"), skill("version: 1.9.0\n"));
        Files.writeString(directory.resolve("current.md"), skill("version: 1.10.0\nbudget_max: 5000\n"));
        Files.writeString(directory.resolve("disabled.md"), skill("version: 2.0.0\nenabled: false\n"));

        var catalog = new SkillCatalog(directory.toString());
        assertEquals(List.of("1.10.0", "1.9.0", "1.0.0"),
                catalog.available().stream().map(Skill::version).toList());
        assertEquals(5000L, catalog.resolve("review").budgetMax());
        assertEquals("检查需求。", catalog.resolve("review@1.0.0").content());
        assertThrows(IllegalArgumentException.class, () -> catalog.resolve("review@2.0.0"));
    }

    @Test void rejectsDuplicateVersions() throws Exception {
        Files.writeString(directory.resolve("first.md"), skill(""));
        Files.writeString(directory.resolve("second.md"), skill("version: 1.0.0\n"));
        assertThrows(IllegalArgumentException.class, () -> new SkillCatalog(directory.toString()));
    }

    private static String skill(String fields) {
        return "---\nname: review\n" + fields + "---\n检查需求。\n";
    }
}
