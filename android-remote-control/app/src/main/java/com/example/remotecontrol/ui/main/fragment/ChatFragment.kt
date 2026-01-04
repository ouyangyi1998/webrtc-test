package com.example.remotecontrol.ui.main.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.remotecontrol.databinding.FragmentChatBinding
import com.example.remotecontrol.databinding.ItemChatBinding
import com.example.remotecontrol.manager.ConnectionManager
import com.example.remotecontrol.manager.LogManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天 Fragment
 */
class ChatFragment : Fragment(), ConnectionManager.ChatListener {
    
    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    
    private val chatAdapter = ChatAdapter()
    private var isDataChannelOpen = false
    
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupRecyclerView()
        setupListeners()
        
        ConnectionManager.addChatListener(this)
        
        // 初始状态
        updateChatEnabled(ConnectionManager.currentState == ConnectionManager.State.DATA_CHANNEL_OPEN)
    }
    
    private fun setupRecyclerView() {
        binding.recyclerChat.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = chatAdapter
        }
    }
    
    private fun setupListeners() {
        binding.btnSendChat.setOnClickListener {
            sendMessage()
        }
        
        binding.etChatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }
    }
    
    private fun sendMessage() {
        if (!isDataChannelOpen) return
        
        val message = binding.etChatInput.text?.toString()?.trim() ?: ""
        if (message.isEmpty()) return
        
        // 显示自己的消息
        chatAdapter.addMessage(ChatMessage("我", message, System.currentTimeMillis()))
        binding.etChatInput.text?.clear()
        scrollToBottom()
        
        // 发送消息
        ConnectionManager.sendChatMessage(message)
    }
    
    private fun scrollToBottom() {
        if (chatAdapter.itemCount > 0) {
            binding.recyclerChat.scrollToPosition(chatAdapter.itemCount - 1)
        }
    }
    
    private fun updateChatEnabled(enabled: Boolean) {
        activity?.runOnUiThread {
            isDataChannelOpen = enabled
            binding.etChatInput.isEnabled = enabled
            binding.btnSendChat.isEnabled = enabled
            
            if (enabled) {
                binding.tvChatStatus.text = "✓ 已连接"
                binding.tvChatStatus.setTextColor(android.graphics.Color.parseColor("#22C55E"))
            } else {
                binding.tvChatStatus.text = "⚠ 未连接"
                binding.tvChatStatus.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
            }
        }
    }
    
    override fun onChatMessage(sender: String, message: String) {
        activity?.runOnUiThread {
            chatAdapter.addMessage(ChatMessage(sender, message, System.currentTimeMillis()))
            scrollToBottom()
        }
    }
    
    override fun onDataChannelStateChanged(isOpen: Boolean) {
        updateChatEnabled(isOpen)
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        ConnectionManager.removeChatListener(this)
        _binding = null
    }
    
    // ========== 数据类和适配器 ==========
    data class ChatMessage(
        val sender: String,
        val message: String,
        val timestamp: Long
    )
    
    inner class ChatAdapter : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {
        private val messages = mutableListOf<ChatMessage>()
        private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        
        fun addMessage(message: ChatMessage) {
            messages.add(message)
            notifyItemInserted(messages.size - 1)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemChatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(messages[position])
        }
        
        override fun getItemCount() = messages.size
        
        inner class ViewHolder(private val binding: ItemChatBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(message: ChatMessage) {
                binding.tvSender.text = message.sender
                binding.tvMessage.text = message.message
                binding.tvTime.text = dateFormat.format(Date(message.timestamp))
                
                // 自己的消息用不同颜色
                if (message.sender == "我") {
                    binding.tvSender.setTextColor(android.graphics.Color.parseColor("#22C55E"))
                } else {
                    binding.tvSender.setTextColor(android.graphics.Color.parseColor("#3B82F6"))
                }
            }
        }
    }
}
