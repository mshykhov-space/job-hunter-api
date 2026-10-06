package com.mshykhov.jobhunter.application.job

import com.mshykhov.jobhunter.api.rest.job.dto.JobCheckRequest
import com.mshykhov.jobhunter.infrastructure.metrics.MatchingMetrics
import com.mshykhov.jobhunter.support.TestFixtures
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.domain.Specification
import java.time.Clock
import java.time.Instant

class JobServiceTest {
    private val jobFacade = mockk<JobFacade>()
    private val jobGroupFacade = mockk<JobGroupFacade>()
    private val matchingMetrics = mockk<MatchingMetrics>(relaxed = true)
    private val clock = mockk<Clock>()
    private val service = JobService(jobFacade, jobGroupFacade, matchingMetrics, clock)

    @Nested
    inner class Ingest {
        private val fixedInstant = Instant.parse("2026-01-01T00:00:00Z")

        @BeforeEach
        fun setUp() {
            every { jobGroupFacade.findByGroupKeys(any()) } returns emptyList()
            every { jobGroupFacade.findOrCreate(any(), any(), any()) } answers {
                JobGroupEntity(
                    groupKey = firstArg(),
                    title = secondArg(),
                    company = thirdArg(),
                )
            }
            every { jobGroupFacade.saveAll(any()) } answers { firstArg<Collection<JobGroupEntity>>().toList() }
            every { clock.instant() } returns fixedInstant
            every { jobFacade.touchLastSeen(any(), any()) } returns Unit
        }

        @Test
        fun `should create new job when URL does not exist`() {
            val request = TestFixtures.jobIngestRequest(url = "https://example.com/new-job", category = Category("java"))
            every { jobFacade.findByUrls(listOf(request.url)) } returns emptyList()
            every { jobFacade.saveAll(any<List<JobEntity>>()) } answers { firstArg() }

            val result = service.ingest(listOf(request))

            assertEquals(1, result.size)
            assertEquals(request.title, result[0].title)
            assertEquals(request.url, result[0].url)
            assertEquals(setOf(Category("java")), result[0].group.categories)
        }

        @Test
        fun `should stamp last seen at on newly created job`() {
            val request = TestFixtures.jobIngestRequest(url = "https://example.com/new-job-touch")
            every { jobFacade.findByUrls(listOf(request.url)) } returns emptyList()
            every { jobFacade.saveAll(any<List<JobEntity>>()) } answers { firstArg() }

            val result = service.ingest(listOf(request))

            assertEquals(fixedInstant, result[0].lastSeenAt)
        }

        @Test
        fun `should stamp last seen at on updated existing job`() {
            val url = "https://example.com/updated-touch"
            val existing = TestFixtures.jobEntity(url = url, title = "Old Title")
            val request = TestFixtures.jobIngestRequest(url = url, title = "New Title")

            every { jobFacade.findByUrls(listOf(url)) } returns listOf(existing)
            every { jobFacade.saveAll(any()) } answers { firstArg() }

            service.ingest(listOf(request))

            assertEquals(fixedInstant, existing.lastSeenAt)
        }

        @Test
        fun `should normalize category to trimmed lowercase`() {
            val request = TestFixtures.jobIngestRequest(url = "https://example.com/trim", category = Category("  Kotlin  "))
            every { jobFacade.findByUrls(listOf(request.url)) } returns emptyList()
            every { jobFacade.saveAll(any<List<JobEntity>>()) } answers { firstArg() }

            val result = service.ingest(listOf(request))

            assertEquals(setOf(Category("kotlin")), result[0].group.categories)
        }

        @Test
        fun `should merge new category into existing group categories`() {
            val url = "https://example.com/merge"
            val group = TestFixtures.jobGroupEntity().apply { categories = setOf(Category("java")) }
            val existing = TestFixtures.jobEntity(url = url, group = group)
            val request = TestFixtures.jobIngestRequest(url = url, category = Category("kotlin"))

            every { jobFacade.findByUrls(listOf(url)) } returns listOf(existing)
            every { jobFacade.saveAll(any<List<JobEntity>>()) } answers { firstArg() }

            service.ingest(listOf(request))

            assertEquals(setOf(Category("java"), Category("kotlin")), group.categories)
        }

        @Test
        fun `should not duplicate existing category on re-ingest`() {
            val url = "https://example.com/no-dup"
            val group = TestFixtures.jobGroupEntity().apply { categories = setOf(Category("kotlin")) }
            val existing =
                TestFixtures.jobEntity(
                    url = url,
                    group = group,
                    title = "Same",
                    description = "Same",
                    salary = null,
                    location = null,
                    remote = null,
                )
            val request =
                TestFixtures.jobIngestRequest(
                    url = url,
                    title = "Same",
                    description = "Same",
                    salary = null,
                    location = null,
                    remote = null,
                    publishedAt = null,
                    category = Category("kotlin"),
                )

            every { jobFacade.findByUrls(listOf(url)) } returns listOf(existing)
            val savedSlot = slot<List<JobEntity>>()
            every { jobFacade.saveAll(capture(savedSlot)) } answers { firstArg() }

            service.ingest(listOf(request))

            assertTrue(savedSlot.captured.isEmpty())
            assertEquals(setOf(Category("kotlin")), group.categories)
        }

        @Test
        fun `should update existing job when fields changed`() {
            val url = "https://example.com/existing"
            val existing = TestFixtures.jobEntity(url = url, title = "Old Title", salary = "3000 USD")
            val request = TestFixtures.jobIngestRequest(url = url, title = "New Title", salary = "5000 USD")

            every { jobFacade.findByUrls(listOf(url)) } returns listOf(existing)
            every { jobFacade.saveAll(any()) } answers { firstArg() }

            val result = service.ingest(listOf(request))

            assertEquals(1, result.size)
            assertEquals("New Title", existing.title)
            assertEquals("5000 USD", existing.salary)
        }

        @Test
        fun `should not update when all fields are identical`() {
            val url = "https://example.com/same"
            val existing =
                TestFixtures.jobEntity(
                    url = url,
                    title = "Same Title",
                    description = "Same description",
                    salary = "5000 USD",
                    location = "Remote",
                    remote = true,
                )
            val request =
                TestFixtures.jobIngestRequest(
                    url = url,
                    title = "Same Title",
                    description = "Same description",
                    salary = "5000 USD",
                    location = "Remote",
                    remote = true,
                    publishedAt = null,
                    category = Category("kotlin"),
                )

            every { jobFacade.findByUrls(listOf(url)) } returns listOf(existing)
            val savedSlot = slot<List<JobEntity>>()
            every { jobFacade.saveAll(capture(savedSlot)) } answers { firstArg() }

            service.ingest(listOf(request))

            assertTrue(savedSlot.captured.isEmpty())
        }

        @ParameterizedTest
        @CsvSource(
            "true, null, true, false, 0",
            "false, null, false, false, 0",
            "true, null, true, true, 1",
            "false, null, false, true, 1",
            "true, false, false, false, 1",
            "false, true, true, false, 1",
            "null, true, true, false, 1",
            "null, false, false, false, 1",
            "null, null, null, false, 0",
            nullValues = ["null"],
        )
        fun `should preserve known remote status when re-ingest has no remote evidence`(
            existingRemote: Boolean?,
            incomingRemote: Boolean?,
            expectedRemote: Boolean?,
            descriptionChanged: Boolean,
            expectedSaveCount: Int,
        ) {
            val url = "https://example.com/remote-evidence"
            val existing = TestFixtures.jobEntity(url = url, remote = existingRemote)
            val request = TestFixtures.jobIngestRequest(
                url = url,
                remote = incomingRemote,
                description = if (descriptionChanged) "Updated description" else existing.description,
                publishedAt = null,
            )
            every { jobFacade.findByUrls(listOf(url)) } returns listOf(existing)
            val saved = slot<List<JobEntity>>()
            every { jobFacade.saveAll(capture(saved)) } answers { firstArg() }

            val result = service.ingest(listOf(request))

            assertEquals(expectedRemote, result.single().remote)
            assertEquals(request.description, result.single().description)
            assertEquals(expectedSaveCount, saved.captured.size)
        }

        @Test
        fun `should deduplicate requests by URL keeping last`() {
            val url = "https://example.com/dup"
            val request1 = TestFixtures.jobIngestRequest(url = url, title = "First")
            val request2 = TestFixtures.jobIngestRequest(url = url, title = "Second")

            every { jobFacade.findByUrls(listOf(url)) } returns emptyList()
            every { jobFacade.saveAll(any()) } answers { firstArg() }

            val result = service.ingest(listOf(request1, request2))

            assertEquals(1, result.size)
            assertEquals("Second", result[0].title)
        }

        @Test
        fun `should parse publishedAt in ISO-8601 format`() {
            val request = TestFixtures.jobIngestRequest(publishedAt = "2026-03-01T10:00:00Z")
            every { jobFacade.findByUrls(any()) } returns emptyList()
            every { jobFacade.saveAll(any()) } answers { firstArg() }

            val result = service.ingest(listOf(request))

            assertEquals(Instant.parse("2026-03-01T10:00:00Z"), result[0].publishedAt)
        }

        @Test
        fun `should keep existing publishedAt when new value is unparseable`() {
            val url = "https://example.com/keep-date"
            val existingDate = Instant.parse("2026-01-01T00:00:00Z")
            val existing = TestFixtures.jobEntity(url = url, title = "Old").apply { publishedAt = existingDate }
            val request = TestFixtures.jobIngestRequest(url = url, title = "Updated", publishedAt = "invalid-date")

            every { jobFacade.findByUrls(listOf(url)) } returns listOf(existing)
            every { jobFacade.saveAll(any()) } answers { firstArg() }

            service.ingest(listOf(request))

            assertEquals(existingDate, existing.publishedAt)
        }

        @Test
        fun `should record ingest count per source`() {
            val request = TestFixtures.jobIngestRequest(url = "https://example.com/metrics-job", source = JobSource.DOU)
            every { jobFacade.findByUrls(listOf(request.url)) } returns emptyList()
            every { jobFacade.saveAll(any<List<JobEntity>>()) } answers { firstArg() }

            service.ingest(listOf(request))

            verify { matchingMetrics.recordIngest(JobSource.DOU, 1) }
        }

        @Test
        fun `should handle empty request list`() {
            every { jobFacade.findByUrls(emptyList()) } returns emptyList()
            every { jobFacade.saveAll(emptyList()) } returns emptyList()

            val result = service.ingest(emptyList())

            assertTrue(result.isEmpty())
        }

        @Test
        fun `should reuse group when two new jobs have same title and company`() {
            val url1 = "https://example.com/job-1"
            val url2 = "https://example.com/job-2"
            val request1 = TestFixtures.jobIngestRequest(url = url1, title = "Kotlin Dev", company = "ACME")
            val request2 = TestFixtures.jobIngestRequest(url = url2, title = "Kotlin Dev", company = "ACME")

            every { jobFacade.findByUrls(any()) } returns emptyList()
            val savedSlot = slot<List<JobEntity>>()
            every { jobFacade.saveAll(capture(savedSlot)) } answers { firstArg() }

            val result = service.ingest(listOf(request1, request2))

            assertEquals(2, result.size)
            val groups = savedSlot.captured.map { it.group }.distinct()
            assertEquals(1, groups.size)
            verify(exactly = 1) { jobGroupFacade.findOrCreate(any(), any(), any()) }
        }

        @Test
        fun `should reuse existing group without creating new one`() {
            val existingGroup = TestFixtures.jobGroupEntity(title = "Kotlin Dev", company = "ACME")

            every { jobGroupFacade.findByGroupKeys(any()) } returns listOf(existingGroup)
            every { jobFacade.findByUrls(any()) } returns emptyList()
            every { jobFacade.saveAll(any()) } answers { firstArg() }

            val request = TestFixtures.jobIngestRequest(title = "Kotlin Dev", company = "ACME")
            service.ingest(listOf(request))

            verify(exactly = 0) { jobGroupFacade.findOrCreate(any(), any(), any()) }
        }

        @Test
        fun `should create separate groups for different companies`() {
            val url1 = "https://example.com/job-1"
            val url2 = "https://example.com/job-2"
            val request1 = TestFixtures.jobIngestRequest(url = url1, title = "Kotlin Dev", company = "ACME")
            val request2 = TestFixtures.jobIngestRequest(url = url2, title = "Kotlin Dev", company = "OtherCorp")

            every { jobFacade.findByUrls(any()) } returns emptyList()
            val savedSlot = slot<List<JobEntity>>()
            every { jobFacade.saveAll(capture(savedSlot)) } answers { firstArg() }

            val result = service.ingest(listOf(request1, request2))

            assertEquals(2, result.size)
            val groups = savedSlot.captured.map { it.group }.distinct()
            assertEquals(2, groups.size)
            verify(exactly = 2) { jobGroupFacade.findOrCreate(any(), any(), any()) }
        }

        @Test
        fun `should not create group for updated existing job`() {
            val url = "https://example.com/existing"
            val existingGroup = TestFixtures.jobGroupEntity()
            val existing = TestFixtures.jobEntity(url = url, title = "Old Title", group = existingGroup)
            val request = TestFixtures.jobIngestRequest(url = url, title = "New Title")

            every { jobFacade.findByUrls(listOf(url)) } returns listOf(existing)
            every { jobFacade.saveAll(any()) } answers { firstArg() }

            service.ingest(listOf(request))

            verify(exactly = 0) { jobGroupFacade.findOrCreate(any(), any(), any()) }
        }

        @Test
        fun `should merge new category into existing group on ingest`() {
            val existingGroup =
                TestFixtures.jobGroupEntity(title = "Kotlin Dev", company = "ACME").apply {
                    categories = setOf(Category("java"))
                }

            every { jobGroupFacade.findByGroupKeys(any()) } returns listOf(existingGroup)
            every { jobFacade.findByUrls(any()) } returns emptyList()
            every { jobFacade.saveAll(any()) } answers { firstArg() }

            val savedGroups = slot<Collection<JobGroupEntity>>()
            every { jobGroupFacade.saveAll(capture(savedGroups)) } answers {
                firstArg<Collection<JobGroupEntity>>().toList()
            }

            val requests =
                listOf(
                    TestFixtures.jobIngestRequest(
                        url = "https://example.com/j1",
                        title = "Kotlin Dev",
                        company = "ACME",
                        category = Category("kotlin"),
                    ),
                )

            service.ingest(requests)

            val savedGroup = savedGroups.captured.single()
            assertEquals(setOf(Category("java"), Category("kotlin")), savedGroup.categories)
        }

        @Test
        fun `should handle mix of new, updated, and unchanged jobs`() {
            val newUrl = "https://example.com/new"
            val updatedUrl = "https://example.com/updated"
            val unchangedUrl = "https://example.com/unchanged"

            val existingUpdated = TestFixtures.jobEntity(url = updatedUrl, title = "Old Title")
            val existingUnchanged =
                TestFixtures.jobEntity(
                    url = unchangedUrl,
                    title = "Same",
                    description = "Same desc",
                    salary = null,
                    location = null,
                    remote = null,
                )

            every { jobFacade.findByUrls(any()) } returns listOf(existingUpdated, existingUnchanged)
            every { jobFacade.saveAll(any()) } answers { firstArg() }

            val requests =
                listOf(
                    TestFixtures.jobIngestRequest(url = newUrl, title = "New Job"),
                    TestFixtures.jobIngestRequest(url = updatedUrl, title = "New Title"),
                    TestFixtures.jobIngestRequest(
                        url = unchangedUrl,
                        title = "Same",
                        description = "Same desc",
                        salary = null,
                        location = null,
                        remote = null,
                        publishedAt = null,
                        category = Category("kotlin"),
                    ),
                )

            val result = service.ingest(requests)

            assertEquals(3, result.size)
        }

        @Test
        fun `should save only changed entities`() {
            val unchangedUrl = "https://example.com/no-change"
            val existing =
                TestFixtures.jobEntity(
                    url = unchangedUrl,
                    title = "Title",
                    description = "Desc",
                    salary = null,
                    location = null,
                    remote = null,
                )

            every { jobFacade.findByUrls(any()) } returns listOf(existing)
            val savedSlot = slot<List<JobEntity>>()
            every { jobFacade.saveAll(capture(savedSlot)) } answers { firstArg() }

            service.ingest(
                listOf(
                    TestFixtures.jobIngestRequest(
                        url = unchangedUrl,
                        title = "Title",
                        description = "Desc",
                        salary = null,
                        location = null,
                        remote = null,
                        publishedAt = null,
                        category = Category("kotlin"),
                    ),
                ),
            )

            assertTrue(savedSlot.captured.isEmpty())
        }

        @Test
        fun `should touch last seen at for unchanged job on every ingest without re-saving it`() {
            val url = "https://example.com/touch-last-seen"
            val existing =
                TestFixtures.jobEntity(
                    url = url,
                    title = "Title",
                    description = "Desc",
                    salary = null,
                    location = null,
                    remote = null,
                )
            val request =
                TestFixtures.jobIngestRequest(
                    url = url,
                    title = "Title",
                    description = "Desc",
                    salary = null,
                    location = null,
                    remote = null,
                    publishedAt = null,
                    category = Category("kotlin"),
                )

            every { jobFacade.findByUrls(listOf(url)) } returns listOf(existing)
            val savedBatches = mutableListOf<List<JobEntity>>()
            every { jobFacade.saveAll(any()) } answers {
                firstArg<List<JobEntity>>().also { savedBatches.add(it) }
            }

            val firstSeenAt = Instant.parse("2026-06-01T00:00:00Z")
            every { clock.instant() } returns firstSeenAt
            service.ingest(listOf(request))

            verify { jobFacade.touchLastSeen(listOf(existing.id), firstSeenAt) }

            val secondSeenAt = Instant.parse("2026-06-02T00:00:00Z")
            every { clock.instant() } returns secondSeenAt
            service.ingest(listOf(request))

            verify { jobFacade.touchLastSeen(listOf(existing.id), secondSeenAt) }
            assertTrue(savedBatches.all { it.isEmpty() })
        }
    }

