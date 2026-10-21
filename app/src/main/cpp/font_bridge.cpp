#include "font_paths.h"
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <unordered_map>

// Public LSPosed native-hook ABI, shared by legacy and modern native library registration.
using Hook = int (*)(void*, void*, void**);
using Loaded = void (*)(const char*, void*);
struct NativeAPIEntries { uint32_t version; Hook hook; int (*unhook)(void*); };
namespace {
Hook hookFunction;
std::atomic<bool> installed{false};
struct File {
    int fd;
    explicit File(int value) : fd(value) {}
    ~File() { close(fd); }
};
using Files = std::unordered_map<std::string, std::shared_ptr<File>>;
std::shared_ptr<const Files> files;
std::mutex installation;
std::unordered_map<void*, void*> originals;
int (*openOriginal)(const char*, int, ...) = nullptr;
int (*open64Original)(const char*, int, ...) = nullptr;
int (*openatOriginal)(int, const char*, int, ...) = nullptr;
int (*openat64Original)(int, const char*, int, ...) = nullptr;
int (*open2Original)(const char*, int) = nullptr;
int (*openat2Original)(int, const char*, int) = nullptr;
FILE* (*fopenOriginal)(const char*, const char*) = nullptr;
FILE* (*fopen64Original)(const char*, const char*) = nullptr;
int (*statOriginal)(const char*, struct stat*) = nullptr;
int (*stat64Original)(const char*, struct stat64*) = nullptr;
int (*fstatatOriginal)(int, const char*, struct stat*, int) = nullptr;
int (*fstatat64Original)(int, const char*, struct stat64*, int) = nullptr;
int (*accessOriginal)(const char*, int) = nullptr;

void log(const char* message) { __android_log_write(ANDROID_LOG_ERROR, "ColorOSMod", message); }
bool writable(int flags) {
    return (flags & O_ACCMODE) != O_RDONLY || (flags & (O_CREAT | O_TRUNC | O_APPEND)) != 0;
}
std::shared_ptr<File> lookup(const char* path, int directory = AT_FDCWD) {
    if (!path) return {};
    // Avoid path resolution and shared-pointer traffic for unrelated application I/O.
    if (!strstr(path, "fonts") && !strstr(path, "font_fallback.xml") && !strstr(path, ".ttf")) return {};
    auto snapshot = std::atomic_load(&files);
    if (!snapshot) return {};
    std::string absolute;
    if (*path == '/') absolute = path;
    else {
        char buffer[4096];
        if (directory == AT_FDCWD) {
            if (!getcwd(buffer, sizeof(buffer))) return {};
        } else {
            char fd[64]; snprintf(fd, sizeof(fd), "/proc/self/fd/%d", directory);
            ssize_t count = readlink(fd, buffer, sizeof(buffer) - 1);
            if (count < 0) return {};
            buffer[count] = 0;
        }
        absolute = std::string(buffer) + "/" + path;
    }
    auto found = snapshot->find(fontbridge::normalize(absolute));
    return found == snapshot->end() ? std::shared_ptr<File>{} : found->second;
}
int reopen(const std::shared_ptr<File>& file, int flags) {
    char path[64]; snprintf(path, sizeof(path), "/proc/self/fd/%d", file->fd);
    // Reopen the pre-authorized FD, not a module private path. Each stream has its own seek offset.
    return openOriginal(path, flags | O_CLOEXEC, 0);
}
int openProxy(const char* path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) { va_list args; va_start(args, flags); mode = va_arg(args, int); va_end(args); }
    if (!writable(flags)) if (auto file = lookup(path)) return reopen(file, flags);
    return openOriginal(path, flags, mode);
}
int open64Proxy(const char* path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) { va_list args; va_start(args, flags); mode = va_arg(args, int); va_end(args); }
    if (!writable(flags)) if (auto file = lookup(path)) return reopen(file, flags);
    return open64Original(path, flags, mode);
}
int openatProxy(int dir, const char* path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) { va_list args; va_start(args, flags); mode = va_arg(args, int); va_end(args); }
    if (!writable(flags)) if (auto file = lookup(path, dir)) return reopen(file, flags);
    return openatOriginal(dir, path, flags, mode);
}
int openat64Proxy(int dir, const char* path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) { va_list args; va_start(args, flags); mode = va_arg(args, int); va_end(args); }
    if (!writable(flags)) if (auto file = lookup(path, dir)) return reopen(file, flags);
    return openat64Original(dir, path, flags, mode);
}
int open2Proxy(const char* path, int flags) {
    if (!writable(flags)) if (auto file = lookup(path)) return reopen(file, flags);
    return open2Original(path, flags);
}
int openat2Proxy(int dir, const char* path, int flags) {
    if (!writable(flags)) if (auto file = lookup(path, dir)) return reopen(file, flags);
    return openat2Original(dir, path, flags);
}
FILE* stream(const std::shared_ptr<File>& file, const char* mode) {
    int fd = reopen(file, O_RDONLY);
    if (fd < 0) return nullptr;
    FILE* result = fdopen(fd, mode);
    if (!result) close(fd);
    return result;
}
FILE* fopenProxy(const char* path, const char* mode) {
    if (mode && mode[0] == 'r' && !strchr(mode, '+')) if (auto file = lookup(path)) return stream(file, mode);
    return fopenOriginal(path, mode);
}
FILE* fopen64Proxy(const char* path, const char* mode) {
    if (mode && mode[0] == 'r' && !strchr(mode, '+')) if (auto file = lookup(path)) return stream(file, mode);
    return fopen64Original(path, mode);
}
int statProxy(const char* path, struct stat* result) {
    if (auto file = lookup(path)) return fstat(file->fd, result);
    return statOriginal(path, result);
}
int stat64Proxy(const char* path, struct stat64* result) {
    if (auto file = lookup(path)) return fstat64(file->fd, result);
    return stat64Original(path, result);
}
int fstatatProxy(int dir, const char* path, struct stat* result, int flags) {
    if (auto file = lookup(path, dir)) return fstat(file->fd, result);
    return fstatatOriginal(dir, path, result, flags);
}
int fstatat64Proxy(int dir, const char* path, struct stat64* result, int flags) {
    if (auto file = lookup(path, dir)) return fstat64(file->fd, result);
    return fstatat64Original(dir, path, result, flags);
}
int accessProxy(const char* path, int mode) {
    if (lookup(path)) { if ((mode & (W_OK | X_OK)) == 0) return 0; errno = EACCES; return -1; }
    return accessOriginal(path, mode);
}
template<typename T> bool intercept(const char* name, void* replacement, T* backup, bool required = false) {
    void* target = dlsym(RTLD_DEFAULT, name);
    if (!target) return !required;
    auto found = originals.find(target);
    if (found != originals.end()) { *backup = reinterpret_cast<T>(found->second); return true; }
    void* original = nullptr;
    if (hookFunction(target, replacement, &original) != 0 || !original) return false;
    *backup = reinterpret_cast<T>(original);
    originals[target] = original;
    return true;
}
void install() {
    std::lock_guard<std::mutex> lock(installation);
    if (installed || !hookFunction) return;
    bool okay = intercept("open", reinterpret_cast<void*>(openProxy), &openOriginal, true);
    okay &= intercept("openat", reinterpret_cast<void*>(openatProxy), &openatOriginal, true);
    okay &= intercept("fopen", reinterpret_cast<void*>(fopenProxy), &fopenOriginal, true);
    okay &= intercept("stat", reinterpret_cast<void*>(statProxy), &statOriginal, true);
    okay &= intercept("open64", reinterpret_cast<void*>(open64Proxy), &open64Original);
    okay &= intercept("openat64", reinterpret_cast<void*>(openat64Proxy), &openat64Original);
    okay &= intercept("__open_2", reinterpret_cast<void*>(open2Proxy), &open2Original);
    okay &= intercept("__openat_2", reinterpret_cast<void*>(openat2Proxy), &openat2Original);
    okay &= intercept("fopen64", reinterpret_cast<void*>(fopen64Proxy), &fopen64Original);
    okay &= intercept("stat64", reinterpret_cast<void*>(stat64Proxy), &stat64Original);
    okay &= intercept("fstatat", reinterpret_cast<void*>(fstatatProxy), &fstatatOriginal);
    okay &= intercept("fstatat64", reinterpret_cast<void*>(fstatat64Proxy), &fstatat64Original);
    okay &= intercept("access", reinterpret_cast<void*>(accessProxy), &accessOriginal);
    installed = okay;
    if (!okay) log("HOOK FAIL native font file redirects");
}
// Copy only when the framework FD cannot be reopened under this process's SELinux domain.
int retain(int input) {
    char path[64]; snprintf(path, sizeof(path), "/proc/self/fd/%d", input);
    int fd = openOriginal(path, O_RDONLY | O_CLOEXEC, 0);
    if (fd >= 0) return fd;
    fd = static_cast<int>(syscall(__NR_memfd_create, "ColorOSModFont", 3 /* CLOEXEC | ALLOW_SEALING */));
    if (fd < 0) return -1;
    char buffer[65536]; off_t position = 0; ssize_t count;
    while ((count = pread(input, buffer, sizeof(buffer), position)) > 0) {
        ssize_t written = 0;
        while (written < count) {
            ssize_t result = write(fd, buffer + written, count - written);
            if (result <= 0) { close(fd); return -1; }
            written += result;
        }
        position += count;
        if (position > 100 * 1024 * 1024) { close(fd); return -1; }
    }
    if (count < 0 || lseek(fd, 0, SEEK_SET) < 0) { close(fd); return -1; }
    fcntl(fd, F_ADD_SEALS, F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL);
    return fd;
}
}
extern "C" __attribute__((visibility("default"), used)) Loaded native_init(const NativeAPIEntries* entries) {
    if (entries) hookFunction = entries->hook;
    install();
    return nullptr;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_rikumi_colorosmod_hooks_NativeFontHooks_configure(JNIEnv* env, jclass, jobjectArray paths, jintArray descriptors) {
    if (!installed) return false;
    jsize count = env->GetArrayLength(paths);
    if (count < 1 || count > 128 || env->GetArrayLength(descriptors) != count) return false;
    auto snapshot = std::make_shared<Files>();
    jint* inputs = env->GetIntArrayElements(descriptors, nullptr);
    for (int i = 0; i < count; i++) {
        auto path = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
        const char* text = env->GetStringUTFChars(path, nullptr);
        std::string normalized = fontbridge::normalize(text);
        env->ReleaseStringUTFChars(path, text); env->DeleteLocalRef(path);
        if (!fontbridge::allowed(normalized)) { env->ReleaseIntArrayElements(descriptors, inputs, JNI_ABORT); return false; }
        int fd = retain(inputs[i]);
        if (fd < 0) { env->ReleaseIntArrayElements(descriptors, inputs, JNI_ABORT); return false; }
        (*snapshot)[normalized] = std::make_shared<File>(fd);
    }
    env->ReleaseIntArrayElements(descriptors, inputs, JNI_ABORT);
    std::atomic_store(&files, std::shared_ptr<const Files>(snapshot));
    log("HOOK OK LSPosed native font file redirects");
    return true;
}
