#include <jni.h>
#include <string>
#include <android/log.h>

#include <sys/eventfd.h>
#include <sys/epoll.h>

#include <unistd.h>
#include <fcntl.h>

const char * TAG = "llk";

static const int EPOLL_MAX_SIZE = 1;

int mEpollFd = -1;

/**
 * 初始化方法
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_llk_jni_MsgQueue_nativeInit(JNIEnv *env, jobject thiz) {
    int wake_fd = eventfd(0, EFD_NONBLOCK);

    if (mEpollFd >= 0){
        close(mEpollFd);
    }
    mEpollFd = epoll_create(EPOLL_MAX_SIZE);

    epoll_event eventItem = epoll_event();
    eventItem.events = EPOLLIN;
    eventItem.data.fd = wake_fd;
    int ctl_result = epoll_ctl(mEpollFd, EPOLL_CTL_ADD, wake_fd, &eventItem);

    __android_log_print(ANDROID_LOG_INFO,
                        TAG,
                        "nativeInit mEpollFd=%d ctl_result=%d, return wake_fd=%d",
                        mEpollFd, ctl_result, wake_fd);

    return wake_fd;
}

/**
 * 阻塞方法
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_llk_jni_MsgQueue_nativePollOnce(JNIEnv *env, jobject thiz,
                                         jint wake_fd,
                                         jint timeout_millis) {
    __android_log_print(ANDROID_LOG_INFO,
            TAG,
            "nativePollOnce wake_fd=%d timeout_millis=%d", wake_fd, timeout_millis);

    struct epoll_event eventItems[EPOLL_MAX_SIZE];
    int eventCount = epoll_wait(mEpollFd, eventItems, EPOLL_MAX_SIZE, timeout_millis);
    if (eventCount < 0){
        __android_log_print(ANDROID_LOG_ERROR,
                            TAG,
                            "nativePollOnce fail, eventCount=%d", eventCount);
    } else if (eventCount == 0){
        __android_log_print(ANDROID_LOG_ERROR,
                            TAG,
                            "nativePollOnce timeout!");
    } else{
        for (int i = 0; i < eventCount; i++) {
            uint32_t epollEvents = eventItems[i].events;
            int fd = eventItems[i].data.fd;
            if (fd == wake_fd && (epollEvents & EPOLLIN)){
                uint64_t counter;
                read(wake_fd, &counter, sizeof(uint64_t));
            }
        }
    }
}

/**
 * 唤醒方法
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_llk_jni_MsgQueue_nativeWake(JNIEnv *env, jobject thiz, jint wake_fd) {
    __android_log_print(ANDROID_LOG_INFO,
                        TAG,
                        "nativePollOnce wake_fd=%d", wake_fd);
    uint64_t inc = 1;
    ssize_t write_result = write(wake_fd, &inc, sizeof(uint64_t));
    __android_log_print(ANDROID_LOG_INFO,
                        TAG,
                        "nativePollOnce write_result=%d", write_result);
}

/**
 * 销毁方法
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_llk_jni_MsgQueue_nativeDestroy(JNIEnv *env, jobject thiz,
                                        jint wake_fd) {
    __android_log_print(ANDROID_LOG_INFO,
                        TAG,
                        "nativeDestroy wake_fd=%d", wake_fd);

    if (mEpollFd >= 0){
        close(mEpollFd);
        mEpollFd = -1;
    }

    close(wake_fd);
}