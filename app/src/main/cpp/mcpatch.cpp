#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <android/log.h>
#include <stdint.h>

#define TAG "MCPatch"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static bool write_protected_uint32(uintptr_t addr, uint32_t value) {
    long page_size = sysconf(_SC_PAGESIZE);
    uintptr_t page = addr & ~(page_size - 1);

    if (mprotect((void *)page, (size_t)page_size, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGE("mprotect RWX failed at %p: %s (errno=%d)", (void *)page, strerror(errno), errno);
        return false;
    }

    *(volatile uint32_t *)addr = value;

    // Flush ARM instruction cache to ensure CPU core sees the updated instruction
    __builtin___clear_cache((char *)addr, (char *)addr + sizeof(uint32_t));

    if (mprotect((void *)page, (size_t)page_size, PROT_READ | PROT_EXEC) != 0) {
        LOGE("mprotect restore RX failed at %p: %s", (void *)page, strerror(errno));
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_os4_musiccover_NativeHelper_patchFastPlayer(JNIEnv *env, jclass clazz, jlong baseAddr) {
    if (baseAddr == 0) {
        LOGE("patchFastPlayer: baseAddr is 0");
        return JNI_FALSE;
    }

    uintptr_t addr1 = (uintptr_t)(baseAddr + 0x42584L);
    uintptr_t addr2 = (uintptr_t)(baseAddr + 0x420a0L);

    uint32_t cur1 = *(volatile uint32_t *)addr1;
    uint32_t cur2 = *(volatile uint32_t *)addr2;

    LOGI("patchFastPlayer: base=0x%lx, cur1=0x%08x, cur2=0x%08x", (unsigned long)baseAddr, cur1, cur2);

    // Target 1: MOV W25, #1 (0x320003f9) -> MOV W25, #7 (0x32000bf9)
    // If already patched (0x32000bf9), skip
    if (cur1 == 0x320003f9) {
        if (!write_protected_uint32(addr1, 0x32000bf9)) {
            LOGE("failed to patch addr1 (0x%lx)", (unsigned long)addr1);
            return JNI_FALSE;
        }
        LOGI("successfully patched addr1 (0x%lx) -> 0x32000bf9", (unsigned long)addr1);
    } else if (cur1 == 0x32000bf9) {
        LOGI("addr1 (0x%lx) already patched", (unsigned long)addr1);
    } else {
        LOGE("unexpected insn at addr1 (0x%lx): 0x%08x", (unsigned long)addr1, cur1);
    }

    // Target 2: MOV W28, #1 (0x320003fc) -> MOV W28, WZR (0x2a1f03fc)
    if (cur2 == 0x320003fc) {
        if (!write_protected_uint32(addr2, 0x2a1f03fc)) {
            LOGE("failed to patch addr2 (0x%lx)", (unsigned long)addr2);
            return JNI_FALSE;
        }
        LOGI("successfully patched addr2 (0x%lx) -> 0x2a1f03fc", (unsigned long)addr2);
    } else if (cur2 == 0x2a1f03fc) {
        LOGI("addr2 (0x%lx) already patched", (unsigned long)addr2);
    } else {
        LOGE("unexpected insn at addr2 (0x%lx): 0x%08x", (unsigned long)addr2, cur2);
    }

    uint32_t ver1 = *(volatile uint32_t *)addr1;
    uint32_t ver2 = *(volatile uint32_t *)addr2;
    LOGI("patchFastPlayer verified: ver1=0x%08x, ver2=0x%08x", ver1, ver2);

    return (ver1 == 0x32000bf9) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_os4_musiccover_NativeHelper_mprotect(JNIEnv *env, jclass clazz, jlong addr, jlong len, jint prot) {
    int ret = mprotect((void *)addr, (size_t)len, (int)prot);
    if (ret != 0) {
        LOGE("mprotect failed at %p len=%ld prot=%d: %s", (void *)addr, (long)len, prot, strerror(errno));
        return JNI_FALSE;
    }
    return JNI_TRUE;
}
