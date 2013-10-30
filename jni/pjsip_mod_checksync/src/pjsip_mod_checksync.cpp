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

#define THIS_FILE "pjsip_mod_checksync.cpp"

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
    callback = cb;
    return pjsip_endpt_register_module(pjsua_get_pjsip_endpt(),	&stub);
}

pj_bool_t CheckSync::on_rx_request(pjsip_rx_data* data)
{
    if (callback == NULL) {
	return PJ_FALSE;
    }

    PJ_LOG(4, (THIS_FILE, "%s", __FUNCTION__));

    if (data == NULL || data->msg_info.cseq == NULL || data->msg_info.msg == NULL) {
	return PJ_FALSE;
    }
    
    // TODO: ...

    return PJ_FALSE;
}

pj_bool_t CheckSync::on_rx_response(pjsip_rx_data *data)
{
    PJ_LOG(4, (THIS_FILE, "%s", __FUNCTION__));

    // TODO: ..

    return PJ_FALSE;
}

pj_status_t CheckSync::on_tx_request(pjsip_tx_data *data)
{
    PJ_LOG(4, (THIS_FILE, "%s", __FUNCTION__));

    // TODO: ..

    return PJ_SUCCESS;
}

pj_status_t CheckSync::on_tx_response(pjsip_tx_data *data)
{
    PJ_LOG(4, (THIS_FILE, "%s", __FUNCTION__));

    // TODO: ..

    return PJ_SUCCESS;
}

void CheckSync::on_tsx_state(pjsip_transaction *tsx, pjsip_event *event)
{
    PJ_LOG(4, (THIS_FILE, "%s", __FUNCTION__));

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
