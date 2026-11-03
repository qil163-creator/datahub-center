package cn.iocoder.yudao.framework.dbswitch.data.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationWriteModesTest {

    @Test
    void legacyDefaultsKeepTruncateAndDrop() {
        assertThat(MigrationWriteModes.allowTruncate(Boolean.TRUE, null)).isTrue();
        assertThat(MigrationWriteModes.allowDrop(Boolean.TRUE, "")).isTrue();
        assertThat(MigrationWriteModes.failIfTargetExists(null)).isFalse();
        assertThat(MigrationWriteModes.failOnFieldReadError(null, Boolean.FALSE)).isTrue();
        assertThat(MigrationWriteModes.failOnFieldReadError(null, null)).isTrue();
    }

    @Test
    void createNewNeverTruncatesOrDropsAndFailsIfTargetExists() {
        assertThat(MigrationWriteModes.allowTruncate(Boolean.TRUE, MigrationWriteModes.CREATE_NEW)).isFalse();
        assertThat(MigrationWriteModes.allowDrop(Boolean.TRUE, MigrationWriteModes.CREATE_NEW)).isFalse();
        assertThat(MigrationWriteModes.failIfTargetExists(MigrationWriteModes.CREATE_NEW)).isTrue();
        assertThat(MigrationWriteModes.failOnMissingSource(MigrationWriteModes.CREATE_NEW)).isTrue();
        assertThat(MigrationWriteModes.failOnFieldReadError(MigrationWriteModes.CREATE_NEW, Boolean.FALSE)).isTrue();
    }

    @Test
    void strictCopyFailsOnFieldReadAndMissingColumns() {
        assertThat(MigrationWriteModes.failOnFieldReadError(MigrationWriteModes.STRICT_COPY, Boolean.FALSE)).isTrue();
        assertThat(MigrationWriteModes.failOnUnmatchedColumns(MigrationWriteModes.STRICT_COPY)).isTrue();
        assertThat(MigrationWriteModes.allowTruncate(Boolean.TRUE, MigrationWriteModes.STRICT_COPY)).isFalse();
    }

}
