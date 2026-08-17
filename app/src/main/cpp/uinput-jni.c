#include <jni.h>
#include <linux/uinput.h>
#include <linux/input.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <time.h>
#include <android/log.h>
#include <errno.h>

#define LOG_TAG "uinput-jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Open /dev/uinput and return the file descriptor
JNIEXPORT jint JNICALL Java_com_idk_touchoverlay_InputInjectorService_nativeOpenUinput(JNIEnv *env, jobject thiz) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        LOGE("Failed to open /dev/uinput: %s", strerror(errno));
        return -1;
    }
    LOGD("Opened /dev/uinput, fd=%d", fd);
    return fd;
}

// Close the uinput device
JNIEXPORT jint JNICALL Java_com_idk_touchoverlay_InputInjectorService_nativeCloseUinput(JNIEnv *env, jobject thiz, jint fd) {
    if (fd < 0) return -1;
    
    // Send destroy ioctl before closing
    ioctl(fd, UI_DEV_DESTROY);
    
    int result = close(fd);
    if (result < 0) {
        LOGE("Failed to close uinput fd: %s", strerror(errno));
        return -1;
    }
    LOGD("Closed uinput fd=%d", fd);
    return 0;
}

// Setup the virtual gamepad device
JNIEXPORT jint JNICALL Java_com_idk_touchoverlay_InputInjectorService_nativeSetupGamepad(JNIEnv *env, jobject thiz, jint fd) {
    if (fd < 0) return -1;

    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));

    // Set device properties
    usetup.id.bustype = BUS_USB;
    usetup.id.vendor = 0x045e;      // Xbox controller VID
    usetup.id.product = 0x02dd;     // Xbox controller PID
    usetup.id.version = 1;
    
    strncpy(usetup.name, "Shizuku Virtual Gamepad", UINPUT_MAX_NAME_SIZE - 1);

    // Enable button events
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0) {
        LOGE("Failed to set EV_KEY: %s", strerror(errno));
        return -1;
    }

    // Enable button codes: A, B, X, Y, LB, RB, Select, Start
    int button_codes[] = {
        BTN_A, BTN_B, BTN_X, BTN_Y,
        BTN_TL, BTN_TR,
        BTN_SELECT, BTN_START,
        BTN_THUMBL, BTN_THUMBR,
        BTN_MODE,
        // Arrow keys for d-pad
        KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT
    };
    
    for (int i = 0; i < sizeof(button_codes) / sizeof(button_codes[0]); i++) {
        if (ioctl(fd, UI_SET_KEYBIT, button_codes[i]) < 0) {
            LOGE("Failed to set key bit %d: %s", button_codes[i], strerror(errno));
            return -1;
        }
    }

    // Enable absolute axis events
    if (ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0) {
        LOGE("Failed to set EV_ABS: %s", strerror(errno));
        return -1;
    }

    // Setup axes: left stick (X/Y), right stick (RX/RY), triggers (Z/RZ)
    struct input_absinfo absinfo;
    
    // Left stick X and Y
    memset(&absinfo, 0, sizeof(absinfo));
    absinfo.minimum = -32768;
    absinfo.maximum = 32767;
    absinfo.fuzz = 16;
    absinfo.flat = 128;
    
    if (ioctl(fd, UI_SET_ABSBIT, ABS_X) < 0 ||
        ioctl(fd, UI_ABS_SETUP, ABS_X, &absinfo) < 0) {
        LOGE("Failed to setup ABS_X: %s", strerror(errno));
        return -1;
    }
    
    if (ioctl(fd, UI_SET_ABSBIT, ABS_Y) < 0 ||
        ioctl(fd, UI_ABS_SETUP, ABS_Y, &absinfo) < 0) {
        LOGE("Failed to setup ABS_Y: %s", strerror(errno));
        return -1;
    }

    // Right stick RX and RY
    if (ioctl(fd, UI_SET_ABSBIT, ABS_RX) < 0 ||
        ioctl(fd, UI_ABS_SETUP, ABS_RX, &absinfo) < 0) {
        LOGE("Failed to setup ABS_RX: %s", strerror(errno));
        return -1;
    }
    
    if (ioctl(fd, UI_SET_ABSBIT, ABS_RY) < 0 ||
        ioctl(fd, UI_ABS_SETUP, ABS_RY, &absinfo) < 0) {
        LOGE("Failed to setup ABS_RY: %s", strerror(errno));
        return -1;
    }

    // Triggers Z and RZ (0-255 range)
    memset(&absinfo, 0, sizeof(absinfo));
    absinfo.minimum = 0;
    absinfo.maximum = 255;
    absinfo.fuzz = 0;
    absinfo.flat = 0;
    
    if (ioctl(fd, UI_SET_ABSBIT, ABS_Z) < 0 ||
        ioctl(fd, UI_ABS_SETUP, ABS_Z, &absinfo) < 0) {
        LOGE("Failed to setup ABS_Z: %s", strerror(errno));
        return -1;
    }
    
    if (ioctl(fd, UI_SET_ABSBIT, ABS_RZ) < 0 ||
        ioctl(fd, UI_ABS_SETUP, ABS_RZ, &absinfo) < 0) {
        LOGE("Failed to setup ABS_RZ: %s", strerror(errno));
        return -1;
    }

    // Create the device
    if (ioctl(fd, UI_DEV_SETUP, &usetup) < 0) {
        LOGE("Failed UI_DEV_SETUP: %s", strerror(errno));
        return -1;
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("Failed UI_DEV_CREATE: %s", strerror(errno));
        return -1;
    }

    // Allow device to settle
    usleep(100000);  // 100ms delay
    
    LOGD("Virtual gamepad created successfully (fd=%d)", fd);
    return 0;
}

// Write an input event
JNIEXPORT jint JNICALL Java_com_idk_touchoverlay_InputInjectorService_nativeWriteEvent(JNIEnv *env, jobject thiz, jint fd, jint type, jint code, jint value) {
    if (fd < 0) return -1;

    struct input_event ev[2];
    memset(ev, 0, sizeof(ev));
    
    // Use system clock for timing (required for proper event ordering)
    struct timespec ts;
    clock_gettime(CLOCK_BOOTTIME, &ts);
    
    // First event: the actual input
    ev[0].time.tv_sec = ts.tv_sec;
    ev[0].time.tv_usec = ts.tv_nsec / 1000;
    ev[0].type = type;     // EV_KEY (1), EV_ABS (3), etc.
    ev[0].code = code;     // Button code or axis code
    ev[0].value = value;   // 1=press, 0=release, or axis value

    // Second event: SYN_REPORT to flush
    ev[1].time.tv_sec = ts.tv_sec;
    ev[1].time.tv_usec = ts.tv_nsec / 1000;
    ev[1].type = EV_SYN;
    ev[1].code = SYN_REPORT;
    ev[1].value = 0;

    // Write both events in one go
    ssize_t result = write(fd, ev, sizeof(ev));
    if (result != sizeof(ev)) {
        LOGE("Failed to write events (wrote %zd, expected %zu): %s", result, sizeof(ev), strerror(errno));
        return -1;
    }

    LOGD("Event sent: type=%d code=%d value=%d", type, code, value);
    return 0;
}
