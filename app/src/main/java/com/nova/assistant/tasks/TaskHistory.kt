package com.nova.assistant.tasks

import com.nova.assistant.core.TaskPlan
import com.nova.assistant.core.TaskPlanCodec
import com.nova.assistant.core.TaskRecord
import com.nova.assistant.memory.TaskDao
import com.nova.assistant.memory.TaskEntity

/** История задач: что просили, что сделали, чем закончилось. */
class TaskHistory(private val dao: TaskDao) {

    suspend fun start(plan: TaskPlan): Long {
        val now = System.currentTimeMillis()
        return dao.insert(
            TaskEntity(
                goal = plan.goal,
                status = "running",
                planJson = TaskPlanCodec.encode(plan),
                report = "",
                createdAt = now,
                finishedAt = now
            )
        )
    }

    suspend fun update(id: Long, status: String, report: String) {
        dao.byId(id)?.let {
            dao.update(
                it.copy(
                    status = status,
                    report = report,
                    finishedAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun recent(): List<TaskRecord> = dao.recent().map {
        TaskRecord(
            id = it.id,
            goal = it.goal,
            status = it.status,
            planJson = it.planJson,
            report = it.report,
            createdAt = it.createdAt,
            finishedAt = it.finishedAt
        )
    }

    suspend fun planOf(record: TaskRecord): TaskPlan? = TaskPlanCodec.decode(record.planJson)

    suspend fun clear() = dao.clear()
}
