cmake_minimum_required(VERSION 3.22.1)
project(airtv C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
set(UXPLAY ${THIRD_PARTY}/uxplay/lib)
set(PLIST ${THIRD_PARTY}/libplist)
set(ALAC ${THIRD_PARTY}/alac)

find_package(openssl REQUIRED CONFIG)

# ---- libplist (C API only) ----
add_library(plist STATIC
    ${PLIST}/libcnary/node.c
    ${PLIST}/libcnary/node_list.c
    ${PLIST}/src/base64.c
    ${PLIST}/src/bplist.c
    ${PLIST}/src/bytearray.c
    ${PLIST}/src/common.c
    ${PLIST}/src/hashtable.c
    ${PLIST}/src/jplist.c
    ${PLIST}/src/jsmn.c
    ${PLIST}/src/oplist.c
    ${PLIST}/src/out-default.c
    ${PLIST}/src/out-limd.c
    ${PLIST}/src/out-plutil.c
    ${PLIST}/src/plist.c
    ${PLIST}/src/ptrarray.c
    ${PLIST}/src/time64.c
    ${PLIST}/src/xplist.c)
target_include_directories(plist PUBLIC ${PLIST}/include PRIVATE ${PLIST}/src ${PLIST}/libcnary/include)
target_compile_definitions(plist PRIVATE
    PACKAGE_VERSION="2.7.0"
    HAVE_STRNDUP HAVE_TM_TM_GMTOFF HAVE_TM_TM_ZONE HAVE_LOCALTIME_R HAVE_GMTIME_R
    __LITTLE_ENDIAN__=1)
target_compile_options(plist PRIVATE -w)

# ---- Apple ALAC decoder ----
add_library(alac STATIC
    ${ALAC}/ALACBitUtilities.c
    ${ALAC}/ALACDecoder.cpp
    ${ALAC}/EndianPortable.c
    ${ALAC}/ag_dec.c
    ${ALAC}/dp_dec.c
    ${ALAC}/matrix_dec.c)
target_include_directories(alac PUBLIC ${ALAC})
target_compile_definitions(alac PRIVATE TARGET_RT_LITTLE_ENDIAN=1)
target_compile_options(alac PRIVATE -w)

# ---- UxPlay AirPlay core ----
file(GLOB UXPLAY_SRC ${UXPLAY}/*.c ${UXPLAY}/playfair/*.c ${UXPLAY}/llhttp/*.c)
add_library(airplay STATIC ${UXPLAY_SRC} dnssd_android.c)
target_include_directories(airplay PUBLIC
    ${UXPLAY} ${UXPLAY}/playfair ${UXPLAY}/llhttp ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_definitions(airplay PRIVATE
    NOHOLD STANDALONE TARGET_POSIX _LINUX _REENTRANT _GNU_SOURCE
    PLIST_210 PLIST_230
    # Identify as Apple TV HD (2015) rather than UxPlay's default Apple TV 3 (2012):
    # senders cap screen mirroring at 30 fps for the older model.
    GLOBAL_MODEL="AppleTV5,3"
    OPENSSL_API_COMPAT=0x10101000L)
target_compile_options(airplay PRIVATE -w)
target_link_libraries(airplay PUBLIC plist openssl::crypto)

# ---- JNI bridge ----
add_library(airtv SHARED airtv_jni.cpp)
target_link_libraries(airtv airplay alac log)
