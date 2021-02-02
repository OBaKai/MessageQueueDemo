# Handler源码学习记录（java层、native层）

模仿Handler原理，使用eventfd+epoll实现Handler基础功能的小案例 -> 
[gayhub地址]: https://github.com/OBaKai/MessageQueueDemo	"gayhub地址"

## java层

#### Handler（线程间切换的工具类）

##### Handler有三种消息
同步消息：最常用的消息；
屏障消息（同步屏障）：该消息无target。在消息队列中插入后会挡住后边的所有同步消息让异步消息先走。撤销该屏障同步消息才能继续通行；
异步消息：享有优先权的消息。

```java
//构函数中有boolean async传参的，都是隐藏的不希望开发者使用。
//mAsynchronous 作用是让该Handler发送的消息全部都是异步消息。
//开发者如果需要用到异步消息，将Message手动setAsynchronous就可以了。
@hide
public Handler(boolean async) {
    ...
    mAsynchronous = async;
}

private boolean enqueueMessage(MessageQueue queue, Message msg, long uptimeMillis) {
        msg.target = this;
        if (mAsynchronous) {
            msg.setAsynchronous(true);
        }
        return queue.enqueueMessage(msg, uptimeMillis);
}
```

#### Looper
```java
private Looper(boolean quitAllowed) {
        mQueue = new MessageQueue(quitAllowed); //创建队列
        mThread = Thread.currentThread(); //用来判断是否在当前线程
}

//静态方法
private static void prepare(boolean quitAllowed) { 
		// 参数quitAllowed，是否允许Looper退出。
		// MainLooper prepareMainLooper 中是false，主线程的Looper不允许退出。子线程的Looper是允许退出的。

        ... 

        sThreadLocal.set(new Looper(quitAllowed)); //Looper保证线程唯一

		...        
}

//静态方法
public static void loop() {
			//由于静态方法，无法直接使用mQueue
			//从sThreadLocal中取Looper，确保取到的是对应的线程Looper
			final Looper me = myLooper();
        	if (me == null) { //prepare检查
            	throw new RuntimeException("No Looper; Looper.prepare() wasn't called on this thread.");
        	}
        	final MessageQueue queue = me.mQueue;

			...

	        for (;;) { //死循环
	        //从队列获取Message
            Message msg = queue.next(); // might block 

            ...
            
            try {
                msg.target.dispatchMessage(msg); //执行事件

                ...

            } finally {

                ...

            }
            
            ...

            msg.recycleUnchecked(); //释放该Message
        }

        ...
}

//注意，这不是静态方法，能直接使用mQueue
public void quit() { //不再接受消息，并且清空所有消息（包括：延迟消息、非延迟消息），最后退出。
    mQueue.quit(false);
}

//注意，这不是静态方法，能直接使用mQueue
public void quitSafely() { //安全退出，不再接受消息，并且清空所有延迟消息。会将所有非延迟消息都派发出去，才退出。
    mQueue.quit(true);
}
```

#### MessageQueue

