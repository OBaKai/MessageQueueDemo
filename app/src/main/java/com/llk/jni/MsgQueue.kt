package com.llk.jni

import android.os.SystemClock
import android.util.Log
import kotlin.math.min


/**
 * author: llk
 * createDate: 2021/1/31
 * detail:
 */

class MsgQueue : Thread(){

    private external fun nativeInit(): Int
    private external fun nativeDestroy(wakeFd: Int)
    private external fun nativePollOnce(wakeFd: Int, timeoutMillis: Int)
    private external fun nativeWake(fd: Int)

    private var mMsgQueue : Msg? = null

    private var mBlocked: Boolean = false

    private var mJavaWakeFd: Int

    private var isRelease : Boolean = false

    init {
        mJavaWakeFd = nativeInit()
    }

    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }

    fun enqueueMessage(msg: Msg, delayTime: Long) {
        if (isRelease) return

        synchronized(this){
            val time = SystemClock.uptimeMillis() + delayTime

            msg.time = time
            var p = mMsgQueue
            var needWake = false

            if (p == null || time < p.time){
                msg.next = p
                mMsgQueue = msg

                needWake = mBlocked
            }else{
                var prev: Msg
                while (true){
                    prev = p!!
                    p = p.next
                    if (p == null || time < p.time) break
                }
                msg.next = p
                prev.next = msg
            }

            if (needWake)
                nativeWake(mJavaWakeFd)

            Log.e("llk", "enqueueMsg ============ : $needWake")
            var s = "\n"
            var g= mMsgQueue
            while (g != null){
                s += g.toString() + "\n"
                g = g.next
            }
            Log.e("llk", "enqueueMsg: $s")
        }
    }

    private fun next() : Msg? {
        var nextPollTimeoutMillis = 0

        while (true){
            nativePollOnce(mJavaWakeFd, nextPollTimeoutMillis)

            synchronized(this){
                val now = SystemClock.uptimeMillis()
                val msg: Msg? = mMsgQueue
                Log.e("llk", "next: ${msg?.toString()}")
                if (msg != null){
                    if (now < msg.time) {
                        nextPollTimeoutMillis = min(msg.time - now, Long.MAX_VALUE).toInt()
                        mBlocked = true
                    } else {
                        mBlocked = false
                        mMsgQueue = msg.next
                        msg.next = null
                        return msg
                    }
                } else {
                    nextPollTimeoutMillis = -1 //没有消息，一直阻塞
                    mBlocked = true
                }
            }

            if (isRelease) return null
        }
    }

    override fun run() {
        super.run()

        while (true){
            val msg = next()
                ?: return //msg为空直接返回
            msg.call.onCall(msg.msg)
        }
    }

    fun release(){
        isRelease = true
        mMsgQueue = null
        
        if (mJavaWakeFd != 0){
            nativeDestroy(mJavaWakeFd)
            mJavaWakeFd = 0
        }
    }
}

class Msg{
    lateinit var msg: String
    lateinit var call: MsgCall
    var time: Long = 0
    var next: Msg? = null

    override fun toString(): String {
        return "Msg{msg=$msg,time=$time,next=${next?.msg}}"
    }
}

interface MsgCall{
    fun onCall(msg: String)
}