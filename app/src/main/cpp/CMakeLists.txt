cmake_minimum_required(VERSION 3.22.1)
project(phonereceiver C)

include(${CMAKE_CURRENT_SOURCE_DIR}/cmake/deps.cmake)

set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Third-party code is compiled with warnings off; we don't maintain it.
set(THIRD_PARTY_FLAGS -w -O2)

# --- libusb ----------------------------------------------------------------------------
# Same source list as libusb/android/jni/libusb.mk.
add_library(usb STATIC
    ${libusb_SOURCE_DIR}/libusb/core.c
    ${libusb_SOURCE_DIR}/libusb/descriptor.c
    ${libusb_SOURCE_DIR}/libusb/hotplug.c
    ${libusb_SOURCE_DIR}/libusb/io.c
    ${libusb_SOURCE_DIR}/libusb/sync.c
    ${libusb_SOURCE_DIR}/libusb/strerror.c
    ${libusb_SOURCE_DIR}/libusb/os/linux_usbfs.c
    ${libusb_SOURCE_DIR}/libusb/os/events_posix.c
    ${libusb_SOURCE_DIR}/libusb/os/threads_posix.c
    ${libusb_SOURCE_DIR}/libusb/os/linux_netlink.c)
target_include_directories(usb
    PRIVATE ${libusb_SOURCE_DIR}/android ${libusb_SOURCE_DIR}/libusb/os
    PUBLIC ${libusb_SOURCE_DIR}/libusb)
target_compile_options(usb PRIVATE ${THIRD_PARTY_FLAGS} -pthread)
target_link_libraries(usb PUBLIC log)

# --- librtlsdr (RTL-SDR Blog fork) -----------------------------------------------------
# librtlsdr.c is compiled through librtlsdr_android.c, which redirects its libusb
# enumeration calls to usb_shim.c so the device comes from Android's UsbManager fd.
add_library(rtlsdr STATIC
    librtlsdr_android.c
    usb_shim.c
    ${rtlsdr_SOURCE_DIR}/src/tuner_e4k.c
    ${rtlsdr_SOURCE_DIR}/src/tuner_fc0012.c
    ${rtlsdr_SOURCE_DIR}/src/tuner_fc0013.c
    ${rtlsdr_SOURCE_DIR}/src/tuner_fc2580.c
    ${rtlsdr_SOURCE_DIR}/src/tuner_r82xx.c)
