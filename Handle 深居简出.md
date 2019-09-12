# Handle 深居简出

## Handle机制
~~~mermaid
graph LR;
title:工作机制
工作线程(workTask)--做完任务数据回调-->handler
handler--传递数据-->主线程(mainThread)
主线程--update UI-->主线程
~~~
通过使用这套机制，主线程可根据工作线程的需求 更新UI，从而避免线程操作不安全的问题。

## Handle构造函数必不可少三大参数理解

在**Handle**构造函数中，存在三大必不可少的参数：**Looper**、**Callback**、**async[是否开启异步]**；
### Looper作用
其中Looper更是必不可少。在**Handle**构造函数中曾有一段注释是这样写到：**If this thread does not have a looper, this handler won't be able to receive messages so an exception is thrown.**
由此可见，Looper是担任一个Thread/MessageQueue角色。源码分析如下:
~~~java
    public Handler(Callback callback, boolean async) {
        if (FIND_POTENTIAL_LEAKS) {
            final Class<? extends Handler> klass = getClass();
            if ((klass.isAnonymousClass() || 
            		klass.isMemberClass() || 
            		klass.isLocalClass()) &&
                    (klass.getModifiers() & Modifier.STATIC) == 0) {
                Log.w(
                	TAG, 
                	"The following Handler class should be static or leaks might occur: " +klass.getCanonicalName());
            }
        }

        mLooper = Looper.myLooper();
        if (mLooper == null) {
            throw new RuntimeException(
                "Can't create handler inside thread " + Thread.currentThread()
                        + " that has not called Looper.prepare()");
        }
        mQueue = mLooper.mQueue;
        mCallback = callback;
        mAsynchronous = async;
    }
~~~

在我们简单使用的工作场景下，我仅仅需要使用Handle的无参构造、或者是传一个Callback、甚至是一个looper，在不牵涉到传递Looper去构建的Handle的时候，它的最终指向构造函数就是上述函数。
async是一个控制Handler是否属于异步发送消息的一个开关。在API28级别中createAsync这个静态方法创建相应的异步Handler。
利用上述函数，我们深扒一下Looper的作用:
Looper为Handler提供一个MessageQueue，MessageQueue存放消息队列，按照先进先出的原则组装成链表数据结构。然后等等Looper去取发送消息示例如下：
~~~mermaid
graph LR;
title:工作机制
Handler--发射数据enqueueMessage-->MessageQueue
MessageQueue--接收到数据实行存放链表存放-->MessageQueue
Looper--取数据next-->MessageQueue
Looper--发射取到的非空Message dispatchMessage-->Handler
~~~
底层方法调用图关系如下：
~~~sequence
Handler -> MessageQueue : 发射消息\nenqueueMessage(MessageQueue,Message,long)
Looper -> MessageQueue:取消息\nMessageQueue.next()
Looper -> Handler :分发消息\ndispatchMessage

~~~
为什么说Handler最终发射方法是调用了enqueueMessage，还有Runnable？先别急，在我们将Runnable这个对象放入Handler的时候其实是调用了getPostMessage这一方法将Runnable封装成一个Message的对象，不信？？？相关源码如下：

~~~java
    private static Message getPostMessage(Runnable r) {
        Message m = Message.obtain();
        m.callback = r;
        return m;
    }
    private static Message getPostMessage(Runnable r, Object token) {
        Message m = Message.obtain();
        m.obj = token;
        m.callback = r;
        return m;
    }
~~~

在上述方法中可以看到其实我们经常使用Runnable赋予给Handler的时候，他其实是调用了getPostMessage，将Runnable封装为Message中的callBack然后返回一个Message。

其实enqueueMessage作用不止是发射数据那么简单。

