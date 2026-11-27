package com.greenart7c3.citrine.tools.detekt

import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

/** The "citrine" rule set; detekt finds it through META-INF/services. */
@Suppress("unused")
class CitrineRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("citrine")

    override fun instance() = RuleSet(ruleSetId, mapOf(RuleName("NoComments") to ::NoComments))
}
