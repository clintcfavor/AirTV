/*
 * AirTV - hooks that let the Android dnssd backend hand service records to Kotlin.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#ifndef AIRTV_DNSSD_ANDROID_H
#define AIRTV_DNSSD_ANDROID_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct dnssd_android_callbacks_s {
    void *cls;
    void (*register_service)(void *cls, int service, const char *name, const char *type,
                             unsigned short port, const char **keys, const char **values, int count);
    void (*unregister_service)(void *cls, int service);
} dnssd_android_callbacks_t;

void dnssd_android_set_callbacks(const dnssd_android_callbacks_t *callbacks);

#ifdef __cplusplus
}
#endif

#endif
