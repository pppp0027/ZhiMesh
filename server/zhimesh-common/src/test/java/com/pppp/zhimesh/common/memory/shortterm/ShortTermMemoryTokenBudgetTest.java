package com.pppp.zhimesh.common.memory.shortterm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermMemoryTokenBudgetTest {

    @Test
    void capsHistoryIndependentlyFromLargeModelWindow() {
        ShortTermMemoryTokenBudget budget = ShortTermMemoryTokenBudget.calculate(
                32_000, 8_000, 2_000, 1_000, 4_000, 0.10D);

        assertThat(budget.historyTokens()).isEqualTo(8_000);
        assertThat(budget.messageWindowTokens()).isEqualTo(10_000);
        assertThat(budget.overcommitted()).isFalse();
    }

    @Test
    void shrinksHistoryWhenFixedInputAndReservesNeedMoreSpace() {
        ShortTermMemoryTokenBudget budget = ShortTermMemoryTokenBudget.calculate(
                10_000, 8_000, 3_000, 1_000, 2_000, 0.10D);

        assertThat(budget.historyTokens()).isEqualTo(3_000);
        assertThat(budget.messageWindowTokens()).isEqualTo(6_000);
        assertThat(budget.overcommitted()).isFalse();
    }

    @Test
    void reportsWhenCurrentInputAlreadyConsumesReservedBudget() {
        ShortTermMemoryTokenBudget budget = ShortTermMemoryTokenBudget.calculate(
                8_000, 4_000, 7_000, 1_000, 2_000, 0.10D);

        assertThat(budget.historyTokens()).isZero();
        assertThat(budget.messageWindowTokens()).isEqualTo(7_000);
        assertThat(budget.overcommitted()).isTrue();
    }
}
