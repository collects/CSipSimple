/**
 * Copyright (C) 2010-2012 Regis Montoya (aka r3gis - www.r3gis.fr)
 * This file is part of pjsip_android.
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

#include "pj_vpx.h"
#include <pjmedia/errno.h>
#include <pjmedia/vid_codec_util.h>
#include <pj/assert.h>
#include <pj/list.h>
#include <pj/log.h>
#include <pj/math.h>
#include <pj/pool.h>
#include <pj/string.h>
#include <pj/os.h>
#if defined(PJ_HAS_UNISTD_H) && PJ_HAS_UNISTD_H!=0
#    include <unistd.h>
#endif

#define PJMEDIA_HAS_VPX_CODEC 1
/*
 * Only build this file if PJMEDIA_HAS_VPX_CODEC != 0 and
 * PJMEDIA_HAS_VIDEO != 0
 */
#if defined(PJMEDIA_HAS_VPX_CODEC) && \
            PJMEDIA_HAS_VPX_CODEC != 0 && \
    defined(PJMEDIA_HAS_VIDEO) && (PJMEDIA_HAS_VIDEO != 0)

#define THIS_FILE   "pj_vpx.c"

#include <vpx/vpx_encoder.h>
#include <vpx/vp8cx.h>
#include <vpx/vpx_decoder.h>
#include <vpx/vp8dx.h>

#if 1
#   define TRACE_(x)    PJ_LOG(4, x)
#else
#   define TRACE_(x)
#endif

/* Prototypes for LibVPX codecs factory */
static pj_status_t pj_vpx_test_alloc(pjmedia_vid_codec_factory *factory,
        const pjmedia_vid_codec_info *id);
static pj_status_t pj_vpx_default_attr(pjmedia_vid_codec_factory *factory,
        const pjmedia_vid_codec_info *info, pjmedia_vid_codec_param *attr);
static pj_status_t pj_vpx_enum_codecs(pjmedia_vid_codec_factory *factory,
        unsigned *count, pjmedia_vid_codec_info codecs[]);
static pj_status_t pj_vpx_alloc_codec(pjmedia_vid_codec_factory *factory,
        const pjmedia_vid_codec_info *info, pjmedia_vid_codec **p_codec);
static pj_status_t pj_vpx_dealloc_codec(pjmedia_vid_codec_factory *factory,
        pjmedia_vid_codec *codec);

/* Prototypes for VPX codec implementation. */
static pj_status_t pj_vpx_codec_init(pjmedia_vid_codec *codec, pj_pool_t *pool);
static pj_status_t pj_vpx_codec_open(pjmedia_vid_codec *codec,
        pjmedia_vid_codec_param *attr);
static pj_status_t pj_vpx_codec_close(pjmedia_vid_codec *codec);
static pj_status_t pj_vpx_codec_modify(pjmedia_vid_codec *codec,
        const pjmedia_vid_codec_param *attr);
static pj_status_t pj_vpx_codec_get_param(pjmedia_vid_codec *codec,
        pjmedia_vid_codec_param *param);
static pj_status_t pj_vpx_codec_encode_begin(pjmedia_vid_codec *codec,
        const pjmedia_vid_encode_opt *opt, const pjmedia_frame *input,
        unsigned out_size, pjmedia_frame *output, pj_bool_t *has_more);
static pj_status_t pj_vpx_codec_encode_more(pjmedia_vid_codec *codec,
        unsigned out_size, pjmedia_frame *output, pj_bool_t *has_more);
static pj_status_t pj_vpx_codec_decode(pjmedia_vid_codec *codec,
        pj_size_t pkt_count, pjmedia_frame packets[], unsigned out_size,
        pjmedia_frame *output);

/* Definition for VPX codec operations. */
static pjmedia_vid_codec_op vpx_op = { &pj_vpx_codec_init, &pj_vpx_codec_open,
        &pj_vpx_codec_close, &pj_vpx_codec_modify, &pj_vpx_codec_get_param,
        &pj_vpx_codec_encode_begin, &pj_vpx_codec_encode_more,
        &pj_vpx_codec_decode, NULL };

/* Definition for VPX factory operations. */
static pjmedia_vid_codec_factory_op vpx_factory_op = { &pj_vpx_test_alloc,
        &pj_vpx_default_attr, &pj_vpx_enum_codecs, &pj_vpx_alloc_codec,
        &pj_vpx_dealloc_codec };

/* VPX codec factory */
static struct vpx_factory {
    pjmedia_vid_codec_factory base;
    pjmedia_vid_codec_mgr *mgr;
    pj_pool_factory *pf;
    pj_pool_t *pool;
    pj_mutex_t *mutex;
} vpx_factory;

typedef struct vpx_codec_desc vpx_codec_desc;

