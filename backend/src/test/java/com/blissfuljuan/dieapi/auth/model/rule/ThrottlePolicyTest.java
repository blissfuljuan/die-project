package com.blissfuljuan.dieapi.auth.model.rule;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThrottlePolicyTest {

    private final ThrottlePolicy policy = new ThrottlePolicy(5, 20, 15);

    @Test
    void allowsBelowBothLimits() {
        assertThat(policy.blocks(0, 0)).isFalse();
        assertThat(policy.blocks(4, 19)).isFalse();
    }

    @Test
    void blocksAtTheIdentifierLimit() {
        assertThat(policy.blocks(5, 0)).isTrue();
        assertThat(policy.blocks(6, 0)).isTrue();
    }

    @Test
    void blocksAtTheAddressLimit() {
        assertThat(policy.blocks(0, 20)).isTrue();
        assertThat(policy.blocks(0, 21)).isTrue();
    }

    @Test
    void eitherLimitAloneIsEnough() {
        assertThat(policy.blocks(5, 20)).isTrue();
        assertThat(policy.blocks(4, 20)).isTrue();
        assertThat(policy.blocks(5, 19)).isTrue();
    }

    @Test
    void exposesTheWindow() {
        assertThat(policy.windowMinutes()).isEqualTo(15);
    }

    @Test
    void resetKeysCarryTheResetBucketPrefix() {
        assertThat(ThrottleBucket.resetKey("a@b.c")).isEqualTo("pwreset:a@b.c");
    }
}
