/*
 * pjsip_mobile_reg_handler.h
 *
 *  Created on: 1 déc. 2012
 *      Author: r3gis3r
 */

#ifndef PJSIP_MOD_CHECKSYNC_H_
#define PJSIP_MOD_CHECKSYNC_H_

#include <pj/config_site.h>
#include <pjsua-lib/pjsua.h>

class CheckSyncCallback 
{
public:
    virtual ~CheckSyncCallback() {}
};

extern "C" 
{
    pj_status_t mod_checksync_init(CheckSyncCallback* callback);
}

#endif /* PJSIP_MOD_CHECKSYNC_H_ */
