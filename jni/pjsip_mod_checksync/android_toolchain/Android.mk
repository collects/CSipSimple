MODULE_PATH := $(patsubst %/,%,$(dir $(call my-dir)))

include $(CLEAR_VARS)
LOCAL_PATH := $(MODULE_PATH)

PJ_ROOT_DIR := $(JNI_PATH)/pjsip/sources
PJ_ANDROID_ROOT_DIR := $(JNI_PATH)/pjsip/android_sources

LOCAL_MODULE := pjsip_mod_checksync

LOCAL_C_INCLUDES += \
	$(PJ_ROOT_DIR)/pjsip/include \
	$(PJ_ROOT_DIR)/pjlib-util/include \
	$(PJ_ROOT_DIR)/pjlib/include \
	$(PJ_ROOT_DIR)/pjmedia/include \
	$(PJ_ROOT_DIR)/pjnath/include \
	$(PJ_ROOT_DIR)/pjlib/include

LOCAL_C_INCLUDES += $(MODULE_PATH)/include

LOCAL_CFLAGS := $(MY_PJSIP_FLAGS)

LOCAL_STATIC_LIBRARIES += libgcc

LOCAL_SRC_FILES := src/pjsip_mod_checksync.cpp 

include $(BUILD_STATIC_LIBRARY)