/* VPX codec private data. */
typedef struct vpx_private {
    const vpx_codec_desc *desc;
    pjmedia_vid_codec_param param; /**< Codec param	    */
    pj_pool_t *pool; /**< Pool for each instance */

    /* Format info and apply format param */
    const pjmedia_video_format_info *enc_vfi;
    pjmedia_video_apply_fmt_param enc_vafp;
    const pjmedia_video_format_info *dec_vfi;
    pjmedia_video_apply_fmt_param dec_vafp;

    /* The vpx encoder. */
    struct vpx_codec_ctx    encoder;
    struct vpx_image        rawimg;
    void                    *enc_buf;
    unsigned                enc_buf_size;
    pj_bool_t                enc_buf_is_keyframe;
    unsigned                 enc_frame_len;
    unsigned                 enc_processed;
    //struct vpx_fixed_buf twopass_stats;
    vpx_codec_iter_t        enc_iter;

    /* The vpx decoder */
    struct vpx_codec_ctx    decoder;
    void                    *dec_buf;
    unsigned                dec_buf_size;
    unsigned                dec_frame_len;
    pj_bool_t               dec_stream_info_init;
    pj_timestamp            last_dec_keyframe_ts;

} vpx_private;

/*
 * Initialize and register VPX codec factory to pjmedia endpoint.
 */PJ_DEF(pj_status_t) pjmedia_codec_vpx_init(pjmedia_vid_codec_mgr *mgr,
        pj_pool_factory *pf) {
    pj_pool_t *pool;
    pj_status_t status;

    TRACE_((THIS_FILE, "Init vpx codec"));

    if (vpx_factory.pool != NULL ) {
        /* Already initialized. */
        return PJ_SUCCESS;
    }

    if (!mgr)
        mgr = pjmedia_vid_codec_mgr_instance();
    PJ_ASSERT_RETURN(mgr, PJ_EINVAL);

    /* Create VPX codec factory. */
    vpx_factory.base.op = &vpx_factory_op;
    vpx_factory.base.factory_data = NULL;
    vpx_factory.mgr = mgr;
    vpx_factory.pf = pf;

    pool = pj_pool_create(pf, "vpx codec factory", 256, 256, NULL );
    if (!pool)
        return PJ_ENOMEM;

    /* Create mutex. */
    status = pj_mutex_create_simple(pool, "vpx codec factory",
            &vpx_factory.mutex);
    if (status != PJ_SUCCESS)
        goto on_error;

    /* Register codec factory to codec manager. */
    status = pjmedia_vid_codec_mgr_register_factory(mgr, &vpx_factory.base);
    if (status != PJ_SUCCESS)
        goto on_error;

    vpx_factory.pool = pool;

    /* Done. */
    return PJ_SUCCESS;

    on_error: pj_pool_release(pool);
    return status;
}

/*
 * Unregister VPX factory from pjmedia endpoint.
 */PJ_DEF(pj_status_t) pjmedia_codec_vpx_deinit(void) {
    pj_status_t status = PJ_SUCCESS;
    TRACE_((THIS_FILE, "Deinit vpx codec"));

    if (vpx_factory.pool == NULL ) {
        /* Already deinitialized */
        return PJ_SUCCESS;
    }

    pj_mutex_lock(vpx_factory.mutex);

    /* Unregister VPX codecs factory. */
    status = pjmedia_vid_codec_mgr_unregister_factory(vpx_factory.mgr,
            &vpx_factory.base);

    /* Destroy mutex. */
    pj_mutex_destroy(vpx_factory.mutex);

    /* Destroy pool. */
    pj_pool_release(vpx_factory.pool);
    vpx_factory.pool = NULL;

    return status;
}

/*
 * Check if factory can allocate the specified codec.
 */
static pj_status_t pj_vpx_test_alloc(pjmedia_vid_codec_factory *factory,
        const pjmedia_vid_codec_info *info) {
    const pj_str_t vpx_tag = { "VP8", 3};

    PJ_ASSERT_RETURN(factory==&vpx_factory.base, PJ_EINVAL);
    PJ_ASSERT_RETURN(info, PJ_EINVAL);

    /* Check encoding name. */
    if (pj_stricmp(&info->encoding_name, &vpx_tag) != 0)
    return PJMEDIA_CODEC_EUNSUP;

    return PJ_SUCCESS;
}

/*
 * Generate default attribute.
 */
static pj_status_t pj_vpx_default_attr(pjmedia_vid_codec_factory *factory,
        const pjmedia_vid_codec_info *info, pjmedia_vid_codec_param *attr) {

    PJ_ASSERT_RETURN(factory==&vpx_factory.base, PJ_EINVAL);
    PJ_ASSERT_RETURN(info && attr, PJ_EINVAL);

    TRACE_((THIS_FILE, "vpx default attr"));


    pj_bzero(attr, sizeof(pjmedia_vid_codec_param));

    /* Scan the requested packings and use the lowest number */
    attr->packing = 1;

    /* Direction */
    attr->dir = PJMEDIA_DIR_ENCODING_DECODING;

    /* Encoded format */
    pjmedia_format_init_video(&attr->enc_fmt, PJMEDIA_FORMAT_PACK('L', 'V', 'P', '8'), 640, 480, 30,
            1);

    /* Decoded format */
    pjmedia_format_init_video(&attr->dec_fmt, PJMEDIA_FORMAT_I420, 640, 480, 30,
            1);

    /* Decoding fmtp */
    attr->dec_fmtp.cnt = 0;

    /* Bitrate */
    attr->enc_fmt.det.vid.avg_bps = 1024 * 1000;
    attr->enc_fmt.det.vid.max_bps = 1024 * 1000;

    attr->enc_fmtp.cnt = 0;

    /* Encoding MTU */
    attr->enc_mtu = PJMEDIA_MAX_VID_PAYLOAD_SIZE;

    return PJ_SUCCESS;
}

