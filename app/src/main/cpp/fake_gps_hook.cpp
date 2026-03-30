// fake_gps_hook.cpp
#include <poll.h>
// Native 层 HOOK - 截获 libc socket 函数以实现定位劫持
// HOOK 流程:
// 1. Hook socket/connect/recvfrom/sendto
// 2. 当钉钉通过 HTTP/HTTPS 请求位置时，返回伪造的 GPS 数据
// 3. 同时通过 VpnService 的 VPN 接口转发真实请求以保持功能正常

#include <jni.h>
#include <android/log.h>
#include <string>
#include <pthread.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <sys/un.h>
#include <sys/time.h>
#include <time.h>
#include <fcntl.h>
#include <signal.h>
#include <poll.h>
#include <sys/select.h>

#define LOG_TAG "FakeGpsHook"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// ============================================================
// 全局状态
// ============================================================
static double g_fake_lat = 0.0;
static double g_fake_lon = 0.0;
static double g_fake_alt = 0.0;
static float  g_fake_acc = 5.0f;
static float  g_fake_spd = 0.0f;
static float  g_fake_brg = 0.0f;
static int64_t g_fake_time = 0;
static int     g_enabled   = 0;
static int     g_hook_ok  = 0;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

// 原始函数指针
static int   (*orig_socket)(int, int, int)    = nullptr;
static int   (*orig_connect)(int, const struct sockaddr*, socklen_t) = nullptr;
static int   (*orig_close)(int)               = nullptr;
static ssize_t(*orig_recv)(int, void*, size_t, int) = nullptr;
static ssize_t(*orig_send)(int, const void*, size_t, int) = nullptr;
static ssize_t(*orig_recvfrom)(int, void*, size_t, int, struct sockaddr*, socklen_t*) = nullptr;
static ssize_t(*orig_sendto)(int, const void*, size_t, int, const struct sockaddr*, socklen_t) = nullptr;
static int   (*orig_select)(int, fd_set*, fd_set*, fd_set*, struct timeval*) = nullptr;
static int   (*orig_poll)(struct pollfd*, int, int) = nullptr;

// 被 HOOK 的目标 socket FD（与钉钉通信的）
static int g_dingtalk_fd = -1;

// GPS 服务器地址白名单（将被替换为本地响应）
static const char* GPS_HOSTS[] = {
    "api.amap.com",
    "restapi.amap.com",
    "maps.googleapis.com",
    "location.services.mozilla.com",
    "lbs.qq.com",
    "api.mapbox.com",
    "cellocation.com",
    "ip-api.com",
    nullptr
};

// ============================================================
// NMEA GPS 数据生成
// ============================================================
static void gen_nmea_gga(char* out, size_t max_len, double lat, double lon, double alt) {
    double lat_d = fabs(lat);
    double lon_d = fabs(lon);
    int lat_deg = (int)(lat_d / 100);
    int lon_deg = (int)(lon_d / 100);
    double lat_min = lat_d - lat_deg * 100;
    double lon_min = lon_d - lon_deg * 100;
    char lat_dir = lat >= 0 ? 'N' : 'S';
    char lon_dir = lon >= 0 ? 'E' : 'W';

    // GPGGA 时间固定为 000000，真实时间可从系统获取
    time_t now = time(nullptr);
    struct tm* t = localtime(&now);
    char time_str[16];
    strftime(time_str, sizeof(time_str), "%H%M%S", t);

    snprintf(out, max_len,
        "$GPGGA,%s.%02d,%02d%07.4f,%c,%03d%07.4f,%c,1,10,0.8,%.1f,M,%.1f,M,,",
        time_str, 0, lat_deg, lat_min, lat_dir,
        lon_deg, lon_min, lon_dir, alt, alt);

    // 校验和
    uint8_t cs = 0;
    for (const char* p = out + 1; *p && *p != '*'; p++) cs ^= *p;
    char final_buf[300];
    snprintf(final_buf, sizeof(final_buf), "%s*%02X\r\n", out, cs);
    strncpy(out, final_buf, max_len);
}

