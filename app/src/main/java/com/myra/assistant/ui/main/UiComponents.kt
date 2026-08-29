package com.myra.assistant.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.myra.assistant.R
import kotlin.math.sin
import kotlin.random.Random

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 20
    private val currentHeights = FloatArray(barCount) { 0.15f }
    private val targetHeights = FloatArray(barCount) { 0.15f }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val barRect = RectF()

    private var targetAmplitude: Float = 0f
    private var animator: ValueAnimator? = null
    private var tick = 0f

    init {
        startAnimation()
    }

    fun startAnimation() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                tick += 0.18f
                updateBars()
                invalidate()
            }
            start()
        }
    }

    fun stopAnimation() {
        animator?.cancel()
    }

    fun setAmplitude(rms: Float) {
        targetAmplitude = rms.coerceIn(0f, 1f)
    }

    private fun updateBars() {
        for (i in 0 until barCount) {
            val waveModifier = (sin(tick + i * 0.45f) + 1f) * 0.5f
            val base = 0.1f + (targetAmplitude * 0.85f * waveModifier)
            targetHeights[i] = base.coerceIn(0.08f, 1.0f)
            // Lerp towards target
            currentHeights[i] += (targetHeights[i] - currentHeights[i]) * 0.35f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val totalBarWidth = w / barCount
        val barWidth = totalBarWidth * 0.55f
        val cornerRadius = barWidth / 2f

        for (i in 0 until barCount) {
            val barHeight = h * currentHeights[i]
            val left = i * totalBarWidth + (totalBarWidth - barWidth) / 2f
            val right = left + barWidth
            val top = (h - barHeight) / 2f
            val bottom = top + barHeight

            barRect.set(left, top, right, bottom)

            val alpha = (150 + currentHeights[i] * 105).toInt().coerceIn(120, 255)
            barPaint.color = Color.parseColor("#FF1744")
            barPaint.alpha = alpha

            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }
}

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_MYRA = 2
    }

    fun addMessage(msg: ChatMessage) {
        // Deduplication for MYRA messages
        if (!msg.isUser && messages.isNotEmpty()) {
            val last = messages.last()
            if (!last.isUser && last.text.trim().equals(msg.text.trim(), ignoreCase = true)) {
                return
            }
        }
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun lastMyraText(): String? {
        return messages.lastOrNull { !it.isUser }?.text
    }

    fun clear() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }

    fun getItemsCount(): Int = messages.size

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_MYRA
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            val view = inflater.inflate(R.layout.item_chat_user, parent, false)
            UserViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_chat_myra, parent, false)
            MyraViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        if (holder is UserViewHolder) {
            holder.bind(msg)
        } else if (holder is MyraViewHolder) {
            holder.bind(msg)
        }
    }

    override fun getItemCount(): Int = messages.size

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.chatMessageText)
        fun bind(msg: ChatMessage) {
            messageText.text = msg.text
        }
    }

    class MyraViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.chatMessageText)
        fun bind(msg: ChatMessage) {
            messageText.text = msg.text
        }
    }
}
