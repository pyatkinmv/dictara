package com.dictara.gateway

import com.dictara.gateway.config.DictaraProperties
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * TODO(remove-restore-only): delete this class with the mode.
 *
 * A reminder that fails the build once the temporary restore-only mode has outlived its time. It was added to bring the
 * old audio back from Telegram and is meant to go; too many such "temporary" things stayed. Do not just move the date
 * without a reason: remove the mode (search the repository for `remove-restore-only`, the list is in
 * docs/ops/runbook.md, "Restore-only mode") or, if it is still needed, say why in the commit and extend the date.
 */
class RestoreOnlyExpiryTest {

    private val removeBy = LocalDate.of(2026, 12, 1)

    @Test
    fun `the temporary restore-only mode is removed by the set date`() {
        assertTrue(
            LocalDate.now().isBefore(removeBy),
            "The temporary RESTORE_ONLY mode should have been removed by $removeBy. Remove it " +
                "(grep -r 'remove-restore-only', the checklist is in docs/ops/runbook.md) or extend this date with a reason.",
        )
    }

    @Test
    fun `the mode is off unless it is switched on`() {
        assertFalse(DictaraProperties().restoreOnly)
    }
}