target_include_directories(rtlsdr
    PRIVATE ${rtlsdr_SOURCE_DIR}/src
    PUBLIC ${rtlsdr_SOURCE_DIR}/include ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_definitions(rtlsdr PRIVATE DETACH_KERNEL_DRIVER PUBLIC rtlsdr_STATIC)
target_compile_options(rtlsdr PRIVATE ${THIRD_PARTY_FLAGS})
target_link_libraries(rtlsdr PUBLIC usb)

# --- zstd (readsb hard-depends on it for trace/API compression) -------------------------
file(GLOB ZSTD_SOURCES
    ${zstd_SOURCE_DIR}/lib/common/*.c
    ${zstd_SOURCE_DIR}/lib/compress/*.c
    ${zstd_SOURCE_DIR}/lib/decompress/*.c)
add_library(zstd STATIC ${ZSTD_SOURCES})
target_include_directories(zstd PUBLIC ${zstd_SOURCE_DIR}/lib PRIVATE ${zstd_SOURCE_DIR}/lib/common)
target_compile_definitions(zstd PRIVATE ZSTD_DISABLE_ASM)
target_compile_options(zstd PRIVATE ${THIRD_PARTY_FLAGS})

# --- readsb ----------------------------------------------------------------------------
# readsb.c is copied with four string patches (checked below) so it can run as a
# library inside the app's :feeder process instead of as a standalone program:
#   1. main() -> readsb_main(), plus the glue in readsb_android_glue.inc
#   2. configureSignals() -> android_configure_signals(); upstream ignores signals 1-31
#      process-wide, which would break ART's own signal handling.
#   3. The JSON writer asks readsb_android_skip_json() before writing aircraft.json. While the app
#      isn't visible it writes once a minute instead of every second: nothing shows it, but the
#      lifetime history still samples which aircraft were heard.
#   4. configAfterParse() keeps the range outline, and with it stats.json's max_distance (the
#      Live feed card's "Max range"), without a position at startup. readsb turned both off when
#      no --lat/--lon was given, but the app only gives the position over gpsd, after startup, so
#      Max range was always 0. Found by the desktop app (its readsb-windows.patch, item 4).
file(READ ${readsb_SOURCE_DIR}/readsb.c READSB_SRC)
set(READSB_SRC_ORIG "${READSB_SRC}")
string(REPLACE
    "int main(int argc, char **argv) {"
    "#include \"readsb_android_glue.inc\"\nint readsb_main(int argc, char **argv) {"
    READSB_SRC "${READSB_SRC}")
string(REPLACE "    configureSignals();" "    android_configure_signals();" READSB_SRC "${READSB_SRC}")
string(REPLACE "if (Modes.onlyBin < 2) {" "if (Modes.onlyBin < 2 && !readsb_android_skip_json()) {" READSB_SRC "${READSB_SRC}")
string(REPLACE "if (!Modes.userLocationValid || !Modes.json_dir || Modes.json_location_accuracy == 0) {"
    "if (/* android: position arrives over gpsd */ !Modes.json_dir || Modes.json_location_accuracy == 0) {"
    READSB_SRC "${READSB_SRC}")
string(FIND "${READSB_SRC}" "readsb_main(int argc" HAS_MAIN)
string(FIND "${READSB_SRC}" "android_configure_signals();" HAS_SIGNALS)
string(FIND "${READSB_SRC}" "!readsb_android_skip_json()" HAS_JSON_PAUSE)
string(FIND "${READSB_SRC}" "/* android: position arrives over gpsd */" HAS_OUTLINE)
string(FIND "${READSB_SRC}" "!Modes.userLocationValid || !Modes.json_dir" OLD_OUTLINE)
if(HAS_MAIN EQUAL -1 OR HAS_SIGNALS EQUAL -1 OR HAS_JSON_PAUSE EQUAL -1 OR HAS_OUTLINE EQUAL -1 OR NOT OLD_OUTLINE EQUAL -1)
    message(FATAL_ERROR "readsb.c no longer matches the Android patch; update CMakeLists.txt for the new readsb revision")
endif()
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/readsb_android.c.tmp "${READSB_SRC}")
configure_file(${CMAKE_CURRENT_BINARY_DIR}/readsb_android.c.tmp ${CMAKE_CURRENT_BINARY_DIR}/readsb_android.c COPYONLY)

# sdr_rtlsdr.c is copied with one string patch (checked below), after the desktop feeder's
# readsb-windows.patch item 5: rtlsdrClose() turns the bias tee off before closing. librtlsdr's
# close leaves it as it is, so a stopped feeder would otherwise keep powering the antenna cable
# until the stick was unplugged. Always, not only with --enable-biastee: a stick whose EEPROM says
# so turns its bias tee on at every open whatever the setting (librtlsdr.c, EEPROM byte 7 bit 1),
# and that must go off at Stop too (security report MF-LOG-10).
file(READ ${readsb_SOURCE_DIR}/sdr_rtlsdr.c RTLSDR_SRC)
string(REPLACE
    "void rtlsdrClose() {\n    if (RTLSDR.dev) {\n        rtlsdr_close(RTLSDR.dev);"
    "void rtlsdrClose() {\n    if (RTLSDR.dev) {\n        /* android: bias tee off on close */\n        rtlsdr_set_bias_tee(RTLSDR.dev, 0);\n        rtlsdr_close(RTLSDR.dev);"
    RTLSDR_SRC "${RTLSDR_SRC}")
string(FIND "${RTLSDR_SRC}" "/* android: bias tee off on close */" HAS_BIASTEE_OFF)
if(HAS_BIASTEE_OFF EQUAL -1)
    message(FATAL_ERROR "sdr_rtlsdr.c no longer matches the Android patch; update CMakeLists.txt for the new readsb revision")
endif()
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/sdr_rtlsdr_android.c.tmp "${RTLSDR_SRC}")
configure_file(${CMAKE_CURRENT_BINARY_DIR}/sdr_rtlsdr_android.c.tmp ${CMAKE_CURRENT_BINARY_DIR}/sdr_rtlsdr_android.c COPYONLY)