    @Nested
    inner class CheckJobs {
        private val fixedInstant = Instant.parse("2026-02-01T00:00:00Z")

        @BeforeEach
        fun setUp() {
            every { clock.instant() } returns fixedInstant
            every { jobFacade.touchLastSeen(any(), any()) } returns Unit
        }

        @Test
        fun `should classify brand new URL as new`() {
            val request = JobCheckRequest(url = "https://example.com/brand-new")
            every { jobFacade.findByUrls(listOf(request.url)) } returns emptyList()

            val result = service.checkJobs(listOf(request))

            assertEquals(listOf(request.url), result.newUrls)
            assertTrue(result.updatedUrls.isEmpty())
            assertTrue(result.unchangedUrls.isEmpty())
        }

        @Test
        fun `should classify existing URL with no changes as unchanged`() {
            val url = "https://example.com/same"
            val existing = TestFixtures.jobEntity(url = url, title = "Same Title", salary = "5000")
            val request = JobCheckRequest(url = url, title = "Same Title", salary = "5000")

            every { jobFacade.findByUrls(listOf(url)) } returns listOf(existing)

            val result = service.checkJobs(listOf(request))

            assertTrue(result.newUrls.isEmpty())
            assertTrue(result.updatedUrls.isEmpty())
            assertEquals(listOf(url), result.unchangedUrls)
        }

        @Test
        fun `should classify existing URL with title change as updated`() {
            val url = "https://example.com/changed"
            val existing = TestFixtures.jobEntity(url = url, title = "Old Title")
            val request = JobCheckRequest(url = url, title = "New Title")

            every { jobFacade.findByUrls(listOf(url)) } returns listOf(existing)

            val result = service.checkJobs(listOf(request))

            assertTrue(result.newUrls.isEmpty())
            assertEquals(listOf(url), result.updatedUrls)
        }

        @Test
        fun `should classify existing URL with salary change as updated`() {
            val url = "https://example.com/salary-changed"
            val existing = TestFixtures.jobEntity(url = url, salary = "3000 USD")
            val request = JobCheckRequest(url = url, salary = "5000 USD")

            every { jobFacade.findByUrls(listOf(url)) } returns listOf(existing)

            val result = service.checkJobs(listOf(request))

            assertEquals(listOf(url), result.updatedUrls)
        }

        @Test
        fun `should classify existing URL with company change as updated`() {
            val url = "https://example.com/company-changed"
            val existing = TestFixtures.jobEntity(url = url, company = "OldCorp")
            val request = JobCheckRequest(url = url, company = "NewCorp")

            every { jobFacade.findByUrls(listOf(url)) } returns listOf(existing)

            val result = service.checkJobs(listOf(request))

            assertEquals(listOf(url), result.updatedUrls)
        }

        @Test
        fun `should classify existing URL with location change as updated`() {
            val url = "https://example.com/location-changed"
            val existing = TestFixtures.jobEntity(url = url, location = "Kyiv")
            val request = JobCheckRequest(url = url, location = "Remote")

            every { jobFacade.findByUrls(listOf(url)) } returns listOf(existing)

            val result = service.checkJobs(listOf(request))

            assertEquals(listOf(url), result.updatedUrls)
        }

        @Test
        fun `should ignore null fields in check request`() {
            val url = "https://example.com/partial"
            val existing = TestFixtures.jobEntity(url = url, title = "Title", salary = "5000")
            val request = JobCheckRequest(url = url)

            every { jobFacade.findByUrls(listOf(url)) } returns listOf(existing)

            val result = service.checkJobs(listOf(request))

            assertEquals(listOf(url), result.unchangedUrls)
        }

        @Test
        fun `should return all-empty lists for empty input`() {
            val result = service.checkJobs(emptyList())

            assertTrue(result.newUrls.isEmpty())
            assertTrue(result.updatedUrls.isEmpty())
            assertTrue(result.unchangedUrls.isEmpty())
        }

        @Test
        fun `should deduplicate check requests by URL`() {
            val url = "https://example.com/dup"
            val request1 = JobCheckRequest(url = url, title = "First")
            val request2 = JobCheckRequest(url = url, title = "Second")

            every { jobFacade.findByUrls(listOf(url)) } returns emptyList()

            val result = service.checkJobs(listOf(request1, request2))

            assertEquals(1, result.newUrls.size)
        }

        @Test
        fun `should touch last seen at for a job classified as unchanged`() {
            val url = "https://example.com/check-touch-unchanged"
            val existing = TestFixtures.jobEntity(url = url, title = "Same Title", salary = "5000")
            val request = JobCheckRequest(url = url, title = "Same Title", salary = "5000")

            every { jobFacade.findByUrls(listOf(url)) } returns listOf(existing)

            val result = service.checkJobs(listOf(request))

            assertEquals(listOf(url), result.unchangedUrls)
            verify { jobFacade.touchLastSeen(listOf(existing.id), fixedInstant) }
        }

        @Test
        fun `should touch last seen at for a job classified as updated`() {
            val url = "https://example.com/check-touch-updated"
            val existing = TestFixtures.jobEntity(url = url, title = "Old Title")
            val request = JobCheckRequest(url = url, title = "New Title")

            every { jobFacade.findByUrls(listOf(url)) } returns listOf(existing)

            service.checkJobs(listOf(request))

            verify { jobFacade.touchLastSeen(listOf(existing.id), fixedInstant) }
        }

        @Test
        fun `should not touch last seen at when every url is brand new`() {
            val request = JobCheckRequest(url = "https://example.com/check-touch-new")

            every { jobFacade.findByUrls(listOf(request.url)) } returns emptyList()

            service.checkJobs(listOf(request))

            verify(exactly = 0) { jobFacade.touchLastSeen(any(), any()) }
        }
    }

