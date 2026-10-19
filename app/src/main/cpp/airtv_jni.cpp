/*
 * AirTV - JNI bridge between the UxPlay AirPlay core and the Android app.
 *
 * The UxPlay library handles the AirPlay protocol (pairing, FairPlay, RTSP,
 * RTP, decryption). Everything it would normally hand to GStreamer is passed
 * up to Kotlin instead, where MediaCodec / AudioTrack do the rendering.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

extern "C" {
#include "raop.h"
#include "dnssd.h"
#include "logger.h"
#include "stream.h"
}
#include "dnssd_android.h"
#include "ALACDecoder.h"
#include "ALACBitUtilities.h"

#define TAG "AirTV-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

JavaVM *g_vm = nullptr;
pthread_key_t g_detach_key;

jobject g_listener = nullptr;
jmethodID m_register_service;
jmethodID m_unregister_service;
jmethodID m_video_codec;
jmethodID m_video_data;
jmethodID m_video_reset;
jmethodID m_audio_format;
jmethodID m_audio_aac;
jmethodID m_audio_pcm;
jmethodID m_audio_flush;
jmethodID m_volume;
jmethodID m_connection;
jmethodID m_metadata;
jmethodID m_cast_play;
jmethodID m_cast_scrub;
jmethodID m_cast_rate;
jmethodID m_cast_stop;
jmethodID m_cast_info;

std::mutex g_lock;
raop_t *g_raop = nullptr;
dnssd_t *g_dnssd = nullptr;
int g_open_connections = 0;
double g_client_volume = 0.0;
/* raop_set_lang() keeps these pointers, so they must outlive the receiver. */
std::string g_lang_system;

ALACDecoder *g_alac = nullptr;
std::vector<uint8_t> g_pcm;

/* ALAC "magic cookie" (ALACSpecificConfig) advertised by AirPlay: 352 spf, 16 bit, 2ch, 44100 Hz */
const uint8_t kAlacConfig[24] = {
    0x00, 0x00, 0x01, 0x60, 0x00, 0x10, 0x28, 0x0a, 0x0e, 0x02, 0x00, 0xff,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xac, 0x44,
};

void detach_thread(void *) {
    if (g_vm) {
        g_vm->DetachCurrentThread();
    }
}

/* Returns a JNIEnv for the calling thread, attaching native threads on first use. */
JNIEnv *env_for_thread() {
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) {
        return env;
    }
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        return nullptr;
    }
    pthread_setspecific(g_detach_key, env);
    return env;
}

jobject listener() {
    return g_listener;
}