```java
//native方法
private native static long nativeInit();
private native static void nativeDestroy(long ptr);
private native void nativePollOnce(long ptr, int timeoutMillis);
private native static void nativeWake(long ptr);
private native static boolean nativeIsPolling(long ptr);
private native static void nativeSetFileDescriptorEvents(long ptr, int fd, int events);

private final boolean mQuitAllowed; //是否允许退出（主线程是false的）

Message mMessages; //消息队列Head（链表）

private boolean mQuitting; //是否退出中

private boolean mBlocked; //是否在阻塞中

//消息入队方法
boolean enqueueMessage(Message msg, long when) {
        
        ...

        synchronized (this) {
            
            ...

            msg.when = when; //执行时间 (系统时间 + 延迟时间)
            Message p = mMessages;
            boolean needWake; //是否需要唤醒

            //情况1：当前队列无消息
            //情况2：使用sendMessageAtFrontOfQueue方法入队，这个放啊when就是为0
            //情况3：新来的这条消息执行时间比队列中所有消息的执行时间都要快，给它先执行。
            if (p == null || when == 0 || when < p.when) {
                // New head, wake up the event queue if blocked.
                msg.next = p;
                mMessages = msg;
                needWake = mBlocked;
            }else {
                //当前正在阻塞中
                //p.target == null 队列头是屏障消息
                //新来的这条消息是异步消息
                //需要出发唤醒
                needWake = mBlocked && p.target == null && msg.isAsynchronous();

                //情况1
                //入队的是Msg2
                //当前队列 Msg1 -> null
                //上边p的赋值，p = Msg1
                //for (;;) {
                //    prev = p; //prev = Msg1
                //    p = p.next; //p = null
                //    if (p == null) { //退出循环
                //        break;
                //    }
                //}
                //msg.next = p; //Msg2 -> null
                //prev.next = msg; //Msg1 -> Msg2 -> null

                //情况2 (如果Msg3 when 小于 Msg2，那么会走if的情况3)
                //入队的是Msg3（when 15）
                //当前队列 Msg2（when 10） -> Msg1（when 20） -> null
                //上边p的赋值，p = Msg2
                //for (;;) {
                //    prev = p; //prev = Msg2
                //    p = p.next; //p = Msg1
                //    if (when < p.when) { //15 < 20 退出循环
                //        break;
                //    }
                //}
                //msg.next = p; //Msg3 -> Msg1 -> null
                //prev.next = msg; //Msg2 -> Msg3 -> Msg1 -> null

                Message prev;
                for (;;) {
                    prev = p;
                    p = p.next;
                    if (p == null || when < p.when) {
                        break;
                    }
                    
                    //Msg3（A）
                    //| -> Msg2(A) -> Msg1 -> null
                    //prv = |
                    //p = Msg2(A)
                    //3 -> 2 -> 1 -> null
                    //| -> 3 -> 2 -> 1 -> null
                    //??? 这个逻辑没看懂
                    if (needWake && p.isAsynchronous()) {
                        needWake = false;
                    }
                }
                msg.next = p;
                prev.next = msg;
            }

            // We can assume mPtr != 0 because mQuitting is false.
            if (needWake) {
                nativeWake(mPtr);
            }
        }
        return true;
    }

//消息出队方法
Message next() {
        final long ptr = mPtr;
        if (ptr == 0) {
            return null;
        }

        int pendingIdleHandlerCount = -1; // -1 only during first iteration
        int nextPollTimeoutMillis = 0; //下一次循环的休眠时长
        for (;;) {

            ...

            nativePollOnce(ptr, nextPollTimeoutMillis);

            synchronized (this) {
                final long now = SystemClock.uptimeMillis();
                Message prevMsg = null;
                Message msg = mMessages;
                if (msg != null && msg.target == null) { //当前msg是屏障消息
                    //寻找异步消息，一个个找，直到找到异步消息就退出循环
                    //这时prevMsg肯定是一个同步消息，msg肯定是异步消息
                    //例子 | -> Msg3 -> Msg2(A) -> Msg1 -> null
                    do {
                        prevMsg = msg; //prevMsg = Msg3
                        msg = msg.next; //msg = Msg2(A)
                    } while (msg != null && !msg.isAsynchronous());
                }
                if (msg != null) {
                    if (now < msg.when) {
                        // 这条消息执行时间还没到，设置一个休眠时长，准备进入休眠。
                        nextPollTimeoutMillis = (int) Math.min(msg.when - now, Integer.MAX_VALUE);
                    } else {
                        mBlocked = false;
                        if (prevMsg != null) {
                            //prevMsg不为空，证明msg是异步消息。那么把队列给连接上
                            //例子 队列变成 | -> Msg3 -> Msg1 -> null
                            prevMsg.next = msg.next;
                        } else {
                            //msg为同步消息
                            mMessages = msg.next;
                        }
                        msg.next = null;
                        msg.markInUse();
                        return msg;
                    }
                } else {
                    //没有消息
                    nextPollTimeoutMillis = -1;
                }

                if (mQuitting) { //Looper.quit()调用，触发退出逻辑。
                    dispose();
                    return null;
                }

                //既然都要MessageQueue都准备要阻塞了，那我们来干点别的吧！！！
                //MessageQueue提供了IdleHandler队列，让我们在当前线程空闲的时候，做一些不那么耗时的事情。
                //这样就可以做优先级低的业务逻辑从而提高性能。（例如：在主线程中，防止消息过多导致ui卡顿，可以适当将优先级低的逻辑放到IdleHandler去处理）
                if (pendingIdleHandlerCount < 0
                        && (mMessages == null || now < mMessages.when)) {
                    pendingIdleHandlerCount = mIdleHandlers.size(); //获取IdleHandler队列数量
                }
                if (pendingIdleHandlerCount <= 0) { //连IdleHandler队列都没东西处理，那就阻塞吧
                    mBlocked = true;
                    continue;
                }

                if (mPendingIdleHandlers == null) {
                    mPendingIdleHandlers = new IdleHandler[Math.max(pendingIdleHandlerCount, 4)];
                }
                mPendingIdleHandlers = mIdleHandlers.toArray(mPendingIdleHandlers);
            }

            //遍历IdleHandler队列，调用其queueIdle方法，处理开发者的逻辑。
            for (int i = 0; i < pendingIdleHandlerCount; i++) {
                final IdleHandler idler = mPendingIdleHandlers[i];
                mPendingIdleHandlers[i] = null; // release the reference to the handler

                //keep是提供给开发者选择的，该IdleHandler是一次性的还是重复利用的。
                //true:执行完后不从IdleHandler队列中移除，下一次空闲继续执行。
                //false:执行完后就从IdleHandler队列中移除了
                boolean keep = false;
                try {
                    keep = idler.queueIdle();
                } catch (Throwable t) {
                    Log.wtf(TAG, "IdleHandler threw exception", t);
                }

                if (!keep) {
                    synchronized (this) {
                        mIdleHandlers.remove(idler);
                    }
                }
            }

            // Reset the idle handler count to 0 so we do not run them again.
            pendingIdleHandlerCount = 0;

            // While calling an idle handler, a new message could have been delivered
            // so go back and look again for a pending message without waiting.
            nextPollTimeoutMillis = 0;
        }
    }

//屏障消息入队方法
private int postSyncBarrier(long when) {
        synchronized (this) {
            final int token = mNextBarrierToken++; //屏障消息的身份id，用于移除令牌的
            final Message msg = Message.obtain();
            msg.markInUse(); //默认就是使用中了
            msg.when = when;
            msg.arg1 = token;

            Message prev = null;
            Message p = mMessages;
            if (when != 0) {
                //|（when ）
                //例子：屏障消息：|（when 5），当前队列：Msg2（when 2）-> Msg1（when 5）-> null
                //循环后：prev = Msg1，p = null
                while (p != null && p.when <= when) {
                    prev = p;
                    p = p.next;
                }
            }
            if (prev != null) {
                msg.next = p; // | -> null
                prev.next = msg;// Msg1 -> | -> null
                //队列变成：Msg2 -> Msg1 -> | -> null
            } else {
                msg.next = p;
                mMessages = msg;
            }
            return token; //返回屏障消息的身份id
        }
    }

//移除屏障消息方法
//注意：从next()逻辑可以看到，屏障消息是不会出队的，只能使用removeSyncBarrier方法才能移除掉。
public void removeSyncBarrier(int token) { //传入消息屏障身份id
        synchronized (this) {
            Message prev = null;
            Message p = mMessages;
            //找到token对应的屏障消息
            //走完循环时，p就是该屏障消息
            while (p != null && (p.target != null || p.arg1 != token)) {
                prev = p;
                p = p.next;
            }
            if (p == null) {
                throw new IllegalStateException("The specified message queue synchronization "
                        + " barrier token has not been posted or has already been removed.");
            }
            final boolean needWake;
            //prev不为空，证明这个屏障消息之前还有没有处理的消息
            //什么情况下prev不为空？可能是这个屏障消息不是第一个屏障吧，第2个？第3个？...
            if (prev != null) { 
                prev.next = p.next; //这里了移除屏障消息，让链表重新连接起来
                needWake = false;
            } else {
                mMessages = p.next; //这里移除了屏障消息，让链表重新连接起来
                needWake = mMessages == null || mMessages.target != null;
            }
            p.recycleUnchecked();

            // If the loop is quitting then it is already awake.
            // We can assume mPtr != 0 when mQuitting is false.
            if (needWake && !mQuitting) {
                nativeWake(mPtr);
            }
        }
    }

//退出消息队列
void quit(boolean safe) { //是否为安全退出
        
        ...

        synchronized (this) {
            if (mQuitting) {
                return;
            }
            mQuitting = true;

            if (safe) {
                removeAllFutureMessagesLocked();
            } else {
                removeAllMessagesLocked();
            }

            // We can assume mPtr != 0 because mQuitting was previously false.
            nativeWake(mPtr);
        }
    }

//不安全退出方法
//一个个消息释放掉
private void removeAllMessagesLocked() {
        Message p = mMessages;
        while (p != null) {
            Message n = p.next;
            p.recycleUnchecked();
            p = n;
        }
        mMessages = null;
    }

//安全退出方法
//将延时消息都释放掉，保留非延时消息，让这些消息执行完。
private void removeAllFutureMessagesLocked() {
        final long now = SystemClock.uptimeMillis();
        Message p = mMessages;
        if (p != null) {
            if (p.when > now) {
                removeAllMessagesLocked();
            } else {
                //例如：
                //now = 5; 队列：Msg3（when 2）-> Msg2（when 4）-> Msg1（when 6）-> null
                Message n;
                for (;;) {
                    n = p.next;
                    if (n == null) {
                        return;
                    }
                    if (n.when > now) {
                        break;
                    }
                    p = n;
                }
                //经过循环后，n = Msg1，p = Msg2
                p.next = null; //断开Msg2后边的队伍
                
                //释放后边的延时消息
                do {
                    p = n;
                    n = p.next;
                    p.recycleUnchecked();
                } while (n != null);
            }
        }
    }

```

