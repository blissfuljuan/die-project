package com.blissfuljuan.dieapi.auth.model.rule;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThrottlePolicyTest {

    private final ThrottlePolicy policy = new ThrottlePolicy(5, 20, 15, 30);

    @Test
    void allowsBelowAllLimits() {
        assertThat(policy.blocks(0, 0, 0)).isFalse();
        assertThat(policy.blocks(4, 29, 19)).isFalse();
    }

    @Test
    void blocksAtTheIdentifierLimitForThatAddress() {
        assertThat(policy.blocks(5, 5, 0)).isTrue();
        assertThat(policy.blocks(6, 6, 0)).isTrue();
    }

    @Test
    void failuresFromOtherAddressesDoNotBlockUntilTheGlobalCap() {
        // A stranger's 25 failures against the identifier must not lock out a caller with none of their own.
        assertThat(policy.blocks(0, 25, 0)).isFalse();
        assertThat(policy.blocks(0, 30, 0)).isTrue();
    }

    @Test
    void blocksAtTheAddressLimit() {
        assertThat(policy.blocks(0, 0, 20)).isTrue();
        assertThat(policy.blocks(0, 0, 21)).isTrue();
    }

    @Test
    void anyOneLimitIsEnough() {
        assertThat(policy.blocks(5, 5, 20)).isTrue();
        assertThat(policy.blocks(4, 4, 20)).isTrue();
        assertThat(policy.blocks(5, 5, 19)).isTrue();
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
