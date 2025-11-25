package com.example.androidworkmanager

import android.content.Context
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.guava.await
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WorkManager 任务管理类 - 实际使用版本
 *
 * 提供 Android WorkManager 核心功能封装：
 * - 一次性任务、周期性任务、任务链、加急任务
 * - 约束条件构建器
 * - 任务观察（LiveData & Flow & Callback）
 * - 任务控制（取消、查询）
 *
 */
object TaskWorkUtil {

    private const val TAG = "TaskWorkUtil"

    // 默认配置常量
    private const val DEFAULT_BACKOFF_DELAY = 10L
    private const val DEFAULT_INITIAL_DELAY = 0L
    private const val MIN_PERIODIC_INTERVAL_MINUTES = 15L

    // 任务标签常量
    const val TAG_ONE_TIME_WORK = "ONE_TIME_WORK"
    const val TAG_PERIODIC_WORK = "PERIODIC_WORK"
    const val TAG_CHAINING_WORK = "CHAINING_WORK"

    // ============================================
    // 一次性任务 (OneTime Work)
    // ============================================

    /**
     * 启动一次性任务（核心方法）
     *
     * @param context 上下文
     * @param workClass 任务类（继承自 ListenableWorker）
     * @param tag 任务标签，用于查询和取消
     * @param inputData 输入数据
     * @param constraints 约束条件
     * @param initialDelay 初始延迟时间（毫秒）
     * @param backoffPolicy 退避策略
     * @param backoffDelay 退避延迟时间（秒）
     * @return 任务 UUID，失败时返回 null
     */
    fun <T : ListenableWorker> startOneTimeWork(
        context: Context,
        workClass: Class<T>,
        tag: String? = null,
        inputData: Data? = null,
        constraints: Constraints? = null,
        initialDelay: Long = DEFAULT_INITIAL_DELAY,
        backoffPolicy: BackoffPolicy = BackoffPolicy.LINEAR,
        backoffDelay: Long = DEFAULT_BACKOFF_DELAY
    ): UUID? {
        return try {
            Log.d(TAG, "startOneTimeWork: class=$workClass, tag=$tag")

            val builder = OneTimeWorkRequest.Builder(workClass)

            tag?.let { builder.addTag(it) }
            inputData?.let { builder.setInputData(it) }
            constraints?.let { builder.setConstraints(it) }

            if (initialDelay > 0) {
                builder.setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            }

            builder.setBackoffCriteria(backoffPolicy, backoffDelay, TimeUnit.SECONDS)

            val workRequest = builder.build()
            WorkManager.getInstance(context).enqueue(workRequest)

            Log.d(TAG, "startOneTimeWork: enqueued with ID=${workRequest.id}")
            workRequest.id
        } catch (e: Exception) {
            Log.e(TAG, "startOneTimeWork: Failed", e)
            null
        }
    }

