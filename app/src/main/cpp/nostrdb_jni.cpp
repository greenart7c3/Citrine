#include <jni.h>
#include <string>
#include <vector>
#include <map>
#include <sstream>
#include <cstring>
#include <cstdlib>
#include <cerrno>
#include <android/log.h>

#include "nostrdb.h"

#define TAG "NostrDbJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─── Helpers ──────────────────────────────────────────────────────────────────

static void hex_to_bytes(const char *hex, unsigned char *out, int len) {
    for (int i = 0; i < len; ++i) {
        unsigned int byte = 0;
        sscanf(hex + 2 * i, "%02x", &byte);
        out[i] = static_cast<unsigned char>(byte);
    }
}

static std::string bytes_to_hex(const unsigned char *bytes, int len) {
    static const char digits[] = "0123456789abcdef";
    std::string hex;
    hex.reserve(static_cast<size_t>(len) * 2);
    for (int i = 0; i < len; ++i) {
        hex.push_back(digits[bytes[i] >> 4]);
        hex.push_back(digits[bytes[i] & 0x0f]);
    }
    return hex;
}

static std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

// Serialize a note to JSON using nostrdb's built-in serialiser.
// Returns empty string on failure.
static std::string note_to_json(struct ndb_note *note) {
    std::vector<char> buf(65536);
    int sz = ndb_note_json(note, buf.data(), static_cast<int>(buf.size()));
    if (sz <= 0) return {};
    if (sz >= static_cast<int>(buf.size())) {
        buf.resize(static_cast<size_t>(sz) + 1);
        sz = ndb_note_json(note, buf.data(), static_cast<int>(buf.size()));
        if (sz <= 0) return {};
    }
    return {buf.data(), static_cast<size_t>(sz)};
}

// Build a NIP-01 filter from a JSON string and run ndb_query.
// Returns the number of results written into *out_notes (capacity = cap).
// Caller must call ndb_end_query() and ndb_filter_destroy() after use.
static int run_query(
    struct ndb *ndb_ptr,
    const std::string &filter_json,
    struct ndb_txn *txn,
    struct ndb_filter *filter,
    struct ndb_note **out_notes,
    int cap)
{
    // 1 MB scratch buffer for filter data (handles large id/author lists)
    std::vector<unsigned char> fbuf(1024 * 1024);
    int ok = ndb_filter_from_json(
        filter_json.c_str(), static_cast<int>(filter_json.size()),
        filter, fbuf.data(), static_cast<int>(fbuf.size()));
    if (!ok) {
        LOGE("ndb_filter_from_json failed: %s", filter_json.c_str());
        return -1;
    }

    if (!ndb_begin_query(ndb_ptr, txn)) {
        LOGE("ndb_begin_query failed");
        ndb_filter_destroy(filter);
        return -1;
    }

    int count = 0;
    ndb_query(txn, filter, 1, out_notes, cap, &count);
    return count;
}

// ─── JNI exports ──────────────────────────────────────────────────────────────

