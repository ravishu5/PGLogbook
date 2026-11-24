package org.mdpeds.logbook.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.mdpeds.logbook.data.LogbookBackend
import org.mdpeds.logbook.data.local.SecureSessionStore
import org.mdpeds.logbook.data.remote.SupabaseBackend
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BackendModule {
    @Provides
    @Singleton
    fun backend(store: SecureSessionStore): LogbookBackend = SupabaseBackend(store)
}