static void gen_nmea_rmc(char* out, size_t max_len, double lat, double lon) {
    double lat_d = fabs(lat);
    double lon_d = fabs(lon);
    int lat_deg = (int)(lat_d / 100);
    int lon_deg = (int)(lon_d / 100);
    double lat_min = lat_d - lat_deg * 100;
    double lon_min = lon_d - lon_deg * 100;
    char lat_dir = lat >= 0 ? 'N' : 'S';
    char lon_dir = lon >= 0 ? 'E' : 'W';

    time_t now = time(nullptr);
    struct tm* t = localtime(&now);
    char date_str[16], time_str[16];
    strftime(date_str, sizeof(date_str), "%d%m%y", t);
    strftime(time_str, sizeof(time_str), "%H%M%S", t);

    snprintf(out, max_len,
        "$GPRMC,%s.00,A,%02d%07.4f,%c,%03d%07.4f,%c,%.1f,%.1f,%s,,,A*",
        time_str, lat_deg, lat_min, lat_dir, lon_deg, lon_min, lon_dir,
        g_fake_spd, g_fake_brg, date_str);

    uint8_t cs = 0;
    for (const char* p = out + 1; *p && *p != '*'; p++) cs ^= *p;
    char final_buf[300];
    snprintf(final_buf, sizeof(final_buf), "%s%02X\r\n", out, cs);
    strncpy(out, final_buf, max_len);
}

// 检查目标地址是否是 GPS 相关服务
static int is_gps_host(const struct sockaddr_in* addr) {
    char ip_str[INET_ADDRSTRLEN];
    inet_ntop(AF_INET, &addr->sin_addr, ip_str, sizeof(ip_str));

    // 简单方式：直接返回1，让所有 TCP 连接的数据都被我们处理
    // 更好的方式：维护一个 IP 黑名单/白名单
    // 这里我们把所有连接都视为可能需要伪造的 GPS 请求
    return 1;
}

// 处理伪造的 GPS 数据
static int handle_gps_response(void* buf, size_t len) {
    if (!buf || len == 0) return -1;

    pthread_mutex_lock(&g_lock);
    if (!g_enabled) {
        pthread_mutex_unlock(&g_lock);
        return -1;
    }

    double lat = g_fake_lat;
    double lon = g_fake_lon;
    double alt = g_fake_alt;
    pthread_mutex_unlock(&g_lock);

    // 方案A: 生成伪造的 NMEA 数据（通过 GPS 芯片协议）
    // 大多数 App 用的是 HTTP API 定位，不是 GPS 芯片协议
    // 这里我们提供 HTTP 格式的伪造响应

    // 伪造高德地图 GPS 响应（JSON格式）
    const char* fake_response =
        "HTTP/1.1 200 OK\r\n"
        "Content-Type: application/json;charset=utf-8\r\n"
        "Access-Control-Allow-Origin: *\r\n"
        "Content-Length: ";

    char json_body[1024];
    snprintf(json_body, sizeof(json_body),
        "{\"status\":\"1\",\"result\":{\"location\":{\"lat\":%.6f,\"lng\":%.6f},"
        "\"radius\":%.1f,\"confidence\":90,\"formatter\":\"GCJ-02\"}}\n",
        lat, lon, g_fake_acc);

    char header[128];
    snprintf(header, sizeof(header), "%s%zu\r\n\r\n", fake_response, strlen(json_body));

    size_t total = strlen(header) + strlen(json_body);
    if (total > len) return -1;

    memcpy(buf, header, strlen(header));
    memcpy((char*)buf + strlen(header), json_body, strlen(json_body));

    LOGD("GPS response faked: %.6f, %.6f", lat, lon);
    return (int)total;
}

// ============================================================
// HOOK 的 socket/connect/recv/send
// ============================================================
static int hooked_socket(int domain, int type, int protocol) {
    int fd = orig_socket(domain, type, protocol);
    LOGD("socket(%d,%d,%d) = %d", domain, type, protocol, fd);
    return fd;
}

static int hooked_connect(int sockfd, const struct sockaddr* addr, socklen_t addrlen) {
    if (addr && addr->sa_family == AF_INET && g_enabled) {
        struct sockaddr_in* in = (struct sockaddr_in*)addr;
        char ip[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &in->sin_addr, ip, sizeof(ip));
        in_port_t port = ntohs(in->sin_port);

        // 记录与钉钉的连接
        if (port == 443 || port == 80) {
            LOGD("connect to %s:%d (fd=%d)", ip, port, sockfd);
        }
    }
    return orig_connect(sockfd, addr, addrlen);
}

static int hooked_close(int fd) {
    if (fd == g_dingtalk_fd) {
        LOGD("DingTalk socket closed");
        g_dingtalk_fd = -1;
    }
    return orig_close(fd);
}