extern "C" {

// --
// nInit(dbPath: String, mapSize: Long): Long
// --
JNIEXPORT jlong JNICALL
Java_com_greenart7c3_citrine_database_NostrDb_nInit(
    JNIEnv *env, jclass /*cls*/, jstring dbPathJ, jlong mapSize)
{
    std::string path = jstr(env, dbPathJ);

    struct ndb_config cfg;
    ndb_default_config(&cfg);
    ndb_config_set_mapsize(&cfg, static_cast<size_t>(mapSize));

    struct ndb *ndb = nullptr;
    int rc = ndb_init(&ndb, path.c_str(), &cfg);
    if (rc == 0 || ndb == nullptr) {
        LOGE("ndb_init failed at '%s' rc=%d errno=%d", path.c_str(), rc, errno);
        return 0L;
    }
    LOGI("nostrdb opened at '%s'", path.c_str());
    return reinterpret_cast<jlong>(ndb);
}

// --
// nClose(handle: Long)
// --
JNIEXPORT void JNICALL
Java_com_greenart7c3_citrine_database_NostrDb_nClose(
    JNIEnv * /*env*/, jclass /*cls*/, jlong handle)
{
    auto *ndb = reinterpret_cast<struct ndb *>(handle);
    if (ndb) {
        ndb_destroy(ndb);
        LOGI("nostrdb closed");
    }
}

// --
// nIngest(handle: Long, eventJson: String): Boolean
// eventJson: raw event object JSON (not wrapped in an array)
// --
JNIEXPORT jboolean JNICALL
Java_com_greenart7c3_citrine_database_NostrDb_nIngest(
    JNIEnv *env, jclass /*cls*/, jlong handle, jstring eventJsonJ)
{
    auto *ndb = reinterpret_cast<struct ndb *>(handle);
    std::string ev = jstr(env, eventJsonJ);

    // ndb_process_client_event expects ["EVENT", <event_json>]
    std::string wrapped;
    wrapped.reserve(ev.size() + 12);
    wrapped += "[\"EVENT\",";
    wrapped += ev;
    wrapped += ']';

    int rc = ndb_process_client_event(ndb, wrapped.c_str(),
                                      static_cast<int>(wrapped.size()));
    return rc > 0 ? JNI_TRUE : JNI_FALSE;
}

// --
// nQuery(handle: Long, filterJson: String, limit: Int): Array<String>
// Returns an array of event JSON strings matching the NIP-01 filter.
// --
JNIEXPORT jobjectArray JNICALL
Java_com_greenart7c3_citrine_database_NostrDb_nQuery(
    JNIEnv *env, jclass /*cls*/, jlong handle, jstring filterJsonJ, jint limit)
{
    auto *ndb_ptr = reinterpret_cast<struct ndb *>(handle);
    jclass str_cls = env->FindClass("java/lang/String");

    std::string fj = jstr(env, filterJsonJ);
    int cap = limit > 0 ? static_cast<int>(limit) : 500;

    std::vector<struct ndb_note *> notes(static_cast<size_t>(cap));
    struct ndb_txn txn;
    struct ndb_filter filter;

    int count = run_query(ndb_ptr, fj, &txn, &filter, notes.data(), cap);
    if (count < 0) {
        return env->NewObjectArray(0, str_cls, nullptr);
    }

    jobjectArray arr = env->NewObjectArray(count, str_cls, nullptr);
    for (int i = 0; i < count; ++i) {
        std::string json = note_to_json(notes[i]);
        if (!json.empty()) {
            jstring js = env->NewStringUTF(json.c_str());
            env->SetObjectArrayElement(arr, i, js);
            env->DeleteLocalRef(js);
        }
    }

    ndb_end_query(&txn);
    ndb_filter_destroy(&filter);
    return arr;
}

// --
// nCount(handle: Long, filterJson: String, limit: Int): Int
// --
JNIEXPORT jint JNICALL
Java_com_greenart7c3_citrine_database_NostrDb_nCount(
    JNIEnv *env, jclass /*cls*/, jlong handle, jstring filterJsonJ, jint limit)
{
    auto *ndb_ptr = reinterpret_cast<struct ndb *>(handle);
    std::string fj = jstr(env, filterJsonJ);
    int cap = limit > 0 ? static_cast<int>(limit) : 100000;

    std::vector<struct ndb_note *> notes(static_cast<size_t>(cap));
    struct ndb_txn txn;
    struct ndb_filter filter;

    int count = run_query(ndb_ptr, fj, &txn, &filter, notes.data(), cap);
    if (count < 0) return 0;

    ndb_end_query(&txn);
    ndb_filter_destroy(&filter);
    return static_cast<jint>(count);
}

// --
// nAllIds(handle: Long): Array<String>
// Returns hex-encoded event IDs for all stored events (capped at 2M).
// --
JNIEXPORT jobjectArray JNICALL
Java_com_greenart7c3_citrine_database_NostrDb_nAllIds(
    JNIEnv *env, jclass /*cls*/, jlong handle)
{
    auto *ndb_ptr = reinterpret_cast<struct ndb *>(handle);
    jclass str_cls = env->FindClass("java/lang/String");

    const int CAP = 2000000;
    std::vector<struct ndb_note *> notes(CAP);
    struct ndb_txn txn;
    struct ndb_filter filter;

    int count = run_query(ndb_ptr, "{}", &txn, &filter, notes.data(), CAP);
    if (count < 0) {
        return env->NewObjectArray(0, str_cls, nullptr);
    }

    jobjectArray arr = env->NewObjectArray(count, str_cls, nullptr);
    for (int i = 0; i < count; ++i) {
        const unsigned char *id = ndb_note_id(notes[i]);
        std::string hex = bytes_to_hex(id, 32);
        jstring js = env->NewStringUTF(hex.c_str());
        env->SetObjectArrayElement(arr, i, js);
        env->DeleteLocalRef(js);
    }

    ndb_end_query(&txn);
    ndb_filter_destroy(&filter);
    return arr;
}

// --
// nDeleteById(handle: Long, idHex: String): Int
// Returns 1 if deleted, 0 otherwise.
// --
JNIEXPORT jint JNICALL
Java_com_greenart7c3_citrine_database_NostrDb_nDeleteById(
    JNIEnv *env, jclass /*cls*/, jlong handle, jstring idHexJ)
{
    auto *ndb_ptr = reinterpret_cast<struct ndb *>(handle);
    std::string hex = jstr(env, idHexJ);
    if (hex.size() != 64) {
        LOGE("nDeleteById: invalid hex len %zu", hex.size());
        return 0;
    }
    unsigned char id[32];
    hex_to_bytes(hex.c_str(), id, 32);
    const unsigned char *ids[1] = {id};
    int rc = ndb_transaction_delete(ndb_ptr, ids, 1);
    return rc > 0 ? 1 : 0;
}

// --
// nDeleteByIds(handle: Long, idHexArray: Array<String>): Int
// Returns number of events deleted.
// --
JNIEXPORT jint JNICALL
Java_com_greenart7c3_citrine_database_NostrDb_nDeleteByIds(
    JNIEnv *env, jclass /*cls*/, jlong handle, jobjectArray idHexArray)
{
    auto *ndb_ptr = reinterpret_cast<struct ndb *>(handle);
    jsize len = env->GetArrayLength(idHexArray);
    if (len == 0) return 0;

    // Storage for decoded bytes (must outlive the ids[] pointer array)
    std::vector<std::vector<unsigned char>> storage(static_cast<size_t>(len));
    std::vector<const unsigned char *> ids(static_cast<size_t>(len), nullptr);
    int valid = 0;

    for (jsize i = 0; i < len; ++i) {
        auto js = reinterpret_cast<jstring>(env->GetObjectArrayElement(idHexArray, i));
        std::string hex = jstr(env, js);
        env->DeleteLocalRef(js);
        if (hex.size() != 64) continue;
        storage[static_cast<size_t>(valid)].resize(32);
        hex_to_bytes(hex.c_str(), storage[static_cast<size_t>(valid)].data(), 32);
        ids[static_cast<size_t>(valid)] = storage[static_cast<size_t>(valid)].data();
        ++valid;
    }
    if (valid == 0) return 0;

    int rc = ndb_transaction_delete(ndb_ptr, ids.data(), valid);
    return rc > 0 ? valid : 0;
}

// --
// nCountByKind(handle: Long): String
// Returns JSON array: [{"kind":1,"count":42}, ...]
// --
JNIEXPORT jstring JNICALL
Java_com_greenart7c3_citrine_database_NostrDb_nCountByKind(
    JNIEnv *env, jclass /*cls*/, jlong handle)
{
    auto *ndb_ptr = reinterpret_cast<struct ndb *>(handle);

    const int CAP = 2000000;
    std::vector<struct ndb_note *> notes(CAP);
    struct ndb_txn txn;
    struct ndb_filter filter;

    int count = run_query(ndb_ptr, "{}", &txn, &filter, notes.data(), CAP);
    if (count < 0) {
        return env->NewStringUTF("[]");
    }

    std::map<int, int> kind_counts;
    for (int i = 0; i < count; ++i) {
        int k = static_cast<int>(ndb_note_kind(notes[i]));
        kind_counts[k]++;
    }

    ndb_end_query(&txn);
    ndb_filter_destroy(&filter);

    std::ostringstream sb;
    sb << '[';
    bool first = true;
    for (const auto &kv : kind_counts) {
        if (!first) sb << ',';
        first = false;
        sb << "{\"kind\":" << kv.first << ",\"count\":" << kv.second << '}';
    }
    sb << ']';
    return env->NewStringUTF(sb.str().c_str());
}

} // extern "C"