/*
 * Enum codecs supported by this factory.
 */
static pj_status_t pj_vpx_enum_codecs(pjmedia_vid_codec_factory *factory,
        unsigned *count, pjmedia_vid_codec_info codecs[]) {

    PJ_ASSERT_RETURN(codecs && *count > 0, PJ_EINVAL);
    PJ_ASSERT_RETURN(factory == &vpx_factory.base, PJ_EINVAL);
    TRACE_((THIS_FILE, "Enum codecs..."));

    *count = 0;
    codecs[*count].fmt_id = PJMEDIA_FORMAT_PACK('L', 'V', 'P', '8');
    codecs[*count].pt = PJMEDIA_RTP_PT_VP8;
    codecs[*count].encoding_name = pj_str("VP8");
    codecs[*count].encoding_desc = pj_str("WebM Project VP8 Encoder");
    codecs[*count].dir = PJMEDIA_DIR_ENCODING_DECODING;
    codecs[*count].clock_rate = 90000;
    codecs[*count].fps_cnt = 0;
    codecs[*count].dec_fmt_id_cnt = 1;
    codecs[*count].dec_fmt_id[0] = PJMEDIA_FORMAT_I420;

    /*
     codecs[*count].fps[0].num = 15;
     codecs[*count].fps[0].denum = 1;
     */
    codecs[*count].packings = PJMEDIA_VID_PACKING_PACKETS;

    (*count)++;

    return PJ_SUCCESS;
}

/*
 * Allocate a new codec instance.
 */
static pj_status_t pj_vpx_alloc_codec(pjmedia_vid_codec_factory *factory,
        const pjmedia_vid_codec_info *info, pjmedia_vid_codec **p_codec) {
    vpx_private *vpx;
    pjmedia_vid_codec *codec;
    pj_pool_t *pool = NULL;
    pj_status_t status = PJ_SUCCESS;

    PJ_ASSERT_RETURN(factory && info && p_codec, PJ_EINVAL);
    PJ_ASSERT_RETURN(factory == &vpx_factory.base, PJ_EINVAL);

    TRACE_((THIS_FILE, "vpx pj_vpx_alloc_codec"));


    if (info->pt != PJMEDIA_RTP_PT_VP8) {
        return PJMEDIA_CODEC_EUNSUP;
    }

    /* Create pool for codec instance */
    pool = pj_pool_create(vpx_factory.pf, "vp8 codec", 512, 512, NULL );
    codec = PJ_POOL_ZALLOC_T(pool, pjmedia_vid_codec);
    if (!codec) {
        status = PJ_ENOMEM;
        goto on_error;
    }
    codec->op = &vpx_op;
    codec->factory = factory;
    vpx = PJ_POOL_ZALLOC_T(pool, vpx_private);
    if (!vpx) {
        status = PJ_ENOMEM;
        goto on_error;
    }
    codec->codec_data = vpx;
    vpx->pool = pool;

    *p_codec = codec;
    return PJ_SUCCESS;

    on_error: if (pool)
        pj_pool_release(pool);
    return status;
}

/*
 * Free codec.
 */
static pj_status_t pj_vpx_dealloc_codec(pjmedia_vid_codec_factory *factory,
        pjmedia_vid_codec *codec) {
    vpx_private *vpx;
    pj_pool_t *pool;

    PJ_ASSERT_RETURN(factory && codec, PJ_EINVAL);
    PJ_ASSERT_RETURN(factory == &vpx_factory.base, PJ_EINVAL);
    TRACE_((THIS_FILE, "vpx pj_vpx_dealloc_codec"));

    /* Close codec, if it's not closed. */
    vpx = (vpx_private*) codec->codec_data;
    pool = vpx->pool;
    codec->codec_data = NULL;
    pj_pool_release(pool);

    return PJ_SUCCESS;
}

/*
 * Init codec.
 */
static pj_status_t pj_vpx_codec_init(pjmedia_vid_codec *codec, pj_pool_t *pool) {
    PJ_UNUSED_ARG(codec);
    PJ_UNUSED_ARG(pool);
    TRACE_((THIS_FILE, "vpx pj_vpx_codec_init"));

    return PJ_SUCCESS;
}

