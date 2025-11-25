package com.example.androidworkmanager

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.workDataOf

class MainActivity : AppCompatActivity() {


    private val TAG = "MainActivity"
    private lateinit var itemRecyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }



        itemRecyclerView = findViewById(R.id.item_rcy)
        itemRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        itemRecyclerView.adapter = ItemAdapter(arrayListOf(
            "最简单的一次性Worker",
            "带约束条件的一次性worker",
            "带参数的一次性worker",
            "唯一的一次性worker",
            "定时10秒后启动的一次性worker",
            "任务链一次性任务"
        )) { position ->
            when(position){
                0->{
                    val uuid = TaskWorkManager.startOneTimeWork(this, TaskWorker::class.java)

                    TaskWorkManager.getWorkInfoById(this, uuid).observeForever { workInfo ->
                        Log.d(TAG, "Observe ID WorkerById worker id:${workInfo?.id} state: ${workInfo?.state}")
                    }
                }
                1->{
                    TaskWorkManager.startConstraintsWorker(this)
                }
                2->{
                    val inputData = workDataOf("param1" to "www.google.com",
                        "param2" to 17,
                        "param3" to false,
                        "param4" to arrayOf("Name1","Name2","Name3"))//不支持list
                    TaskWorkManager.startOneTimeWork(this,TaskWorker::class.java, tag = TaskWorkManager.TAG_ONE_TIME_WORK_NAME,inputData)
                }
                3->{
                    TaskWorkManager.startWorkerWithUnique(this)
                }
                4->{
                    TaskWorkManager.startScheduledWork(this)
                }
                5->{
                    TaskWorkManager.startChainingWork(this)
                }

            }
            
        }

        //观察 带Tag的任务，默认一次性任务
        TaskWorkManager.getWorkInfoByTag(this).observeForever { workInfos ->
            workInfos.forEach { workInfo ->

                val progress = workInfo.progress.getInt("progress",0)
                Log.d(TAG, "Observe TAG Worker worker id:${workInfo.id} state: ${workInfo.state},progress:$progress")

            }
        }

        //观察 唯一任务
        TaskWorkManager.startObserveUniqueWorker(this).observeForever {
            it.forEach { workInfo ->
                Log.d(TAG, "Observe Unique Worker worker id:${workInfo.id} state: ${workInfo.state}")
            }
        }

        //观察 任务链任务
//        TaskWorkManager.startObserveWorkerByTag(this, TaskWorkManager.TAG_CHAINING_WORK_NAME).observeForever {
//            it.forEach { workInfo ->
//                Log.d(TAG, "Observe TAG Worker worker id:${workInfo.id} state: ${workInfo.state}")
//            }
//        }


    }
}

class ItemAdapter(private val datas: List<String>, private val onItemClick: (Int) -> Unit) : RecyclerView.Adapter<ItemAdapter.ItemViewHolder>() {


    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ItemViewHolder {

        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_layout, parent, false)
        return ItemViewHolder(view)

    }


    override fun onBindViewHolder(
        holder: ItemViewHolder,
        position: Int
    ) {
        holder.itemTitle.text = datas[position]
        holder.itemView.setOnClickListener {
            onItemClick(position)
        }
    }

    override fun getItemCount(): Int {
        return datas.size
    }


    class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView){
        var itemTitle: TextView = itemView.findViewById(R.id.item_title)
    }

}