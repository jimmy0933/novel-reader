package com.novelreader.app

import android.app.Application
import com.novelreader.app.data.local.AppDatabase
import com.novelreader.app.data.repository.NovelRepository

class NovelApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }
    val repository: NovelRepository by lazy { NovelRepository(this) }
}