static pj_status_t pj_vpx_encoder_open(vpx_private *vpx) {
    vpx_codec_flags_t flags = 0;
    const struct vpx_codec_iface *iface = &vpx_codec_vp8_cx_algo;
    struct vpx_codec_enc_cfg enccfg;
    int cpu_speed, rc_max_intra_target;
    int res;

    TRACE_((THIS_FILE, "vpx pj_vpx_encoder_open"));

    res = vpx_codec_enc_config_default(iface, &enccfg, 0);
    if (res != VPX_CODEC_OK) {
        PJ_LOG(1,
                (THIS_FILE, "Failed to get vpx default config : %s", vpx_codec_err_to_string(res)));
        return PJMEDIA_CODEC_EFAILED;
    }

    enccfg.g_w = vpx->param.enc_fmt.det.vid.size.w;
    enccfg.g_h = vpx->param.enc_fmt.det.vid.size.h;
    enccfg.g_timebase.num = vpx->param.enc_fmt.det.vid.fps.num;
    enccfg.g_timebase.den = vpx->param.enc_fmt.det.vid.fps.denum;
    //provide dummy value to initialize wrapper, values will be updated each _encode()
    vpx_img_wrap(&vpx->rawimg, VPX_IMG_FMT_I420,
            vpx->param.enc_fmt.det.vid.size.w, vpx->param.enc_fmt.det.vid.size.h,
            1, (unsigned char*)1);

    // Following config taken from webRTC project.
    // vpx seems to support more configurable/complex settings.
    // could be interesting to have a look, but for now, consider
    // webRTC settings as optimized for our needs
#if defined(PJ_HAS_UNISTD_H) && PJ_HAS_UNISTD_H!=0
    if (enccfg.g_w * enccfg.g_h > 704 * 576
            && sysconf(_SC_NPROCESSORS_CONF) > 1) {
        // 2 threads when larger than 4CIF
        enccfg.g_threads = 2;
    } else
#endif
    {
        enccfg.g_threads = 1;
    }
    enccfg.g_lag_in_frames = 0;
    enccfg.g_pass = VPX_RC_ONE_PASS;
    enccfg.rc_end_usage = VPX_CBR;
    enccfg.rc_target_bitrate = vpx->param.enc_fmt.det.vid.avg_bps / 1000; // in kbit/s
    enccfg.g_timebase.num = 1;
    enccfg.g_timebase.den = 90000;
    // TODO : need a setting for 2 following ?
    enccfg.g_error_resilient = 0;
    enccfg.rc_resize_allowed = 1;
    enccfg.rc_min_quantizer = 2;
    enccfg.rc_max_quantizer = 56;
    enccfg.rc_undershoot_pct = 100;
    enccfg.rc_overshoot_pct = 15;
    enccfg.rc_buf_initial_sz = 500;
    enccfg.rc_buf_optimal_sz = 600;
    enccfg.rc_buf_sz = 1000;
    // Not use feedback_mode
    enccfg.kf_mode = VPX_KF_AUTO;
    enccfg.kf_max_dist = 3000;

    /* scalePar = 0.5; maxFrameRate = 30fps*/
    /* Don't go below 3 times the per frame bandwidth. */
    rc_max_intra_target = PJ_MAX(300, enccfg.rc_buf_sz * 0.5 * 30 / 10);
    // for android cpu speed set to -12
    // TODO : adjust for other platform as done in webRTC
    cpu_speed = -12;
    //flags |= VPX_CODEC_USE_OUTPUT_PARTITION;
    res = vpx_codec_enc_init(&vpx->encoder, vpx_codec_vp8_cx(), &enccfg, flags);
    if (res != VPX_CODEC_OK) {
        PJ_LOG(1,
                (THIS_FILE, "Failed to init vpx encoder : %s", vpx_codec_err_to_string(res)));
        return PJMEDIA_CODEC_EFAILED;
    }
    vpx_codec_control(&vpx->encoder, VP8E_SET_STATIC_THRESHOLD, 1);
    vpx_codec_control(&vpx->encoder, VP8E_SET_CPUUSED, cpu_speed);
    vpx_codec_control(&vpx->encoder, VP8E_SET_TOKEN_PARTITIONS,
            VP8_ONE_TOKENPARTITION);
    vpx_codec_control(&vpx->encoder, VP8E_SET_NOISE_SENSITIVITY, 1);
    vpx_codec_control(&vpx->encoder, VP8E_SET_MAX_INTRA_BITRATE_PCT,
            rc_max_intra_target);
    vpx->enc_iter = NULL;

    vpx->enc_buf_size = vpx->enc_vafp.framebytes;
    vpx->enc_buf = pj_pool_alloc(vpx->pool, vpx->enc_buf_size);

    vpx->dec_buf_size = vpx->dec_vafp.framebytes;
    vpx->dec_buf = pj_pool_alloc(vpx->pool, vpx->dec_buf_size);

    return PJ_SUCCESS;
}

