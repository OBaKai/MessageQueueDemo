package com.llk.jni

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View

class MainActivity : Activity(){

    private lateinit var msgQueue: MsgQueue

    private object msgCall : MsgCall{
        override fun onCall(msg: String) {
            Log.e("llk", "onCall: $msg")
        }
    };

    fun addMsg(view: View) {
        val b = ((Math.random() + 1) * 5).toInt()

        val msg = Msg()
        msg.msg = "msg_$b"
        msg.call = msgCall

        msgQueue.enqueueMessage(msg, 0)
    }

    fun addDelayMsg(view: View) {
        val b = ((Math.random() + 1) * 5).toInt()

        val msg = Msg()
        msg.msg = "msg(delay)_$b"
        msg.call = msgCall

        msgQueue.enqueueMessage(msg, b * 1000L)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        msgQueue = MsgQueue()
        msgQueue.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        msgQueue.release()
    }
}
