package org.mdpeds.logbook.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.mdpeds.logbook.data.LogbookBackend
import org.mdpeds.logbook.data.demo.DemoBackend
import org.mdpeds.logbook.data.local.BundledReference
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BackendModule {
    @Provides
    @Singleton
    fun backend(bundled: BundledReference): LogbookBackend =
        DemoBackend(bundled.data, Clock.systemDefaultZone(), latencyMs = 150)
}
