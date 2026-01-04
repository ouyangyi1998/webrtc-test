package com.example.remotecontrol.ui.main.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.remotecontrol.databinding.FragmentLogBinding
import com.example.remotecontrol.databinding.ItemLogBinding
import com.example.remotecontrol.manager.LogManager

/**
 * 日志 Fragment
 */
class LogFragment : Fragment(), LogManager.LogListener {
    
    private var _binding: FragmentLogBinding? = null
    private val binding get() = _binding!!
    
    private val logAdapter = LogAdapter()
    
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupRecyclerView()
        setupListeners()
        
        // 加载已有日志
        logAdapter.setLogs(LogManager.getLogs())
        updateLogCount()
        
        LogManager.addListener(this)
    }
    
    private fun setupRecyclerView() {
        binding.recyclerLog.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = logAdapter
        }
    }
    
    private fun setupListeners() {
        binding.btnClearLog.setOnClickListener {
            LogManager.clear()
            Toast.makeText(requireContext(), "日志已清空", Toast.LENGTH_SHORT).show()
        }
        
        binding.btnExportLog.setOnClickListener {
            val file = LogManager.export(requireContext())
            if (file != null) {
                Toast.makeText(requireContext(), "日志已导出到: ${file.name}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(requireContext(), "导出失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun updateLogCount() {
        binding.tvLogCount.text = "共 ${logAdapter.itemCount} 条日志"
    }
    
    override fun onLogAdded(entry: LogManager.LogEntry) {
        activity?.runOnUiThread {
            logAdapter.addLog(entry)
            updateLogCount()
            // 自动滚动到底部
            if (logAdapter.itemCount > 0) {
                binding.recyclerLog.scrollToPosition(logAdapter.itemCount - 1)
            }
        }
    }
    
    override fun onLogsCleared() {
        activity?.runOnUiThread {
            logAdapter.clear()
            updateLogCount()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        LogManager.removeListener(this)
        _binding = null
    }
    
    // ========== 适配器 ==========
    inner class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {
        private val logs = mutableListOf<LogManager.LogEntry>()
        
        fun setLogs(newLogs: List<LogManager.LogEntry>) {
            logs.clear()
            logs.addAll(newLogs)
            notifyDataSetChanged()
        }
        
        fun addLog(entry: LogManager.LogEntry) {
            logs.add(entry)
            notifyItemInserted(logs.size - 1)
        }
        
        fun clear() {
            logs.clear()
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(logs[position])
        }
        
        override fun getItemCount() = logs.size
        
        inner class ViewHolder(private val binding: ItemLogBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(entry: LogManager.LogEntry) {
                binding.tvLogItem.text = entry.formatted()
                
                // 根据日志级别设置颜色
                val color = when (entry.level) {
                    LogManager.LogEntry.Level.ERROR -> android.graphics.Color.parseColor("#EF4444")
                    LogManager.LogEntry.Level.WARN -> android.graphics.Color.parseColor("#F59E0B")
                    LogManager.LogEntry.Level.DEBUG -> android.graphics.Color.parseColor("#6B7280")
                    else -> android.graphics.Color.WHITE
                }
                binding.tvLogItem.setTextColor(color)
            }
        }
    }
}
