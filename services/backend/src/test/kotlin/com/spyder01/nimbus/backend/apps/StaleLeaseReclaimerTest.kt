package com.spyder01.nimbus.backend.apps

import com.spyder01.nimbus.backend.apps.services.StaleLeaseReclaimer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import kotlin.test.assertEquals

// The tests switch the reclaimer off (src/test/resources/application.properties) so they decide when stale tasks are
// handled; it is on in a normal run, and these check both.
class StaleLeaseReclaimerTest {
    @SpringBootTest(properties = ["nimbus.deployments.reclaim-enabled=true"])
    class Enabled(@Autowired private val ctx: ApplicationContext) {
        @Test
        fun `it runs by default`() = assertEquals(1, ctx.getBeansOfType(StaleLeaseReclaimer::class.java).size)
    }

    @SpringBootTest
    class DisabledInTests(@Autowired private val ctx: ApplicationContext) {
        @Test
        fun `the tests switch it off`() = assertEquals(0, ctx.getBeansOfType(StaleLeaseReclaimer::class.java).size)
    }
}