void check_exception(JNIEnv *env) {
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

jbyteArray to_java(JNIEnv *env, const void *data, int len) {
    jbyteArray array = env->NewByteArray(len);
    if (array) {
        env->SetByteArrayRegion(array, 0, len, reinterpret_cast<const jbyte *>(data));
    }
    return array;
}

/* ---- dnssd -> NsdManager ---- */

void on_register_service(void *, int service, const char *name, const char *type, unsigned short port,
                         const char **keys, const char **values, int count) {
    JNIEnv *env = env_for_thread();
    if (!env || !listener()) return;
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray jkeys = env->NewObjectArray(count, string_class, nullptr);
    jobjectArray jvalues = env->NewObjectArray(count, string_class, nullptr);
    for (int i = 0; i < count; i++) {
        jstring k = env->NewStringUTF(keys[i]);
        jstring v = env->NewStringUTF(values[i]);
        env->SetObjectArrayElement(jkeys, i, k);
        env->SetObjectArrayElement(jvalues, i, v);
        env->DeleteLocalRef(k);
        env->DeleteLocalRef(v);
    }
    jstring jname = env->NewStringUTF(name);
    jstring jtype = env->NewStringUTF(type);
    env->CallVoidMethod(listener(), m_register_service, service, jname, jtype, (jint) port, jkeys, jvalues);
    check_exception(env);
    env->DeleteLocalRef(jname);
    env->DeleteLocalRef(jtype);
    env->DeleteLocalRef(jkeys);
    env->DeleteLocalRef(jvalues);
    env->DeleteLocalRef(string_class);
}

void on_unregister_service(void *, int service) {
    JNIEnv *env = env_for_thread();
    if (!env || !listener()) return;
    env->CallVoidMethod(listener(), m_unregister_service, service);
    check_exception(env);
}

/* ---- raop callbacks ---- */

void cb_conn_init(void *) {
    std::lock_guard<std::mutex> guard(g_lock);
    g_open_connections++;
    LOGI("connection opened (%d open)", g_open_connections);
}

void cb_conn_destroy(void *) {
    int open;
    {
        std::lock_guard<std::mutex> guard(g_lock);
        g_open_connections--;
        open = g_open_connections;
    }
    LOGI("connection closed (%d open)", open);
    if (open == 0) {
        JNIEnv *env = env_for_thread();
        if (!env || !listener()) return;
        env->CallVoidMethod(listener(), m_connection, JNI_FALSE);
        check_exception(env);
    }
}

void cb_conn_reset(void *, int reason) {
    LOGI("connection reset, reason %d", reason);
    JNIEnv *env = env_for_thread();
    if (!env || !listener()) return;
    env->CallVoidMethod(listener(), m_connection, JNI_FALSE);
    check_exception(env);
}

void cb_conn_feedback(void *) {
}

void cast_stop(JNIEnv *env) {
    env->CallVoidMethod(listener(), m_cast_stop);
    check_exception(env);
}

void cb_video_reset(void *, reset_type_t type) {
    LOGI("video reset, type %d", (int) type);
    JNIEnv *env = env_for_thread();
    if (!env || !listener()) return;
    switch (type) {
    case RESET_TYPE_ON_VIDEO_PLAY:
        /* A cast is starting; nothing to tear down. */
        return;
    case RESET_TYPE_NOHOLD:
        /* A new sender took over: drop the previous sender's cast as well as its mirroring. */
        cast_stop(env);
        if (g_raop) raop_destroy_airplay_video(g_raop, -1);
        break;
    case RESET_TYPE_HLS_SHUTDOWN:
    case RESET_TYPE_HLS_EOS:
        cast_stop(env);
        if (g_raop) {
            raop_destroy_airplay_video(g_raop, -1);
            if (type == RESET_TYPE_HLS_SHUTDOWN) raop_remove_hls_connections(g_raop);
        }
        break;
    default:
        break;
    }
    env->CallVoidMethod(listener(), m_video_reset);
    check_exception(env);
}

int cb_video_set_codec(void *, video_codec_t codec) {
    JNIEnv *env = env_for_thread();
    if (!env || !listener()) return 0;
    env->CallVoidMethod(listener(), m_video_codec, codec == VIDEO_CODEC_H265 ? JNI_TRUE : JNI_FALSE);
    check_exception(env);
    return 0;
}

void cb_video_process(void *, raop_ntp_t *, video_decode_struct *data) {
    JNIEnv *env = env_for_thread();
    if (!env || !listener() || data->data_len <= 0) return;
    jbyteArray frame = to_java(env, data->data, data->data_len);
    if (!frame) return;
    env->CallVoidMethod(listener(), m_video_data, frame, data->is_h265 ? JNI_TRUE : JNI_FALSE,
                        (jlong) (data->ntp_time_remote / 1000));
    check_exception(env);
    env->DeleteLocalRef(frame);
}

void cb_video_pause(void *) {
}

void cb_video_resume(void *) {
}

void cb_video_flush(void *) {
}

void cb_video_report_size(void *, float *width_source, float *height_source, float *width, float *height) {
    LOGI("client screen %.0fx%.0f, stream %.0fx%.0f", *width_source, *height_source, *width, *height);
}

void cb_mirror_video_running(void *, bool is_running) {
    if (!is_running) return;
    JNIEnv *env = env_for_thread();
    if (!env || !listener()) return;
    env->CallVoidMethod(listener(), m_connection, JNI_TRUE);
    check_exception(env);
}

void cb_audio_get_format(void *, unsigned char *ct, unsigned short *spf, bool *using_screen, bool *is_media,
                         uint64_t *audio_format) {
    LOGI("audio format ct=%d spf=%d screen=%d media=%d format=0x%llx", *ct, *spf, *using_screen, *is_media,
         (unsigned long long) *audio_format);
    if (*ct == 2) {
        std::lock_guard<std::mutex> guard(g_lock);
        if (!g_alac) {
            g_alac = new ALACDecoder();
            if (g_alac->Init((void *) kAlacConfig, sizeof(kAlacConfig)) != 0) {
                LOGE("ALAC decoder init failed");
                delete g_alac;
                g_alac = nullptr;
            }
        }
    }
    JNIEnv *env = env_for_thread();
    if (!env || !listener()) return;
    env->CallVoidMethod(listener(), m_audio_format, (jint) *ct);
    check_exception(env);
}

void cb_audio_process(void *, raop_ntp_t *, audio_decode_struct *data) {
    JNIEnv *env = env_for_thread();
    if (!env || !listener() || data->data_len <= 0) return;
    jlong pts = (jlong) (data->ntp_time_remote / 1000);

    if (data->ct == 2) {
        jbyteArray pcm = nullptr;
        {
            std::lock_guard<std::mutex> guard(g_lock);
            if (!g_alac) return;
            const uint32_t frames = 352;
            const uint32_t channels = 2;
            g_pcm.resize(frames * channels * sizeof(int16_t) * 2);
            BitBuffer bits;
            BitBufferInit(&bits, data->data, (uint32_t) data->data_len);
            uint32_t out_frames = 0;
            if (g_alac->Decode(&bits, g_pcm.data(), frames, channels, &out_frames) != 0 || out_frames == 0) {
                return;
            }
            pcm = to_java(env, g_pcm.data(), (int) (out_frames * channels * sizeof(int16_t)));
        }
        if (!pcm) return;
        env->CallVoidMethod(listener(), m_audio_pcm, pcm, pts);
        check_exception(env);
        env->DeleteLocalRef(pcm);
        return;
    }

    jbyteArray packet = to_java(env, data->data, data->data_len);
    if (!packet) return;
    env->CallVoidMethod(listener(), m_audio_aac, packet, (jint) data->ct, pts);
    check_exception(env);
    env->DeleteLocalRef(packet);
}

void cb_audio_flush(void *) {
    JNIEnv *env = env_for_thread();
    if (!env || !listener()) return;
    env->CallVoidMethod(listener(), m_audio_flush);
    check_exception(env);
}

double cb_audio_set_client_volume(void *) {
    return g_client_volume;
}

void cb_audio_set_volume(void *, float volume) {
    g_client_volume = volume;
    JNIEnv *env = env_for_thread();
    if (!env || !listener()) return;
    env->CallVoidMethod(listener(), m_volume, (jfloat) volume);
    check_exception(env);
}

/* DMAP metadata: we only pull out title (minm), artist (asar) and album (asal). */
void cb_audio_set_metadata(void *, const void *buffer, int buflen) {
    const unsigned char *p = static_cast<const unsigned char *>(buffer);
    std::string title, artist, album;
    int i = 0;
    while (i + 8 <= buflen) {
        char tag[5] = {0};
        memcpy(tag, p + i, 4);
        int len = (p[i + 4] << 24) | (p[i + 5] << 16) | (p[i + 6] << 8) | p[i + 7];
        if (len < 0 || i + 8 + len > buflen) break;
        if (!strcmp(tag, "mlit")) {  /* container: descend */
            i += 8;
            continue;
        }
        std::string value(reinterpret_cast<const char *>(p + i + 8), (size_t) len);
        if (!strcmp(tag, "minm")) title = value;
        else if (!strcmp(tag, "asar")) artist = value;
        else if (!strcmp(tag, "asal")) album = value;
        i += 8 + len;
    }
    JNIEnv *env = env_for_thread();
    if (!env || !listener()) return;
    jstring jt = env->NewStringUTF(title.c_str());
    jstring ja = env->NewStringUTF(artist.c_str());
    jstring jb = env->NewStringUTF(album.c_str());
    env->CallVoidMethod(listener(), m_metadata, jt, ja, jb);
    check_exception(env);
    env->DeleteLocalRef(jt);
    env->DeleteLocalRef(ja);
    env->DeleteLocalRef(jb);
}

void cb_audio_set_coverart(void *, const void *, int) {
}

void cb_audio_stop_coverart_rendering(void *) {
}

void cb_audio_set_progress(void *, uint32_t *, uint32_t *, uint32_t *) {
}

void cb_audio_remote_control_id(void *, const char *, const char *) {
}

void cb_export_dacp(void *, const char *, const char *) {
}

void cb_report_client_request(void *, char *deviceid, char *model, char *name, bool *admit) {
    LOGI("client request: %s (%s, %s)", name ? name : "?", model ? model : "?", deviceid ? deviceid : "?");
    *admit = true;
}

void cb_display_pin(void *, char *pin) {
    LOGI("pin requested: %s", pin);
}

void cb_register_client(void *, const char *, const char *, const char *) {
}

bool cb_check_register(void *, const char *) {
    return true;
}

const char *cb_passwd(void *, int *len) {
    *len = 0;
    return nullptr;
}

/*
 * AirPlay video casting: the sender hands over a stream URL (an http(s) URL from apps such
 * as GlobeTV or Safari, or a localhost master playlist that UxPlay rebuilds for YouTube) and
 * the TV plays it itself with ExoPlayer, at the stream's own frame rate. The sender just
 * polls playback-info, so its screen can be locked.
 */
void cb_on_video_play(void *, const char *location, const float start_position) {
    LOGI("cast play %s from %.1fs", location, start_position);
    JNIEnv *env = env_for_thread();
    if (!env || !listener()) return;
    jstring jlocation = env->NewStringUTF(location);
    env->CallVoidMethod(listener(), m_cast_play, jlocation, (jfloat) start_position);
    env->DeleteLocalRef(jlocation);
    check_exception(env);
}

void cb_on_video_scrub(void *, const float position) {
    JNIEnv *env = env_for_thread();
    if (!env || !listener()) return;
    env->CallVoidMethod(listener(), m_cast_scrub, (jfloat) position);
    check_exception(env);
}

void cb_on_video_rate(void *, const float rate) {
    JNIEnv *env = env_for_thread();
    if (!env || !listener()) return;
    env->CallVoidMethod(listener(), m_cast_rate, (jfloat) rate);
    check_exception(env);
}

void cb_on_video_stop(void *) {
    JNIEnv *env = env_for_thread();
    if (!env || !listener()) return;
    cast_stop(env);
}

/*
 * Fills playback_info from the Kotlin player's snapshot:
 * [duration, position, seekStart, seekDuration, rate, bufferEmpty, bufferFull].
 * position -1 means "not ready yet"; duration -1 means "finished" (UxPlay then tears down).
 */
void cb_on_video_acquire_playback_info(void *, playback_info_t *info) {
    info->duration = 0;
    info->position = -1.0;
    info->seek_start = 0;
    info->seek_duration = 0;
    info->rate = 0;
    info->ready_to_play = false;
    info->playback_buffer_empty = true;
    info->playback_buffer_full = false;
    info->playback_likely_to_keep_up = false;

    JNIEnv *env = env_for_thread();
    if (!env || !listener()) return;
    auto values = (jdoubleArray) env->CallObjectMethod(listener(), m_cast_info);
    check_exception(env);
    if (!values) return;
    if (env->GetArrayLength(values) >= 7) {
        jdouble v[7];
        env->GetDoubleArrayRegion(values, 0, 7, v);
        info->duration = v[0];
        info->position = v[1];
        info->seek_start = v[2];
        info->seek_duration = v[3];
        info->rate = (float) v[4];
        info->playback_buffer_empty = v[5] != 0;
        info->playback_buffer_full = v[6] != 0;
        info->ready_to_play = info->position >= 0;
        info->playback_likely_to_keep_up = !info->playback_buffer_empty;
    }
    env->DeleteLocalRef(values);
}

/* The sender is swapping playlists (e.g. YouTube ads): pause and report where we were. */
float cb_on_video_playlist_remove(void *) {
    JNIEnv *env = env_for_thread();
    if (!env || !listener()) return 0.0f;
    env->CallVoidMethod(listener(), m_cast_rate, (jfloat) 0.0f);
    check_exception(env);
    playback_info_t info;
    cb_on_video_acquire_playback_info(nullptr, &info);
    return info.position > 0 ? (float) info.position : 0.0f;
}

void cb_log(void *, int level, const char *msg) {
    int prio = ANDROID_LOG_DEBUG;
    if (level <= LOGGER_ERR) prio = ANDROID_LOG_ERROR;
    else if (level == LOGGER_WARNING) prio = ANDROID_LOG_WARN;
    else if (level <= LOGGER_INFO) prio = ANDROID_LOG_INFO;
    __android_log_write(prio, "AirTV-raop", msg);
}

bool parse_mac(const char *mac, std::vector<char> &out) {
    unsigned int b[6];
    if (sscanf(mac, "%2x:%2x:%2x:%2x:%2x:%2x", &b[0], &b[1], &b[2], &b[3], &b[4], &b[5]) != 6) {
        return false;
    }
    out.clear();
    for (unsigned int v : b) out.push_back((char) v);
    return true;
}

/* Feature bits as configured by UxPlay's start_dnssd() with -hls (bits 0 Video and
   4 VideoHTTPLiveStreams), without H.265. */
void configure_features(dnssd_t *dnssd) {
    const int on[] = {0, 1, 2, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 25, 27, 28, 30};
    const int off[] = {3, 8, 23, 24, 26, 29, 31, 42};
    for (int bit : on) dnssd_set_airplay_features(dnssd, bit, 1);
    for (int bit : off) dnssd_set_airplay_features(dnssd, bit, 0);
}

void stop_locked() {
    if (g_raop) {
        raop_stop_httpd(g_raop);
        raop_destroy(g_raop);
        g_raop = nullptr;
    }
    if (g_dnssd) {
        dnssd_unregister_raop(g_dnssd);
        dnssd_unregister_airplay(g_dnssd);
        dnssd_destroy(g_dnssd);
        g_dnssd = nullptr;
    }
    {
        std::lock_guard<std::mutex> guard(g_lock);
        delete g_alac;
        g_alac = nullptr;
        g_open_connections = 0;
    }
}

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    pthread_key_create(&g_detach_key, detach_thread);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_airtv_AirPlayNative_nativeStart(JNIEnv *env, jclass, jobject jlistener, jstring jname,
                                         jstring jmac, jstring jkeyfile, jint width, jint height, jint fps,
                                         jstring jlanguages) {
    stop_locked();

    if (g_listener) {
        env->DeleteGlobalRef(g_listener);
    }
    g_listener = env->NewGlobalRef(jlistener);
    jclass cls = env->GetObjectClass(jlistener);
    m_register_service = env->GetMethodID(cls, "onRegisterService",
                                          "(ILjava/lang/String;Ljava/lang/String;I[Ljava/lang/String;[Ljava/lang/String;)V");
    m_unregister_service = env->GetMethodID(cls, "onUnregisterService", "(I)V");
    m_video_codec = env->GetMethodID(cls, "onVideoCodec", "(Z)V");
    m_video_data = env->GetMethodID(cls, "onVideoData", "([BZJ)V");
    m_video_reset = env->GetMethodID(cls, "onVideoReset", "()V");
    m_audio_format = env->GetMethodID(cls, "onAudioFormat", "(I)V");
    m_audio_aac = env->GetMethodID(cls, "onAudioCompressed", "([BIJ)V");
    m_audio_pcm = env->GetMethodID(cls, "onAudioPcm", "([BJ)V");
    m_audio_flush = env->GetMethodID(cls, "onAudioFlush", "()V");
    m_volume = env->GetMethodID(cls, "onVolume", "(F)V");
    m_connection = env->GetMethodID(cls, "onConnection", "(Z)V");
    m_metadata = env->GetMethodID(cls, "onMetadata", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    m_cast_play = env->GetMethodID(cls, "onCastPlay", "(Ljava/lang/String;F)V");
    m_cast_scrub = env->GetMethodID(cls, "onCastScrub", "(F)V");
    m_cast_rate = env->GetMethodID(cls, "onCastRate", "(F)V");
    m_cast_stop = env->GetMethodID(cls, "onCastStop", "()V");
    m_cast_info = env->GetMethodID(cls, "castPlaybackInfo", "()[D");
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return -10;
    }

    const char *name = env->GetStringUTFChars(jname, nullptr);
    const char *mac = env->GetStringUTFChars(jmac, nullptr);
    const char *keyfile = env->GetStringUTFChars(jkeyfile, nullptr);
    std::string name_str(name), mac_str(mac), keyfile_str(keyfile);
    env->ReleaseStringUTFChars(jname, name);
    env->ReleaseStringUTFChars(jmac, mac);
    env->ReleaseStringUTFChars(jkeyfile, keyfile);
    const char *languages = env->GetStringUTFChars(jlanguages, nullptr);
    g_lang_system = (languages && *languages) ? languages : "en";
    env->ReleaseStringUTFChars(jlanguages, languages);

    std::vector<char> hw_addr;
    if (!parse_mac(mac_str.c_str(), hw_addr)) {
        LOGE("bad device id %s", mac_str.c_str());
        return -1;
    }

    dnssd_android_callbacks_t dcbs;
    memset(&dcbs, 0, sizeof(dcbs));
    dcbs.register_service = on_register_service;
    dcbs.unregister_service = on_unregister_service;
    dnssd_android_set_callbacks(&dcbs);

    int error = 0;
    g_dnssd = dnssd_init(name_str.c_str(), (int) name_str.size(), hw_addr.data(), (int) hw_addr.size(), 0, &error);
    if (error || !g_dnssd) {
        LOGE("dnssd_init failed: %d", error);
        g_dnssd = nullptr;
        return -2;
    }
    configure_features(g_dnssd);

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.conn_init = cb_conn_init;
    cbs.conn_destroy = cb_conn_destroy;
    cbs.conn_reset = cb_conn_reset;
    cbs.conn_feedback = cb_conn_feedback;
    cbs.audio_process = cb_audio_process;
    cbs.video_process = cb_video_process;
    cbs.audio_flush = cb_audio_flush;
    cbs.video_flush = cb_video_flush;
    cbs.video_pause = cb_video_pause;
    cbs.video_resume = cb_video_resume;
    cbs.audio_set_client_volume = cb_audio_set_client_volume;
    cbs.audio_set_volume = cb_audio_set_volume;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.video_report_size = cb_video_report_size;
    cbs.audio_set_metadata = cb_audio_set_metadata;
    cbs.audio_set_coverart = cb_audio_set_coverart;
    cbs.audio_stop_coverart_rendering = cb_audio_stop_coverart_rendering;
    cbs.audio_set_progress = cb_audio_set_progress;
    cbs.audio_remote_control_id = cb_audio_remote_control_id;
    cbs.report_client_request = cb_report_client_request;
    cbs.display_pin = cb_display_pin;
    cbs.register_client = cb_register_client;
    cbs.check_register = cb_check_register;
    cbs.passwd = cb_passwd;
    cbs.export_dacp = cb_export_dacp;
    cbs.video_reset = cb_video_reset;
    cbs.video_set_codec = cb_video_set_codec;
    cbs.mirror_video_running = cb_mirror_video_running;
    cbs.on_video_play = cb_on_video_play;
    cbs.on_video_scrub = cb_on_video_scrub;
    cbs.on_video_rate = cb_on_video_rate;
    cbs.on_video_stop = cb_on_video_stop;
    cbs.on_video_playlist_remove = cb_on_video_playlist_remove;
    cbs.on_video_acquire_playback_info = cb_on_video_acquire_playback_info;

    g_raop = raop_init(&cbs);
    if (!g_raop) {
        LOGE("raop_init failed");
        stop_locked();
        return -3;
    }
    raop_set_log_callback(g_raop, cb_log, nullptr);
    raop_set_log_level(g_raop, LOGGER_INFO);
    if (raop_init2(g_raop, 1 /* nohold: a new client takes over */, mac_str.c_str(), keyfile_str.c_str())) {
        LOGE("raop_init2 failed");
        free(g_raop);
        g_raop = nullptr;
        stop_locked();
        return -4;
    }
    if (width > 0) raop_set_plist(g_raop, "width", width);
    if (height > 0) raop_set_plist(g_raop, "height", height);
    if (fps > 0) {
        raop_set_plist(g_raop, "refreshRate", fps);
        raop_set_plist(g_raop, "maxFPS", fps);
    }
    /* Log the sender's once-a-second streaming report (its chosen fps, drops, bitrate). */
    raop_set_plist(g_raop, "clientFPSdata", 1);
    /* Accept AirPlay video casts (the "AirPlay" button in video players), not just mirroring. */
    raop_set_plist(g_raop, "hls", 1);
    /* Which audio/subtitle renditions to pick from YouTube's master playlists; UxPlay requires
       a non-empty system language list ("en-US:en"). */
    raop_set_lang(g_raop, nullptr, nullptr, g_lang_system.c_str());

    /* The standard AirPlay ports (as UxPlay's -p option). Fixed ports matter: senders cache
     * the advertised port, so a port that changes on every app start breaks reconnecting. */
    unsigned short tcp[2] = {7100, 7000};
    unsigned short udp[3] = {7011, 6001, 6000};
    raop_set_tcp_ports(g_raop, tcp);
    raop_set_udp_ports(g_raop, udp);

    unsigned short port = raop_get_port(g_raop);
    if (raop_start_httpd(g_raop, &port) < 0) {
        LOGW("port %d unavailable, falling back to dynamic ports", port);
        unsigned short any_tcp[2] = {0, 0};
        unsigned short any_udp[3] = {0, 0, 0};
        raop_set_tcp_ports(g_raop, any_tcp);
        raop_set_udp_ports(g_raop, any_udp);
        port = 0;
        if (raop_start_httpd(g_raop, &port) < 0) {
            LOGE("raop_start_httpd failed");
            stop_locked();
            return -5;
        }
    }
    raop_set_port(g_raop, port);
    raop_set_dnssd(g_raop, g_dnssd);

    if (dnssd_register_raop(g_dnssd, port) || dnssd_register_airplay(g_dnssd, port)) {
        LOGE("dnssd register failed");
        stop_locked();
        return -6;
    }
    LOGI("AirPlay receiver \"%s\" listening on port %d", name_str.c_str(), port);
    return port;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_airtv_AirPlayNative_nativeStop(JNIEnv *env, jclass) {
    stop_locked();
    dnssd_android_set_callbacks(nullptr);
    if (g_listener) {
        env->DeleteGlobalRef(g_listener);
        g_listener = nullptr;
    }
}