static pj_status_t pj_vpx_decoder_open(vpx_private *vpx) {
    vpx_codec_flags_t flags = 0;
    vpx_codec_dec_cfg_t cfg;
    int res;

    cfg.threads = 1;
    cfg.h = 0;
    cfg.w = 0;

    res = vpx_codec_dec_init(&vpx->decoder, vpx_codec_vp8_dx(), &cfg, flags);
    if (res != VPX_CODEC_OK) {
        PJ_LOG(1,
                (THIS_FILE, "Failed to init vpx decoder : %s", vpx_codec_err_to_string(res)));
        return PJ_ENOMEM;
    }

    return PJ_SUCCESS;
}

/*
 * Open codec.
 */
static pj_status_t pj_vpx_codec_open(pjmedia_vid_codec *codec,
        pjmedia_vid_codec_param *attr) {
    vpx_private *vpx;
    pj_status_t status;

    PJ_ASSERT_RETURN(codec && attr, PJ_EINVAL);
    vpx = (vpx_private*) codec->codec_data;

    pj_memcpy(&vpx->param, attr, sizeof(*attr));

    /* Normalize encoding MTU in codec param */
    if (attr->enc_mtu > PJMEDIA_MAX_VID_PAYLOAD_SIZE) {
        attr->enc_mtu = PJMEDIA_MAX_VID_PAYLOAD_SIZE;
    }

    /* We only support I420 frames for encoding/decoding */
    /* TODO : support more and take into account incoming params */
    attr->dec_fmt.id = PJMEDIA_FORMAT_I420;
    attr->enc_fmt.id = PJMEDIA_FORMAT_I420;

    /* Init format info and apply-param of decoder */
    vpx->dec_vfi = pjmedia_get_video_format_info(NULL, vpx->param.dec_fmt.id);
    if (!vpx->dec_vfi) {
        status = PJ_EINVAL;
        goto on_error;
    }
    pj_bzero(&vpx->dec_vafp, sizeof(vpx->dec_vafp));
    vpx->dec_vafp.size = vpx->param.dec_fmt.det.vid.size;
    vpx->dec_vafp.buffer = NULL;
    status = (*vpx->dec_vfi->apply_fmt)(vpx->dec_vfi, &vpx->dec_vafp);
    if (status != PJ_SUCCESS) {
        goto on_error;
    }

    /* Init format info and apply-param of encoder */
    vpx->enc_vfi = pjmedia_get_video_format_info(NULL, vpx->param.dec_fmt.id);
    if (!vpx->enc_vfi) {
        status = PJ_EINVAL;
        goto on_error;
    }
    pj_bzero(&vpx->enc_vafp, sizeof(vpx->enc_vafp));
    vpx->enc_vafp.size = vpx->param.enc_fmt.det.vid.size;
    vpx->enc_vafp.buffer = NULL;
    status = (*vpx->enc_vfi->apply_fmt)(vpx->enc_vfi, &vpx->enc_vafp);
    if (status != PJ_SUCCESS) {
        goto on_error;
    }

    /* Open the encoder */
    TRACE_((THIS_FILE, "Open vpx version : %s build : %s",
    		vpx_codec_version_str(), vpx_codec_build_config()));

    if (vpx->param.dir & PJMEDIA_DIR_ENCODING) {
        status = pj_vpx_encoder_open(vpx);
        if (status != PJ_SUCCESS) {
            goto on_error;
        }
    }
    if (vpx->param.dir & PJMEDIA_DIR_DECODING) {
        status = pj_vpx_decoder_open(vpx);
        if (status != PJ_SUCCESS) {
            goto on_error;
        }
    }


    /* Update codec attributes, e.g: encoding format may be changed by
     * SDP fmtp negotiation.
     */
    pj_memcpy(attr, &vpx->param, sizeof(*attr));

    return PJ_SUCCESS;

    on_error: pj_vpx_codec_close(codec);
    return status;
}

/*
 * Close codec.
 */
static pj_status_t pj_vpx_codec_close(pjmedia_vid_codec *codec) {
    vpx_private *vpx;

    PJ_ASSERT_RETURN(codec, PJ_EINVAL);
    vpx = (vpx_private*) codec->codec_data;

    // TODO : close vpx here
    vpx_codec_destroy(&vpx->decoder);
    vpx_codec_destroy(&vpx->encoder);

    return PJ_SUCCESS;
}

/*
 * Modify codec settings.
 */
static pj_status_t pj_vpx_codec_modify(pjmedia_vid_codec *codec,
        const pjmedia_vid_codec_param *attr) {
    vpx_private *vpx = (vpx_private*) codec->codec_data;

    // TODO : add bitrate change support here
    PJ_UNUSED_ARG(attr);
    PJ_UNUSED_ARG(vpx);

    return PJ_ENOTSUP;
}

static pj_status_t pj_vpx_codec_get_param(pjmedia_vid_codec *codec,
        pjmedia_vid_codec_param *param) {
    vpx_private *vpx;

    PJ_ASSERT_RETURN(codec && param, PJ_EINVAL);

    vpx = (vpx_private*) codec->codec_data;
    pj_memcpy(param, &vpx->param, sizeof(*param));

    return PJ_SUCCESS;
}

