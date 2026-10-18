package com.novelreader.app.data.local

import androidx.room.withTransaction

suspend fun <R> AppDatabase.runInTransaction(block: suspend () -> R): R {
    return this.withTransaction(block)
}
