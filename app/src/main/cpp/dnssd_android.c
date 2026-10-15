/*
 * AirTV - dnssd backend for Android.
 *
 * UxPlay ships backends for Bonjour/Avahi (dns_sd) and a self-contained mDNS
 * responder. On Android the system already owns UDP 5353, so instead of
 * responding to mDNS ourselves we build the TXT records here and hand them to
 * Kotlin, which registers the services with NsdManager.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "dnssd.h"
#include "dnssdint.h"
#include "global.h"
#include "utils.h"
#include "dnssd_android.h"

#define TXT_MAX_BYTES 1024
#define TXT_MAX_ITEMS 32

typedef struct txt_record_s {
    unsigned char bytes[TXT_MAX_BYTES];
    int length;
    char keys[TXT_MAX_ITEMS][32];
    char values[TXT_MAX_ITEMS][96];
    int count;
} txt_record_t;

typedef struct dnssd_private_s {
    txt_record_t raop_record;
    txt_record_t airplay_record;
    char raop_name[256];
    char airplay_name[256];
    int raop_registered;
    int airplay_registered;
} dnssd_private_t;

static dnssd_android_callbacks_t g_callbacks;

void dnssd_android_set_callbacks(const dnssd_android_callbacks_t *callbacks)
{
    if (callbacks) {
        g_callbacks = *callbacks;
    } else {
        memset(&g_callbacks, 0, sizeof(g_callbacks));
    }
}

static int txt_add(txt_record_t *txt, const char *key, const char *value)
{
    size_t klen = strlen(key);
    size_t vlen = strlen(value);
    size_t item = klen + 1 + vlen;
    if (item > 255 || txt->length + 1 + (int) item > TXT_MAX_BYTES || txt->count >= TXT_MAX_ITEMS) {
        return -1;
    }
    if (klen >= sizeof(txt->keys[0]) || vlen >= sizeof(txt->values[0])) {
        return -1;
    }
    /* DNS wire format: length-prefixed "key=value" strings */
    txt->bytes[txt->length++] = (unsigned char) item;
    memcpy(txt->bytes + txt->length, key, klen);
    txt->length += (int) klen;
    txt->bytes[txt->length++] = '=';
    memcpy(txt->bytes + txt->length, value, vlen);
    txt->length += (int) vlen;

    strcpy(txt->keys[txt->count], key);
    strcpy(txt->values[txt->count], value);
    txt->count++;
    return 0;
}

static void txt_reset(txt_record_t *txt)
{
    txt->length = 0;
    txt->count = 0;
}

static int build_raop_txt(dnssd_t *pub, txt_record_t *txt)
{
    char features[22] = {0};
    const char *pw = "false";
    const char *sf = RAOP_SF;

    switch (pub->pin_pw) {
    case 1:
        pw = "true";
        sf = "0x8c";
        break;
    case 2:
    case 3:
        pw = "true";
        sf = "0x84";
        break;
    default:
        break;
    }

    snprintf(features, sizeof(features), "0x%X,0x%X", pub->features1, pub->features2);
    txt_reset(txt);

    return txt_add(txt, "ch", RAOP_CH) ||
           txt_add(txt, "cn", RAOP_CN) ||
           txt_add(txt, "da", RAOP_DA) ||
           txt_add(txt, "et", RAOP_ET) ||
           txt_add(txt, "vv", RAOP_VV) ||
           txt_add(txt, "ft", features) ||
           txt_add(txt, "am", GLOBAL_MODEL) ||
           txt_add(txt, "md", RAOP_MD) ||
           txt_add(txt, "rhd", RAOP_RHD) ||
           txt_add(txt, "pw", pw) ||
           txt_add(txt, "sr", RAOP_SR) ||
           txt_add(txt, "ss", RAOP_SS) ||
           txt_add(txt, "sv", RAOP_SV) ||
           txt_add(txt, "tp", RAOP_TP) ||
           txt_add(txt, "txtvers", RAOP_TXTVERS) ||
           txt_add(txt, "sf", sf) ||
           txt_add(txt, "vs", RAOP_VS) ||
           txt_add(txt, "vn", RAOP_VN) ||
           txt_add(txt, "pk", pub->pk ? pub->pk : "");
}

static int build_airplay_txt(dnssd_t *pub, txt_record_t *txt)
{
    char device_id[3 * MAX_HWADDR_LEN];
    char features[22] = {0};

    if (utils_hwaddr_airplay(device_id, sizeof(device_id), pub->hw_addr, pub->hw_addr_len) < 0) {
        return -1;
    }
    snprintf(features, sizeof(features), "0x%X,0x%X", pub->features1, pub->features2);
    txt_reset(txt);

    return txt_add(txt, "deviceid", device_id) ||
           txt_add(txt, "features", features) ||
           txt_add(txt, "pw", pub->pin_pw ? "true" : "false") ||
           txt_add(txt, "flags", "0x4") ||
           txt_add(txt, "model", GLOBAL_MODEL) ||
           txt_add(txt, "pk", pub->pk ? pub->pk : "") ||
           txt_add(txt, "pi", AIRPLAY_PI) ||
           txt_add(txt, "srcvers", AIRPLAY_SRCVERS) ||
           txt_add(txt, "vv", AIRPLAY_VV);
}