    @Nested
    inner class SearchPublic {
        @Test
        fun `should return paginated results`() {
            every { jobFacade.findAll(any<Specification<JobEntity>>(), any<PageRequest>()) } answers {
                val pageable = secondArg<PageRequest>()
                PageImpl(emptyList(), pageable, 0)
            }

            val result = service.searchPublic(page = 0, size = 10, search = null, sources = null, remote = null, publishedAfter = null)

            assertEquals(0, result.page)
            assertEquals(10, result.size)
        }

        @Test
        fun `should clamp page size to max 100`() {
            every { jobFacade.findAll(any<Specification<JobEntity>>(), any<PageRequest>()) } answers {
                val pageable = secondArg<PageRequest>()
                PageImpl(emptyList(), pageable, 0)
            }

            val result = service.searchPublic(page = 0, size = 500, search = null, sources = null, remote = null, publishedAfter = null)

            assertEquals(100, result.size)
        }

        @Test
        fun `should clamp page to minimum 0`() {
            every { jobFacade.findAll(any<Specification<JobEntity>>(), any<PageRequest>()) } answers {
                val pageable = secondArg<PageRequest>()
                PageImpl(emptyList(), pageable, 0)
            }

            val result = service.searchPublic(page = -5, size = 10, search = null, sources = null, remote = null, publishedAfter = null)

            assertEquals(0, result.page)
        }
    }
}
