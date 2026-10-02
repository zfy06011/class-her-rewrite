package com.classher.timetable.di

import com.classher.timetable.data.school.SdwuParser
import com.classher.timetable.domain.SchoolParser
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SchoolModule {
    @Binds
    abstract fun parser(implementation: SdwuParser): SchoolParser
}