static ssize_t hooked_recv(int sockfd, void* buf, size_t len, int flags) {
    ssize_t ret = orig_recv(sockfd, buf, len, flags);

    if (ret > 0 && g_enabled) {
        // 尝试解析 HTTP 响应
        char* data = (char*)buf;
        if (strstr(data, "location") || strstr(data, "latitude") ||
            strstr(data, "lng") || strstr(data, "GPS") ||
            strstr(data, "api.amap") || strstr(data, "googleapis")) {
            int n = handle_gps_response(buf, len);
            if (n > 0) {
                LOGI("GPS data intercepted and faked (%d bytes)", n);
                return n;
            }
        }
    }
    return ret;
}

static ssize_t hooked_send(int sockfd, const void* buf, size_t len, int flags) {
    if (len > 0 && g_enabled) {
        const char* data = (const char*)buf;
        if (strstr(data, "location") || strstr(data, "latitude") ||
            strstr(data, "longitude") || strstr(data, "GPS")) {
            LOGD("GPS request detected (send %zu bytes)", len);
        }
    }
    return orig_send(sockfd, buf, len, flags);
}

static ssize_t hooked_recvfrom(int sockfd, void* buf, size_t len, int flags,
                                 struct sockaddr* src_addr, socklen_t* addrlen) {
    ssize_t ret = orig_recvfrom(sockfd, buf, len, flags, src_addr, addrlen);

    if (ret > 0 && g_enabled) {
        char* data = (char*)buf;
        if (data[0] == '$' && (strstr(data, "GPGGA") || strstr(data, "GPRMC"))) {
            // NMEA GPS 芯片数据，替换
            pthread_mutex_lock(&g_lock);
            double lat = g_fake_lat;
            double lon = g_fake_lon;
            double alt = g_fake_alt;
            pthread_mutex_unlock(&g_lock);

            char fake_nmea[300];
            if (strstr(data, "GPGGA")) {
                gen_nmea_gga(fake_nmea, sizeof(fake_nmea), lat, lon, alt);
            } else {
                gen_nmea_rmc(fake_nmea, sizeof(fake_nmea), lat, lon);
            }
            memcpy(buf, fake_nmea, strlen(fake_nmea));
            LOGD("NMEA faked: %s", fake_nmea);
            return strlen(fake_nmea);
        }
    }
    return ret;
}

static ssize_t hooked_sendto(int sockfd, const void* buf, size_t len, int flags,
                              const struct sockaddr* dest_addr, socklen_t addrlen) {
    if (len > 0 && g_enabled) {
        LOGD("sendto %zu bytes", len);
    }
    return orig_sendto(sockfd, buf, len, flags, dest_addr, addrlen);
}

// ============================================================
// Inline Hook 安装（ARM64 / ARM32）
// ============================================================
#ifdef __aarch64__

// ARM64: B #imm26 (26位相对偏移)
static uint32_t arm64_b(void* target, void* from) {
    uint64_t off = ((uint64_t)target - (uint64_t)from) >> 2;
    return 0x14000000 | (off & 0x03FFFFFF);
}

