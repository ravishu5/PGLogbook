package org.mdpeds.logbook.ui.common

import kotlinx.serialization.builtins.ListSerializer
import org.mdpeds.logbook.data.Loaded
import org.mdpeds.logbook.data.LogbookRepository
import org.mdpeds.logbook.domain.model.StaffMember
import org.mdpeds.logbook.domain.model.StudentOverview

suspend fun LogbookRepository.overview(studentId: String): Loaded<StudentOverview> =
    cached("overview:$studentId", StudentOverview.serializer()) { backend.studentOverview(studentId) }

suspend fun LogbookRepository.overviews(): Loaded<List<StudentOverview>> =
    cached("overviews", ListSerializer(StudentOverview.serializer())) { backend.studentOverviews() }

suspend fun LogbookRepository.staffList(): Loaded<List<StaffMember>> =
    cached("staff", ListSerializer(StaffMember.serializer())) { backend.staff() }

/** Combines the offline flags of several loads. */
fun anyOffline(vararg loads: Loaded<*>): Boolean = loads.any { it.offline }
