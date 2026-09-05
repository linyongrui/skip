package com.example.skip

import com.example.skip.data.RuleAction
import com.example.skip.data.SkipRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleModelsTest {
    @Test fun ruleNeedsAPrimarySelector() {
        assertEquals("Set a view ID, text, or content description", SkipRule(packageName = "com.example.target").validate())
    }

    @Test fun validRulePassesValidation() {
        assertNull(SkipRule(packageName = "com.example.target", text = "Skip", action = RuleAction.CLICK).validate())
    }
}