~~~java
    private boolean enqueueMessage(MessageQueue queue, Message msg, long uptimeMillis) {
        msg.target = this;
        if (mAsynchronous) {
            msg.setAsynchronous(true);
        }
        return queue.enqueueMessage(msg, uptimeMillis);
    }

    boolean enqueueMessage(Message msg, long when) {
        if (msg.target == null) {
            throw new IllegalArgumentException("Message must have a target.");
        }
        if (msg.isInUse()) {
            throw new IllegalStateException(msg + " This message is already in use.");
        }

        synchronized (this) {
            if (mQuitting) {
                IllegalStateException e = new IllegalStateException(
                        msg.target + " sending message to a Handler on a dead thread");
                Log.w(TAG, e.getMessage(), e);
                msg.recycle();
                return false;
            }

            msg.markInUse();
            msg.when = when;
            Message p = mMessages;
            boolean needWake;
            if (p == null || when == 0 || when < p.when) {
                // New head, wake up the event queue if blocked.
                msg.next = p;
                mMessages = msg;
                needWake = mBlocked;
            } else {
                // Inserted within the middle of the queue.  Usually we don't have to wake
                // up the event queue unless there is a barrier at the head of the queue
                // and the message is the earliest asynchronous message in the queue.
                needWake = mBlocked && p.target == null && msg.isAsynchronous();
                Message prev;
                for (;;) {
                    prev = p;
                    p = p.next;
                    if (p == null || when < p.when) {
                        break;
                    }
                    if (needWake && p.isAsynchronous()) {
                        needWake = false;
                    }
                }
                msg.next = p; // invariant: p == prev.next
                prev.next = msg;
            }

            // We can assume mPtr != 0 because mQuitting is false.
            if (needWake) {
                nativeWake(mPtr);
            }
        }
        return true;
    }
~~~

在上述代码中可以得到，Message在被发射数据的时候，他的tag会以当前发射器（handler）作为标记，当MessageQueue接收的Message已经被使用了或者没有标记的时候会抛出异常，最终利用synchronized这样一个方法保证Message链表的添加的安全。

还记得Looper如何死循环的存活的吗？在Looper死循环中，Looper其中做了一件事就是从MessageQueue中取出消息。分发到所属的Handler中代码如下:

~~~java
for (;;) {
            Message msg = queue.next(); // might block
            if (msg == null) {
                // No message indicates that the message queue is quitting.
                return;
            }

            // This must be in a local variable, in case a UI event sets the logger
            final Printer logging = me.mLogging;
            if (logging != null) {
                logging.println(">>>>> Dispatching to " + msg.target + " " +
                        msg.callback + ": " + msg.what);
            }

            final long traceTag = me.mTraceTag;
            long slowDispatchThresholdMs = me.mSlowDispatchThresholdMs;
            long slowDeliveryThresholdMs = me.mSlowDeliveryThresholdMs;
            if (thresholdOverride > 0) {
                slowDispatchThresholdMs = thresholdOverride;
                slowDeliveryThresholdMs = thresholdOverride;
            }
            final boolean logSlowDelivery = (slowDeliveryThresholdMs > 0) && (msg.when > 0);
            final boolean logSlowDispatch = (slowDispatchThresholdMs > 0);

            final boolean needStartTime = logSlowDelivery || logSlowDispatch;
            final boolean needEndTime = logSlowDispatch;

            if (traceTag != 0 && Trace.isTagEnabled(traceTag)) {
                Trace.traceBegin(traceTag, msg.target.getTraceName(msg));
            }

            final long dispatchStart = needStartTime ? SystemClock.uptimeMillis() : 0;
            final long dispatchEnd;
            try {
                msg.target.dispatchMessage(msg);
                dispatchEnd = needEndTime ? SystemClock.uptimeMillis() : 0;
            } finally {
                if (traceTag != 0) {
                    Trace.traceEnd(traceTag);
                }
            }
            if (logSlowDelivery) {
                if (slowDeliveryDetected) {
                    if ((dispatchStart - msg.when) <= 10) {
                        Slog.w(TAG, "Drained");
                        slowDeliveryDetected = false;
                    }
                } else {
                    if (showSlowLog(slowDeliveryThresholdMs, msg.when, dispatchStart, "delivery",
                            msg)) {
                        // Once we write a slow delivery log, suppress until the queue drains.
                        slowDeliveryDetected = true;
                    }
                }
            }
            if (logSlowDispatch) {
                showSlowLog(slowDispatchThresholdMs, dispatchStart, dispatchEnd, "dispatch", msg);
            }

            if (logging != null) {
                logging.println("<<<<< Finished to " + msg.target + " " + msg.callback);
            }

            // Make sure that during the course of dispatching the
            // identity of the thread wasn't corrupted.
            final long newIdent = Binder.clearCallingIdentity();
            if (ident != newIdent) {
                Log.wtf(TAG, "Thread identity changed from 0x"
                        + Long.toHexString(ident) + " to 0x"
                        + Long.toHexString(newIdent) + " while dispatching to "
                        + msg.target.getClass().getName() + " "
                        + msg.callback + " what=" + msg.what);
            }

            msg.recycleUnchecked();
        }