/*
 * Encode frames.
 */

static pj_status_t pj_vpx_codec_encode_begin(pjmedia_vid_codec *codec,
        const pjmedia_vid_encode_opt *opt, const pjmedia_frame *input,
        unsigned out_size, pjmedia_frame *output, pj_bool_t *has_more) {
    vpx_private *vpx = (vpx_private*) codec->codec_data;
    vpx_image_t *rawimg;
    vpx_enc_frame_flags_t flags = 0;
    pj_uint8_t *p;
    int i, res;

    PJ_ASSERT_RETURN(codec && input, PJ_EINVAL);

    p = (pj_uint8_t*) input->buf;

    *has_more = PJ_FALSE;

    rawimg = &vpx->rawimg;
    // TODO : make sure it's I420 ?
    if(input->size < vpx->enc_vafp.framebytes){
        PJ_LOG(1, (THIS_FILE, "Frame provided is too small !"));
        return PJ_ETOOSMALL;
    }

    for (i = 0; i < vpx->enc_vfi->plane_cnt; ++i) {
        rawimg->planes[i] = p;
        rawimg->stride[i] = vpx->enc_vafp.strides[i];
        p += vpx->enc_vafp.plane_bytes[i];
    }

    if (opt && opt->force_keyframe) {
        flags |= VPX_EFLAG_FORCE_KF;
    }

    res = vpx_codec_encode(&vpx->encoder, rawimg, input->timestamp.u64, 1, flags,
            VPX_DL_REALTIME);
    if (res != VPX_CODEC_OK) {
        PJ_LOG(1, (THIS_FILE, "Failed to encode : %s %s",
                      vpx_codec_err_to_string(res), vpx->encoder.err_detail));
        return PJMEDIA_CODEC_EFAILED;
    }

    vpx->enc_iter = NULL;
    vpx->enc_frame_len = 0;
    vpx->enc_processed = 0;
    return pj_vpx_codec_encode_more(codec, out_size, output, has_more);
}

static pj_status_t pj_vpx_codec_encode_more(pjmedia_vid_codec *codec,
        unsigned out_size, pjmedia_frame *output, pj_bool_t *has_more) {
    vpx_private *vpx = (vpx_private*) codec->codec_data;
    const vpx_codec_cx_pkt_t *pkt;

    /* Default return */
    *has_more = PJ_FALSE;
    output->size = 0;
    output->type = PJMEDIA_FRAME_TYPE_NONE;

    if (vpx->enc_frame_len == 0) {
        /*
         * For now we assume that we have only one cx data here
         * Which is probably fine as we do not ask encoder to bufferize
         */
        //PJ_LOG(4, (THIS_FILE, "Encode one frame at %p", vpx->enc_iter));
        pkt = vpx_codec_get_cx_data(&vpx->encoder, &vpx->enc_iter);
        if (pkt == NULL ) {
            if (!vpx->encoder.err) {
                PJ_LOG(3, (THIS_FILE, "Encoder packet dropped"));
                return PJ_SUCCESS;
            } else {
                PJ_LOG(1,
                        (THIS_FILE, "Failed to get cx datas : %s", vpx_codec_err_to_string(vpx->encoder.err)));
                return PJMEDIA_CODEC_EFAILED;
            }
        } else if (pkt->kind == VPX_CODEC_CX_FRAME_PKT) {
            pj_memcpy(vpx->enc_buf, pkt->data.frame.buf, pkt->data.frame.sz);
            vpx->enc_frame_len = pkt->data.frame.sz;
            vpx->enc_processed = 0;
            vpx->enc_buf_is_keyframe = !!(pkt->data.frame.flags & VPX_FRAME_IS_KEY) ? PJ_TRUE : PJ_FALSE;

            //PJ_LOG(4, (THIS_FILE, "Encoded with 0 byte : %d", ((pj_uint8_t*)(vpx->enc_buf))[0]));
        } else {
            PJ_LOG(6, (THIS_FILE, "Vpx packet kind %d not take into account", pkt->kind));
            return PJ_SUCCESS;
        }
    }
    // TODO we should support if iter not over too

    if(vpx->enc_frame_len > 0) {
        //PJ_LOG(4, (THIS_FILE, "We have an enc_frame : %d; max : %d", vpx->enc_frame_len, vpx->param.enc_mtu));
        /* Reserve 1 octet for vp8 packetization info */
        unsigned max_size = vpx->param.enc_mtu - 1;
        unsigned remaining_size = vpx->enc_frame_len - vpx->enc_processed;
        /* TODO : we could equally distributed packets sizes */
        unsigned payload_len = PJ_MIN(remaining_size, max_size);
        pj_uint8_t* p = (pj_uint8_t*) output->buf;
        pj_uint8_t* s = (pj_uint8_t*) vpx->enc_buf;
        //PJ_LOG(4, (THIS_FILE, "Payload : %d", payload_len));

        output->type = PJMEDIA_FRAME_TYPE_VIDEO;
        output->bit_info = 0;
        if (vpx->enc_buf_is_keyframe) {
            output->bit_info |= PJMEDIA_VID_FRM_KEYFRAME;
        }
        /* Set vp8 packetization info */
        p[0] = 0;
        if(vpx->enc_processed == 0) p[0] |= 0x10;
        if(!vpx->enc_buf_is_keyframe) p[0] |= 0x20;

        pj_memcpy( (p + 1), (s + vpx->enc_processed), payload_len);
        output->size = payload_len + 1;

        vpx->enc_processed += payload_len;
        *has_more = !(vpx->enc_processed == vpx->enc_frame_len);
    }

    //PJ_LOG(4, (THIS_FILE, "Encoded size %d", output->size));

    return PJ_SUCCESS;
}

