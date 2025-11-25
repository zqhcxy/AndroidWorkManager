package com.example.androidworkmanager

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * worker任务 管理类，  学习版本
 */
object TaskWorkManager {

    private const val TAG = "TaskWorkManager"

    const val UNIQUE_WORK_NAME = "UNIQUE_TASK_SCHEDULER"

    const val TAG_ONE_TIME_WORK_NAME = "ONE_TIME_TASK_SCHEDULER"

    const val TAG_CHAINING_WORK_NAME = "CHAINING_WORK"


    /**
     * 启动一次性任务
     * @param context
     * @param workClass 任务类
     * @param tag 任务类型标记
     * @param inputData 附带参数
     */
    fun <T : ListenableWorker> startOneTimeWork(
        context: Context,
        workClass: Class<T>,
        tag: String? = null,
        inputData: Data? = null
    ): UUID {

        Log.d(TAG,"startOneTimeWork workClass$workClass, tag:$tag, inputData:$inputData")

        val builder = OneTimeWorkRequest.Builder(workClass)
        //有标签
        tag?.let {
            Log.d(TAG,"startOneTimeWork add tag")
            builder.addTag(it)
        }
        //有附带数据
        inputData?.let {
            Log.d(TAG,"startOneTimeWork setInputData")
            builder.setInputData(it)
        }
        // 设置退避策略 (Backoff Policy)，处理重试
        // 如果任务失败，10秒后重试，线性增加等待时间
        builder.setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)

        val taskWork = builder.build()
        WorkManager.getInstance(context).enqueue(taskWork)
        Log.d(TAG,"startOneTimeWork finish")
        return taskWork.id
    }

    /**
     * 带约束约束条件的启动 一次性worker
     */
    fun startConstraintsWorker(context: Context, tag: String = TAG_ONE_TIME_WORK_NAME): UUID {
        Log.d(TAG, "starConstraintsWorker")



        //约束条件
        val constraints =Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)//必须有网络
            .setRequiresBatteryNotLow(true)//电量不能太低
            .setRequiresCharging(true)//必须有充电
            .setRequiresStorageNotLow(true)//存储空间不能太低
            .build()

        //创建任务请求
        val taskWork = OneTimeWorkRequest.Builder(TaskWorker::class.java)
            .setConstraints(constraints)
            .addTag(tag)
            .build()

        WorkManager.getInstance(context).enqueue(taskWork)
        return taskWork.id
    }



    /**
     * 启动一个唯一性worker
     */
    fun startWorkerWithUnique(context: Context) : UUID {

        Log.d(TAG, "startWorkerWithUnique")

        /*
            ExistingWorkPolicy 策略
                REPLACE：替换旧的，以当前任务为最新
                KEEP: 如果已有相同名字的任务在运行，**忽略**新任务（常用于“防止重复点击”）
                APPEND：如果已有任务，新任务排在它**后面**执行（常用于“上传队列”）。
                APPEND_OR_REPLACE：类似 APPEND，但如果前一个任务失败了，新任务会替换它而不是被阻塞。
         */

        val taskWork = OneTimeWorkRequest.Builder(TaskWorker::class.java).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,//唯一标识
            ExistingWorkPolicy.REPLACE,//任务存在策略,如果有重复添加则替换旧的
            taskWork)



        return taskWork.id
    }


    /**
     * 定时启动 一次性worker
     */
    fun startScheduledWork(context: Context) : UUID{

        val inputData = workDataOf("param1" to "Scheduled Work")//不支持list
        val taskWork = OneTimeWorkRequest.Builder(TaskWorker::class.java)
            .setInitialDelay(10000,TimeUnit.MILLISECONDS)
            .setInputData(inputData)
            .addTag(TAG_ONE_TIME_WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueue(taskWork)
        return taskWork.id
    }


    /**
     * 启动一个任务链 一次性worker
     */
    fun startChainingWork(context: Context){

        Log.d(TAG,"startChainingWork")
        val firstWork = OneTimeWorkRequest.Builder(FirstWork::class.java).addTag(TAG_CHAINING_WORK_NAME).build()
        val secondWork = OneTimeWorkRequest.Builder(SecondWork::class.java).addTag(TAG_CHAINING_WORK_NAME).build()
        val threeWork = OneTimeWorkRequest.Builder(ThreeWork::class.java).addTag(TAG_CHAINING_WORK_NAME).build()

        //按顺序执行任务，其中有一个失败，则不会进行后续任务

        WorkManager.getInstance(context).beginWith(firstWork).then(secondWork).then(threeWork).enqueue()

    }

    /**
     * 启动一个周期性任务，最小间隔15分
     */
    fun startPeriodicChainingWork(context: Context) {

        Log.d(TAG, "startPeriodicChainingWork")

        //最小间隔 15分钟
        val saveRequest = PeriodicWorkRequestBuilder<TaskWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueue(saveRequest)
    }


    /*
       高级进阶
     */

    /**
     * 加急任务，马上执行，不会有等待时间
     */
    fun startExpeditedWork(context: Context) : UUID{
        /*
            Android 12+ 需要实现为前台任务
         */




        Log.d(TAG, "startExpeditedWork")
        val request = OneTimeWorkRequest.Builder(TaskWorker::class.java)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)//设置加急
            .build()

        WorkManager.getInstance(context).enqueue(request)
        return request.id
    }





    /**
     * 观察数据
     * @param context
     * @param tag 任务类型标记，观察对应类型的 任务，默认TAG_ONE_TIME_WORK_NAME
     */
    fun getWorkInfoByTag(context: Context, tag: String = TAG_ONE_TIME_WORK_NAME) : LiveData<List<WorkInfo>>{
        //提供LiveData 给外部观察，让外部自己 管理生命周期
        return WorkManager.getInstance(context).getWorkInfosByTagLiveData(tag)
    }

    /**
     * 观察数据
     * @param context
     * @param uuid 观察指定任务的id
     */
    fun getWorkInfoById(context: Context, uuid: UUID): LiveData<WorkInfo?> {
        return WorkManager.getInstance(context).getWorkInfoByIdLiveData(uuid)
    }

    /**
     * 观察 唯一任务 状态
     */
    fun startObserveUniqueWorker(context: Context):LiveData<List<WorkInfo>> {
        return WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(UNIQUE_WORK_NAME)
    }

    /**
     * 根据uuid 取消worker
     * @param context
     * @param id 任务id
     */
    fun cancelWorkById(context: Context, id: UUID){
        WorkManager.getInstance(context).cancelWorkById(id)
    }

    /**
     * 根据tag 取消worker
     * @param context
     * @param tag 任务类型标记
     */
    fun cancelWorkByTag(context: Context, tag: String){
        WorkManager.getInstance(context).cancelAllWorkByTag(tag)
    }


    /**
     * 清除所有已经完成、取消或失败的任务记录
     */
    fun resetCache(context: Context){
        WorkManager.getInstance(context).pruneWork()
    }

}