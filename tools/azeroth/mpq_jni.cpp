// JNI bridge from app.azerothsurvivors.Mpq to StormLib: open an MPQ archive read-only, look a file up
// by its in-archive name and read it whole. Calls on one archive must not overlap; Mpq serializes them.
#include <jni.h>
#include <cstdint>
#include <vector>
#include "StormLib.h"

namespace {
struct Utf {
    JNIEnv * env;
    jstring str;
    const char * chars;
    Utf(JNIEnv * e, jstring s) : env(e), str(s), chars(e->GetStringUTFChars(s, nullptr)) {}
    ~Utf() { if (chars) env->ReleaseStringUTFChars(str, chars); }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_azerothsurvivors_Mpq_nativeOpen(JNIEnv * env, jclass, jstring path) {
    Utf p(env, path);
    HANDLE archive = nullptr;
    if (!p.chars || !SFileOpenArchive(p.chars, 0, STREAM_FLAG_READ_ONLY | MPQ_OPEN_NO_LISTFILE | MPQ_OPEN_NO_ATTRIBUTES, &archive)) {
        return 0;
    }
    return reinterpret_cast<jlong>(archive);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_azerothsurvivors_Mpq_nativeRead(JNIEnv * env, jclass, jlong handle, jstring name) {
    HANDLE archive = reinterpret_cast<HANDLE>(handle);
    Utf n(env, name);
    if (!archive || !n.chars || !SFileHasFile(archive, n.chars)) return nullptr;
    HANDLE file = nullptr;
    if (!SFileOpenFileEx(archive, n.chars, SFILE_OPEN_FROM_MPQ, &file)) return nullptr;
    DWORD high = 0;
    DWORD size = SFileGetFileSize(file, &high);
    if (size == SFILE_INVALID_SIZE || high != 0) {
        SFileCloseFile(file);
        return nullptr;
    }
    std::vector<uint8_t> data(size);
    DWORD read = 0;
    bool ok = size == 0 || SFileReadFile(file, data.data(), size, &read, nullptr) || read == size;
    SFileCloseFile(file);
    if (!ok || read != size) return nullptr;
    jbyteArray out = env->NewByteArray(static_cast<jsize>(size));
    if (out && size) env->SetByteArrayRegion(out, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(data.data()));
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_app_azerothsurvivors_Mpq_nativeClose(JNIEnv *, jclass, jlong handle) {
    if (handle) SFileCloseArchive(reinterpret_cast<HANDLE>(handle));
}