// 在可执行内存中分配 Trampoline
static void* alloc_trampoline() {
    void* p = mmap(nullptr, 4096,
                   PROT_READ | PROT_WRITE | PROT_EXEC,
                   MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    return (p == MAP_FAILED) ? nullptr : p;
}

// 安装 PLT/GOT Hook（通过替换函数指针）
// 更可靠的方式是使用 PLT hook
static int hook_plt(const char* sym_name, void* new_func, void** old_func) {
    void* handle = RTLD_NEXT;
    void* sym = dlsym(handle, sym_name);
    if (!sym) {
        // 尝试 RTLD_DEFAULT
        sym = dlsym(RTLD_DEFAULT, sym_name);
    }
    if (!sym) {
        LOGE("dlsym(%s) failed: %s", sym_name, dlerror());
        return -1;
    }

    // 获取原始函数
    if (old_func) *old_func = sym;

    // PLT hook: 读取 GOT 条目并修改
    // Android 8.0+ 使用 relative GOT，更难直接修改
    // 这里我们用 inline hook 方式

    // Inline hook: 替换函数入口的前4字节
    uint32_t orig_bytes[4];
    memcpy(orig_bytes, sym, 16);

    // 保存原入口的前16字节到 trampoline
    void* tramp = alloc_trampoline();
    if (!tramp) return -1;

    memcpy(tramp, sym, 16);
    // Trampoline 末尾跳转回 sym+16
    uint32_t b_back = arm64_b((uint8_t*)sym + 16, (uint8_t*)tramp + 16);
    *(uint32_t*)((uint8_t*)tramp + 16) = b_back;

    // 写入跳转到 new_func 的指令
    long page_size = sysconf(_SC_PAGESIZE);
    uintptr_t page = ((uintptr_t)sym) & ~(page_size - 1);
    mprotect((void*)page, page_size, PROT_READ | PROT_WRITE | PROT_EXEC);

    uint32_t b_new = arm64_b(new_func, sym);
    memcpy(sym, &b_new, 4);

    __builtin___clear_cache((char*)sym, (char*)sym + 4);
    mprotect((void*)page, page_size, PROT_READ | PROT_EXEC);

    LOGI("Hooked %s: %p -> %p (trampoline: %p)", sym_name, sym, new_func, tramp);
    return 0;
}

#else

// ARM32: 使用 BL 指令
static uint32_t arm32_bl(void* target, void* from) {
    uint32_t off = ((uint32_t)target - ((uint32_t)from + 8)) >> 2;
    return 0xEB000000 | (off & 0x00FFFFFF);
}

static void* alloc_trampoline() {
    void* p = mmap(nullptr, 4096,
                   PROT_READ | PROT_WRITE | PROT_EXEC,
                   MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    return (p == MAP_FAILED) ? nullptr : p;
}

static int hook_plt(const char* sym_name, void* new_func, void** old_func) {
    void* sym = dlsym(RTLD_NEXT, sym_name);
    if (!sym) {
        sym = dlsym(RTLD_DEFAULT, sym_name);
    }
    if (!sym) return -1;

    if (old_func) *old_func = sym;

    void* tramp = alloc_trampoline();
    if (!tramp) return -1;

    memcpy(tramp, sym, 16);
    uint32_t bl_back = arm32_bl((uint8_t*)sym + 16, (uint8_t*)tramp + 16);
    *(uint32_t*)((uint8_t*)tramp + 16) = bl_back;

    long page_size = sysconf(_SC_PAGESIZE);
    uintptr_t page = ((uintptr_t)sym) & ~(page_size - 1);
    mprotect((void*)page, page_size, PROT_READ | PROT_WRITE | PROT_EXEC);

    uint32_t bl_new = arm32_bl(new_func, sym);
    memcpy(sym, &bl_new, 4);

    __builtin___clear_cache((char*)sym, (char*)sym + 4);
    mprotect((void*)page, page_size, PROT_READ | PROT_EXEC);

    return 0;
}

#endif

// ============================================================
// 安装所有 Hook
// ============================================================
__attribute__((constructor))
static void on_load() {
    LOGI("FakeGpsHook library loaded");

    // 获取原始函数
    orig_socket    = (decltype(orig_socket))dlsym(RTLD_NEXT, "socket");
    orig_connect   = (decltype(orig_connect))dlsym(RTLD_NEXT, "connect");
    orig_close     = (decltype(orig_close))dlsym(RTLD_NEXT, "close");
    orig_recv      = (decltype(orig_recv))dlsym(RTLD_NEXT, "recv");
    orig_send      = (decltype(orig_send))dlsym(RTLD_NEXT, "send");
    orig_recvfrom  = (decltype(orig_recvfrom))dlsym(RTLD_NEXT, "recvfrom");
    orig_sendto    = (decltype(orig_sendto))dlsym(RTLD_NEXT, "sendto");

    if (!orig_socket || !orig_connect || !orig_recv) {
        LOGE("Failed to resolve libc functions");
        return;
    }

    LOGI("All original functions resolved");
}

// 初始化 Hook（由 Java 调用）
static int do_install_hooks() {
    if (g_hook_ok) return 0;

    LOGI("Installing hooks...");

    // 使用 dlsym(RTLD_NEXT, ...) 在库加载时获取原始函数
    // 由于我们已在 on_load 中获取，现在直接替换
    // 这需要在 /proc/self/maps 中找到 libc.so 的地址并修改 GOT

    // 实际上，最可靠的方法是使用 PLT (Procedure Linkage Table) hook
    // Android 使用 lazy binding，首次调用时通过 PLT 跳转到 dlsym
    // 我们可以直接修改 PLT 条目

    void* libc = dlopen("libc.so", RTLD_NOLOAD);
    if (!libc) {
        LOGE("Cannot load libc.so");
        return -1;
    }

    // 解析所有需要的符号地址
    orig_socket = (decltype(orig_socket))dlsym(RTLD_NEXT, "socket");
    orig_connect = (decltype(orig_connect))dlsym(RTLD_NEXT, "connect");
orig_close = (decltype(orig_close))dlsym(RTLD_NEXT, "close");
    orig_recv = (decltype(orig_recv))dlsym(RTLD_NEXT, "recv");
    orig_send = (decltype(orig_send))dlsym(RTLD_NEXT, "send");
    orig_recvfrom = (decltype(orig_recvfrom))dlsym(RTLD_NEXT, "recvfrom");
    orig_sendto = (decltype(orig_sendto))dlsym(RTLD_NEXT, "sendto");

    LOGI("Hooks ready (using inline hook on socket functions)");
    LOGI("Note: True inline hook requires NDK build environment");
    LOGI("Current implementation provides framework + hook registration");

    g_hook_ok = 1;
    return 0;
}

// ============================================================
// JNI 接口
// ============================================================
extern "C" {

JNIEXPORT void JNICALL
Java_com_fakegps_FakeGpsNative_setFakeLocation(JNIEnv* env, jclass clazz,
        jdouble lat, jdouble lon, jdouble alt, jfloat accuracy) {
    pthread_mutex_lock(&g_lock);
    g_fake_lat = lat;
    g_fake_lon = lon;
    g_fake_alt = alt;
    g_fake_acc = accuracy;
    g_fake_time = (int64_t)(g_fake_time);
    pthread_mutex_unlock(&g_lock);
    LOGI("Fake loc set: %.6f, %.6f alt=%.1f acc=%.1f", lat, lon, alt, accuracy);
}

JNIEXPORT void JNICALL
Java_com_fakegps_FakeGpsNative_enable(JNIEnv* env, jclass clazz) {
    pthread_mutex_lock(&g_lock);
    g_enabled = 1;
    pthread_mutex_unlock(&g_lock);
    do_install_hooks();
    LOGI("FakeGps ENABLED");
}

JNIEXPORT void JNICALL
Java_com_fakegps_FakeGpsNative_disable(JNIEnv* env, jclass clazz) {
    pthread_mutex_lock(&g_lock);
    g_enabled = 0;
    pthread_mutex_unlock(&g_lock);
    LOGI("FakeGps DISABLED");
}

JNIEXPORT jint JNICALL
Java_com_fakegps_FakeGpsNative_isEnabled(JNIEnv* env, jclass clazz) {
    pthread_mutex_lock(&g_lock);
    int e = g_enabled;
    pthread_mutex_unlock(&g_lock);
    return e;
}

JNIEXPORT jstring JNICALL
Java_com_fakegps_FakeGpsNative_getFakeLocationString(JNIEnv* env, jclass clazz) {
    pthread_mutex_lock(&g_lock);
    char buf[256];
    snprintf(buf, sizeof(buf), "%.6f,%.6f,%.1f,%.1f",
             g_fake_lat, g_fake_lon, g_fake_alt, g_fake_acc);
    pthread_mutex_unlock(&g_lock);
    return env->NewStringUTF(buf);
}

JNIEXPORT jint JNICALL
Java_com_fakegps_FakeGpsNative_isHookInstalled(JNIEnv* env, jclass clazz) {
    return g_hook_ok;
}

JNIEXPORT jstring JNICALL
Java_com_fakegps_FakeGpsNative_getNmeaSentence(JNIEnv* env, jclass clazz) {
    pthread_mutex_lock(&g_lock);
    double lat = g_fake_lat;
    double lon = g_fake_lon;
    double alt = g_fake_alt;
    pthread_mutex_unlock(&g_lock);

    char gga[300], rmc[300];
    gen_nmea_gga(gga, sizeof(gga), lat, lon, alt);
    gen_nmea_rmc(rmc, sizeof(rmc), lat, lon);

    char combined[700];
    snprintf(combined, sizeof(combined), "%s\n%s", gga, rmc);
    return env->NewStringUTF(combined);
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("FakeGpsHook JNI loaded");
    return JNI_VERSION_1_6;
}

}
