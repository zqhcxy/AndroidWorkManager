package com.example.androidworkmanager

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.delay

/**
 * WorkManager的 触发worker
 */
class TaskWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object{
        private const val TAG = "TaskWorker"
    }

    override suspend fun doWork(): Result {

        try {
            Log.d(TAG, "doWork: 执行任务")




            inputData.apply {
                val param1 = getString("param1")
                val param2 = getInt("param2", defaultValue = -1)
                val params3 = getBoolean("param3",false)
                val param4 = getStringArray("param4")
                Log.d(TAG, "inputData param1: $param1, param2: $param2, param3: $params3, param4: $param4")
            }


            //更新进度
            setProgressAsync(workDataOf("progress" to 50))

            delay(3000)
            setProgressAsync(workDataOf("progress" to 100))
            Log.d(TAG, "doWork: 任务完成")
            return Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Log.d(TAG, "doWork: 任务失败")
            return Result.retry()
        }


    }

}