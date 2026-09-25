package com.terrydemo.skip

import com.terrydemo.skip.data.RuleAction
import com.terrydemo.skip.data.SkipRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleModelsTest {
    @Test fun ruleNeedsAPrimarySelector() {
        assertEquals("Set a view ID, text, or content description", SkipRule(packageName = "com.terrydemo.target").validate())
    }

    @Test fun validRulePassesValidation() {
        assertNull(SkipRule(packageName = "com.terrydemo.target", text = "Skip", action = RuleAction.CLICK).validate())
    }
}