static void publish(int service, const char *name, const char *type, unsigned short port, txt_record_t *txt)
{
    if (!g_callbacks.register_service) {
        return;
    }
    const char *keys[TXT_MAX_ITEMS];
    const char *values[TXT_MAX_ITEMS];
    for (int i = 0; i < txt->count; i++) {
        keys[i] = txt->keys[i];
        values[i] = txt->values[i];
    }
    g_callbacks.register_service(g_callbacks.cls, service, name, type, port, keys, values, txt->count);
}

void *
dnssd_private_init(dnssd_t *pub, int *error)
{
    char hwaddr[2 * MAX_HWADDR_LEN + 1];
    if (error) *error = DNSSD_ERROR_NOERROR;

    dnssd_private_t *dnssd = (dnssd_private_t *) calloc(1, sizeof(dnssd_private_t));
    if (!dnssd) {
        if (error) *error = DNSSD_ERROR_OUTOFMEM;
        return NULL;
    }
    if (utils_hwaddr_raop(hwaddr, sizeof(hwaddr), pub->hw_addr, pub->hw_addr_len) < 0) {
        free(dnssd);
        if (error) *error = DNSSD_ERROR_HWADDRLEN;
        return NULL;
    }
    snprintf(dnssd->raop_name, sizeof(dnssd->raop_name), "%s@%s", hwaddr, pub->name);
    snprintf(dnssd->airplay_name, sizeof(dnssd->airplay_name), "%s", pub->name);
    return dnssd;
}

void
dnssd_private_destroy(void *private)
{
    free(private);
}

int
dnssd_register_raop(dnssd_t *pub, unsigned short port)
{
    assert(pub && pub->dnssd_private);
    dnssd_private_t *dnssd = (dnssd_private_t *) pub->dnssd_private;
    if (build_raop_txt(pub, &dnssd->raop_record)) {
        return -1;
    }
    dnssd->raop_registered = 1;
    publish(DNSSD_SERVICE_RAOP, dnssd->raop_name, "_raop._tcp", port, &dnssd->raop_record);
    return 0;
}

int
dnssd_register_airplay(dnssd_t *pub, unsigned short port)
{
    assert(pub && pub->dnssd_private);
    dnssd_private_t *dnssd = (dnssd_private_t *) pub->dnssd_private;
    if (build_airplay_txt(pub, &dnssd->airplay_record)) {
        return -1;
    }
    dnssd->airplay_registered = 1;
    publish(DNSSD_SERVICE_AIRPLAY, dnssd->airplay_name, "_airplay._tcp", port, &dnssd->airplay_record);
    return 0;
}

void
dnssd_unregister_raop(dnssd_t *pub)
{
    assert(pub && pub->dnssd_private);
    dnssd_private_t *dnssd = (dnssd_private_t *) pub->dnssd_private;
    if (dnssd->raop_registered && g_callbacks.unregister_service) {
        g_callbacks.unregister_service(g_callbacks.cls, DNSSD_SERVICE_RAOP);
    }
    dnssd->raop_registered = 0;
}

void
dnssd_unregister_airplay(dnssd_t *pub)
{
    assert(pub && pub->dnssd_private);
    dnssd_private_t *dnssd = (dnssd_private_t *) pub->dnssd_private;
    if (dnssd->airplay_registered && g_callbacks.unregister_service) {
        g_callbacks.unregister_service(g_callbacks.cls, DNSSD_SERVICE_AIRPLAY);
    }
    dnssd->airplay_registered = 0;
}

const char *
dnssd_get_raop_txt(dnssd_t *pub, int *length)
{
    dnssd_private_t *dnssd = (dnssd_private_t *) pub->dnssd_private;
    *length = dnssd->raop_record.length;
    return (const char *) dnssd->raop_record.bytes;
}

const char *
dnssd_get_airplay_txt(dnssd_t *pub, int *length)
{
    dnssd_private_t *dnssd = (dnssd_private_t *) pub->dnssd_private;
    *length = dnssd->airplay_record.length;
    return (const char *) dnssd->airplay_record.bytes;
}

int
dnssd_get_service_fd(dnssd_t *pub, int service)
{
    (void) pub;
    (void) service;
    return -1;
}

int
dnssd_process_service(dnssd_t *pub, int service)
{
    (void) pub;
    (void) service;
    return 0;
}

void
dnssd_error_text(int *error, const char *appname)
{
    (void) error;
    (void) appname;
}
