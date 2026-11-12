// virtual_mouse.cpp — 通过 /dev/uinput 注入虚拟鼠标 (需 root chmod 666)
// 提供: 相对移动 (光标) / 左右键 / 滚轮, 系统级语义, 所有 App 通用。

#include <jni.h>
#include <linux/uinput.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstring>
#include <sys/ioctl.h>
#include <android/log.h>

#define LOG_TAG "VirtualMouse"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static int g_fd = -1;

static void emit(int type, int code, int value) {
    if (g_fd < 0) return;
    input_event ev{};
    ev.type = (uint16_t)type;
    ev.code = (uint16_t)code;
    ev.value = value;
    if (write(g_fd, &ev, sizeof(ev)) < 0) {
        LOGE("write ev failed type=%d code=%d", type, code);
    }
}

static void syn() { emit(EV_SYN, SYN_REPORT, 0); }

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_rk3588_gesturelib_VirtualMouse_nativeSetup(JNIEnv*, jclass) {
    if (g_fd >= 0) return JNI_TRUE;  // 已创建
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        LOGE("open /dev/uinput failed (需要 root chmod 666)");
        return JNI_FALSE;
    }
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_REL);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    ioctl(fd, UI_SET_KEYBIT, BTN_LEFT);
    ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT);
    ioctl(fd, UI_SET_RELBIT, REL_X);
    ioctl(fd, UI_SET_RELBIT, REL_Y);
    ioctl(fd, UI_SET_RELBIT, REL_WHEEL);

    uinput_setup us{};
    us.id.bustype = BUS_USB;
    us.id.vendor = 0x3588;
    us.id.product = 0x0001;
    strncpy(us.name, "AirGesture Mouse", UINPUT_MAX_NAME_SIZE - 1);
    if (ioctl(fd, UI_DEV_SETUP, &us) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("uinput dev setup/create failed");
        close(fd);
        return JNI_FALSE;
    }
    g_fd = fd;
    LOGI("virtual mouse created");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_rk3588_gesturelib_VirtualMouse_nativeShutdown(JNIEnv*, jclass) {
    if (g_fd >= 0) {
        ioctl(g_fd, UI_DEV_DESTROY);
        close(g_fd);
        g_fd = -1;
        LOGI("virtual mouse destroyed");
    }
}

/** 相对移动光标 */
JNIEXPORT void JNICALL
Java_com_rk3588_gesturelib_VirtualMouse_nativeRel(JNIEnv*, jclass, jint dx, jint dy) {
    if (g_fd < 0 || (dx == 0 && dy == 0)) return;
    emit(EV_REL, REL_X, dx);
    emit(EV_REL, REL_Y, dy);
    syn();
}

/** 按键: left ? BTN_LEFT : BTN_RIGHT */
JNIEXPORT void JNICALL
Java_com_rk3588_gesturelib_VirtualMouse_nativeButton(JNIEnv*, jclass,
                                                     jboolean left, jboolean down) {
    if (g_fd < 0) return;
    emit(EV_KEY, left ? BTN_LEFT : BTN_RIGHT, down ? 1 : 0);
    syn();
}

/** 滚轮: dy>0 向上 */
JNIEXPORT void JNICALL
Java_com_rk3588_gesturelib_VirtualMouse_nativeWheel(JNIEnv*, jclass, jint dy) {
    if (g_fd < 0 || dy == 0) return;
    emit(EV_REL, REL_WHEEL, dy);
    syn();
}

} // extern "C"
