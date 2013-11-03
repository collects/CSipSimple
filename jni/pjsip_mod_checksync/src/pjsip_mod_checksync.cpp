/**
 * Copyright (C) 2013 Duzy Chan <code@duzy.info>
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <pjsip.h>
#include <pjsip_ua.h>
#include <pjlib-util.h>
#include <pjlib.h>
#include <pjlib.h>
#include <pjsua.h>
#include <pjsua-lib/pjsua_internal.h>
#include "pjsip_mod_checksync.h"
#include <android/log.h>

#define TAG "pjsip_mod_checksync"
#define THIS_FILE "pjsip_mod_checksync.cpp"
#define LOGD(fmt, args...) __android_log_print(ANDROID_LOG_DEBUG, TAG, "%d:\t%s: "fmt, __LINE__, __FUNCTION__, ##args)
#define LOGE(fmt, args...) __android_log_print(ANDROID_LOG_ERROR, TAG, "%d:\t%s: "fmt, __LINE__, __FUNCTION__, ##args)

class CheckSync
{
    static pjsip_module stub;
    static CheckSyncCallback* callback;

public:
    static void on_tsx_state(pjsip_transaction *tsx, pjsip_event *event);
    static pj_bool_t on_rx_request(pjsip_rx_data* data);
    static pj_bool_t on_rx_response(pjsip_rx_data *data);
    static pj_status_t on_tx_request(pjsip_tx_data *data);
    static pj_status_t on_tx_response(pjsip_tx_data *data);
    static pj_status_t init(CheckSyncCallback* cb);
};

pj_status_t CheckSync::init(CheckSyncCallback* cb)
{
    LOGD("%p", cb);

    callback = cb;

    return pjsip_endpt_register_module(pjsua_get_pjsip_endpt(),	&stub);
}

pj_bool_t CheckSync::on_rx_request(pjsip_rx_data* data)
{
    //LOGD("%s", __FUNCTION__);

    if (callback == NULL) {
	return PJ_FALSE;
    }

    if (data == NULL || data->msg_info.cseq == NULL || data->msg_info.msg == NULL) {
	return PJ_FALSE;
    }

    if (pjsip_method_cmp(&data->msg_info.msg->line.req.method,  &pjsip_notify_method) != 0) {
	return PJ_FALSE;
    }

    int n = 0;
    char event[512];
    pj_str_t hdr_name = pj_str("Event");
    pjsip_hdr *hdr = (pjsip_hdr*) pjsip_msg_find_hdr_by_name(data->msg_info.msg, &hdr_name, NULL);

    memset(event, 0, 512);

    //n = hdr->vptr->print_on(hdr, event, 512);
    //n = pjsip_msg_print(data->msg_info.msg, event, 512);
    n = pjsip_hdr_print_on(hdr, event, 512);

    // this is buggy -- crashing
    //LOGD("%p: %d, %s, %s", hdr, hdr->type, hdr->name, &event[0]);
    //LOGD("%s", event);

    char *s = strchr(event, ':');
    if (s == NULL) {
	LOGE("%s", event);
	return PJ_FALSE;
    }

    for (++s; *s && isspace(*s); ++s) /* ignore ':' and spaces */ ;

    if (strncmp(s, "check-sync", 10) == 0) {
	for (s += 10; *s && (isspace(*s) || *s == ';'); ++s) /* ignore ';' and spaces */ ;
	//LOGD("check-sync: %s", s);
	callback->on_event(s);
    }

    /*
    for (pjsip_hdr *h = data->msg_info.msg->hdr.next; h != &data->msg_info.msg->hdr; h = h->next) {
	memset(event, 0, 512);
	n = pjsip_hdr_print_on(h, event, 512);
	// this is buggy -- crashing
	LOGD("%p: %d, %s, %s", h, h->type, h->name, &event[0]);
	LOGD("%d, %s", n, event);
    }
    */
    return PJ_FALSE;
}

pj_bool_t CheckSync::on_rx_response(pjsip_rx_data *data)
{
    //LOGD("%s", __FUNCTION__);

    // TODO: ..

    return PJ_FALSE;
}

pj_status_t CheckSync::on_tx_request(pjsip_tx_data *data)
{
    //LOGD("%s", __FUNCTION__);

    // TODO: ..

    return PJ_SUCCESS;
}

pj_status_t CheckSync::on_tx_response(pjsip_tx_data *data)
{
    //LOGD("%s", __FUNCTION__);

    // TODO: ..

    return PJ_SUCCESS;
}

void CheckSync::on_tsx_state(pjsip_transaction *tsx, pjsip_event *event)
{
    //LOGD("%s", __FUNCTION__);

    // TODO: ..
}

CheckSyncCallback* CheckSync::callback = NULL;
pjsip_module CheckSync::stub = {
    NULL, NULL,         /* prev, next.	*/
    {			/* Name.	*/
	(char*)"mod-checksync", 13
    },
    -1,                 /* Id           */
    PJSIP_MOD_PRIORITY_TSX_LAYER - 1,  /* Priority         */
    NULL,               /* load()       */
    NULL,               /* start()      */
    NULL,               /* stop()       */
    NULL,               /* unload()     */
    &CheckSync::on_rx_request,
    &CheckSync::on_rx_response,
    &CheckSync::on_tx_request,
    &CheckSync::on_tx_response,
    &CheckSync::on_tsx_state,
};

pj_status_t mod_checksync_init(CheckSyncCallback* cb)
{
    return CheckSync::init(cb);
}
