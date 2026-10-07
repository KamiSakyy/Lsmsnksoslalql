package com.fcmessenger.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fcmessenger.app.App

/** Доотправляет очередь QUEUED/FAILED, как только появляется сеть. */
class RetryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as App).container.repo
        return try {
            repo.retryQueued()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
