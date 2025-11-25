package com.example.androidworkmanager

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay

class FirstWork(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams)  {


    override suspend fun doWork(): Result {
        Log.d("FirstWork", "FirstWork doWork start")
        delay(3000)
        Log.d("FirstWork", "FirstWork doWork finish")
       return Result.success()
    }
}