package org.mdpeds.logbook

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import org.mdpeds.logbook.sync.Notifier
import org.mdpeds.logbook.sync.WorkSyncScheduler
import javax.inject.Inject

@HiltAndroidApp
class LogbookApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var scheduler: WorkSyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        notifier.createChannel()
        scheduler.schedulePeriodic()
    }
}