/*
 * Decode frame.
 */

static pj_status_t check_decode_result(pjmedia_vid_codec *codec,
        const vpx_image_t *img, const pj_timestamp *ts) {
    vpx_private *vpx = (vpx_private*) codec->codec_data;
    pjmedia_video_apply_fmt_param *vafp = &vpx->dec_vafp;
    pjmedia_event event;
    int res, reference_updates = 0;

    /* Check for format change.
     * Decoder output format is set by libavcodec, in case it is different
     * to the configured param.
     */
    if (img->d_w != (int) vafp->size.w || img->d_h != (int) vafp->size.h) {
        pj_status_t status;

        /* Update decoder format in param */
        vpx->param.dec_fmt.det.vid.size.w = img->d_w;
        vpx->param.dec_fmt.det.vid.size.h = img->d_h;

        /* Re-init format info and apply-param of decoder */
        vpx->dec_vfi = pjmedia_get_video_format_info(NULL,
                vpx->param.dec_fmt.id);
        if (!vpx->dec_vfi)
            return PJ_ENOTSUP;
        pj_bzero(&vpx->dec_vafp, sizeof(vpx->dec_vafp));
        vpx->dec_vafp.size = vpx->param.dec_fmt.det.vid.size;
        vpx->dec_vafp.buffer = NULL;
        status = (*vpx->dec_vfi->apply_fmt)(vpx->dec_vfi, &vpx->dec_vafp);
        if (status != PJ_SUCCESS)
            return status;

        /* TODO : Realloc buffer if necessary */

        /* Broadcast format changed event */
        pjmedia_event_init(&event, PJMEDIA_EVENT_FMT_CHANGED, ts, codec);
        event.data.fmt_changed.dir = PJMEDIA_DIR_DECODING;
        pj_memcpy(&event.data.fmt_changed.new_fmt, &vpx->param.dec_fmt,
                sizeof(vpx->param.dec_fmt));
        pjmedia_event_publish(NULL, codec, &event, 0);
    }

    /* Check for found keyframe */
    res = vpx_codec_control(&vpx->decoder, VP8D_GET_LAST_REF_UPDATES,
            &reference_updates);
    if (res == VPX_CODEC_OK) {
        pj_bool_t got_keyframe = (reference_updates & VP8_GOLD_FRAME);
        if (got_keyframe) {
            pj_get_timestamp(&vpx->last_dec_keyframe_ts);

            /* Broadcast keyframe event */
            pjmedia_event_init(&event, PJMEDIA_EVENT_KEYFRAME_FOUND, ts, codec);
            pjmedia_event_publish(NULL, codec, &event, 0);
        }
    }

    return PJ_SUCCESS;
}

static pj_status_t pj_vpx_codec_decode_whole(pjmedia_vid_codec *codec,
        const vpx_image_t *img, const pj_timestamp *ts,
        unsigned output_buf_len, pjmedia_frame *output) {
	pj_status_t status;
    int half_width = (img->d_w + 1) >> 1;
    int half_height = (img->d_h + 1) >> 1;
    uint8_t* buf;
    uint32_t pos = 0;
    uint32_t plane, y;
    uint8_t* buffer = output->buf;
    int buffer_size = img->d_w * img->d_h + half_width * half_height * 2;

    /* Check decoding result, e.g: see if the format got changed,
     * keyframe found/missing.
     */
    status = check_decode_result(codec, img, ts);
    if (status != PJ_SUCCESS)
    	return status;

    /* Reset output frame bit info */
    output->bit_info = 0;
    output->timestamp = *ts;

    if (buffer_size <= output_buf_len) {
        for (plane = 0; plane < 3; plane++) {
            unsigned int width = (plane ? half_width : img->d_w);
            unsigned int height = (plane ? half_height : img->d_h);
            buf = img->planes[plane];
            for (y = 0; y < height; y++) {
                pj_memcpy(&buffer[pos], buf, width);
                pos += width;
                buf += img->stride[plane];
            }
        }
        output->size = buffer_size;
        output->type = PJMEDIA_FRAME_TYPE_VIDEO;
    } else {
        PJ_LOG(1, (THIS_FILE, "Frame ignored because of too small buffer"));

        return PJ_ETOOSMALL;
    }
    //PJ_LOG(4, (THIS_FILE, "Decoded packet %d", output->size));
    return PJ_SUCCESS;
}