    /**
     * 启动唯一性任务
     *
     * 用于防止重复任务，例如：数据同步、文件上传等
     *
     * @param context 上下文
     * @param uniqueWorkName 唯一任务名称
     * @param workClass 任务类
     * @param existingWorkPolicy 任务存在时的策略
     *   - REPLACE: 替换旧任务
     *   - KEEP: 保留旧任务，忽略新任务
     *   - APPEND: 新任务排在旧任务后面
     *   - APPEND_OR_REPLACE: 如果旧任务失败，替换它
     * @param inputData 输入数据
     * @param constraints 约束条件
     * @return 任务 UUID，失败时返回 null
     */
    fun <T : ListenableWorker> startUniqueWork(
        context: Context,
        uniqueWorkName: String,
        workClass: Class<T>,
        existingWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
        inputData: Data? = null,
        constraints: Constraints? = null
    ): UUID? {
        return try {
            Log.d(TAG, "startUniqueWork: name=$uniqueWorkName, policy=$existingWorkPolicy")

            val builder = OneTimeWorkRequest.Builder(workClass)
            inputData?.let { builder.setInputData(it) }
            constraints?.let { builder.setConstraints(it) }

            val workRequest = builder.build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName,
                existingWorkPolicy,
                workRequest
            )

            Log.d(TAG, "startUniqueWork: enqueued with ID=${workRequest.id}")
            workRequest.id
        } catch (e: Exception) {
            Log.e(TAG, "startUniqueWork: Failed", e)
            null
        }
    }

    // ============================================
    // 任务链 (Work Chaining)
    // ============================================

    /**
     * 启动串行任务链
     *
     * 任务按顺序执行，前一个成功才会执行下一个
     *
     * @param context 上下文
     * @param works 任务列表（按顺序执行）
     * @param tag 任务链标签
     */
    fun startChainingWork(
        context: Context,
        works: List<OneTimeWorkRequest>,
        tag: String = TAG_CHAINING_WORK
    ) {
        try {
            if (works.isEmpty()) {
                Log.w(TAG, "startChainingWork: Empty work list")
                return
            }

            Log.d(TAG, "startChainingWork: Starting chain with ${works.size} works")

            val workManager = WorkManager.getInstance(context)
            var continuation = workManager.beginWith(works.first())

            works.drop(1).forEach { work ->
                continuation = continuation.then(work)
            }

            continuation.enqueue()
            Log.d(TAG, "startChainingWork: Chain enqueued successfully")
        } catch (e: Exception) {
            Log.e(TAG, "startChainingWork: Failed", e)
        }
    }

    /**
     * 启动并行任务链
     *
     * 多个任务并行执行，全部完成后执行最终任务
     *
     * 使用场景：
     * - 并行下载多个文件，然后合并
     * - 并行处理多个数据源，然后汇总
     *
     * @param context 上下文
     * @param parallelWorks 并行执行的任务列表
     * @param finalWork 并行任务完成后执行的汇总任务
     */
    fun startParallelChainingWork(
        context: Context,
        parallelWorks: List<OneTimeWorkRequest>,
        finalWork: OneTimeWorkRequest
    ) {
        try {
            if (parallelWorks.isEmpty()) {
                Log.w(TAG, "startParallelChainingWork: Empty parallel work list")
                return
            }

            Log.d(TAG, "startParallelChainingWork: ${parallelWorks.size} parallel works")

            WorkManager.getInstance(context)
                .beginWith(parallelWorks)
                .then(finalWork)
                .enqueue()

            Log.d(TAG, "startParallelChainingWork: Chain enqueued successfully")
        } catch (e: Exception) {
            Log.e(TAG, "startParallelChainingWork: Failed", e)
        }
    }

    // ============================================
    // 周期性任务 (Periodic Work)
    // ============================================

    /**
     * 启动周期性任务
     *
     * 注意：最小间隔为 15 分钟（Android 系统限制）
     *
     * @param context 上下文
     * @param workClass 任务类
     * @param intervalMinutes 重复间隔（分钟），最小 15 分钟
     * @param flexIntervalMinutes 灵活间隔（分钟），可选
     * @param constraints 约束条件
     * @param inputData 输入数据
     * @param tag 任务标签
     * @return 任务 UUID，失败时返回 null
     */
    fun <T : ListenableWorker> startPeriodicWork(
        context: Context,
        workClass: Class<T>,
        intervalMinutes: Long = MIN_PERIODIC_INTERVAL_MINUTES,
        flexIntervalMinutes: Long? = null,
        constraints: Constraints? = null,
        inputData: Data? = null,
        tag: String = TAG_PERIODIC_WORK
    ): UUID? {
        return try {
            val actualInterval = intervalMinutes.coerceAtLeast(MIN_PERIODIC_INTERVAL_MINUTES)

            if (intervalMinutes < MIN_PERIODIC_INTERVAL_MINUTES) {
                Log.w(TAG, "startPeriodicWork: Interval adjusted from $intervalMinutes to $actualInterval minutes")
            }

            Log.d(TAG, "startPeriodicWork: interval=${actualInterval}min, flex=${flexIntervalMinutes}min")

            val builder = if (flexIntervalMinutes != null && flexIntervalMinutes > 0) {
                PeriodicWorkRequestBuilder<CoroutineWorker>(
                    actualInterval, TimeUnit.MINUTES,
                    flexIntervalMinutes, TimeUnit.MINUTES
                )
            } else {
                PeriodicWorkRequestBuilder<CoroutineWorker>(actualInterval, TimeUnit.MINUTES)
            }

            constraints?.let { builder.setConstraints(it) }
            inputData?.let { builder.setInputData(it) }
            tag.let { builder.addTag(it) }

            val workRequest = builder.build()
            WorkManager.getInstance(context).enqueue(workRequest)

            Log.d(TAG, "startPeriodicWork: enqueued with ID=${workRequest.id}")
            workRequest.id
        } catch (e: Exception) {
            Log.e(TAG, "startPeriodicWork: Failed", e)
            null
        }
    }

    /**
     * 启动唯一周期性任务（推荐）
     *
     * 避免重复创建周期任务，适合应用级别的定期同步
     *
     * @param context 上下文
     * @param uniqueWorkName 唯一任务名称
     * @param workClass 任务类
     * @param intervalMinutes 重复间隔（分钟）
     * @param existingWorkPolicy 任务存在时的策略
     *   - KEEP: 保留现有任务（推荐）
     *   - REPLACE: 替换现有任务
     *   - UPDATE: 更新现有任务（Android WorkManager 2.7.0+）
     * @param constraints 约束条件
     * @return 任务 UUID，失败时返回 null
     */
    fun <T : ListenableWorker> startUniquePeriodicWork(
        context: Context,
        uniqueWorkName: String,
        workClass: Class<T>,
        intervalMinutes: Long = MIN_PERIODIC_INTERVAL_MINUTES,
        existingWorkPolicy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
        constraints: Constraints? = null
    ): UUID? {
        return try {
            val actualInterval = intervalMinutes.coerceAtLeast(MIN_PERIODIC_INTERVAL_MINUTES)

            Log.d(TAG, "startUniquePeriodicWork: name=$uniqueWorkName, interval=${actualInterval}min")

            val builder = PeriodicWorkRequestBuilder<CoroutineWorker>(actualInterval, TimeUnit.MINUTES)
            constraints?.let { builder.setConstraints(it) }

            val workRequest = builder.build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                uniqueWorkName,
                existingWorkPolicy,
                workRequest as PeriodicWorkRequest
            )

            Log.d(TAG, "startUniquePeriodicWork: enqueued with ID=${workRequest.id}")
            workRequest.id
        } catch (e: Exception) {
            Log.e(TAG, "startUniquePeriodicWork: Failed", e)
            null
        }
    }

    // ============================================
    // 加急任务 (Expedited Work)
    // ============================================

    /**
     * 启动加急任务（立即执行，不等待）
     *
     * 注意：
     * - Android 12+ 需要在 Worker 中实现 getForegroundInfo()
     * - 适合紧急任务，如用户触发的即时同步
     *
     * @param context 上下文
     * @param workClass 任务类
     * @param outOfQuotaPolicy 配额不足时的策略
     * @param inputData 输入数据
     * @return 任务 UUID，失败时返回 null
     */
    fun <T : ListenableWorker> startExpeditedWork(
        context: Context,
        workClass: Class<T>,
        outOfQuotaPolicy: OutOfQuotaPolicy = OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST,
        inputData: Data? = null
    ): UUID? {
        return try {
            Log.d(TAG, "startExpeditedWork: class=$workClass")

            val builder = OneTimeWorkRequest.Builder(workClass)
                .setExpedited(outOfQuotaPolicy)

            inputData?.let { builder.setInputData(it) }

            val workRequest = builder.build()
            WorkManager.getInstance(context).enqueue(workRequest)

            Log.d(TAG, "startExpeditedWork: enqueued with ID=${workRequest.id}")
            workRequest.id
        } catch (e: Exception) {
            Log.e(TAG, "startExpeditedWork: Failed", e)
            null
        }
    }

    // ============================================
    // 约束条件构建器
    // ============================================

    /**
     * 创建自定义约束条件
     *
     * @param networkType 网络类型要求
     *   - NOT_REQUIRED: 无需网络
     *   - CONNECTED: 任何网络
     *   - UNMETERED: WiFi 或无限流量（推荐用于大文件）
     *   - NOT_ROAMING: 非漫游网络
     *   - METERED: 计费网络
     * @param requiresCharging 是否需要充电
     * @param requiresBatteryNotLow 是否要求电量充足
     * @param requiresStorageNotLow 是否要求存储空间充足
     * @param requiresDeviceIdle 是否要求设备空闲（Android 6.0+）
     * @return 约束条件
     */
    fun createConstraints(
        networkType: NetworkType = NetworkType.NOT_REQUIRED,
        requiresCharging: Boolean = false,
        requiresBatteryNotLow: Boolean = false,
        requiresStorageNotLow: Boolean = false,
        requiresDeviceIdle: Boolean = false
    ): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(requiresCharging)
            .setRequiresBatteryNotLow(requiresBatteryNotLow)
            .setRequiresStorageNotLow(requiresStorageNotLow)
            .setRequiresDeviceIdle(requiresDeviceIdle)
            .build()
    }

    /**
     * 创建仅需网络的约束（最常用）
     *
     * @param networkType 网络类型
     *   - CONNECTED: 任何网络（默认）
     *   - UNMETERED: 仅 WiFi（推荐用于大文件上传/下载）
     * @return 约束条件
     */
    fun createNetworkConstraints(networkType: NetworkType = NetworkType.CONNECTED): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()
    }

    // ============================================
    // 任务观察 - LiveData
    // ============================================

    /**
     * 根据 UUID 观察任务状态（LiveData）
     *
     * 适合在 Activity/Fragment 中使用
     *
     * @param context 上下文
     * @param uuid 任务 UUID
     * @return LiveData<WorkInfo?>
     */
    fun getWorkInfoById(context: Context, uuid: UUID): LiveData<WorkInfo?> {
        return WorkManager.getInstance(context).getWorkInfoByIdLiveData(uuid)
    }

    /**
     * 根据标签观察任务状态（LiveData）
     *
     * 可以观察同一标签的多个任务
     *
     * @param context 上下文
     * @param tag 任务标签
     * @return LiveData<List<WorkInfo>>
     */
    fun getWorkInfoByTag(context: Context, tag: String): LiveData<List<WorkInfo>> {
        return WorkManager.getInstance(context).getWorkInfosByTagLiveData(tag)
    }

    // ============================================
    // 任务观察 - Kotlin Flow
    // ============================================

    /**
     * 根据 UUID 观察任务状态（Flow）
     *
     * 适合在 ViewModel 中使用
     *
     * @param context 上下文
     * @param uuid 任务 UUID
     * @return Flow<WorkInfo?>
     */
    fun getWorkInfoByIdFlow(context: Context, uuid: UUID): Flow<WorkInfo?> {
        return WorkManager.getInstance(context).getWorkInfoByIdFlow(uuid)
    }

    /**
     * 根据标签观察任务状态（Flow）
     *
     * @param context 上下文
     * @param tag 任务标签
     * @return Flow<List<WorkInfo>>
     */
    fun getWorkInfoByTagFlow(context: Context, tag: String): Flow<List<WorkInfo>> {
        return WorkManager.getInstance(context).getWorkInfosByTagFlow(tag)
    }

    // ============================================
    // 任务观察 - 回调方式
    // ============================================

    /**
     * 观察任务状态并触发回调（便捷方法）
     *
     * 适合简单场景，不想管理 LiveData 生命周期时使用
     *
     * @param context 上下文
     * @param lifecycleOwner 生命周期所有者
     * @param workId 任务 UUID
     * @param onEnqueued 任务入队回调
     * @param onRunning 任务运行回调
     * @param onSuccess 任务成功回调（携带输出数据）
     * @param onFailure 任务失败回调（携带输出数据）
     * @param onCancelled 任务取消回调
     */
    fun observeWorkWithCallback(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        workId: UUID,
        onEnqueued: (() -> Unit)? = null,
        onRunning: (() -> Unit)? = null,
        onSuccess: ((Data) -> Unit)? = null,
        onFailure: ((Data) -> Unit)? = null,
        onCancelled: (() -> Unit)? = null
    ) {
        getWorkInfoById(context, workId).observe(lifecycleOwner) { workInfo ->
            workInfo?.let {
                when (it.state) {
                    WorkInfo.State.ENQUEUED -> {
                        Log.d(TAG, "Work $workId: ENQUEUED")
                        onEnqueued?.invoke()
                    }
                    WorkInfo.State.RUNNING -> {
                        Log.d(TAG, "Work $workId: RUNNING")
                        onRunning?.invoke()
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        Log.d(TAG, "Work $workId: SUCCEEDED")
                        onSuccess?.invoke(it.outputData)
                    }
                    WorkInfo.State.FAILED -> {
                        Log.e(TAG, "Work $workId: FAILED")
                        onFailure?.invoke(it.outputData)
                    }
                    WorkInfo.State.CANCELLED -> {
                        Log.w(TAG, "Work $workId: CANCELLED")
                        onCancelled?.invoke()
                    }
                    WorkInfo.State.BLOCKED -> {
                        Log.d(TAG, "Work $workId: BLOCKED")
                    }
                }
            }
        }
    }

    // ============================================
    // 任务取消
    // ============================================

    /**
     * 根据 UUID 取消任务
     *
     * @param context 上下文
     * @param id 任务 UUID
     */
    fun cancelWorkById(context: Context, id: UUID) {
        try {
            Log.d(TAG, "cancelWorkById: $id")
            WorkManager.getInstance(context).cancelWorkById(id)
        } catch (e: Exception) {
            Log.e(TAG, "cancelWorkById: Failed", e)
        }
    }

    /**
     * 根据标签取消任务
     *
     * @param context 上下文
     * @param tag 任务标签
     */
    fun cancelWorkByTag(context: Context, tag: String) {
        try {
            Log.d(TAG, "cancelWorkByTag: $tag")
            WorkManager.getInstance(context).cancelAllWorkByTag(tag)
        } catch (e: Exception) {
            Log.e(TAG, "cancelWorkByTag: Failed", e)
        }
    }

    /**
     * 取消唯一任务
     *
     * @param context 上下文
     * @param uniqueWorkName 唯一任务名称
     */
    fun cancelUniqueWork(context: Context, uniqueWorkName: String) {
        try {
            Log.d(TAG, "cancelUniqueWork: $uniqueWorkName")
            WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName)
        } catch (e: Exception) {
            Log.e(TAG, "cancelUniqueWork: Failed", e)
        }
    }

    // ============================================
    // 任务查询
    // ============================================

    /**
     * 检查任务是否正在运行
     *
     * @param context 上下文
     * @param workId 任务 UUID
     * @return 是否正在运行
     */
    suspend fun isWorkRunning(context: Context, workId: UUID): Boolean {
        return try {
            val workInfo = WorkManager.getInstance(context).getWorkInfoById(workId).await()
            workInfo?.state == WorkInfo.State.RUNNING
        } catch (e: Exception) {
            Log.e(TAG, "isWorkRunning: Failed", e)
            false
        }
    }

    /**
     * 检查唯一任务是否存在（正在队列中或运行中）
     *
     * @param context 上下文
     * @param uniqueWorkName 唯一任务名称
     * @return 是否存在
     */
    suspend fun isUniqueWorkExists(context: Context, uniqueWorkName: String): Boolean {
        return try {
            val workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(uniqueWorkName)
                .await()
            workInfos.isNotEmpty() && workInfos.any { it.state.isActive }
        } catch (e: Exception) {
            Log.e(TAG, "isUniqueWorkExists: Failed", e)
            false
        }
    }

    // ============================================
    // 数据管理
    // ============================================

    /**
     * 清除已完成、取消或失败的任务记录
     *
     * 建议在应用启动时调用，避免数据库过大
     *
     * @param context 上下文
     */
    fun pruneWork(context: Context) {
        try {
            Log.d(TAG, "pruneWork: Cleaning up completed work records")
            WorkManager.getInstance(context).pruneWork()
        } catch (e: Exception) {
            Log.e(TAG, "pruneWork: Failed", e)
        }
    }
}

// ============================================
// 扩展属性（便捷工具）
// ============================================

/**
 * WorkInfo.State 扩展属性：是否已完成
 */
val WorkInfo.State.isFinished: Boolean
    get() = this == WorkInfo.State.SUCCEEDED ||
            this == WorkInfo.State.FAILED ||
            this == WorkInfo.State.CANCELLED

/**
 * WorkInfo.State 扩展属性：是否活跃（队列中或运行中）
 */
val WorkInfo.State.isActive: Boolean
    get() = this == WorkInfo.State.ENQUEUED || this == WorkInfo.State.RUNNING

/**
 * WorkInfo 扩展属性：是否已完成
 */
val WorkInfo.isFinished: Boolean
    get() = state.isFinished

/**
 * WorkInfo 扩展属性：是否活跃
 */
val WorkInfo.isActive: Boolean
    get() = state.isActive