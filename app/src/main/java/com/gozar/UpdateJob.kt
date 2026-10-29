package com.gozar

import android.app.job.JobParameters
import android.app.job.JobService

/** Android-scheduled network check; delivery timing remains under the system's battery rules. */
class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        UpdateChecker.check(applicationContext) {
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
