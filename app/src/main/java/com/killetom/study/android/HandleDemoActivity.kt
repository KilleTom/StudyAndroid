package com.killetom.study.android

import android.annotation.SuppressLint
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import kotlinx.android.synthetic.main.activity_handle_demo.*
import java.lang.StringBuilder

class HandleDemoActivity : AppCompatActivity() {


    private val handleCall = Handler.Callback { msg: Message? ->
        Log.i("ypz", "${msg?.what}")
        return@Callback msg?.what == 100
    }

    private var handle: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_handle_demo)

        easy_use_btn.setOnClickListener { baseAsync() }

        intercept_btn.setOnClickListener {
            Thread {
                val h = Handler()
                h.sendMessage(Message())
            }.start()
        }


    }


    private fun baseUse() {
        val handler = @SuppressLint("HandlerLeak")
        object : Handler() {
            override fun handleMessage(msg: Message?) {
                val message = msg ?: return
                val obj = message.obj
                setResult(obj.toString())
            }
        }
        handler.sendMessage(Message().apply {
            obj = "message"
        })
    }

    private fun interceptMessage() {
        val sb = StringBuilder()
        val handler = @SuppressLint("HandlerLeak")
        object : Handler(handleCall) {
            override fun handleMessage(msg: Message?) {
                val message = msg ?: return
                val obj = message.obj

                if (message.what == 103) {
                    sb.append("$obj")
                    setResult(sb.toString())
                } else {
                    sb.append("$obj-")
                }
            }
        }

        for (index in 98..103)
            handler.sendMessage(Message().apply {
                what = index
                obj = index
            })


    }

    private fun setResult(message: String) {
        runOnUiThread {
            message_tv.text = message
        }
    }

    private fun baseAsync() {
        val sb = StringBuilder()
        val asyncHandler = Handler.createAsync(Looper.myLooper(), handleCall)

        val handler = @SuppressLint("HandlerLeak")
        object : Handler() {
            override fun handleMessage(msg: Message?) {
                val message = msg ?: return
                val obj = message.obj
                setResult(obj.toString())
            }
        }

        for (index in 1..103)
            asyncHandler.sendMessage(Message.obtain(handler, index, index))
    }

}