## native层
#### 源码位置（也放了一份在这里 -> MessageQueueDemo/native_source_code/）
android-6.0/system/core/libutils/Looper.cpp
android-6.0/system/core/include/utils/Looper.h
android-6.0/frameworks/base/core/jni/android_os_MessageQueue.cpp
android-6.0/frameworks/base/core/jni/android_os_MessageQueue.h

```objectivec
static JNINativeMethod gMessageQueueMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit", "()J", (void*)android_os_MessageQueue_nativeInit },
    { "nativeDestroy", "(J)V", (void*)android_os_MessageQueue_nativeDestroy },
    { "nativePollOnce", "(JI)V", (void*)android_os_MessageQueue_nativePollOnce },
    { "nativeWake", "(J)V", (void*)android_os_MessageQueue_nativeWake },
    { "nativeIsPolling", "(J)Z", (void*)android_os_MessageQueue_nativeIsPolling },
    { "nativeSetFileDescriptorEvents", "(JII)V",
            (void*)android_os_MessageQueue_nativeSetFileDescriptorEvents },
};

//注册JNI方法
int register_android_os_MessageQueue(JNIEnv* env) {
    int res = RegisterMethodsOrDie(env, "android/os/MessageQueue", gMessageQueueMethods,
                                   NELEM(gMessageQueueMethods));

    jclass clazz = FindClassOrDie(env, "android/os/MessageQueue");
    gMessageQueueClassInfo.mPtr = GetFieldIDOrDie(env, clazz, "mPtr", "J");
    gMessageQueueClassInfo.dispatchEvents = GetMethodIDOrDie(env, clazz,
            "dispatchEvents", "(II)I");

    return res;
}



//native层核心是Looper对eventfb + epoll的封装。
static jlong android_os_MessageQueue_nativeInit(JNIEnv* env, jclass clazz) {
    NativeMessageQueue* nativeMessageQueue = new NativeMessageQueue(); //创建一个本地的消息队列
    if (!nativeMessageQueue) {
        jniThrowRuntimeException(env, "Unable to allocate native queue");
        return 0;
    }

    nativeMessageQueue->incStrong(env); //强引用指针计数，智能指针（RefBase）

    //强转为jlong，这个jlong是nativeMessageQueue地址
    //并保存到java层，之后java层便可以通过这个地址，强转回nativeMessageQueue指针
    return reinterpret_cast<jlong>(nativeMessageQueue); 
}

NativeMessageQueue::NativeMessageQueue() :
        mPollEnv(NULL), mPollObj(NULL), mExceptionObj(NULL) {
    mLooper = Looper::getForThread(); //从当前线程中获取Looper
    if (mLooper == NULL) { //为空则创建并保存
        mLooper = new Looper(false);
        //通过 pthread_getpecific 和 pthread_setspecific 保证线程唯一
        //类似于java ThreadLocal
        Looper::setForThread(mLooper);
    }
}


/*
 备注：
 native Looper中
    函数
    addFd
    removeFd
    sendMessage
    sendMessageDelayed
    removeMessages
    ...


    结构体
    Message
    Request
    Response
    ...

    类
    MessageHandler
    WeakMessageHandler
    ...

    还有向量mRequest、向量mResponse等等
    都是提供给native层使用Looper的相关逻辑来的。
    与java层无关的。
*/


Looper::Looper(bool allowNonCallbacks) :
        mAllowNonCallbacks(allowNonCallbacks), mSendingMessage(false),
        mPolling(false), mEpollFd(-1), mEpollRebuildRequired(false),
        mNextRequestSeq(0), mResponseIndex(0), mNextMessageUptime(LLONG_MAX) {
    /*
    eventfd相关知识

	api：
	创建一个eventfd对象（就像是打开一个eventfd的文件，类似普通文件的open操作。）
    int eventfd(unsigned int initval, int flags) 用来实现进程(线程)间的 等待/通知(wait/notify) 机制
    initval：该对象是一个内核维护的无符号的64位整型计数器。初始化为initval的值。
    flags：
        EFD_CLOEXEC：文件被设置成 O_CLOEXEC，简单说就是fork子进程时不继承，对于多线程的程序设上这个值不会有错的。
        EFD_NONBLOCK：功能同open的O_NONBLOCK，设对象为非阻塞状态。
	        如果没有设置这个状态的话，read读eventfd，并且计数器的值为0就一直堵塞在read调用当中。
	        要是设置了这个标志，就会返回一个EAGAIN错误(errno = EAGAIN)。
        EFD_SEMAPHORE：支持semophore语义的read，简单说read一次值就减1
    return：用于事件通知的文件描述符

    write()：设置counter值。多次调用counter会累加，例： write(1)；write(2); write(3); -> counter为6
    read()：读取counter值，并将counter值置0，如果是semophore就减1。

    */
    mWakeEventFd = eventfd(0, EFD_NONBLOCK); //mWakeEventFd这里表示唤醒Looper的文件描述符
    LOG_ALWAYS_FATAL_IF(mWakeEventFd < 0, "Could not make wake event fd.  errno=%d", errno);

    AutoMutex _l(mLock);
    rebuildEpollLocked();
}

void Looper::rebuildEpollLocked() {
    if (mEpollFd >= 0) {
#if DEBUG_CALLBACKS
        ALOGD("%p ~ rebuildEpollLocked - rebuilding epoll set", this);
#endif
        //如果存在旧的epoll句柄，就先关闭。
        close(mEpollFd);
    }

    /*
	epoll相关知识
	（select/poll/epoll都是IO多路复用机制，可以同时监控多个描述符，当某个描述符就绪(读或写就绪)，则立刻通知相应程序进行读或写操作。本质上select/poll/epoll都是同步I/O，即读写是阻塞的。）
	在 select/poll中，进程只有在调用一定的方法后，内核才对所有监视的文件描述符进行扫描，
	而epoll事先通过epoll_ctl()来注册一个文件描述符，一旦基于某个文件描述符就绪时，
	内核会采用类似callback的回调机制，迅速激活这个文件描述符，当进程调用epoll_wait() 
	时便得到通知。(此处去掉了遍历文件描述符，而是通过监听回调的的机制。这正是epoll的魅力所在。)
	epoll优势
	监视的描述符数量不受限制，所支持的FD上限是最大可以打开文件的数目，具体数目可以cat /proc/sys/fs/file-max查看，一般来说这个数目和系统内存关系很大，以3G的手机来说这个值为20-30万。
	IO性能不会随着监视fd的数量增长而下降。epoll不同于select和poll轮询的方式，而是通过每个fd定义的回调函数来实现的，只有就绪的fd才会执行回调函数。
	如果没有大量的空闲或者死亡连接，epoll的效率并不会比select/poll高很多。但当遇到大量的空闲连接的场景下，epoll的效率大大高于select/poll。

	api：
	创建函数
	int epoll_create(int size);
	size：监听的描述符个数。内部支持动态扩展的。
	return：返回epoll的fd（ls /proc/<pid>/fd/ 可查；用完epoll后必须调用close()关闭否则可能导致fd被耗尽。）

	事件注册函数
	int epoll_ctl(int epfd, int op, int fd, struct epoll_event *event);
	epfd：是epoll_create()的返回值；
	op：表示op操作，用三个宏来表示，分别代表添加、删除和修改对fd的监听事件；
		EPOLL_CTL_ADD (添加)
		EPOLL_CTL_DEL (删除)
		EPOLL_CTL_MOD（修改）
	fd：需要监听的文件描述符；
	epoll_event：需要监听的事件，struct epoll_event结构如下：
		struct epoll_event {
	    	__uint32_t events;  //Epoll事件
	    		events可取值：(表示对应的文件描述符的操作)
				EPOLLIN ：可读（包括对端SOCKET正常关闭）；
				EPOLLOUT：可写；
				EPOLLERR：错误；
				EPOLLHUP：中断；
				EPOLLPRI：高优先级的可读（这里应该表示有带外数据到来）；
				EPOLLET： 将EPOLL设为边缘触发模式，这是相对于水平触发来说的。
				EPOLLONESHOT：只监听一次事件，当监听完这次事件之后就不再监听该事件
	    	epoll_data_t data;  //用户可用数据
	  	};
    return：0：注册成功  <0：出现错误，需要检查 errno错误码判断错误类型

	等待事件
	int epoll_wait(int epfd, struct epoll_event * events, int maxevents, int timeout);
	epfd：等待epfd上的io事件，最多返回maxevents个事件；
	events：用来从内核得到事件的集合；
	maxevents：events数量，该maxevents值不能大于创建epoll_create()时的size；
	timeout：超时时间（毫秒，0会立即返回）。
    return：0：超时返回  >0：有n个fd触发事件  <0：出现错误，需要检查 errno错误码判断错误类型
    */
    mEpollFd = epoll_create(EPOLL_SIZE_HINT);
    LOG_ALWAYS_FATAL_IF(mEpollFd < 0, "Could not create epoll instance.  errno=%d", errno);

    struct epoll_event eventItem;
  	//memset函数：作用是在一段内存块中填充某个给定的值，它对较大的结构体或数组进行清零操作的一种最快方法
    memset(& eventItem, 0, sizeof(epoll_event));
    eventItem.events = EPOLLIN; //可读事件
    eventItem.data.fd = mWakeEventFd; //eventfd的fd排上用场了。
    int result = epoll_ctl(mEpollFd, EPOLL_CTL_ADD, mWakeEventFd, & eventItem); //注册epoll事件监听

    ...

}




void NativeMessageQueue::pollOnce(JNIEnv* env, jobject pollObj, int timeoutMillis) {
    mPollEnv = env;
    mPollObj = pollObj;
    mLooper->pollOnce(timeoutMillis);
    mPollObj = NULL;
    mPollEnv = NULL;

    ...
}

int Looper::pollOnce(int timeoutMillis, int* outFd, int* outEvents, void** outData) {
    int result = 0;
    for (;;) { //死循环

        ...

        /*
        enum {
            POLL_WAKE = -1, //表示Looper的wake方法被调用，write事件触发
            POLL_CALLBACK = -2, //表示某个被监听fd被触发。
            POLL_TIMEOUT = -3, //表示等待超时
            POLL_ERROR = -4, //表示等待期间发生错误
        };
        */
        if (result != 0) { //当result不等于0时，就会跳出循环，返回到java层

            ...

            return result;
        }

        result = pollInner(timeoutMillis);
    }
}

int Looper::pollInner(int timeoutMillis) {
    
    ...

    struct epoll_event eventItems[EPOLL_MAX_EVENTS]; //事件集合(eventItems)，EPOLL_MAX_EVENTS为最大事件数量，它的值为16
    //等待事件发生或者超时(timeoutMillis)，如果有事件发生就会将放入事件集合(eventItems)，返回的eventCount为事件数量
    //如果没有事件发生进入休眠等待，如果timeoutMillis时间后还没有被唤醒，也会返回0
    int eventCount = epoll_wait(mEpollFd, eventItems, EPOLL_MAX_EVENTS, timeoutMillis);

    ...

    // Check for poll error.
    if (eventCount < 0) {
        ...
        result = POLL_ERROR;
        ...
    }

    // Check for poll timeout.
    if (eventCount == 0) {
        ...
        result = POLL_TIMEOUT;
        ...
    }

    // Handle all events.
    ...
    for (int i = 0; i < eventCount; i++) { //遍历事件集合（eventItems），处理事件
        int fd = eventItems[i].data.fd;
        uint32_t epollEvents = eventItems[i].events;
        if (fd == mWakeEventFd) { //处理eventfd（java层的事件）
            if (epollEvents & EPOLLIN) {
                awoken();
            } else { //其他文件描述符，就进行它们自己的处理逻辑
                ...
            }
        } else {

            ...

        }
    }

    //下面是处理Native的Message
    ...

    return result;
}

void Looper::awoken() {
    ...
    uint64_t counter;
    //该TEMP_FAILURE_RETRY宏定义 用于忽略系统中断造成的错误。常用于系统调用。
    //将eventfd的数据读出来，其实就是一个消费的动作。
    TEMP_FAILURE_RETRY(read(mWakeEventFd, &counter, sizeof(uint64_t))); 
}







void NativeMessageQueue::wake() {
    mLooper->wake();
}


void Looper::wake() {
#if DEBUG_POLL_AND_WAKE
    ALOGD("%p ~ wake", this);
#endif

    uint64_t inc = 1;
    //向eventfd写入1，write调用这样就会激活epoll，从而让pollOnce返回到java层
    ssize_t nWrite = TEMP_FAILURE_RETRY(write(mWakeEventFd, &inc, sizeof(uint64_t)));
    if (nWrite != sizeof(uint64_t)) {
        if (errno != EAGAIN) {
            ALOGW("Could not write wake signal, errno=%d", errno);
        }
    }
}
```