static pj_status_t pj_vpx_codec_decode(pjmedia_vid_codec *codec,
        pj_size_t pkt_count, pjmedia_frame packets[], unsigned out_size,
        pjmedia_frame *output) {
    vpx_private *vpx = (vpx_private*) codec->codec_data;
    pj_uint8_t *p;
    pj_uint8_t *s;
    vpx_image_t *img;
    vpx_codec_iter_t iter = NULL;
    int i, res;

    PJ_ASSERT_RETURN(codec && pkt_count > 0 && packets && output, PJ_EINVAL);

    i = 0;

    output->type = PJMEDIA_FRAME_TYPE_NONE;
    output->size = 0;

    s = packets[0].buf;
    p = vpx->dec_buf;
    vpx->dec_frame_len = 0;

    /* TODO : packet parsing is absolutely incomplete here !!!!
     * We should manage extensions, partitions etc
     * */
    for (i = 0; i < pkt_count; ++i) {
        unsigned extension_len = 0;
        unsigned payload_size = packets[i].size;

        if(payload_size <= 0){
            continue;
        }

        s = packets[i].buf;

        /* First octet is for */
        /* |X|R|N|S|PartID | */
        if( s[0] & 0x80 ){
            if(payload_size <= 1){
                continue;
            }
            /* We have extension in octet 2 */
            /* |I|L|T|K| RSV   | */
            if( s[1] & 0x80 ){
                extension_len++;
                if(payload_size <= 2){
                    continue;
                }
                // I present check M flag for long picture ID
                if( s[2] & 0x80 ) extension_len++;
            }
            if( s[1] & 0x40 ) extension_len++;
            if( (s[1] & 0x20) | (s[1] & 0x10) ) extension_len++;
        }

        s += (extension_len + 1);
        payload_size -= (extension_len + 1);


        //PJ_LOG(4, (THIS_FILE, "Unpack RTP %d size %d, start %d", i, packets[i].size, s[0] & 0x10));
        if((vpx->dec_frame_len + payload_size) < vpx->dec_buf_size) {
            pj_memcpy((p + vpx->dec_frame_len), s, payload_size);
            vpx->dec_frame_len += payload_size;
        } else {
            PJ_LOG(1, (THIS_FILE, "Buffer is too small"));
        }
    }

    if(vpx->dec_frame_len == 0){
        PJ_LOG(1, (THIS_FILE, "No content for these packets"));
        return PJ_SUCCESS;
    }

    res = vpx_codec_decode(&vpx->decoder,
            vpx->dec_buf, vpx->dec_frame_len,
            0, VPX_DL_REALTIME);
    if (res == VPX_CODEC_UNSUP_BITSTREAM){
        PJ_LOG(2, (THIS_FILE, "More likely we are missing a keyframe, request it"));
        /* Broadcast missing keyframe event */
       pjmedia_event event;
       pjmedia_event_init(&event, PJMEDIA_EVENT_KEYFRAME_MISSING,
               &packets[0].timestamp,
               codec);
       pjmedia_event_publish(NULL, codec, &event, 0);
       return PJMEDIA_CODEC_EBADBITSTREAM;
    } else if (res == VPX_CODEC_CORRUPT_FRAME) {
        PJ_LOG(1,
                (THIS_FILE, "Failed to decode packet : %s of size %d (%s)",
                		vpx_codec_err_to_string(res), vpx->dec_frame_len,
                		vpx->decoder.err_detail));
        /* TODO : better error code. Map with res? */
        return PJMEDIA_CODEC_EFRMINLEN;
    } else if (res == VPX_CODEC_INVALID_PARAM) {
            PJ_LOG(1,
                    (THIS_FILE, "Failed to decode packet : %s (%s)",
                    		vpx_codec_err_to_string(res),
                    		vpx->decoder.err_detail));
            /* TODO : better error code. Map with res? */
            return PJMEDIA_CODEC_EINMODE;
    } else if (res != VPX_CODEC_OK) {
        PJ_LOG(1,
                (THIS_FILE, "Failed to decode packet : %s (%s)",
                		vpx_codec_err_to_string(res),
                		vpx->decoder.err_detail));
        /* TODO : better error code. Map with res? */
        return PJMEDIA_ERROR;
    }

    /* No need to loop here for first implementation */
    img = vpx_codec_get_frame(&vpx->decoder, &iter);
    if (img != NULL) {
        /* We have a frame available */
        return pj_vpx_codec_decode_whole(codec, img, &packets[0].timestamp, out_size, output);
    }

    return PJ_SUCCESS;
}

#endif	/* PJMEDIA_HAS_VPX_VID_CODEC */