~~~

在looper的死循环的源码中可以得到Looper不断从MessageQueue.next()中不断取出消息，然后通过一系列的判断进行消息分配。通过调用next()再去调用dispatchMessage()来实现出消息取发。

### Callback的作用

在Handler的构造函数中Callback的作用是一个是否需要预处理的拦截器作用。

~~~mermaid
graph LR;
title:消息分发机制
start[Handler_dispatchMessage]-->judge_Message{Message是否存在Callback}
judge_Message-- YES -->run_MessageCallBack[运行Message中的Callback]
judge_Message-- NO -->Judge_Handler_CallBack{Handler是否有Callback}
Judge_Handler_CallBack--YES-->run_Callback{执行Callback是否拦截}
Judge_Handler_CallBack--NO-->handleMessage[执行handleMessage]
run_Callback--YES-->intercept_Message[拦截消息处理返回True]
run_Callback--NO-->handleMessage[执行handleMessage]
handleMessage-->stop[消息分发完成]
intercept_Message-->stop
run_MessageCallBack-->stop

~~~

在上述流程图可以很清晰看出其实Handler中构造参数Callback就是为了进行消息拦截的作用，而Message中Callback对象往往对应于Handler postRunnable中Runnable这一对象。

## handler常见问题追踪

### 子线程创建运行Handler的血泪

为什么Handler在子线程中运行会抛出异常？

先上一段小菜给客官品尝：

~~~kotl
intercept_btn.setOnClickListener {
            Thread {
                val h = Handler()
                h.sendMessage(Message())
            }.start()
        }
~~~

当我点击这个按钮的按钮的时候App必崩，会出现RuntimeException，信息如下

~~~kotlin
    java.lang.RuntimeException: 
		Can't create handler inside thread 
		Thread[Thread-2,5,main] that has not called Looper.prepare()
        	at android.os.Handler.<init>(Handler.java:205)
        	at android.os.Handler.<init>(Handler.java:118)
~~~

为什么在主线程中创建Handler就不会出现异常呢？原因在于ActivityThread。在ActivityThread中其实Looper.perpare()这一方法早已被调用代码如下:

~~~java
   public static void main(String[] args) {
       //.....省略部分代码
        Looper.prepareMainLooper();
   }

    public static void prepareMainLooper() {
        prepare(false);
        synchronized (Looper.class) {
            if (sMainLooper != null) {
                throw new IllegalStateException("The main Looper has already been prepared.");
            }
            sMainLooper = myLooper();
        }
    }
~~~

在上述代码中就可以其实一启动App的时候ActivityThread在启动的时候就会调用Looper.prepareMainLooper()这一方法从而调用Looper.prepare(false)这一方法。所以在主线程中创建Handler的时候则不会触发这一异常。

流程图如下:

~~~mermaid
graph LR;
title:Looper.prepare的初始化
ActivityMainThread--prepareMainLooper-->Looper
Looper--调用prepare初始化-->Looper
~~~





