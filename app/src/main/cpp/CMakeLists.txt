cmake_minimum_required(VERSION 3.22.1)
project("citrine_nostrdb")

# ──────────────────────────────────────────────────────────────────────────────
# Source layout expected after running scripts/setup_nostrdb.sh:
#
#   app/src/main/cpp/
#   ├── CMakeLists.txt       (this file)
#   ├── nostrdb_jni.cpp      (our JNI bridge)
#   └── nostrdb/             (git clone of github.com/damus-io/nostrdb)
#       ├── nostrdb.c
#       ├── nostrdb.h
#       └── deps/
#           ├── lmdb/        (mdb.c, midl.c, lmdb.h)
#           ├── secp256k1/   (bitcoin secp256k1 bundled with nostrdb)
#           └── flatcc/      (flatbuffers C compiler runtime)
# ──────────────────────────────────────────────────────────────────────────────

set(NOSTRDB_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/nostrdb")
set(LMDB_DIR    "${NOSTRDB_ROOT}/deps/lmdb")
set(SECP_DIR    "${NOSTRDB_ROOT}/deps/secp256k1")
set(FLATCC_DIR  "${NOSTRDB_ROOT}/deps/flatcc")

# ── secp256k1 ──────────────────────────────────────────────────────────────────
add_library(secp256k1_ndb STATIC
    "${SECP_DIR}/src/secp256k1.c"
)
target_include_directories(secp256k1_ndb PRIVATE
    "${SECP_DIR}"
    "${SECP_DIR}/src"
    "${SECP_DIR}/include"
)
target_compile_definitions(secp256k1_ndb PRIVATE
    SECP256K1_BUILD
    ENABLE_MODULE_RECOVERY
    USE_NUM_NONE
    USE_FIELD_10X26
    USE_SCALAR_8X32
    ECMULT_WINDOW_SIZE=15
    ECMULT_GEN_PREC_BITS=4
)
target_compile_options(secp256k1_ndb PRIVATE -fvisibility=hidden -w)

# ── flatcc runtime ─────────────────────────────────────────────────────────────
file(GLOB FLATCC_RUNTIME_SRCS "${FLATCC_DIR}/src/runtime/*.c")
add_library(flatcc_ndb STATIC ${FLATCC_RUNTIME_SRCS})
target_include_directories(flatcc_ndb PUBLIC "${FLATCC_DIR}/include")
target_compile_options(flatcc_ndb PRIVATE -fvisibility=hidden -w)

# ── lmdb ───────────────────────────────────────────────────────────────────────
add_library(lmdb_ndb STATIC
    "${LMDB_DIR}/mdb.c"
    "${LMDB_DIR}/midl.c"
)
target_include_directories(lmdb_ndb PUBLIC "${LMDB_DIR}")
target_compile_definitions(lmdb_ndb PRIVATE MDB_USE_POSIX_MUTEX)
target_compile_options(lmdb_ndb PRIVATE -fvisibility=hidden -w)

# ── nostrdb core ───────────────────────────────────────────────────────────────
add_library(nostrdb_core STATIC
    "${NOSTRDB_ROOT}/nostrdb.c"
)
target_include_directories(nostrdb_core PUBLIC
    "${NOSTRDB_ROOT}"
    "${LMDB_DIR}"
    "${SECP_DIR}/include"
    "${FLATCC_DIR}/include"
)
target_compile_definitions(nostrdb_core PRIVATE MDB_USE_POSIX_MUTEX)
target_compile_options(nostrdb_core PRIVATE -fvisibility=hidden -w)
target_link_libraries(nostrdb_core PRIVATE lmdb_ndb secp256k1_ndb flatcc_ndb)

# ── JNI bridge shared library ──────────────────────────────────────────────────
find_library(log-lib log)

add_library(citrine_nostrdb SHARED nostrdb_jni.cpp)
target_include_directories(citrine_nostrdb PRIVATE "${NOSTRDB_ROOT}")
target_link_libraries(citrine_nostrdb
    nostrdb_core
    lmdb_ndb
    secp256k1_ndb
    flatcc_ndb
    ${log-lib}
    android
)
target_compile_options(citrine_nostrdb PRIVATE -fvisibility=hidden)