# fasthash.c is copied with one string patch (checked below): fasthash64() read its input as
# uint64_t words, and readsb hashes byte strings at any address (net_io.c's receiver IDs). 32-bit
# ARM can't load 8 bytes from an address that isn't 4-byte aligned, so armeabi-v7a devices (an
# Android TV, November 2026) crashed with SIGBUS a few seconds into every run. The words are now
# copied out of a byte pointer, which gives the same hashes on every ABI.
file(READ ${readsb_SOURCE_DIR}/fasthash.c FASTHASH_SRC)
string(REPLACE
    "    const uint64_t *pos = (const uint64_t *)buf;\n    const uint64_t *end = pos + (len / 8);"
    "    /* android: unaligned-safe */\n    const unsigned char *pos = (const unsigned char *)buf;\n    const unsigned char *end = pos + (len / 8) * 8;"
    FASTHASH_SRC "${FASTHASH_SRC}")
string(REPLACE "        v  = *pos++;" "        __builtin_memcpy(&v, pos, sizeof v);\n        pos += sizeof v;" FASTHASH_SRC "${FASTHASH_SRC}")
string(FIND "${FASTHASH_SRC}" "/* android: unaligned-safe */" HAS_UNALIGNED_POS)
string(FIND "${FASTHASH_SRC}" "__builtin_memcpy(&v, pos, sizeof v);" HAS_UNALIGNED_READ)
if(HAS_UNALIGNED_POS EQUAL -1 OR HAS_UNALIGNED_READ EQUAL -1)
    message(FATAL_ERROR "fasthash.c no longer matches the Android patch; update CMakeLists.txt for the new readsb revision")
endif()
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/fasthash_android.c.tmp "${FASTHASH_SRC}")
configure_file(${CMAKE_CURRENT_BINARY_DIR}/fasthash_android.c.tmp ${CMAKE_CURRENT_BINARY_DIR}/fasthash_android.c COPYONLY)

file(READ ${readsb_SOURCE_DIR}/version READSB_VERSION)
string(STRIP "${READSB_VERSION}" READSB_VERSION)

set(R ${readsb_SOURCE_DIR})
add_library(readsb STATIC
    ${CMAKE_CURRENT_BINARY_DIR}/readsb_android.c
    ${R}/argp.c ${R}/anet.c ${R}/interactive.c ${R}/mode_ac.c ${R}/mode_s.c ${R}/comm_b.c
    ${R}/json_out.c ${R}/net_io.c ${R}/crc.c ${R}/demod_2400.c
    ${R}/uat2esnt/uat2esnt.c ${R}/uat2esnt/uat_decode.c
    ${R}/stats.c ${R}/cpr.c ${R}/icao_filter.c ${R}/track.c ${R}/util.c ${CMAKE_CURRENT_BINARY_DIR}/fasthash_android.c
    ${R}/convert.c ${R}/sdr_ifile.c ${R}/sdr_beast.c ${R}/sdr.c ${R}/ais_charset.c
    ${R}/globe_index.c ${R}/geomag.c ${R}/receiver.c ${R}/aircraft.c ${R}/api.c
    ${R}/threadpool.c ${CMAKE_CURRENT_BINARY_DIR}/sdr_rtlsdr_android.c)
target_include_directories(readsb PRIVATE ${R} ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_definitions(readsb PRIVATE
    _GNU_SOURCE _DEFAULT_SOURCE
    ENABLE_RTLSDR DISABLE_INTERACTIVE
    # --enable-biastee: power an amplifier through the antenna cable (Settings; off by default).
    ENABLE_RTLSDR_BIASTEE
    SC16Q11_TABLE_BITS=8
    MODES_READSB_VERSION="${READSB_VERSION} android-poc"
    READSB_SHORT_VERSION="${READSB_VERSION}"
    READSB_SHORT_COMMIT="android")
target_compile_options(readsb PRIVATE ${THIRD_PARTY_FLAGS}
    -include ${CMAKE_CURRENT_SOURCE_DIR}/android_compat.h)
target_link_libraries(readsb PUBLIC rtlsdr zstd z m)

# --- JNI bridge (the only .so packaged into the APK) -----------------------------------
add_library(phonereceiver SHARED jni_bridge.c)
target_compile_options(phonereceiver PRIVATE -Wall -Wextra)
target_link_libraries(phonereceiver PRIVATE readsb log)
