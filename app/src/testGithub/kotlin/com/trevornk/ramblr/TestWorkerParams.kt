package com.trevornk.ramblr

import androidx.work.Data
import androidx.work.ProgressUpdater
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.SerialExecutor
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.UUID
import java.util.concurrent.Executor

/**
 * A minimal, real (not mocked) [WorkerParameters] builder for driving a plain [androidx.work.Worker]
 * subclass's actual [androidx.work.Worker.doWork] synchronously in a JVM unit test -- no
 * `androidx.work:work-testing` dependency (not on this project's classpath, see build.gradle.kts)
 * and no WorkManager runtime initialization required, since `Worker.doWork()` never touches
 * WorkManager's own scheduling machinery; it only reads [WorkerParameters]' plain accessors
 * (inputData, runAttemptCount) and the few `ListenableWorker` methods a `Worker` subclass calls
 * synchronously (`setForegroundAsync`/`isStopped`).
 *
 * This exists specifically so [SelfUpdateInstallWorker]'s pre-flight install-permission gate
 * (#253) can be exercised through its REAL `doWork()` entry point -- constructing the real
 * `PackageManager.canRequestPackageInstalls()` result via Robolectric's
 * `ShadowPackageManager.setCanRequestPackageInstalls`, not by testing
 * [SelfUpdateInstallGate.canAttemptInstall] in isolation (already covered by
 * [SelfUpdateInstallGateTest]) and just trusting the worker wires it up correctly.
 */
object TestWorkerParams {
    private val immediateExecutor = Executor { it.run() }

    private val taskExecutor = object : TaskExecutor {
        private val serial = object : SerialExecutor {
            override fun execute(command: Runnable) = command.run()
            override fun hasPendingTasks(): Boolean = false
        }
        override fun getMainThreadExecutor(): Executor = immediateExecutor
        override fun getSerialTaskExecutor(): SerialExecutor = serial
    }

    private val progressUpdater = ProgressUpdater { _, _, _ -> Futures.immediateFuture(null) }
    private val foregroundUpdater = ForegroundUpdater { _, _, _: ForegroundInfo -> Futures.immediateFuture(null) }

    fun build(inputData: Data = Data.EMPTY, runAttemptCount: Int = 0): WorkerParameters =
        WorkerParameters(
            UUID.randomUUID(),
            inputData,
            emptyList(),
            WorkerParameters.RuntimeExtras(),
            runAttemptCount,
            0,
            immediateExecutor,
            kotlin.coroutines.EmptyCoroutineContext,
            taskExecutor,
            object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: android.content.Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): androidx.work.ListenableWorker? = null
            },
            progressUpdater,
            foregroundUpdater,
        )
}
