// http.c
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <ctype.h>
#include <stdint.h>       // ← added for uint32_t
#include <android/log.h>

#include "tun2http.h"

#define LOG_TAG "Tun2Http_HTTP"
#define LOG(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)

// Buffer used by patch_http_url() – 2×MTU is more than enough
static uint32_t patch_buffer[2 * MTU / sizeof(uint32_t) + 1];

static int next_header(const char **data, size_t *len);

static const char http_503[] __attribute__((unused)) =
        "HTTP/1.1 503 Service Temporarily Unavailable\r\n"
        "Content-Type: text/html\r\n"
        "Connection: close\r\n\r\n"
        "Backend not available";

/* -------------------------------------------------------------------------- */
/*  get_header – extract value of a specific header (e.g. "Host:")            */
/* -------------------------------------------------------------------------- */
int get_header(const char *header, const char *data, size_t data_len, char *value) {
    int len, header_len = (int)strlen(header);

    const char *ptr = data;
    size_t remaining = data_len;

    /* loop through headers stopping at first blank line */
    while ((len = next_header(&ptr, &remaining)) != 0) {
        if (len > header_len && strncasecmp(header, ptr, header_len) == 0) {
            /* skip leading whitespace after header name */
            while (header_len < len && isblank(ptr[header_len]))
                header_len++;

            if (value == NULL)
                return -4;

            int value_len = len - header_len;
            strncpy(value, ptr + header_len, value_len);
            value[value_len] = '\0';
            return value_len;
        }

        // move to next header
        ptr += len;
        remaining -= len;
    }

    /* No data left after headers → incomplete request */
    if (remaining == 0)
        return -1;

    return -2; // header not found
}

/* -------------------------------------------------------------------------- */
/*  next_header – returns length of next header line (without CRLF)           */
/* -------------------------------------------------------------------------- */
int next_header(const char **data, size_t *len)
{
    const char *p = *data;
    size_t n = *len;

    // skip until \r\n
    while (n > 2 && (p[0] != '\r' || p[1] != '\n')) {
        p++;
        n--;
    }

    size_t header_len = p - *data;

    if (n <= 2) {               // not even CRLF found → incomplete
        *data = p;
        *len = n;
        return 0;
    }

    // skip the CRLF
    *data = p + 2;
    *len = n - 2;

    return (int)header_len;
}

/* -------------------------------------------------------------------------- */
/*  simple string search inside packet                                         */
/* -------------------------------------------------------------------------- */
uint8_t *find_data(uint8_t *data, size_t data_len, const char *value)
{
    size_t vlen = strlen(value);
    if (data_len < vlen) return NULL;

    for (size_t i = 0; i <= data_len - vlen; i++) {
        if (strncasecmp((char*)data + i, value, vlen) == 0)
            return data + i;
    }
    return NULL;
}

/* -------------------------------------------------------------------------- */
/*  patch_http_url – inject http://host into absolute-URI requests             */
/* -------------------------------------------------------------------------- */
uint8_t *patch_http_url(uint8_t *data, size_t *data_len)
{
    __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, "patch_http_url start");

    char hostname[512] = {0};
    uint8_t *host_hdr = find_data(data, *data_len, "Host: ");
    if (!host_hdr) {
        LOG("no Host header");
        return NULL;
    }

    // extract hostname
    host_hdr += 6;  // skip "Host: "
    size_t hlen = 0;
    while (hlen < sizeof(hostname)-1 && host_hdr[hlen] != '\r') {
        hostname[hlen] = host_hdr[hlen];
        hlen++;
    }

    // find request method
    const char *methods[] = {"GET ", "POST ", "PUT ", "DELETE ", "HEAD ",
                             "OPTIONS ", "PATCH ", "TRACE ", "PROPFIND ",
                             "PROPPATCH ", "MKCOL ", "COPY ", "MOVE ",
                             "LOCK ", "UNLOCK "};
    uint8_t *method_pos = NULL;
    size_t method_len = 0;

    for (size_t i = 0; i < sizeof(methods)/sizeof(methods[0]); i++) {
        uint8_t *p = find_data(data, *data_len, methods[i]);
        if (p) {
            method_pos = p;
            method_len = strlen(methods[i]);
            break;
        }
    }

    if (!method_pos) {
        LOG("no supported HTTP method found");
        return NULL;
    }

    size_t insert_pos = method_pos - data + method_len;

    // already patched ?
    if (*data_len > insert_pos + 7 &&
        memcmp(data + insert_pos, "http://", 7) == 0) {
        LOG("already patched");
        return NULL;
    }

    // build new packet
    uint8_t *newpkt = (uint8_t*)patch_buffer;
    size_t prefix_len = insert_pos;

    memcpy(newpkt, data, prefix_len);
    memcpy(newpkt + prefix_len, "http://", 7);
    memcpy(newpkt + prefix_len + 7, hostname, hlen);
    memcpy(newpkt + prefix_len + 7 + hlen, data + insert_pos, *data_len - insert_pos);

    *data_len += 7 + hlen;

    __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, "patched → http://%s", hostname);
    return newpkt;
}