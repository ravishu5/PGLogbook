package org.mdpeds.logbook.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.mdpeds.logbook.data.SyncScheduler
import org.mdpeds.logbook.data.local.LocalDatabase
import org.mdpeds.logbook.sync.WorkSyncScheduler
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): LocalDatabase =
        Room.databaseBuilder(context, LocalDatabase::class.java, "pglogbook.db").build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds
    abstract fun scheduler(impl: WorkSyncScheduler): SyncScheduler
}
