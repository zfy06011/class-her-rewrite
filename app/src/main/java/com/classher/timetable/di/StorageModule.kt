package com.classher.timetable.di

import android.content.Context
import androidx.room.Room
import com.classher.timetable.data.local.*
import com.classher.timetable.domain.AppSettings
import com.classher.timetable.domain.ScheduleRepository
import com.classher.timetable.domain.ScheduleWriteGate
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): ScheduleDatabase =
        Room.databaseBuilder(context, ScheduleDatabase::class.java, "classher.db").build()
    @Provides @Singleton fun gate() = ScheduleWriteGate()
    @Provides @Singleton fun schedules(database: ScheduleDatabase, gate: ScheduleWriteGate): ScheduleRepository =
        RoomScheduleRepository(database, gate)
    @Provides @Singleton fun settings(@ApplicationContext context: Context): AppSettings = PreferenceSettings(context)
}
