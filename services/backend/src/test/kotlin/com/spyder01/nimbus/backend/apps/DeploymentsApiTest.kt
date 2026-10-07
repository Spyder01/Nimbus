package com.spyder01.nimbus.backend.apps

import com.spyder01.nimbus.backend.apps.entities.DeploymentState
import com.spyder01.nimbus.backend.apps.entities.DeploymentTask
import com.spyder01.nimbus.backend.apps.entities.TaskState
import com.spyder01.nimbus.backend.apps.repositories.DeploymentTaskRepository
import com.spyder01.nimbus.backend.apps.repositories.DeploymentRepository
import com.spyder01.nimbus.backend.apps.services.DeploymentWorkerService
import com.spyder01.nimbus.backend.users.entities.User
import com.spyder01.nimbus.backend.users.repositories.UserRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MutableClock(private var now: Instant = Instant.parse("2030-01-01T00:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
    fun advance(d: Duration) { now = now.plus(d) }
}

@TestConfiguration
class TestClockConfig {
    @Bean
    @Primary
    fun testClock() = MutableClock()
}

// max-attempts=2 so the retry limit is easy to hit. Everything rolls back (see AppsApiTest).
@SpringBootTest(properties = ["nimbus.deployments.max-attempts=2"])
@AutoConfigureMockMvc
@Transactional
@Import(TestClockConfig::class)
class DeploymentsApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val em: EntityManager,
    @Autowired private val json: JsonMapper,
    @Autowired private val worker: DeploymentWorkerService,
    @Autowired private val deployments: DeploymentRepository,
    @Autowired private val taskRepo: DeploymentTaskRepository,
    @Autowired private val clock: MutableClock,
) {
    private lateinit var alice: User
    private lateinit var bob: User

    // Created in @BeforeEach, which runs inside the test's transaction, so these rows are rolled back with it.
    // (As property initializers they ran before the transaction began and stayed in the database for good.)
    @BeforeEach
    fun createUsers() {
        alice = users.saveAndFlush(User(name = "alice"))
        bob = users.saveAndFlush(User(name = "bob"))
    }

    private fun call(user: User, req: MockHttpServletRequestBuilder, body: Any? = null): ResultActions {
        em.flush()
        em.clear()
        val r = req.with(oauth2Login().attributes { it["userId"] = user.id.toString() }).with(csrf())
        if (body != null) r.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))
        return mvc.perform(r)
    }

    private fun idOf(result: ResultActions) = json.readTree(result.andReturn().response.contentAsString).get("id").asString()

    private fun node(id: String, name: String = id, image: String = "nginx:1.27") =
        mapOf("id" to id, "type" to "container", "position" to mapOf("x" to 0, "y" to 0), "data" to mapOf("name" to name, "image" to image))

    private fun edge(source: String, target: String) = mapOf("id" to "$source-$target", "source" to source, "target" to target)

    /** An app whose draft has these containers (id = name) and "a needs b" edges, complete enough to deploy. */
    private fun appWith(names: List<String>, vararg needs: Pair<String, String>, user: User = alice, name: String = "shop"): String {
        val id = idOf(call(user, post("/api/apps"), mapOf("name" to name)).andExpect(status().isCreated))
        call(user, put("/api/apps/$id/draft"),
            mapOf("nodes" to names.map { node(it) }, "edges" to needs.map { edge(it.first, it.second) }, "baseRevision" to 0)).andExpect(status().isOk)
        return id
    }

    /** web needs api needs db. Deploys db, then api, then web. */
    private fun chain(user: User = alice, name: String = "shop") = appWith(listOf("web", "api", "db"), "web" to "api", "api" to "db", user = user, name = name)

    private fun deploy(app: String, user: User = alice) = call(user, post("/api/apps/$app/deployments"))
    private fun deployOk(app: String) = idOf(deploy(app).andExpect(status().isCreated))
    private fun appState(app: String) = json.readTree(call(alice, get("/api/apps/$app")).andReturn().response.contentAsString).get("state").asString()
    private fun cancel(app: String, dep: String) = call(alice, post("/api/apps/$app/deployments/$dep/cancel"))

    private fun tasks(dep: String): List<DeploymentTask> = run {
        em.flush(); em.clear()
        taskRepo.findAllByDeploymentIdOrderByOrdinalAsc(UUID.fromString(dep))
    }
    private fun task(dep: String, name: String) = tasks(dep).single { it.name == name }
    private fun states(dep: String) = tasks(dep).associate { it.name to it.state }
    private fun depState(dep: String) = run { em.flush(); em.clear(); deployments.findById(UUID.fromString(dep)).orElseThrow() }
    private fun claim(dep: String, name: String, worker: String = "w1") = this.worker.claim(task(dep, name).id!!, worker)
    private fun finishTask(dep: String, name: String, worker: String = "w1") { claim(dep, name, worker); assertTrue(this.worker.complete(task(dep, name).id!!, worker)) }

    // ---- requesting a deployment ----

    @Test
    fun `an empty or incomplete graph can't be deployed`() {
        val empty = idOf(call(alice, post("/api/apps"), mapOf("name" to "empty")).andExpect(status().isCreated))
        deploy(empty).andExpect(status().`is`(422)).andExpect(jsonPath("$.error").value("nothing_to_deploy"))

        val app = idOf(call(alice, post("/api/apps"), mapOf("name" to "half")).andExpect(status().isCreated))
        call(alice, put("/api/apps/$app/draft"), mapOf("nodes" to listOf(node("a", name = "Bad Name", image = "")), "edges" to emptyList<Any>(), "baseRevision" to 0))
        deploy(app).andExpect(status().`is`(422))
            .andExpect(jsonPath("$.error").value("graph_incomplete"))
            .andExpect(jsonPath("$.problems.length()").value(2))
        call(alice, get("/api/apps/$app/deployments")).andExpect(jsonPath("$.deployments.length()").value(0))
        assertEquals("DRAFT", appState(app))
    }

    @Test
    fun `a circular dependency is flagged on the containers involved and blocks the deploy`() {
        val app = appWith(listOf("web", "api", "db"), "web" to "api", "api" to "web", "api" to "db")

        // visible while editing: the draft reports a problem on each container in the loop, not on db
        val draft = json.readTree(call(alice, get("/api/apps/$app")).andReturn().response.contentAsString).get("draft").get("problems")
        val flagged = HashSet<String>()
        for (p in draft) {
            flagged += p.get("nodeId").asString()
            assertEquals("connections", p.get("field").asString())
            assertTrue(p.get("message").asString().startsWith("Circular dependency: "))
        }
        assertEquals(setOf("web", "api"), flagged)

        deploy(app).andExpect(status().`is`(422))
            .andExpect(jsonPath("$.error").value("graph_incomplete"))
            .andExpect(jsonPath("$.problems[0].message").value("Circular dependency: api → web → api"))
        call(alice, get("/api/apps/$app/deployments")).andExpect(jsonPath("$.deployments.length()").value(0))
        assertEquals("DRAFT", appState(app))
    }

    @Test
    fun `deploy plans one task per container in dependency order`() {
        val app = chain()
        val dep = deployOk(app)

        val planned = tasks(dep)
        assertEquals(listOf("db", "api", "web"), planned.map { it.name })
        assertEquals(listOf(0, 1, 2), planned.map { it.layer })
        assertEquals(listOf(0, 1, 2), planned.map { it.ordinal })
        assertEquals(listOf(TaskState.QUEUED, TaskState.PENDING, TaskState.PENDING), planned.map { it.state })
        assertEquals(listOf<List<UUID>>(emptyList(), listOf(planned[0].id!!), listOf(planned[1].id!!)), planned.map { it.dependsOn })
        assertEquals("nginx:1.27", planned[0].spec.image)

        call(alice, get("/api/apps/$app/deployments/$dep")).andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("QUEUED"))
            .andExpect(jsonPath("$.tasksTotal").value(3)).andExpect(jsonPath("$.tasksSucceeded").value(0))
            .andExpect(jsonPath("$.tasks.length()").value(3))
            .andExpect(jsonPath("$.tasks[0].name").value("db")).andExpect(jsonPath("$.tasks[0].state").value("QUEUED"))
            .andExpect(jsonPath("$.tasks[2].dependsOn[0]").value("api"))
        call(alice, get("/api/apps")).andExpect(jsonPath("$[0].state").value("DEPLOYING"))
            .andExpect(jsonPath("$[0].activeDeployment.tasksTotal").value(3)).andExpect(jsonPath("$[0].activeDeployment.tasks.length()").value(3))
    }

    @Test
    fun `deploy pins a saved version, reusing the latest when the draft is unchanged`() {
        val app = chain()
        deploy(app).andExpect(status().isCreated).andExpect(jsonPath("$.versionRevision").value(1))
        call(alice, get("/api/apps/$app/versions")).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].note").value("Deployed"))

        val other = chain(name = "other")
        call(alice, post("/api/apps/$other/versions"), mapOf("note" to "mine")).andExpect(status().isCreated)
        deploy(other).andExpect(status().isCreated).andExpect(jsonPath("$.versionRevision").value(1))
        call(alice, get("/api/apps/$other/versions")).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].note").value("mine"))
    }

    @Test
    fun `only one deployment can be active per app`() {
        val app = chain()
        val first = deployOk(app)
        deploy(app).andExpect(status().isConflict).andExpect(jsonPath("$.error").value("deployment_active"))
        cancel(app, first).andExpect(status().isOk)
        deploy(app).andExpect(status().isCreated)
    }

    // ---- running tasks ----

    @Test
    fun `only ready tasks can be claimed, so dependencies always go first`() {
        val dep = deployOk(chain())
        assertNull(claim(dep, "api"), "api waits for db")
        assertNull(claim(dep, "web"), "web waits for api")
        val first = assertNotNull(worker.claimNext("w1"))
        // other queued rows outside this test's transaction can't be seen, so the oldest ready one is ours
        assertEquals("db", first.name)
        assertNull(claim(dep, "db", "w2"), "already leased")
    }

    @Test
    fun `finishing a task makes the tasks that needed it ready, and the last one finishes the deployment`() {
        val app = chain()
        val dep = deployOk(app)
        assertEquals(DeploymentState.QUEUED, depState(dep).state)

        claim(dep, "db")
        assertEquals(DeploymentState.IN_PROGRESS, depState(dep).state)
        assertNotNull(depState(dep).startedAt)
        assertEquals("DEPLOYING", appState(app))
        assertTrue(worker.complete(task(dep, "db").id!!, "w1"))
        assertEquals(mapOf("db" to TaskState.SUCCEEDED, "api" to TaskState.QUEUED, "web" to TaskState.PENDING), states(dep))

        finishTask(dep, "api")
        assertEquals(mapOf("db" to TaskState.SUCCEEDED, "api" to TaskState.SUCCEEDED, "web" to TaskState.QUEUED), states(dep))
        call(alice, get("/api/apps/$app/deployments/$dep")).andExpect(jsonPath("$.state").value("IN_PROGRESS")).andExpect(jsonPath("$.tasksSucceeded").value(2))

        finishTask(dep, "web")
        val done = depState(dep)
        assertEquals(DeploymentState.SUCCEEDED, done.state)
        assertNotNull(done.finishedAt)
        assertEquals("RUNNING", appState(app))
        call(alice, get("/api/apps/$app/deployments/$dep")).andExpect(jsonPath("$.tasksSucceeded").value(3))
    }

    @Test
    fun `independent containers run in parallel and a container waits for all of its dependencies`() {
        val app = appWith(listOf("web", "cache", "db"), "web" to "cache", "web" to "db")
        val dep = deployOk(app)
        assertEquals(mapOf("cache" to TaskState.QUEUED, "db" to TaskState.QUEUED, "web" to TaskState.PENDING), states(dep))

        assertNotNull(claim(dep, "cache", "w1"))
        assertNotNull(claim(dep, "db", "w2"))
        assertTrue(worker.complete(task(dep, "cache").id!!, "w1"))
        assertEquals(TaskState.PENDING, states(dep)["web"], "db is still running")
        assertTrue(worker.complete(task(dep, "db").id!!, "w2"))
        assertEquals(TaskState.QUEUED, states(dep)["web"])
    }

    // ---- failure ----

    @Test
    fun `a failed task abandons the rest, running tasks stop, and the deployment fails`() {
        val app = appWith(listOf("web", "api", "cache", "db"), "web" to "api", "api" to "db", "api" to "cache")
        val dep = deployOk(app)
        claim(dep, "db", "w1")
        claim(dep, "cache", "w2")

        assertTrue(worker.fail(task(dep, "cache").id!!, "w2", "image pull failed"))
        assertEquals(mapOf("cache" to TaskState.FAILED, "db" to TaskState.IN_PROGRESS, "api" to TaskState.CANCELLED, "web" to TaskState.CANCELLED), states(dep))
        assertEquals(DeploymentState.IN_PROGRESS, depState(dep).state, "db is still running")

        val beat = worker.heartbeat(task(dep, "db").id!!, "w1")
        assertTrue(beat.holdsLease && beat.stopRequested)
        assertTrue(worker.acknowledgeStop(task(dep, "db").id!!, "w1"))

        val failed = depState(dep)
        assertEquals(DeploymentState.FAILED, failed.state)
        assertEquals("Container cache failed: image pull failed", failed.error)
        assertEquals("FAILED", appState(app))
        deploy(app).andExpect(status().isCreated) // a failed deployment doesn't block a new one
    }

    @Test
    fun `a failure with nothing else running fails the deployment at once`() {
        val app = chain()
        val dep = deployOk(app)
        claim(dep, "db")
        assertTrue(worker.fail(task(dep, "db").id!!, "w1", "boom"))
        assertEquals(DeploymentState.FAILED, depState(dep).state)
        assertEquals(mapOf("db" to TaskState.FAILED, "api" to TaskState.CANCELLED, "web" to TaskState.CANCELLED), states(dep))
    }

    // ---- cancelling ----

    @Test
    fun `cancelling before any task started cancels everything at once`() {
        val app = chain()
        val dep = deployOk(app)
        cancel(app, dep).andExpect(status().isOk).andExpect(jsonPath("$.state").value("CANCELLED"))
        assertTrue(states(dep).values.all { it == TaskState.CANCELLED })
        assertEquals("DRAFT", appState(app))
        assertEquals("Cancelled before it started", depState(dep).error)
        cancel(app, dep).andExpect(status().isConflict).andExpect(jsonPath("$.error").value("not_cancellable"))
    }

    @Test
    fun `cancelling a running deployment is cooperative`() {
        val app = chain()
        val dep = deployOk(app)
        claim(dep, "db")

        cancel(app, dep).andExpect(status().isOk).andExpect(jsonPath("$.state").value("IN_PROGRESS")).andExpect(jsonPath("$.cancelRequested").value(true))
        assertEquals(mapOf("db" to TaskState.IN_PROGRESS, "api" to TaskState.CANCELLED, "web" to TaskState.CANCELLED), states(dep))
        assertEquals("DEPLOYING", appState(app))

        assertTrue(worker.heartbeat(task(dep, "db").id!!, "w1").stopRequested)
        assertTrue(worker.acknowledgeStop(task(dep, "db").id!!, "w1"))
        assertEquals(DeploymentState.CANCELLED, depState(dep).state)
        assertEquals("DRAFT", appState(app))
        cancel(app, dep).andExpect(status().isConflict)
    }

    @Test
    fun `cancelling between tasks finishes at once because nothing is running`() {
        val app = chain()
        val dep = deployOk(app)
        finishTask(dep, "db") // api is ready but not started
        cancel(app, dep).andExpect(status().isOk).andExpect(jsonPath("$.state").value("CANCELLED"))
        assertEquals(mapOf("db" to TaskState.SUCCEEDED, "api" to TaskState.CANCELLED, "web" to TaskState.CANCELLED), states(dep))
    }

    @Test
    fun `a running task that finishes after a cancel does not restart the deployment`() {
        val app = chain()
        val dep = deployOk(app)
        claim(dep, "db")
        cancel(app, dep).andExpect(status().isOk)
        assertTrue(worker.complete(task(dep, "db").id!!, "w1")) // it was already done
        assertEquals(DeploymentState.CANCELLED, depState(dep).state)
        assertEquals(TaskState.CANCELLED, states(dep)["api"], "dependents are not promoted once stopping")
    }

    @Test
    fun `after an earlier success a cancelled deployment leaves the app running`() {
        val app = appWith(listOf("web"))
        val first = deployOk(app)
        finishTask(first, "web")
        assertEquals("RUNNING", appState(app))

        call(alice, put("/api/apps/$app/draft"), mapOf("nodes" to listOf(node("web"), node("api")), "edges" to emptyList<Any>(), "baseRevision" to 1))
        cancel(app, deployOk(app)).andExpect(status().isOk)
        assertEquals("RUNNING", appState(app))
    }

    // ---- leases (per task) ----

    @Test
    fun `claiming sets a fixed expiry and heartbeats only refresh the updated time`() {
        val app = chain()
        val dep = deployOk(app)
        val claimed = assertNotNull(claim(dep, "db"))
        val acquired = assertNotNull(claimed.leaseAcquiredAt)
        val expires = assertNotNull(claimed.leaseExpiresAt)
        assertEquals(Duration.ofMinutes(30), Duration.between(acquired, expires))
        assertEquals(1, claimed.attempts)
        assertEquals(TaskState.IN_PROGRESS, claimed.state)

        val id = claimed.id!!
        clock.advance(Duration.ofMinutes(1))
        assertTrue(worker.heartbeat(id, "w1").holdsLease)
        val after = task(dep, "db")
        assertEquals(expires, after.leaseExpiresAt)
        assertEquals(acquired.plus(Duration.ofMinutes(1)), after.leaseUpdatedAt)

        assertFalse(worker.heartbeat(id, "w2").holdsLease, "only the holder may heartbeat")
        assertFalse(worker.complete(id, "w2"))
        assertFalse(worker.fail(id, "w2", "x"))
    }

    @Test
    fun `leases belong to tasks, so two tasks of one deployment are leased independently`() {
        val app = appWith(listOf("a", "b"))
        val dep = deployOk(app)
        val a = assertNotNull(claim(dep, "a", "w1"))
        val b = assertNotNull(claim(dep, "b", "w2"))
        assertEquals("w1", a.leaseOwner); assertEquals("w2", b.leaseOwner)
        assertFalse(worker.complete(a.id!!, "w2"))
        assertTrue(worker.complete(a.id!!, "w1"))
        assertEquals(DeploymentState.IN_PROGRESS, depState(dep).state, "b is still running")
        assertTrue(worker.complete(b.id!!, "w2"))
        assertEquals(DeploymentState.SUCCEEDED, depState(dep).state)
    }

    @Test
    fun `a task's lease ends at the fixed expiry even though heartbeats are fresh`() {
        val dep = deployOk(chain())
        val id = claim(dep, "db")!!.id!!
        repeat(29) { clock.advance(Duration.ofMinutes(1)); assertTrue(worker.heartbeat(id, "w1").holdsLease) } // t = 29 min

        clock.advance(Duration.ofMinutes(1)) // t = 30 min: the last heartbeat is a minute old, but the lease is over
        assertFalse(worker.heartbeat(id, "w1").holdsLease)
        assertFalse(worker.complete(id, "w1"))
        assertEquals(1, worker.reclaimStale())
        assertEquals(TaskState.QUEUED, task(dep, "db").state)
    }

    @Test
    fun `a silent worker's task is queued again up to the limit, then it fails the deployment`() {
        val app = chain()
        val dep = deployOk(app)

        claim(dep, "db", "w1")
        clock.advance(Duration.ofMinutes(1))
        assertEquals(0, worker.reclaimStale(), "a recent heartbeat is not stale")
        clock.advance(Duration.ofMinutes(3))
        assertEquals(1, worker.reclaimStale())
        task(dep, "db").let {
            assertEquals(TaskState.QUEUED, it.state)
            assertEquals(1, it.attempts)
            assertNull(it.leaseOwner); assertNull(it.leaseExpiresAt)
        }
        assertEquals(DeploymentState.IN_PROGRESS, depState(dep).state)

        claim(dep, "db", "w2")
        clock.advance(Duration.ofMinutes(3))
        assertEquals(1, worker.reclaimStale())
        task(dep, "db").let {
            assertEquals(TaskState.FAILED, it.state)
            assertEquals(2, it.attempts)
            assertTrue(it.error!!.contains("2 of 2"))
        }
        assertEquals(DeploymentState.FAILED, depState(dep).state)
        assertEquals(mapOf("db" to TaskState.FAILED, "api" to TaskState.CANCELLED, "web" to TaskState.CANCELLED), states(dep))
        assertEquals("FAILED", appState(app))
    }

    @Test
    fun `a stale task of a deployment being stopped is cancelled, not retried`() {
        val app = chain()
        val dep = deployOk(app)
        claim(dep, "db")
        cancel(app, dep).andExpect(status().isOk)
        clock.advance(Duration.ofMinutes(5))
        assertEquals(1, worker.reclaimStale())
        assertEquals(TaskState.CANCELLED, task(dep, "db").state)
        assertEquals(DeploymentState.CANCELLED, depState(dep).state)
    }

    // ---- ownership and housekeeping ----

    @Test
    fun `other users can't deploy, see or cancel`() {
        val app = chain()
        val dep = deployOk(app)
        deploy(app, bob).andExpect(status().isNotFound)
        call(bob, get("/api/apps/$app/deployments")).andExpect(status().isNotFound)
        call(bob, get("/api/apps/$app/deployments/$dep")).andExpect(status().isNotFound)
        call(bob, post("/api/apps/$app/deployments/$dep/cancel")).andExpect(status().isNotFound)
        call(alice, get("/api/apps/$app/deployments/$dep")).andExpect(status().isOk)
        val bobsApp = chain(bob, "mine")
        call(alice, post("/api/apps/$bobsApp/deployments/$dep/cancel")).andExpect(status().isNotFound)
    }

    @Test
    fun `an app can't be deleted while a deployment is active, and deleting removes its tasks`() {
        val app = chain()
        val dep = deployOk(app)
        call(alice, delete("/api/apps/$app")).andExpect(status().isConflict).andExpect(jsonPath("$.error").value("deployment_active"))
        cancel(app, dep).andExpect(status().isOk)
        call(alice, delete("/api/apps/$app")).andExpect(status().isNoContent)
        em.flush(); em.clear()
        assertEquals(0, taskRepo.findAllByDeploymentIdOrderByOrdinalAsc(UUID.fromString(dep)).size)
    }

    @Test
    fun `a version that was deployed survives pruning`() {
        val app = appWith(listOf("web"))
        val dep = deployOk(app) // pins version 1
        cancel(app, dep).andExpect(status().isOk)
        var base = 1L
        repeat(52) { i ->
            call(alice, put("/api/apps/$app/draft"), mapOf("nodes" to listOf(node("web", image = "nginx:1.$i")), "edges" to emptyList<Any>(), "baseRevision" to base)).andExpect(status().isOk)
            base++
            call(alice, post("/api/apps/$app/versions")).andExpect(status().isCreated)
        }
        // 53 versions exist; only the newest 50 are kept, plus the deployed one.
        call(alice, get("/api/apps/$app/versions/1")).andExpect(status().isOk)
        call(alice, get("/api/apps/$app/versions/2")).andExpect(status().isNotFound)
        call(alice, get("/api/apps/$app/versions")).andExpect(jsonPath("$.length()").value(51))
    }
}
